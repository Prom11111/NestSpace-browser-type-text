#include "native_environment_internal.h"

#include <sys/syscall.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include <memory>
#include <string>
#include <unordered_map>
#include <utility>

namespace {

std::string to_string(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::unordered_map<std::string, std::string> to_properties(
    JNIEnv* env,
    jobjectArray values
) {
    std::unordered_map<std::string, std::string> result;
    if (values == nullptr) return result;
    const jsize length = env->GetArrayLength(values);
    for (jsize index = 0; index + 1 < length; index += 2) {
        auto key = static_cast<jstring>(env->GetObjectArrayElement(values, index));
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values, index + 1));
        const std::string name = to_string(env, key);
        const std::string content = to_string(env, value);
        if (key != nullptr) env->DeleteLocalRef(key);
        if (value != nullptr) env->DeleteLocalRef(value);
        if (!name.empty()) result[name] = content;
    }
    return result;
}

std::string data_alias(const std::string& path) {
    static constexpr char kUser[] = "/data/user/0/";
    static constexpr char kData[] = "/data/data/";
    if (path.rfind(kUser, 0) == 0) {
        return std::string(kData) + path.substr(sizeof(kUser) - 1);
    }
    if (path.rfind(kData, 0) == 0) {
        return std::string(kUser) + path.substr(sizeof(kData) - 1);
    }
    return {};
}

jlongArray hook_state_array(JNIEnv* env, nestspace::HookState state) {
    const jlong values[8] = {
        static_cast<jlong>(state.patch_count),
        static_cast<jlong>(state.hooked_modules),
        static_cast<jlong>(state.scanned_modules),
        static_cast<jlong>(state.target_modules),
        static_cast<jlong>(state.errors),
        static_cast<jlong>(state.virtual_proc_reads),
        static_cast<jlong>(state.property_reads),
        static_cast<jlong>(state.dlopen_calls),
    };
    jlongArray result = env->NewLongArray(8);
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, 8, values);
    }
    return result;
}

struct PropertyCallbackCookie {
    std::string value;
};

