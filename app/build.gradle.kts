plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.zmeyka.avpnp"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.zmeyka.avpnp"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        aidl = true
    }

    // The TUN helper must exist as a real executable on disk so `su` can run it.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Provided by LSPosed at runtime; never bundled.
    compileOnly("de.robv.android.xposed:api:82")
}
