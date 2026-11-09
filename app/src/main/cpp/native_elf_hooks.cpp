#include "native_environment_internal.h"

#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <link.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cerrno>
#include <condition_variable>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <string_view>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace nestspace {
namespace {

constexpr char kLogTag[] = "NativeHooks";

struct PatchedSlot {
    void** address;
    void* original;
};

struct ModuleKey {
    uintptr_t base;
    size_t name_hash;

    bool operator==(const ModuleKey& other) const {
        return base == other.base && name_hash == other.name_hash;
    }
};

struct ModuleKeyHash {
    size_t operator()(const ModuleKey& value) const {
        size_t result = value.base;
        result ^= value.name_hash + 0x9e3779b97f4a7c15ULL +
            (result << 6U) + (result >> 2U);
        return result;
    }
};

struct HookCounters {
    int errors = 0;
};

std::mutex g_hook_lock;
std::vector<PatchedSlot> g_patched_slots;
std::unordered_set<void**> g_patched_addresses;
std::unordered_set<ModuleKey, ModuleKeyHash> g_scanned_modules;
std::unordered_set<ModuleKey, ModuleKeyHash> g_target_modules;
std::unordered_set<ModuleKey, ModuleKeyHash> g_hooked_modules;
HookCounters g_counters;
std::mutex g_refresh_queue_lock;
std::condition_variable g_refresh_queue_ready;
bool g_refresh_requested = false;
bool g_refresh_worker_started = false;

ModuleKey module_key(const dl_phdr_info& info) {
    const std::string_view name = info.dlpi_name == nullptr
        ? std::string_view{}
        : std::string_view(info.dlpi_name);
    return ModuleKey{
        static_cast<uintptr_t>(info.dlpi_addr),
        std::hash<std::string_view>{}(name)
    };
}

bool starts_with_prefix(const char* name, const std::string& prefix) {
    if (name == nullptr || prefix.empty()) return false;
    const size_t length = prefix.size();
    return strncmp(name, prefix.c_str(), length) == 0 &&
        (name[length] == '\0' || name[length] == '/');
}

bool matches_prefix(
    const char* name,
    const std::string& prefix,
    const std::string& alias
) {
    return starts_with_prefix(name, prefix) ||
        (!alias.empty() && starts_with_prefix(name, alias));
}

bool target_object(const char* name) {
    if (name == nullptr || name[0] == '\0') return false;
    const std::string_view path(name);
    const size_t slash = path.find_last_of('/');
    const std::string_view basename = slash == std::string_view::npos
        ? path
        : path.substr(slash + 1);
    // TLS/crypto libraries use their own fork and entropy checks. Rewriting
    // libc/property entry points inside these modules can turn RAND_bytes into
    // a process abort, while they do not need virtual package identity.
    if (basename.find("ssl") != std::string_view::npos ||
        basename.find("crypto") != std::string_view::npos) {
        return false;
    }
    // Downloaded plugin modules are versioned independently from the target
    // APK and commonly ship their own linker/JNI initialization. Rewriting
    // their libc slots can invalidate internal function pointers.
    if (path.find("/files/plugins/") != std::string_view::npos) {
        return false;
    }
    const Environment* environment = current_environment();
    if (environment == nullptr) return false;
    return matches_prefix(
            name,
            environment->native_library_dir,
            environment->native_library_dir_alias
        ) ||
        matches_prefix(
            name,
            environment->data_root,
            environment->data_root_alias
        );
}

uint16_t native_machine() {
#if defined(__aarch64__)
    return EM_AARCH64;
#elif defined(__arm__)
    return EM_ARM;
#elif defined(__x86_64__)
    return EM_X86_64;
#elif defined(__i386__)
    return EM_386;
#else
    return EM_NONE;
#endif
}

bool is_hook_relocation(uint16_t machine, ElfW(Xword) type) {
    switch (machine) {
        case EM_AARCH64:
            // R_AARCH64_GLOB_DAT / R_AARCH64_JUMP_SLOT.
            return type == 1025 || type == 1026;
        case EM_ARM:
            // R_ARM_GLOB_DAT / R_ARM_JUMP_SLOT.
            return type == 21 || type == 22;
        case EM_X86_64:
            // R_X86_64_GLOB_DAT / R_X86_64_JUMP_SLOT.
            return type == 6 || type == 7;
        case EM_386:
            // R_386_GLOB_DAT / R_386_JMP_SLOT.
            return type == 6 || type == 7;
        default:
            return false;
    }
}

unsigned int relocation_type(ElfW(Xword) information) {
#if defined(__LP64__)
    return ELF64_R_TYPE(information);
#else
    return ELF32_R_TYPE(information);
#endif
}

unsigned int relocation_symbol(ElfW(Xword) information) {
#if defined(__LP64__)
    return ELF64_R_SYM(information);
#else
    return ELF32_R_SYM(information);
#endif
}

uintptr_t runtime_address(
    const dl_phdr_info& info,
    ElfW(Addr) value
) {
    for (int index = 0; index < info.dlpi_phnum; ++index) {
        const auto& header = info.dlpi_phdr[index];
        if (header.p_type != PT_LOAD) continue;
        const uintptr_t start = info.dlpi_addr + header.p_vaddr;
        const uintptr_t end = start + header.p_memsz;
        if (value >= start && value < end) return static_cast<uintptr_t>(value);
    }
    return info.dlpi_addr + static_cast<uintptr_t>(value);
}

bool write_slot(void** address, void* value) {
    static const long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) return false;
    const uintptr_t page = reinterpret_cast<uintptr_t>(address) &
        ~(static_cast<uintptr_t>(page_size) - 1U);
    if (mprotect(
            reinterpret_cast<void*>(page),
            static_cast<size_t>(page_size),
            PROT_READ | PROT_WRITE
        ) == 0) {
        __atomic_store_n(address, value, __ATOMIC_RELEASE);
        return true;
    }