void property_callback_probe(
    void* cookie,
    const char*,
    const char* value,
    uint32_t
) {
    if (cookie != nullptr && value != nullptr) {
        static_cast<PropertyCallbackCookie*>(cookie)->value = value;
    }
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeInstall(
    JNIEnv* env,
    jclass,
    jstring instance_id,
    jstring package_name,
    jstring data_root,
    jstring external_data_root,
    jstring process_name,
    jstring native_library_dir,
    jobjectArray properties,
    jint host_uid,
    jint observed_uid,
    jint virtual_pid,
    jlong identity_revision,
    jlong capability_mask
) {
    auto environment = std::make_unique<nestspace::Environment>();
    environment->instance_id = to_string(env, instance_id);
    environment->package_name = to_string(env, package_name);
    environment->data_root = to_string(env, data_root);
    environment->external_data_root = to_string(env, external_data_root);
    environment->process_name = to_string(env, process_name);
    environment->native_library_dir = to_string(env, native_library_dir);
    environment->target_data_root =
        "/data/user/0/" + environment->package_name;
    environment->target_data_alias =
        "/data/data/" + environment->package_name;
    environment->target_device_root =
        "/data/user_de/0/" + environment->package_name;
    environment->target_external_root =
        "/storage/emulated/0/Android/data/" + environment->package_name;
    environment->target_external_alias =
        "/sdcard/Android/data/" + environment->package_name;
    environment->actual_device_root =
        environment->data_root + "/device_protected";
    environment->native_library_dir_alias =
        data_alias(environment->native_library_dir);
    environment->data_root_alias = data_alias(environment->data_root);
    environment->property_snapshot = nestspace::build_property_snapshot(
        to_properties(env, properties)
    );
    environment->host_uid = host_uid;
    environment->observed_uid = observed_uid;
    environment->virtual_pid = virtual_pid;
    environment->identity_revision = identity_revision;
    environment->capability_mask = capability_mask;

    nestspace::restore_hooks();
    nestspace::publish_environment(std::move(environment));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeIdentity(
    JNIEnv* env,
    jclass
) {
    const nestspace::Environment* environment = nestspace::current_environment();
    jint values[4] = {
        environment ? environment->host_uid : -1,
        environment ? environment->observed_uid : -1,
        environment ? environment->virtual_pid : -1,
        static_cast<jint>(getuid()),
    };
    jintArray result = env->NewIntArray(4);
    if (result != nullptr) {
        env->SetIntArrayRegion(result, 0, 4, values);
    }
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeProcessName(
    JNIEnv* env,
    jclass
) {
    const nestspace::Environment* environment = nestspace::current_environment();
    if (environment == nullptr) return nullptr;
    return env->NewStringUTF(environment->process_name.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativePageSize(
    JNIEnv*,
    jclass
) {
    const long page_size = sysconf(_SC_PAGESIZE);
    return page_size > 0 ? static_cast<jlong>(page_size) : 0;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeProbe(
    JNIEnv* env,
    jclass
) {
    const long page_size = sysconf(_SC_PAGESIZE);
    jlong flags = 0;
    if (nestspace::can_open_for_read("/proc/self/status")) {
        flags |= nestspace::kProbeStatusReadable;
    }
    if (nestspace::can_open_for_read("/proc/self/maps")) {
        flags |= nestspace::kProbeMapsReadable;
    }
    if (nestspace::can_open_for_read("/proc/self/mountinfo")) {
        flags |= nestspace::kProbeMountInfoReadable;
    }
    if (nestspace::can_open_for_read("/proc/self/attr/current")) {
        flags |= nestspace::kProbeSelinuxReadable;
    }
    if (nestspace::can_syscall_open_for_read("/proc/self/status")) {
        flags |= nestspace::kProbeDirectOpenReadable;
    }
    if (nestspace::can_syscall_stat("/proc/self/status")) {
        flags |= nestspace::kProbeDirectStatReadable;
    }

    char abi[PROP_VALUE_MAX] = {};
    const int abi_length = __system_property_get("ro.product.cpu.abi", abi);
    if (abi_length > 0 && abi_length < PROP_VALUE_MAX) {
        flags |= nestspace::kProbePropertyReadable;
    }

    const jlong values[6] = {
        static_cast<jlong>(getuid()),
        static_cast<jlong>(syscall(SYS_getuid)),
        static_cast<jlong>(nestspace::read_status_uid()),
        page_size > 0 ? static_cast<jlong>(page_size) : 0,
        flags,
        0,
    };
    jlongArray result = env->NewLongArray(6);
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, 6, values);
    }
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeProductAbi(
    JNIEnv* env,
    jclass
) {
    char abi[PROP_VALUE_MAX] = {};
    const int length = __system_property_get("ro.product.cpu.abi", abi);
    if (length <= 0 || length >= PROP_VALUE_MAX) return nullptr;
    return env->NewStringUTF(abi);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeRewritePath(
    JNIEnv* env,
    jclass,
    jstring path
) {
    const std::string input = to_string(env, path);
    std::string rewritten;
    if (!nestspace::rewrite_target_path(input.c_str(), &rewritten)) return nullptr;
    return env->NewStringUTF(rewritten.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeReadVirtualProc(
    JNIEnv* env,
    jclass,
    jstring path
) {
    const std::string input = to_string(env, path);
    const std::string content = nestspace::virtualized_proc_content(input.c_str());
    if (content.empty()) return nullptr;
    return env->NewStringUTF(content.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeReadProperty(
    JNIEnv* env,
    jclass,
    jstring name
) {
    const std::string key = to_string(env, name);
    char value[PROP_VALUE_MAX] = {};
    if (nestspace::hook_property_get(key.c_str(), value) <= 0) return nullptr;
    return env->NewStringUTF(value);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeReadPropertyCallback(
    JNIEnv* env,
    jclass,
    jstring name
) {
    const std::string key = to_string(env, name);
    const void* property = nestspace::hook_property_find(key.c_str());
    if (property == nullptr) return nullptr;
    PropertyCallbackCookie cookie;
    nestspace::hook_property_read_callback(
        property,
        property_callback_probe,
        &cookie
    );
    if (cookie.value.empty()) return nullptr;
    return env->NewStringUTF(cookie.value.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeIdentityRevision(
    JNIEnv*,
    jclass
) {
    const nestspace::Environment* environment = nestspace::current_environment();
    return environment ? environment->identity_revision : 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeCapabilityMask(
    JNIEnv*,
    jclass
) {
    const nestspace::Environment* environment = nestspace::current_environment();
    return environment ? environment->capability_mask : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativePublishIdentity(
    JNIEnv*,
    jclass,
    jlong revision
) {
    const nestspace::Environment* current = nestspace::current_environment();
    if (current == nullptr || revision < current->identity_revision) {
        return JNI_FALSE;
    }
    if (revision == current->identity_revision) return JNI_TRUE;
    auto updated = std::make_unique<nestspace::Environment>(*current);
    updated->identity_revision = revision;
    nestspace::publish_environment(std::move(updated));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeRefreshHooks(
    JNIEnv* env,
    jclass,
    jboolean scan_elf_fallback
) {
    return hook_state_array(
        env,
        nestspace::refresh_hooks(scan_elf_fallback == JNI_TRUE)
    );
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeHookSnapshot(
    JNIEnv* env,
    jclass
) {
    return hook_state_array(env, nestspace::hook_state());
}

extern "C" JNIEXPORT void JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeClear(
    JNIEnv*,
    jclass
) {
    nestspace::clear_environment();
    nestspace::restore_hooks();
}
