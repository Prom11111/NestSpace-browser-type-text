package com.coke.nestspace.fastpath

import com.coke.nestspace.i18n.AppLanguage
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

data class AppFunctionsStatus(
    val platformAvailable: Boolean,
    val managerAvailable: Boolean,
    val canExecuteOtherApps: Boolean,
    val note: String
)

/**
 * AppFunctions is Android 16+ (API 36).
 *
 * Permission reality:
 * - An app can always execute its own AppFunctions.
 * - Executing another app's AppFunctions needs
 *   android.permission.EXECUTE_APP_FUNCTIONS, which is privileged/system-level
 *   on current Android builds. A normal APK usually cannot get it.
 * - A container-internal APK's AppFunctions are not registered with the system,
 *   so they need a local bridge in the container process.
 *
 * This basic version exposes detection/status and tool plumbing only.
 */
object AppFunctionsBridge {
    private const val EXECUTE_APP_FUNCTIONS = "android.permission.EXECUTE_APP_FUNCTIONS"

    fun status(context: Context): AppFunctionsStatus {
        val platformAvailable = Build.VERSION.SDK_INT >= 36
        val managerAvailable = runCatching {
            Class.forName("android.app.appfunctions.AppFunctionManager")
        }.isSuccess
        val canExecuteOtherApps = runCatching {
            context.checkSelfPermission(EXECUTE_APP_FUNCTIONS) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        val note = when {
            !platformAvailable ->
                AppLanguage.tr(
                    "Android 16+ 才支持 AppFunctions。",
                    "AppFunctions requires Android 16 or newer."
                )

            !managerAvailable ->
                AppLanguage.tr(
                    "系统未提供 AppFunctionManager；当前设备/ROM 不支持。",
                    "The system has no AppFunctionManager; this device/ROM does not support it."
                )

            canExecuteOtherApps ->
                AppLanguage.tr(
                    "检测到 EXECUTE_APP_FUNCTIONS 权限：可尝试系统 App 的 AppFunctions 快路径。",
                    "EXECUTE_APP_FUNCTIONS is granted: system app AppFunctions fast " +
                        "paths may be available."
                )

            else ->
                AppLanguage.tr(
                    "可用范围：宿主自身 AppFunctions + 容器内 APK 的本地桥接。" +
                        "调用其他系统 App 需要 EXECUTE_APP_FUNCTIONS 特权权限，普通 APK 通常没有。",
                    "Available scope: the host's own AppFunctions plus local " +
                        "bridging for APKs inside the container. Calling other system " +
                        "apps requires the privileged EXECUTE_APP_FUNCTIONS permission, " +
                        "which normal APKs usually do not have."
                )
        }

        return AppFunctionsStatus(
            platformAvailable = platformAvailable,
            managerAvailable = managerAvailable,
            canExecuteOtherApps = canExecuteOtherApps,
            note = note
        )
    }
}