    const int protect_error = errno;
    struct iovec local{&value, sizeof(value)};
    struct iovec remote{address, sizeof(value)};
    const long written = syscall(
        SYS_process_vm_writev,
        getpid(),
        &local,
        1,
        &remote,
        1,
        0
    );
    if (written == static_cast<long>(sizeof(value))) {
        static bool logged_fallback = false;
        if (!logged_fallback) {
            logged_fallback = true;
            __android_log_print(
                ANDROID_LOG_DEBUG,
                kLogTag,
                "patched through process_vm_writev slot=%p",
                reinterpret_cast<void*>(address)
            );
        }
        return true;
    }
    const int write_error = errno;
    if (g_counters.errors < 3) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "slot write failed page=%p slot=%p mprotect_errno=%d write_errno=%d",
            reinterpret_cast<void*>(page),
            reinterpret_cast<void*>(address),
            protect_error,
            write_error
        );
    }
    return false;
}

void patch_slot(void** address, void* replacement) {
    if (address == nullptr || g_patched_addresses.count(address) != 0) return;
    const void* current = __atomic_load_n(address, __ATOMIC_ACQUIRE);
    g_patched_addresses.insert(address);
    if (current == replacement) return;
    if (!write_slot(address, replacement)) {
        g_patched_addresses.erase(address);
        ++g_counters.errors;
        if (g_counters.errors <= 3) {
            __android_log_print(
                ANDROID_LOG_WARN,
                kLogTag,
                "patch slot failed slot=%p",
                reinterpret_cast<void*>(address)
            );
        }
        return;
    }
    g_patched_slots.push_back(
        PatchedSlot{address, const_cast<void*>(current)}
    );
    __atomic_store_n(address, replacement, __ATOMIC_RELEASE);
}

template <typename Relocation>
void patch_relocations(
    uintptr_t base,
    uint16_t machine,
    const Relocation* relocations,
    size_t size,
    const ElfW(Sym)* symbols,
    const char* strings,
    bool loader_only
) {
    if (relocations == nullptr || symbols == nullptr || strings == nullptr) return;
    const size_t count = size / sizeof(Relocation);
    for (size_t index = 0; index < count; ++index) {
        const auto& relocation = relocations[index];
        const auto type = relocation_type(relocation.r_info);
        if (!is_hook_relocation(machine, type)) continue;
        const auto symbol_index = relocation_symbol(relocation.r_info);
        const char* name = strings + symbols[symbol_index].st_name;
        void* replacement = loader_only
            ? loader_replacement_for(name)
            : replacement_for(name);
        if (replacement == nullptr) continue;
        auto** address = reinterpret_cast<void**>(
            base + relocation.r_offset
        );
        patch_slot(address, replacement);
    }
}

