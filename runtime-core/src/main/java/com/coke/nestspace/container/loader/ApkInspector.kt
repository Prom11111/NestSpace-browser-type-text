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
import android.content.res.AssetManager
import android.content.res.XmlResourceParser
import android.os.Build
import android.util.Log
import com.coke.nestspace.container.HiddenApiCompat
import com.coke.nestspace.container.intent.ContainerIntentFilter
import com.coke.nestspace.container.intent.ManifestIntentFilters
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.jar.JarFile
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
    val signingCertDigests: Set<String>,
    val v1SignatureSha256: String?,
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
        if (info == null) {
            // Some platforms return null for config splits whose manifest has
            // android:hasCode="false" (for example a split with native libs).
            // Splits do not need the component data, so read their manifest and
            // v1 signer directly instead of rejecting the whole install.
            return inspectSplitFallback(apkPath, requireLauncher)
        }

        val appInfo = info.applicationInfo ?: error(AppLanguage.tr("APK 没有 applicationInfo", "APK has no applicationInfo"))
        appInfo.sourceDir = apkPath
        appInfo.publicSourceDir = apkPath

        val filters = ManifestIntentFilters.parse(apkPath)
        val activityInfos = info.activities
            ?.filter { !it.name.isNullOrBlank() }
            ?.toMutableList()
            ?: mutableListOf()
        filters.activityAliases.forEach { (alias, target) ->
            if (activityInfos.none { it.name == alias }) {
                val targetInfo = activityInfos.firstOrNull { it.name == target }
                if (targetInfo != null) {
                    activityInfos += ActivityInfo(targetInfo).apply {
                        name = alias
                        targetActivity = target
                    }
                }
            }
        }
        val enabledActivities = activityInfos.filter { it.enabled }
        val launcherName = filters.activities.entries.firstOrNull { (_, componentFilters) ->
            componentFilters.any { filter ->
                filter.hasAction(Intent.ACTION_MAIN) &&
                    filter.hasCategory(Intent.CATEGORY_LAUNCHER)
            }
        }?.key
        val launcher = launcherName?.let { alias ->
            enabledActivities.firstOrNull { it.name == alias }
                ?: filters.activityAliases[alias]?.let { target ->
                    enabledActivities.firstOrNull { it.name == target }?.let { targetInfo ->
                        ActivityInfo(targetInfo).apply {
                            name = alias
                            targetActivity = target
                        }
                    }
                }
        }
        val activity = launcher
            ?: enabledActivities.firstOrNull { it.exported }
            ?: enabledActivities.firstOrNull()

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
            launcherActivity = launcherName ?: activity?.name,
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
            signingCertDigests = signingCertDigests(info),
            v1SignatureSha256 = v1SignatureDigest(apkPath),
            requiresSecureEnv = requiresSecureEnv(appInfo),
            nativeAbis = nativeAbis(apkPath)
        )
    }

    @Suppress("DEPRECATION")
    internal fun signatureDigest(info: PackageInfo): String? {
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

    /**
     * All certificates the platform associates with an APK, including older
     * certificates retained for signing-key rotation. A config split may only
     * expose the older v1 certificate, so comparing one certificate digest
     * against the base APK's current signer rejects valid split installs.
     */
    @Suppress("DEPRECATION")
    private fun signingCertDigests(info: PackageInfo): Set<String> {
        val certificates = buildList {
            info.signingInfo?.apkContentsSigners?.let(::addAll)
            info.signingInfo?.signingCertificateHistory?.let(::addAll)
            info.signatures?.let(::addAll)
        }
        return certificates
            .map { signature ->
                MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }
            .toSet()
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

    private data class SplitManifest(
        val packageName: String,
        val splitName: String,
        val versionCode: Long,
        val versionName: String?,
        val minSdk: Int,
        val targetSdk: Int
    )

    /**
     * Fallback for config split APKs that [PackageManager.getPackageArchiveInfo]
     * cannot parse on some platform builds (typically manifests with
     * android:hasCode="false" and android:extractNativeLibs="true").
     *
     * Splits contain no launcher or components, so only the package name,
     * split name, version and signing certificate are needed. The v1
     * certificate is used because the platform parser is unavailable.
     */
    private fun inspectSplitFallback(apkPath: String, requireLauncher: Boolean): ApkDescriptor {
        val manifest = parseSplitManifest(apkPath)
        if (manifest == null || requireLauncher) {
            error(AppLanguage.tr("无法解析 APK manifest: $apkPath", "Could not parse APK manifest: $apkPath"))
        }
        val signature = v1SignatureDigest(apkPath)
            ?: error(
                AppLanguage.tr(
                    "无法读取 split APK 签名: $apkPath",
                    "Could not read the split APK signature: $apkPath"
                )
            )
        val appInfo = ApplicationInfo().apply {
            packageName = manifest.packageName
            sourceDir = apkPath
            publicSourceDir = apkPath
            minSdkVersion = manifest.minSdk
            targetSdkVersion = manifest.targetSdk
        }
        @Suppress("DEPRECATION")
        val packageInfo = PackageInfo().apply {
            packageName = manifest.packageName
            versionCode = manifest.versionCode.toInt()
            versionName = manifest.versionName
            splitNames = arrayOf(manifest.splitName)
            applicationInfo = appInfo
        }
        return ApkDescriptor(
            apkPath = apkPath,
            packageName = manifest.packageName,
            label = manifest.packageName,
            launcherActivity = null,
            activityInfo = null,
            activities = emptyList(),
            services = emptyList(),
            providers = emptyList(),
            receivers = emptyList(),
            activityFilters = emptyMap(),
            serviceFilters = emptyMap(),
            receiverFilters = emptyMap(),
            packageInfo = packageInfo,
            applicationInfo = appInfo,
            splitName = manifest.splitName,
            versionCode = manifest.versionCode,
            versionName = manifest.versionName,
            minSdk = manifest.minSdk,
            targetSdk = manifest.targetSdk,
            signatureSha256 = signature,
            signingCertDigests = v1CertificateDigests(apkPath),
            v1SignatureSha256 = signature,
            requiresSecureEnv = false,
            nativeAbis = nativeAbis(apkPath)
        )
    }

    private fun parseSplitManifest(apkPath: String): SplitManifest? {
        HiddenApiCompat.ensure()
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        return try {
            val cookie = AssetManager::class.java
                .getMethod("addAssetPath", String::class.java)
                .invoke(assets, apkPath) as? Int ?: 0
            if (cookie == 0) {
                null
            } else {
                var packageName = ""
                var splitName: String? = null
                var versionCode = 0L
                var versionName: String? = null
                var minSdk = 1
                var targetSdk = 0
                assets.openXmlResourceParser(cookie, "AndroidManifest.xml").use { xml ->
                    var event = xml.eventType
                    while (event != XmlResourceParser.END_DOCUMENT) {
                        if (event == XmlResourceParser.START_TAG) {
                            when (xml.name) {
                                "manifest" -> {
                                    packageName = xml.getAttributeValue(null, "package").orEmpty()
                                    splitName = xml.getAttributeValue(null, "split")
                                    versionCode = xml.getAttributeIntValue(
                                        ANDROID_NS,
                                        "versionCode",
                                        0
                                    ).toLong()
                                    versionName = xml.getAttributeValue(
                                        ANDROID_NS,
                                        "versionName"
                                    )?.takeUnless { it.startsWith("@") }
                                }

                                "uses-sdk" -> {
                                    minSdk = xml.getAttributeIntValue(
                                        ANDROID_NS,
                                        "minSdkVersion",
                                        minSdk
                                    )
                                    targetSdk = xml.getAttributeIntValue(
                                        ANDROID_NS,
                                        "targetSdkVersion",
                                        targetSdk
                                    )
                                }
                            }
                        }
                        event = xml.next()
                    }
                }
                if (packageName.isBlank() || splitName.isNullOrBlank()) {
                    null
                } else {
                    SplitManifest(
                        packageName = packageName,
                        splitName = splitName!!,
                        versionCode = versionCode,
                        versionName = versionName,
                        minSdk = minSdk,
                        targetSdk = targetSdk
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "split manifest fallback failed: $apkPath", t)
            null
        } finally {
            runCatching { assets.close() }
        }
    }

    /** Mirrors [signatureDigest] using the v1 signer certificate. */
    private fun v1SignatureDigest(apkPath: String): String? = runCatching {
        JarFile(File(apkPath)).use { jar ->
            val entry = jar.entries().asSequence().firstOrNull { entry ->
                entry.name.startsWith("META-INF/") &&
                    (entry.name.endsWith(".RSA", true) ||
                        entry.name.endsWith(".DSA", true) ||
                        entry.name.endsWith(".EC", true))
            } ?: return@use null
            val certs = CertificateFactory.getInstance("X.509")
                .generateCertificates(jar.getInputStream(entry))
                .toList()
            if (certs.isEmpty()) return@use null
            val ordered = certs
                .map { cert ->
                    cert.encoded.joinToString("") { "%02x".format(it) } to cert.encoded
                }
                .sortedBy { it.first }
            val digest = MessageDigest.getInstance("SHA-256")
            ordered.forEach { digest.update(it.second) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }.onFailure { Log.w(TAG, "read v1 signature failed: $apkPath", it) }.getOrNull()

    private fun v1CertificateDigests(apkPath: String): Set<String> = runCatching {
        JarFile(File(apkPath)).use { jar ->
            val entry = jar.entries().asSequence().firstOrNull { entry ->
                entry.name.startsWith("META-INF/") &&
                    (entry.name.endsWith(".RSA", true) ||
                        entry.name.endsWith(".DSA", true) ||
                        entry.name.endsWith(".EC", true))
            } ?: return@use emptySet()
            CertificateFactory.getInstance("X.509")
                .generateCertificates(jar.getInputStream(entry))
                .map { certificate ->
                    MessageDigest.getInstance("SHA-256")
                        .digest(certificate.encoded)
                        .joinToString("") { "%02x".format(it) }
                }
                .toSet()
        }
    }.onFailure { Log.w(TAG, "read v1 certificates failed: $apkPath", it) }
        .getOrDefault(emptySet())

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
}
