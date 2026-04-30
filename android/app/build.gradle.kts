plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.temuxllm"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.temuxllm.service"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-phase2a"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        // litert_lm_main is shipped as an asset, not as a JNI lib, so no
        // jniLibs filter is needed yet. Phase 2b will revisit.
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