bool hook_object(const dl_phdr_info& info, bool loader_only) {
    const ElfW(Phdr)* dynamic_header = nullptr;
    for (int index = 0; index < info.dlpi_phnum; ++index) {
        if (info.dlpi_phdr[index].p_type == PT_DYNAMIC) {
            dynamic_header = &info.dlpi_phdr[index];
            break;
        }
    }
    if (dynamic_header == nullptr) {
        ++g_counters.errors;
        return false;
    }

    auto* dynamic = reinterpret_cast<const ElfW(Dyn)*>(
        info.dlpi_addr + dynamic_header->p_vaddr
    );
    const ElfW(Sym)* symbols = nullptr;
    const char* strings = nullptr;
    const void* jmprel = nullptr;
    size_t jmprel_size = 0;
    const ElfW(Rel)* rel = nullptr;
    size_t rel_size = 0;
    const ElfW(Rela)* rela = nullptr;
    size_t rela_size = 0;
    for (auto entry = dynamic; entry->d_tag != DT_NULL; ++entry) {
        switch (entry->d_tag) {
            case DT_SYMTAB:
                symbols = reinterpret_cast<const ElfW(Sym)*>(
                    runtime_address(info, entry->d_un.d_ptr)
                );
                break;
            case DT_STRTAB:
                strings = reinterpret_cast<const char*>(
                    runtime_address(info, entry->d_un.d_ptr)
                );
                break;
            case DT_JMPREL:
                jmprel = reinterpret_cast<const void*>(
                    runtime_address(info, entry->d_un.d_ptr)
                );
                break;
            case DT_PLTRELSZ:
                jmprel_size = entry->d_un.d_val;
                break;
            case DT_REL:
                rel = reinterpret_cast<const ElfW(Rel)*>(
                    runtime_address(info, entry->d_un.d_ptr)
                );
                break;
            case DT_RELSZ:
                rel_size = entry->d_un.d_val;
                break;
            case DT_RELA:
                rela = reinterpret_cast<const ElfW(Rela)*>(
                    runtime_address(info, entry->d_un.d_ptr)
                );
                break;
            case DT_RELASZ:
                rela_size = entry->d_un.d_val;
                break;
            default:
                break;
        }
    }

    const size_t before = g_patched_slots.size();
    if (jmprel != nullptr && jmprel_size > 0) {
#if defined(__arm__) || defined(__i386__)
        patch_relocations(
            info.dlpi_addr,
            native_machine(),
            static_cast<const ElfW(Rel)*>(jmprel),
            jmprel_size,
            symbols,
            strings,
            loader_only
        );
#else
        patch_relocations(
            info.dlpi_addr,
            native_machine(),
            static_cast<const ElfW(Rela)*>(jmprel),
            jmprel_size,
            symbols,
            strings,
            loader_only
        );
#endif
    }
    patch_relocations(
        info.dlpi_addr,
        native_machine(),
        rel,
        rel_size,
        symbols,
        strings,
        loader_only
    );
    patch_relocations(
        info.dlpi_addr,
        native_machine(),
        rela,
        rela_size,
        symbols,
        strings,
        loader_only
    );
    return g_patched_slots.size() > before;
}

struct MappedModule {
    std::string path;
    uintptr_t base = UINTPTR_MAX;
    uintptr_t end = 0;
};

