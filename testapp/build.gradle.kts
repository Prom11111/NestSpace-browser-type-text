plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.coke.testapp"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.coke.testapp"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        manifestPlaceholders["requireSecureEnv"] = "0"
    }

    buildTypes {
        getByName("debug") {}
        create("secure") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".secure"
            manifestPlaceholders["requireSecureEnv"] = "1"
            matchingFallbacks += listOf("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
