package com.coke.nestspace.container.runtime

data class VirtualEnvironmentFacts(
    val contextPackageName: String,
    val applicationPackageName: String,
    val applicationUid: Int,
    val processUid: Int,
    val processPid: Int,
    val contextDataDir: String,
    val applicationDataDir: String,
    val contextIsDeviceProtected: Boolean,
    val deviceProtectedDataDir: String,
    val nativeSnapshot: NativeEnvironmentSnapshot?
)

data class EnvironmentIssue(
    val code: String,
    val severity: Severity,
    val message: String
) {
    enum class Severity { WARNING, ERROR }
}

data class EnvironmentValidation(
    val issues: List<EnvironmentIssue>
) {
    val isConsistent: Boolean
        get() = issues.none { it.severity == EnvironmentIssue.Severity.ERROR }
}

object VirtualEnvironmentValidator {
    fun validate(
        environment: VirtualEnvironment,
        facts: VirtualEnvironmentFacts
    ): EnvironmentValidation {
        val issues = mutableListOf<EnvironmentIssue>()

        if (facts.contextPackageName != environment.packageName) {
            issues += error(
                "CONTEXT_PACKAGE_MISMATCH",
                "Context package ${facts.contextPackageName} != ${environment.packageName}"
            )
        }
        if (facts.applicationPackageName != environment.packageName) {
            issues += error(
                "APPLICATION_PACKAGE_MISMATCH",
                "Application package ${facts.applicationPackageName} != ${environment.packageName}"
            )
        }
        if (facts.applicationUid != environment.observedUid) {
            issues += error(
                "APPLICATION_UID_MISMATCH",
                "Application UID ${facts.applicationUid} != observed ${environment.observedUid}"
            )
        }
        if (facts.processUid != environment.hostUid) {
            issues += error(
                "PROCESS_UID_MISMATCH",
                "Process UID ${facts.processUid} != host ${environment.hostUid}"
            )
        }
        if (facts.processPid != environment.virtualPid) {
            issues += warning(
                "PROCESS_PID_CHANGED",
                "Process PID ${facts.processPid} != snapshot ${environment.virtualPid}"
            )
        }
        if (facts.contextDataDir != environment.dataRoot) {
            issues += error(
                "CONTEXT_DATA_DIR_MISMATCH",
                "Context dataDir ${facts.contextDataDir} != ${environment.dataRoot}"
            )
        }
        if (facts.applicationDataDir != environment.dataRoot) {
            issues += error(
                "APPLICATION_DATA_DIR_MISMATCH",
                "Application dataDir ${facts.applicationDataDir} != ${environment.dataRoot}"
            )
        }
        if (!facts.contextIsDeviceProtected) {
            issues += error(
                "DEVICE_PROTECTED_CONTEXT_MISMATCH",
                "Derived context does not report device-protected storage"
            )
        }
        if (facts.deviceProtectedDataDir != environment.deviceProtectedDataRoot) {
            issues += error(
                "DEVICE_PROTECTED_DATA_DIR_MISMATCH",
                "Device-protected dataDir ${facts.deviceProtectedDataDir} != " +
                    environment.deviceProtectedDataRoot
            )
        }

        val native = facts.nativeSnapshot
        if (native == null) {
            issues += warning(
                "NATIVE_SNAPSHOT_UNAVAILABLE",
                "Native environment snapshot is unavailable"
            )
        } else {
            if (native.configuredHostUid != environment.hostUid) {
                issues += error(
                    "NATIVE_HOST_UID_MISMATCH",
                    "Native host UID ${native.configuredHostUid} != ${environment.hostUid}"
                )
            }
            if (native.configuredObservedUid != environment.observedUid) {
                issues += error(
                    "NATIVE_OBSERVED_UID_MISMATCH",
                    "Native observed UID ${native.configuredObservedUid} != " +
                        environment.observedUid
                )
            }
            if (native.configuredPid != environment.virtualPid) {
                issues += warning(
                    "NATIVE_PID_MISMATCH",
                    "Native PID ${native.configuredPid} != ${environment.virtualPid}"
                )
            }
            if (native.processUid != environment.hostUid) {
                issues += error(
                    "NATIVE_PROCESS_UID_MISMATCH",
                    "Native process UID ${native.processUid} != ${environment.hostUid}"
                )
            }
        }

        return EnvironmentValidation(issues)
    }

    private fun error(code: String, message: String) = EnvironmentIssue(
        code = code,
        severity = EnvironmentIssue.Severity.ERROR,
        message = message
    )

    private fun warning(code: String, message: String) = EnvironmentIssue(
        code = code,
        severity = EnvironmentIssue.Severity.WARNING,
        message = message
    )
}