std::string read_file_bytes(const char* path) {
    const long fd = syscall(
        SYS_openat,
        AT_FDCWD,
        path,
        O_RDONLY | O_CLOEXEC,
        0
    );
    if (fd < 0) return {};
    struct stat status{};
    if (fstat(static_cast<int>(fd), &status) != 0 ||
        status.st_size > 256 * 1024 * 1024) {
        close(static_cast<int>(fd));
        return {};
    }
    std::string value(
        status.st_size > 0 ? static_cast<size_t>(status.st_size) : 0,
        '\0'
    );
    size_t offset = 0;
    while (offset < 256 * 1024 * 1024) {
        if (offset == value.size()) {
            value.resize(value.size() + 16 * 1024);
        }
        const ssize_t count = read(
            static_cast<int>(fd),
            value.data() + offset,
            value.size() - offset
        );
        if (count <= 0) break;
        offset += static_cast<size_t>(count);
    }
    close(static_cast<int>(fd));
    value.resize(offset);
    return value;
}

bool file_offset_for_vaddr(
    const ElfW(Phdr)* headers,
    size_t count,
    ElfW(Addr) address,
    size_t* result
) {
    for (size_t index = 0; index < count; ++index) {
        const auto& header = headers[index];
        if (header.p_type != PT_LOAD) continue;
        if (address < header.p_vaddr ||
            address >= header.p_vaddr + header.p_filesz) {
            continue;
        }
        *result = header.p_offset + (address - header.p_vaddr);
        return true;
    }
    return false;
}

bool hook_elf_file(const std::string& path, uintptr_t base) {
    const std::string bytes = read_file_bytes(path.c_str());
    if (bytes.size() < sizeof(ElfW(Ehdr))) return false;
    const auto* header = reinterpret_cast<const ElfW(Ehdr)*>(bytes.data());
    if (memcmp(header->e_ident, ELFMAG, SELFMAG) != 0 ||
        header->e_phoff == 0 ||
        header->e_phnum == 0 ||
        header->e_phoff + sizeof(ElfW(Phdr)) * header->e_phnum > bytes.size()) {
        return false;
    }
    const auto* headers = reinterpret_cast<const ElfW(Phdr)*>(
        bytes.data() + header->e_phoff
    );
    const ElfW(Phdr)* dynamic_header = nullptr;
    for (size_t index = 0; index < header->e_phnum; ++index) {
        if (headers[index].p_type == PT_DYNAMIC) {
            dynamic_header = &headers[index];
            break;
        }
    }
    if (dynamic_header == nullptr ||
        dynamic_header->p_offset + dynamic_header->p_filesz > bytes.size()) {
        return false;
    }

    const auto* dynamic = reinterpret_cast<const ElfW(Dyn)*>(
        bytes.data() + dynamic_header->p_offset
    );
    const ElfW(Sym)* symbols = nullptr;
    const char* strings = nullptr;
    const ElfW(Rel)* rel = nullptr;
    size_t rel_size = 0;
    const ElfW(Rela)* rela = nullptr;
    size_t rela_size = 0;
    const void* jmprel = nullptr;
    size_t jmprel_size = 0;
    ElfW(Sword) plt_kind = 0;
    for (auto entry = dynamic; entry->d_tag != DT_NULL; ++entry) {
        size_t offset = 0;
        switch (entry->d_tag) {
            case DT_SYMTAB:
                if (file_offset_for_vaddr(
                        headers,
                        header->e_phnum,
                        entry->d_un.d_ptr,
                        &offset
                    )) {
                    symbols = reinterpret_cast<const ElfW(Sym)*>(
                        bytes.data() + offset
                    );
                }
                break;
            case DT_STRTAB:
                if (file_offset_for_vaddr(
                        headers,
                        header->e_phnum,
                        entry->d_un.d_ptr,
                        &offset
                    )) {
                    strings = bytes.data() + offset;
                }
                break;
            case DT_PLTREL:
                plt_kind = entry->d_un.d_val;
                break;
            case DT_JMPREL:
                if (file_offset_for_vaddr(
                        headers,
                        header->e_phnum,
                        entry->d_un.d_ptr,
                        &offset
                    )) {
                    jmprel = bytes.data() + offset;
                }
                break;
            case DT_PLTRELSZ:
                jmprel_size = entry->d_un.d_val;
                break;
            case DT_REL:
                if (file_offset_for_vaddr(
                        headers,
                        header->e_phnum,
                        entry->d_un.d_ptr,
                        &offset
                    )) {
                    rel = reinterpret_cast<const ElfW(Rel)*>(
                        bytes.data() + offset
                    );
                }
                break;
            case DT_RELSZ:
                rel_size = entry->d_un.d_val;
                break;
            case DT_RELA:
                if (file_offset_for_vaddr(
                        headers,
                        header->e_phnum,
                        entry->d_un.d_ptr,
                        &offset
                    )) {
                    rela = reinterpret_cast<const ElfW(Rela)*>(
                        bytes.data() + offset
                    );
                }
                break;
            case DT_RELASZ:
                rela_size = entry->d_un.d_val;
                break;
            default:
                break;
        }
    }
    if (symbols == nullptr || strings == nullptr) return false;
    const size_t before = g_patched_slots.size();
    if (jmprel != nullptr && jmprel_size > 0) {
        if (plt_kind == DT_RELA) {
            patch_relocations(
                base,
                header->e_machine,
                static_cast<const ElfW(Rela)*>(jmprel),
                jmprel_size,
                symbols,
                strings,
                false
            );
        } else if (plt_kind == DT_REL) {
            patch_relocations(
                base,
                header->e_machine,
                static_cast<const ElfW(Rel)*>(jmprel),
                jmprel_size,
                symbols,
                strings,
                false
            );
        }
    }
    patch_relocations(
        base,
        header->e_machine,
        rel,
        rel_size,
        symbols,
        strings,
        false
    );
    patch_relocations(
        base,
        header->e_machine,
        rela,
        rela_size,
        symbols,
        strings,
        false
    );
    return g_patched_slots.size() > before;
}

