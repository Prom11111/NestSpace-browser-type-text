package com.coke.nestspace.container.runtime

import org.json.JSONArray
import org.json.JSONObject

/**
 * Secret-free runtime snapshot. It describes observable process facts and
 * startup results, but never serializes identity values, cookies or requests.
 */
object RuntimeDiagnostics {
    const val SCHEMA_VERSION = 1
    const val MAIN_FILE_NAME = "runtime-diagnostics.json"

    fun fileName(packageName: String, processName: String): String {
        if (processName == packageName) return MAIN_FILE_NAME
        val safe = processName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "runtime-diagnostics-$safe.json"
    }

    fun build(
        environment: VirtualEnvironment,
        identity: VirtualIdentitySnapshot,
        validation: EnvironmentValidation,
        startup: AndroidStartupReport,
        native: NativeEnvironmentSnapshot?,
        probe: NativeRuntimeProbe?,
        processUid: Int,
        processPid: Int,
        headless: Boolean,
        pathMapProbe: String? = null,
        externalPathMapProbe: String? = null,
        procViewProbe: String? = null,
        propertyValueProbe: String? = null,
        propertyCallbackProbe: String? = null
    ): JSONObject = JSONObject()
        .put("schema_version", SCHEMA_VERSION)
        .put("generated_at", System.currentTimeMillis())
        .put("instance_id", environment.instanceId)
        .put("package", environment.packageName)
        .put("version_name", environment.versionName ?: JSONObject.NULL)
        .put("version_code", environment.versionCode)
        .put("environment_version", 1)
        .put("identity_revision", identity.revision)
        .put("capabilities", JSONArray(environment.capabilities.map { it.name }.sorted()))
        .put("java_uid", processUid)
        .put("expected_observed_uid", environment.observedUid)
        .put("host_uid", environment.hostUid)
        .put("native_uid", native?.processUid ?: JSONObject.NULL)
        .put("direct_syscall_uid", probe?.directSyscallUid ?: JSONObject.NULL)
        .put("proc_status_uid", probe?.procStatusUid ?: JSONObject.NULL)
        .put("process_name", environment.processName)
        .put("native_process_name", native?.processName ?: JSONObject.NULL)
        .put("pid", processPid)
        .put("android_api", probeAndroidApi(environment))
        .put("page_size", native?.pageSize ?: probe?.pageSize ?: 0L)
        .put("headless", headless)
        .put(
            "storage",
            JSONObject()
                .put("credential", "instance")
                .put(
                    "device_protected",
                    if (RuntimeCapability.DEVICE_PROTECTED_STORAGE in environment.capabilities) {
                        "instance"
                    } else {
                        "unverified"
                    }
                )
                .put(
                    "external",
                    if (RuntimeCapability.EXTERNAL_STORAGE in environment.capabilities) {
                        "instance"
                    } else {
                        "unverified"
                    }
                )
        )
        .put(
            "environment_validation",
            JSONObject()
                .put("consistent", validation.isConsistent)
                .put(
                    "issues",
                    JSONArray(
                        validation.issues.map { issue ->
                            JSONObject()
                                .put("code", issue.code)
                                .put("severity", issue.severity.name)
                                .put("message", issue.message)
                        }
                    )
                )
        )
        .put(
            "native_facade",
            JSONObject()
                .put(
                    "mode",
                    if ((native?.hookCount ?: 0) > 0) "active" else "diagnostic"
                )
                .put(
                    "uid_hook",
                    if ((native?.hookCount ?: 0) > 0) "active" else "disabled"
                )
                .put(
                    "proc_hook",
                    when {
                        (native?.virtualProcReadCount ?: 0) > 0 -> "active"
                        (native?.hookCount ?: 0) > 0 -> "available-unexercised"
                        else -> "unverified"
                    }
                )
                .put(
                    "property_hook",
                    when {
                        (native?.propertyReadCount ?: 0) > 0 -> "active"
                        (native?.hookCount ?: 0) > 0 -> "get-available-callback-unverified"
                        else -> "disabled"
                    }
                )
                .put(
                    "path_hook",
                    if ((native?.hookCount ?: 0) > 0) "active" else "disabled"
                )
                .put(
                    "direct_syscall_probe",
                    if (probe?.directSyscallUid == processUid) "verified" else "unverified"
                )
                .put(
                    "direct_syscall_hook",
                    if ((native?.hookCount ?: 0) > 0) {
                        "got-active-inline-unverified"
                    } else {
                        "disabled"
                    }
                )
                .put(
                    "path_map_probe",
                    if (pathMapProbe != null) "verified" else "unverified"
                )
                .put(
                    "external_path_map_probe",
                    if (externalPathMapProbe != null) "verified" else "unverified"
                )
                .put(
                    "proc_view_probe",
                    if (procViewProbe != null) "verified" else "unverified"
                )
                .put(
                    "property_value_probe",
                    if (propertyValueProbe != null) "verified" else "unverified"
                )
                .put(
                    "property_callback_probe",
                    if (propertyCallbackProbe != null) "verified" else "unverified"
                )
                .put(
                    "direct_openat_probe",
                    if (probe?.directSyscallOpenReadable == true) "verified" else "unverified"
                )
                .put(
                    "direct_statx_probe",
                    if (probe?.directSyscallStatReadable == true) "verified" else "unverified"
                )
                .put(
                    "proc_mountinfo_probe",
                    if (probe?.procMountInfoReadable == true) "verified" else "unverified"
                )
                .put(
                    "selinux_context_probe",
                    if (probe?.selinuxContextReadable == true) "verified" else "unverified"
                )
                .put(
                    "hooks",
                    JSONArray(
                        if ((native?.hookCount ?: 0) > 0) {
                            listOf(
                                "getuid",
                                "geteuid",
                                "open",
                                "__open_2",
                                "openat",
                                "__openat_2",
                                "access",
                                "stat",
                                "readlink",
                                "__readlink_chk",
                                "__readlinkat_chk",
                                "close",
                                "dup",
                                "dup2",
                                "dup3",
                                "fcntl",
                                "syscall",
                                "fopen",
                                "fopen64",
                                "fclose",
                                "__system_property_get",
                                "__system_property_find",
                                "__system_property_read_callback",
                                "__system_property_read",
                                "__system_property_serial",
                                "proc:status",
                                "proc:cmdline",
                                "proc:maps",
                                "proc:mountinfo",
                                "proc:fd"
                            )
                        } else {
                            emptyList()
                        }
                    )
                )
                .put("patched_slots", native?.hookCount ?: 0)
                .put("patched_modules", native?.hookedModuleCount ?: 0)
                .put(
                    "scan",
                    JSONObject()
                        .put("modules", native?.scannedModuleCount ?: 0)
                        .put("targets", native?.targetModuleCount ?: 0)
                        .put("errors", native?.hookErrorCount ?: 0)
                        .put("virtual_proc_reads", native?.virtualProcReadCount ?: 0)
                        .put("property_reads", native?.propertyReadCount ?: 0)
                )
        )
        .put(
            "startup",
            JSONObject()
                .put("completed", startup.completed)
                .put("total_duration_ms", startup.totalDurationMillis)
                .put(
                    "phases",
                    JSONArray(
                        startup.phases.map { phase ->
                            JSONObject()
                                .put("phase", phase.phase.name)
                                .put("outcome", phase.outcome.name)
                                .put("duration_ms", phase.durationMillis)
                                .put("detail", phase.detail ?: JSONObject.NULL)
                                .put("failure", phase.failure ?: JSONObject.NULL)
                        }
                    )
                )
        )

    private fun probeAndroidApi(environment: VirtualEnvironment): Int =
        environment.properties["runtime.android_api"]?.toIntOrNull() ?: 0
}
