plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val tagVersionName = providers.gradleProperty("versionName").orNull
    ?.trim()
    ?.takeIf { it.isNotBlank() }
val tagVersionCode = providers.gradleProperty("versionCode").orNull?.toIntOrNull()

// -PnestspaceHostAbi selects the native ABIs packaged in the host APK:
//   universal    all ABIs, for users who do not want to pick
//   arm64        ARM64 phones and ARM-translation emulators (MuMu);
//                maps to the arm64-v8a ABI
//   x86_64       x86_64 PCs/emulators without an ARM translator
//   <abi>[,<abi>] any explicit list of the supported ABIs
// gradle.properties sets arm64 for IDE/plain builds, and every release
// variant passes an explicit value; there is no implicit multi-ABI default.
val supportedHostAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
val abiAliases = mapOf("arm64" to "arm64-v8a")
val hostAbi = providers.gradleProperty("nestspaceHostAbi").orNull
    ?.trim()
    ?.lowercase()
    ?: error(
        "nestspaceHostAbi is not set; add it to gradle.properties or pass " +
            "-PnestspaceHostAbi=universal|arm64|x86_64"
    )
val hostAbis = when (hostAbi) {
    "universal" -> supportedHostAbis
    else -> {
        val abis = hostAbi.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { abiAliases[it] ?: it }
        require(abis.isNotEmpty() && abis.all { it in supportedHostAbis }) {
            "Unknown nestspaceHostAbi=$hostAbi " +
                "(supported: universal, arm64, ${supportedHostAbis.joinToString()})"
        }
        abis
    }
}

// Release signing material is injected through environment variables so CI can
// keep the keystore and passwords in GitHub Secrets. Local release builds
// without these variables stay unsigned. Tag builds set
// NESTSPACE_REQUIRE_RELEASE_SIGNING=1 and fail fast when a secret is missing.
val releaseKeystorePath = providers.environmentVariable("NESTSPACE_KEYSTORE").orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
val releaseStorePassword = providers.environmentVariable("NESTSPACE_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("NESTSPACE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("NESTSPACE_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseKeystorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }
val requireReleaseSigning =
    providers.environmentVariable("NESTSPACE_REQUIRE_RELEASE_SIGNING").orNull?.toBoolean() == true
if (requireReleaseSigning && !hasReleaseSigning) {
    error(
        "Release signing is required, but NESTSPACE_KEYSTORE, " +
            "NESTSPACE_KEYSTORE_PASSWORD, NESTSPACE_KEY_ALIAS and " +
            "NESTSPACE_KEY_PASSWORD must all be set."
    )
}

fun devPluginProjectName(directoryName: String): String =
    directoryName.lowercase()
        .replace(Regex("[^a-z0-9_-]+"), "-")
        .trim('-')

val discoveredDevPluginProjects = buildList {
    add(":dev-plugin-runtime-demo")
    file("../dev-plugins").listFiles()
        ?.filter { it.isDirectory }
        ?.sortedBy { it.name }
        ?.forEach { directory ->
            val name = devPluginProjectName(directory.name)
            if (name.isNotEmpty() &&
                File(directory, "build.gradle.kts").isFile
            ) {
                add(":dev-plugin-$name")
            }
        }
}

android {
    namespace = "com.coke.nestspace"
    ndkVersion = "27.2.12479018"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.coke.nestspace"
        minSdk = 24
        targetSdk = 37
        // CI derives these from a vX.Y.Z tag; local builds fall back to 1/1.0.
        versionCode = tagVersionCode ?: 1
        versionName = tagVersionName ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The arm64 host (the project default; ABI arm64-v8a) omits x86_64
        // native libraries.
        //
        // On x86_64 devices with an ARM translator (MuMu and similar),
        // PackageManager picks the first device ABI found in the APK. If the
        // host installs as x86_64, target ARM libraries are opened through the
        // native-bridge "fallback" path, which loses the ARM linker namespace:
        // WeChat's libwechatnormsg.so aborts in JNI_OnLoad (__stack_chk_fail)
        // right after dlsym("getenv") from /system/lib64/libc.so. Without an
        // x86_64 library the same device installs the host as arm64-v8a (an
        // ARM-translated app), and target ARM libraries load in the ARM
        // namespace exactly like on a real phone. The universal package also
        // contains x86_64 and will install as x86_64 on those devices; pick
        // arm64 there.
        //
        // Use -PnestspaceHostAbi=universal/arm64/x86_64 to build the release
        // variants. The x86_64 variant is for PCs/emulators without an ARM
        // translator and must not be used on a translator device.
        ndk {
            abiFilters += hostAbis
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(project(":plugin-api"))
    implementation(project(":runtime-api"))
    implementation(project(":runtime-core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Per-app WebView proxy override for the built-in browser.
    implementation(libs.androidx.webkit)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    discoveredDevPluginProjects.forEach {
        debugImplementation(project(it))
    }

    // Basic app: local MCP server + local agent + space management
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    // Offline (on-device) speech recognition. The small language model is
    // downloaded once into app storage by LocalSpeechModelStore.
    implementation(libs.vosk.android)
    // Required for Android 9+ hidden APIs used by the Stage B single-process
    // container (Activity.attach / AssetManager.addAssetPath).
    implementation(libs.hiddenapibypass)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