void scan_maps_for_target_modules() {
    static bool logged_read = false;
    static bool logged_paths = false;
    static auto last_scan = std::chrono::steady_clock::time_point{};
    const auto now = std::chrono::steady_clock::now();
    if (last_scan != std::chrono::steady_clock::time_point{} &&
        now - last_scan < std::chrono::milliseconds(500)) {
        return;
    }
    last_scan = now;
    const std::string maps = read_file_bytes("/proc/self/maps");
    if (!logged_read) {
        logged_read = true;
        __android_log_print(
            ANDROID_LOG_DEBUG,
            kLogTag,
            "maps scan bytes=%zu",
            maps.size()
        );
    }
    if (maps.empty()) return;

    std::unordered_map<std::string, MappedModule> modules;
    size_t offset = 0;
    while (offset < maps.size()) {
        const size_t end = maps.find('\n', offset);
        const size_t length = end == std::string::npos
            ? maps.size() - offset
            : end - offset;
        const std::string line = maps.substr(offset, length);
        unsigned long start = 0;
        unsigned long stop = 0;
        unsigned long file_offset = 0;
        if (sscanf(
                line.c_str(),
                "%lx-%lx %*s %lx",
                &start,
                &stop,
                &file_offset
            ) == 3) {
            const size_t path_start = line.find('/');
            if (path_start != std::string::npos) {
                std::string path = line.substr(path_start);
                const std::string deleted = " (deleted)";
                if (path.size() >= deleted.size() &&
                    path.compare(
                        path.size() - deleted.size(),
                        deleted.size(),
                        deleted
                    ) == 0) {
                    path.resize(path.size() - deleted.size());
                }
                if (path.size() < 3 ||
                    path.compare(path.size() - 3, 3, ".so") != 0) {
                    if (end == std::string::npos) break;
                    offset = end + 1;
                    continue;
                }
                const bool target = target_object(path.c_str());
                if (!logged_paths && path.find("/vspace/") != std::string::npos) {
                    logged_paths = true;
                    __android_log_print(
                        ANDROID_LOG_DEBUG,
                        kLogTag,
                        "maps path target=%d path=%s",
                        target ? 1 : 0,
                        path.c_str()
                    );
                }
                if (target) {
                    auto& module = modules[path];
                    module.path = path;
                    module.base = std::min(
                        module.base,
                        static_cast<uintptr_t>(start - file_offset)
                    );
                    module.end = std::max(
                        module.end,
                        static_cast<uintptr_t>(stop)
                    );
                }
            }
        }
        if (end == std::string::npos) break;
        offset = end + 1;
    }

    for (const auto& item : modules) {
        const auto& module = item.second;
        const ModuleKey key{
            module.base,
            std::hash<std::string_view>{}(module.path)
        };
        if (!g_scanned_modules.insert(key).second) continue;
        g_target_modules.insert(key);
        if (hook_elf_file(module.path, module.base)) {
            g_hooked_modules.insert(key);
        }
    }
}

