#include <jni.h>
#include <fcntl.h>
#include <sys/syscall.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include <array>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace {

std::string read_file(const char* path, size_t limit = 64 * 1024) {
    FILE* file = fopen(path, "re");
    if (file == nullptr) return {};
    std::string result;
    std::array<char, 4096> buffer{};
    while (result.size() < limit) {
        const size_t count = fread(buffer.data(), 1, buffer.size(), file);
        if (count == 0) break;
        result.append(buffer.data(), count);
    }
    fclose(file);
    return result;
}

std::string read_fd(int fd, size_t limit = 64 * 1024) {
    if (fd < 0) return {};
    std::string result;
    std::array<char, 4096> buffer{};
    while (result.size() < limit) {
        const ssize_t count = read(fd, buffer.data(), buffer.size());
        if (count <= 0) break;
        result.append(buffer.data(), static_cast<size_t>(count));
    }
    close(fd);
    return result;
}

jstring to_string(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

std::string property(const char* name) {
    char value[PROP_VALUE_MAX] = {};
    __system_property_get(name, value);
    return value;
}

std::string fd_link(const char* path) {
    const int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return "open-failed";
    std::string proc_path = "/proc/self/fd/" + std::to_string(fd);
    char value[4096] = {};
    const ssize_t length = readlink(proc_path.c_str(), value, sizeof(value) - 1);
    close(fd);
    if (length <= 0) return "readlink-failed";
    value[length] = '\0';
    return value;
}

bool starts_with(const std::string& value, const char* prefix) {
    return value.rfind(prefix, 0) == 0;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_coke_testapp_NativeProbe_nativeEnvironmentSummary(
    JNIEnv* env,
    jclass
) {
    char cmdline[4096] = {};
    const int cmd_fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (cmd_fd >= 0) {
        const ssize_t length = read(cmd_fd, cmdline, sizeof(cmdline) - 1);
        if (length > 0) cmdline[length] = '\0';
        close(cmd_fd);
    }
    const std::string status = read_file("/proc/self/status", 16 * 1024);
    std::string uid_line;
    std::string name_line;
    size_t offset = 0;
    while (offset < status.size()) {
        const size_t end = status.find('\n', offset);
        const std::string line = status.substr(
            offset,
            end == std::string::npos ? std::string::npos : end - offset
        );
        if (starts_with(line, "Uid:")) uid_line = line;
        if (starts_with(line, "Name:")) name_line = line;
        if (end == std::string::npos) break;
        offset = end + 1;
    }
    const std::string summary =
        "getuid=" + std::to_string(getuid()) +
        "\ngeteuid=" + std::to_string(geteuid()) +
        "\nsyscall_getuid=" + std::to_string(syscall(SYS_getuid)) +
        "\npid=" + std::to_string(getpid()) +
        "\ncmdline=" + std::string(cmdline) +
        "\n" + name_line +
        "\n" + uid_line +
        "\nabi=" + property("ro.product.cpu.abi") +
        "\nsdk=" + property("ro.build.version.sdk") +
        "\nmodel=" + property("ro.product.model");
    return to_string(env, summary);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_coke_testapp_NativeProbe_nativeOpenPath(
    JNIEnv* env,
    jclass,
    jstring path
) {
    if (path == nullptr) return nullptr;
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return nullptr;
    const int fd = open(chars, O_RDONLY | O_CLOEXEC);
    env->ReleaseStringUTFChars(path, chars);
    if (fd < 0) return to_string(env, "open-failed");
    return to_string(env, read_fd(fd));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_coke_testapp_NativeProbe_nativeFopenPath(
    JNIEnv* env,
    jclass,
    jstring path
) {
    if (path == nullptr) return nullptr;
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return nullptr;
    const std::string value = read_file(chars, 64 * 1024);
    env->ReleaseStringUTFChars(path, chars);
    return to_string(env, value.empty() ? "fopen-failed" : value);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_coke_testapp_NativeProbe_nativeSyscallOpenPath(
    JNIEnv* env,
    jclass,
    jstring path
) {
    if (path == nullptr) return nullptr;
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return nullptr;
    const long fd = syscall(
        SYS_openat,
        AT_FDCWD,
        chars,
        O_RDONLY | O_CLOEXEC,
        0
    );
    env->ReleaseStringUTFChars(path, chars);
    if (fd < 0) return to_string(env, "syscall-open-failed");
    return to_string(env, read_fd(static_cast<int>(fd)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_coke_testapp_NativeProbe_nativeFdPath(
    JNIEnv* env,
    jclass,
    jstring path
) {
    if (path == nullptr) return nullptr;
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return nullptr;
    const std::string value = fd_link(chars);
    env->ReleaseStringUTFChars(path, chars);
    return to_string(env, value);
}
