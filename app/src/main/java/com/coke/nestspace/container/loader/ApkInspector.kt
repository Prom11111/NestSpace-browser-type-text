package com.coke.nestspace.container.loader

import com.coke.nestspace.i18n.AppLanguage
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import com.coke.nestspace.container.intent.ContainerIntentFilter
import com.coke.nestspace.container.intent.ManifestIntentFilters
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

data class ApkDescriptor(
    val apkPath: String,
    val packageName: String,
    val label: String,
    val launcherActivity: String?,
    val activityInfo: ActivityInfo?,
    val activities: List<ActivityInfo>,
    val services: List<ServiceInfo>,
    val providers: List<ProviderInfo>,
    val receivers: List<ActivityInfo>,
    val activityFilters: Map<String, List<ContainerIntentFilter>>,
    val serviceFilters: Map<String, List<ContainerIntentFilter>>,
    val receiverFilters: Map<String, List<ContainerIntentFilter>>,
    val packageInfo: PackageInfo,
    val applicationInfo: ApplicationInfo,
    val splitName: String?,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int,
    val targetSdk: Int,
    val signatureSha256: String?,
    val requiresSecureEnv: Boolean,
    val nativeAbis: Set<String>
) {
    val hasLauncher: Boolean get() = activityInfo != null && !launcherActivity.isNullOrBlank()
    val hasNativeLibs: Boolean get() = nativeAbis.isNotEmpty()
}

object ApkInspector {

    private const val TAG = "ApkInspector"

    /**
     * Parses an APK (base or split) without installing it.
     *
     * @param requireLauncher when true, the APK must contain an Activity. Use false when
     * inspecting split APKs, which normally have no launcher Activity.
     */
    @Suppress("DEPRECATION")
    fun inspect(
        context: Context,
        apkPath: String,
        requireLauncher: Boolean = true
    ): ApkDescriptor {
        val pm = context.packageManager
        var flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
            PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS or
            PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA or
            PackageManager.GET_SIGNATURES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            flags = flags or PackageManager.GET_SIGNING_CERTIFICATES
        }
        val info = pm.getPackageArchiveInfo(apkPath, flags)
            ?: error(AppLanguage.tr("无法解析 APK manifest: $apkPath", "Could not parse APK manifest: $apkPath"))

        val appInfo = info.applicationInfo ?: error(AppLanguage.tr("APK 没有 applicationInfo", "APK has no applicationInfo"))
        appInfo.sourceDir = apkPath
        appInfo.publicSourceDir = apkPath

        val filters = ManifestIntentFilters.parse(apkPath)
        val activityInfos = info.activities
            ?.filter { !it.name.isNullOrBlank() }
            .orEmpty()
        val launcher = activityInfos.firstOrNull { candidate ->
            filters.activities[candidate.name]?.any { filter ->
                filter.hasAction(Intent.ACTION_MAIN) &&
                    filter.hasCategory(Intent.CATEGORY_LAUNCHER)
            } == true
        }
        val activity = launcher
            ?: activityInfos.firstOrNull { it.exported }
            ?: activityInfos.firstOrNull()

        if (requireLauncher && activity == null) {
            error(
                AppLanguage.tr(
                    "APK 没有 Activity: ${info.packageName}",
                    "APK has no Activity: ${info.packageName}"
                )
            )
        }

        val label = runCatching {
            appInfo.nonLocalizedLabel?.toString()
                ?: appInfo.loadLabel(pm).toString()
        }.getOrDefault(info.packageName)

        return ApkDescriptor(
            apkPath = apkPath,
            packageName = info.packageName,
            label = label,
            launcherActivity = activity?.name,
            activityInfo = activity,
            activities = activityInfos,
            services = info.services?.filter { !it.name.isNullOrBlank() }.orEmpty(),
            providers = info.providers?.filter { !it.name.isNullOrBlank() }.orEmpty(),
            receivers = info.receivers?.filter { !it.name.isNullOrBlank() }.orEmpty(),
            activityFilters = filters.activities,
            serviceFilters = filters.services,
            receiverFilters = filters.receivers,
            packageInfo = info,
            applicationInfo = appInfo,
            splitName = info.splitNames?.firstOrNull { it.isNotBlank() },
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            },
            versionName = info.versionName,
            minSdk = appInfo.minSdkVersion.takeIf { it > 0 } ?: 1,
            targetSdk = appInfo.targetSdkVersion,
            signatureSha256 = signatureDigest(info),
            requiresSecureEnv = requiresSecureEnv(appInfo),
            nativeAbis = nativeAbis(apkPath)
        )
    }

    @Suppress("DEPRECATION")
    private fun signatureDigest(info: PackageInfo): String? {
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.takeIf { it.isNotEmpty() }
                ?: info.signingInfo?.signingCertificateHistory?.takeIf { it.isNotEmpty() }
                ?: info.signatures
        } else {
            info.signatures
        }
        if (signatures.isNullOrEmpty()) return null

        val digest = MessageDigest.getInstance("SHA-256")
        signatures.sortedBy { it.toCharsString() }.forEach { digest.update(it.toByteArray()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun requiresSecureEnv(appInfo: ApplicationInfo): Boolean {
        val value = runCatching { appInfo.metaData?.get("REQUIRE_SECURE_ENV") }.getOrNull()
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value == "1" || value.equals("true", ignoreCase = true)
            else -> false
        }
    }

    fun nativeAbis(apkPath: String): Set<String> = runCatching {
        ZipFile(File(apkPath)).use { zip ->
            zip.entries().asSequence()
                .mapNotNull { entry ->
                    val parts = entry.name.split('/')
                    if (parts.size >= 3 && parts[0] == "lib" && entry.name.endsWith(".so")) {
                        parts[1]
                    } else {
                        null
                    }
                }
                .toSet()
        }
    }.onFailure { Log.w(TAG, "read zip failed: $apkPath", it) }
        .getOrDefault(emptySet())
}
