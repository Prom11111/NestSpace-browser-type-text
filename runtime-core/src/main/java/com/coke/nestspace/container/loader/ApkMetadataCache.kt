package com.coke.nestspace.container.loader

import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo
import android.os.Parcel
import android.os.Parcelable
import android.util.AtomicFile
import android.util.Log
import com.coke.nestspace.container.intent.ContainerIntentFilter
import com.coke.nestspace.engine.ApkInstallIndex
import java.io.File

/**
 * Process-shared cache for the expensive parts of [ApkInspector.inspect].
 *
 * The cache is accepted only after the immutable APK paths, sizes, mtimes and
 * payload hashes in [ApkInstallIndex] still match. Any decode or validation
 * failure returns null so callers can fall back to a complete inspection.
 */
internal object ApkMetadataCache {

    private const val TAG = "ApkMetadataCache"
    private const val CACHE_SCHEMA_VERSION = 3
    private const val CACHE_FILE = "apk-descriptor-v1.bin"
    private const val MAX_CACHE_BYTES = 64 * 1024 * 1024

    fun descriptorPath(baseApkPath: String): String =
        File(File(baseApkPath).parentFile, CACHE_FILE).absolutePath

    fun read(
        index: ApkInstallIndex,
        baseApkPath: String,
        expectedPackageName: String,
        expectedVersionCode: Long,
        expectedSignatureSha256: String?
    ): ApkDescriptor? {
        if (!index.isCurrent()) return null
        val expectedPath = descriptorPath(baseApkPath)
        if (File(expectedPath).absolutePath != File(index.descriptorCachePath).absolutePath) {
            return null
        }
        val bytes = runCatching {
            AtomicFile(File(expectedPath)).openRead().use { it.readBytes() }
        }.onFailure {
            Log.w(TAG, "read descriptor cache failed: $expectedPath", it)
        }.getOrNull()?.takeIf { it.size in 1..MAX_CACHE_BYTES } ?: return null
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            if (parcel.readInt() != CACHE_SCHEMA_VERSION) return null
            val descriptor = readDescriptor(parcel, baseApkPath, index.nativeAbis)
            if (descriptor.packageName != expectedPackageName ||
                descriptor.versionCode != expectedVersionCode ||
                (expectedSignatureSha256 != null &&
                    descriptor.signatureSha256 != expectedSignatureSha256)
            ) {
                null
            } else {
                descriptor
            }
        } catch (t: Throwable) {
            Log.w(TAG, "decode descriptor cache failed: $expectedPath", t)
            null
        } finally {
            parcel.recycle()
        }
    }

    fun write(descriptor: ApkDescriptor, index: ApkInstallIndex): Boolean {
        if (index.schemaVersion <= 0 || !index.isCurrent()) return false
        val target = File(index.descriptorCachePath)
        target.parentFile?.mkdirs()
        val parcel = Parcel.obtain()
        return try {
            parcel.writeInt(CACHE_SCHEMA_VERSION)
            writeDescriptor(parcel, descriptor)
            val bytes = parcel.marshall()
            val atomicFile = AtomicFile(target)
            val output = atomicFile.startWrite()
            try {
                output.write(bytes)
                atomicFile.finishWrite(output)
            } catch (t: Throwable) {
                atomicFile.failWrite(output)
                throw t
            }
            target.setReadOnly()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "write descriptor cache failed: ${target.absolutePath}", t)
            false
        } finally {
            parcel.recycle()
        }
    }

    private fun writeDescriptor(parcel: Parcel, descriptor: ApkDescriptor) {
        parcel.writeString(descriptor.packageName)
        parcel.writeString(descriptor.label)
        parcel.writeString(descriptor.launcherActivity)
        parcel.writeString(descriptor.splitName)
        parcel.writeLong(descriptor.versionCode)
        parcel.writeString(descriptor.versionName)
        parcel.writeInt(descriptor.minSdk)
        parcel.writeInt(descriptor.targetSdk)
        parcel.writeString(descriptor.signatureSha256)
        parcel.writeStringList(descriptor.signingCertDigests.sorted())
        parcel.writeString(descriptor.v1SignatureSha256)
        parcel.writeInt(if (descriptor.requiresSecureEnv) 1 else 0)
        parcel.writeParcelableBlob(descriptor.packageInfo)
        parcel.writeParcelableBlob(descriptor.applicationInfo)
        parcel.writeParcelableBlob(descriptor.activityInfo)
        parcel.writeInfoList(descriptor.activities)
        parcel.writeInfoList(descriptor.services)
        parcel.writeInfoList(descriptor.providers)
        parcel.writeInfoList(descriptor.receivers)
        parcel.writeFilters(descriptor.activityFilters)
        parcel.writeFilters(descriptor.serviceFilters)
        parcel.writeFilters(descriptor.receiverFilters)
    }

    @Suppress("DEPRECATION")
    private fun readDescriptor(
        parcel: Parcel,
        baseApkPath: String,
        nativeAbis: Set<String>
    ): ApkDescriptor {
        val packageName = parcel.readString().orEmpty()
        val label = parcel.readString().orEmpty()
        val launcherActivity = parcel.readString()
        val splitName = parcel.readString()
        val versionCode = parcel.readLong()
        val versionName = parcel.readString()
        val minSdk = parcel.readInt()
        val targetSdk = parcel.readInt()
        val signatureSha256 = parcel.readString()
        val signingCertDigests = parcel.createStringArrayList().orEmpty().toSet()
        val v1SignatureSha256 = parcel.readString()
        val requiresSecureEnv = parcel.readInt() != 0
        val loader = PackageInfo::class.java.classLoader
            ?: ApkMetadataCache::class.java.classLoader
        val packageInfo = requireNotNull(parcel.readParcelableBlob<PackageInfo>(loader))
        val applicationInfo = requireNotNull(
            parcel.readParcelableBlob<ApplicationInfo>(loader)
        )
        val activityInfo = parcel.readParcelableBlob<ActivityInfo>(loader)
        val activities = parcel.readInfoList<ActivityInfo>(loader)
        val services = parcel.readInfoList<ServiceInfo>(loader)
        val providers = parcel.readInfoList<ProviderInfo>(loader)
        val receivers = parcel.readInfoList<ActivityInfo>(loader)
        val activityFilters = parcel.readFilters()
        val serviceFilters = parcel.readFilters()
        val receiverFilters = parcel.readFilters()

        applicationInfo.sourceDir = baseApkPath
        applicationInfo.publicSourceDir = baseApkPath
        packageInfo.applicationInfo = applicationInfo
        packageInfo.activities = activities.toTypedArray()
        packageInfo.services = services.toTypedArray()
        packageInfo.providers = providers.toTypedArray()
        packageInfo.receivers = receivers.toTypedArray()

        return ApkDescriptor(
            apkPath = baseApkPath,
            packageName = packageName,
            label = label,
            launcherActivity = launcherActivity,
            activityInfo = activityInfo,
            activities = activities,
            services = services,
            providers = providers,
            receivers = receivers,
            activityFilters = activityFilters,
            serviceFilters = serviceFilters,
            receiverFilters = receiverFilters,
            packageInfo = packageInfo,
            applicationInfo = applicationInfo,
            splitName = splitName,
            versionCode = versionCode,
            versionName = versionName,
            minSdk = minSdk,
            targetSdk = targetSdk,
            signatureSha256 = signatureSha256,
            signingCertDigests = signingCertDigests,
            v1SignatureSha256 = v1SignatureSha256,
            requiresSecureEnv = requiresSecureEnv,
            nativeAbis = nativeAbis
        )
    }

    private fun Parcel.writeInfoList(values: List<Parcelable>) {
        writeInt(values.size)
        values.forEach { writeParcelableBlob(it) }
    }

    private fun <T> Parcel.readInfoList(loader: ClassLoader): List<T> {
        val size = readInt()
        return buildList(size) {
            repeat(size) {
                readParcelableBlob<T>(loader)?.let(::add)
            }
        }
    }

    private fun Parcel.writeParcelableBlob(value: Parcelable?) {
        val inner = Parcel.obtain()
        try {
            inner.writeParcelable(value, 0)
            writeByteArray(inner.marshall())
        } finally {
            inner.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun <T> Parcel.readParcelableBlob(loader: ClassLoader): T? {
        val bytes = createByteArray() ?: return null
        val inner = Parcel.obtain()
        return try {
            inner.unmarshall(bytes, 0, bytes.size)
            inner.setDataPosition(0)
            inner.readParcelable(loader)
        } finally {
            inner.recycle()
        }
    }

    private fun Parcel.writeFilters(
        values: Map<String, List<ContainerIntentFilter>>
    ) {
        writeInt(values.size)
        values.forEach { (component, filters) ->
            writeString(component)
            writeInt(filters.size)
            filters.forEach { writeFilter(it) }
        }
    }

    private fun Parcel.writeFilter(filter: ContainerIntentFilter) {
        writeStringList(filter.actions.toList())
        writeStringList(filter.categories.toList())
        writeStringList(filter.schemes.toList())
        writeStringList(filter.mimeTypes.toList())
        writeInt(filter.priority)
        writeInt(filter.authorities.size)
        filter.authorities.forEach {
            writeString(it.host)
            writeString(it.port)
        }
        writeInt(filter.paths.size)
        filter.paths.forEach {
            writeInt(it.kind.ordinal)
            writeString(it.value)
        }
        writeInt(filter.schemeSpecificParts.size)
        filter.schemeSpecificParts.forEach {
            writeInt(it.kind.ordinal)
            writeString(it.value)
        }
    }

    private fun Parcel.readFilters(): Map<String, List<ContainerIntentFilter>> {
        val result = linkedMapOf<String, List<ContainerIntentFilter>>()
        repeat(readInt()) {
            val component = readString().orEmpty()
            val filters = List(readInt()) { readFilter() }
            result[component] = filters
        }
        return result
    }

    private fun Parcel.readFilter(): ContainerIntentFilter =
        ContainerIntentFilter().apply {
            actions.addAll(createStringArrayList().orEmpty())
            categories.addAll(createStringArrayList().orEmpty())
            schemes.addAll(createStringArrayList().orEmpty())
            mimeTypes.addAll(createStringArrayList().orEmpty())
            priority = readInt()
            repeat(readInt()) {
                authorities.add(
                    ContainerIntentFilter.Authority(
                        host = readString().orEmpty(),
                        port = readString()
                    )
                )
            }
            repeat(readInt()) {
                paths.add(
                    ContainerIntentFilter.PathSpec(
                        kind = ContainerIntentFilter.PathKind.entries[readInt()],
                        value = readString().orEmpty()
                    )
                )
            }
            repeat(readInt()) {
                schemeSpecificParts.add(
                    ContainerIntentFilter.SspSpec(
                        kind = ContainerIntentFilter.SspKind.entries[readInt()],
                        value = readString().orEmpty()
                    )
                )
            }
        }
}
