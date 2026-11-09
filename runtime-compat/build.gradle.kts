plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.coke.nestspace.runtime.compat"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    api(project(":runtime-api"))
    api(project(":runtime-core"))
    testImplementation(libs.junit)
}
