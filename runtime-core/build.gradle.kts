plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.coke.nestspace.runtime.core"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
    }

    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":plugin-api"))
    implementation(project(":runtime-plugin-api"))
    api(project(":runtime-api"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hiddenapibypass)
    implementation(libs.json)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
