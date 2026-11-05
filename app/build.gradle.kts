plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val tagVersionName = providers.gradleProperty("versionName").orNull
    ?.trim()
    ?.takeIf { it.isNotBlank() }
val tagVersionCode = providers.gradleProperty("versionCode").orNull?.toIntOrNull()

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