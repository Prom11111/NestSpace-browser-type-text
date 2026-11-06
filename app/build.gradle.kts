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
val supportedHostAbis = listOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86", "x86_64")
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

android {
    namespace = "com.coke.nestspace"
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

    buildTypes {
        release {
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
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Per-app WebView proxy override for the built-in browser.
    implementation("androidx.webkit:webkit:1.18.0")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Basic app: local MCP server + local agent + space management
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Offline (on-device) speech recognition. The small language model is
    // downloaded once into app storage by LocalSpeechModelStore.
    implementation("com.alphacephei:vosk-android:0.3.47")
    // Required for Android 9+ hidden APIs used by the Stage B single-process
    // container (Activity.attach / AssetManager.addAssetPath).
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}