bool loader_object(const char* name) {
    if (name == nullptr || name[0] == '\0') return false;
    const std::string_view path(name);
    constexpr std::string_view kSuffixes[] = {
        "libart.so",
        "libnativeloader.so",
        "libandroid_runtime.so",
    };
    for (const auto suffix : kSuffixes) {
        if (path.size() >= suffix.size() &&
            path.compare(path.size() - suffix.size(), suffix.size(), suffix) == 0) {
            return true;
        }
    }
    return false;
}

int hook_callback(dl_phdr_info* info, size_t, void*) {
    if (info == nullptr) return 0;
    const ModuleKey key = module_key(*info);
    if (!g_scanned_modules.insert(key).second) return 0;
    if (current_environment() != nullptr && loader_object(info->dlpi_name)) {
        hook_object(*info, true);
        return 0;
    }
    if (!target_object(info->dlpi_name)) return 0;
    g_target_modules.insert(key);
    if (hook_object(*info, false)) g_hooked_modules.insert(key);
    return 0;
}

HookState snapshot_locked() {
    return HookState{
        static_cast<int>(g_patched_slots.size()),
        static_cast<int>(g_hooked_modules.size()),
        static_cast<int>(g_scanned_modules.size()),
        static_cast<int>(g_target_modules.size()),
        g_counters.errors,
        g_virtual_proc_reads.load(std::memory_order_relaxed),
        g_property_reads.load(std::memory_order_relaxed),
        g_dlopen_calls.load(std::memory_order_relaxed),
    };
}

}  // namespace

HookState refresh_hooks(bool scan_elf_fallback) {
    initialize_real_functions();
    std::lock_guard<std::mutex> guard(g_hook_lock);
    dl_iterate_phdr(hook_callback, nullptr);
    if (scan_elf_fallback) scan_maps_for_target_modules();
    return snapshot_locked();
}

void refresh_loaded_modules() {
    initialize_real_functions();
    std::lock_guard<std::mutex> guard(g_hook_lock);
    dl_iterate_phdr(hook_callback, nullptr);
}

void request_loaded_modules_refresh() {
    {
        std::lock_guard<std::mutex> guard(g_refresh_queue_lock);
        g_refresh_requested = true;
        if (!g_refresh_worker_started) {
            g_refresh_worker_started = true;
            std::thread([] {
                for (;;) {
                    {
                        std::unique_lock<std::mutex> lock(g_refresh_queue_lock);
                        g_refresh_queue_ready.wait(lock, [] {
                            return g_refresh_requested;
                        });
                        g_refresh_requested = false;
                    }
                    refresh_loaded_modules();
                }
            }).detach();
        }
    }
    g_refresh_queue_ready.notify_one();
}

HookState hook_state() {
    std::lock_guard<std::mutex> guard(g_hook_lock);
    return snapshot_locked();
}

void restore_hooks() {
    {
        std::lock_guard<std::mutex> queue_guard(g_refresh_queue_lock);
        g_refresh_requested = false;
    }
    std::lock_guard<std::mutex> guard(g_hook_lock);
    for (const auto& patched : g_patched_slots) {
        write_slot(patched.address, patched.original);
    }
    g_patched_slots.clear();
    g_patched_addresses.clear();
    g_scanned_modules.clear();
    g_target_modules.clear();
    g_hooked_modules.clear();
    g_counters.errors = 0;
    g_virtual_proc_reads.store(0, std::memory_order_relaxed);
    g_property_reads.store(0, std::memory_order_relaxed);
    g_dlopen_calls.store(0, std::memory_order_relaxed);
    clear_fd_paths();
}

}  // namespace nestspace
