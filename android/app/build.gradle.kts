import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.temuxllm"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.temuxllm.service"
        minSdk = 33
        // targetSdk lags compileSdk so we don't opt into Android 16 runtime
        // behaviour changes until we've actually validated them on a device.
        targetSdk = 35
        versionCode = 7
        versionName = "0.3.1"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs {
            // The Maven SDK ships its own native libs as JNI; let AGP merge them
            // into the APK normally. We no longer ship a CLI binary here.
            useLegacyPackaging = true
        }
    }
}

// Kotlin 2.x: use compilerOptions DSL instead of the deprecated kotlinOptions block.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // Coroutines for the Engine.sendMessageAsync() Flow consumer.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // In-process LiteRT-LM Android SDK (replaces the subprocess wrapper around
    // the v0.11.0-rc.1 CLI binary). Bumps the heavy lifting off ProcessBuilder
    // and into a shared engine that loads the model once and serves multiple
    // /api/generate calls. Also unlocks streaming via Kotlin Flow.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.11.0-rc1")
}
