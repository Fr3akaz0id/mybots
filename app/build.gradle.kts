plugins {
    id("com.android.application") version "8.8.2"
    id("org.jetbrains.kotlin.android") version "2.1.10"
}

android {
    namespace = "com.freaklair.hermesgadget"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.freaklair.hermesgadget"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "1.0.1-android"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            // Keystore never lives in the repo. Path + password via env, or the
            // default local paths; builds without them still produce an APK,
            // just unsigned-for-install.
            val ks = file(System.getenv("GADGET_KEYSTORE") ?: System.getProperty("user.home") + "/.hermes/gadget-release.keystore")
            val pwFile = file(System.getenv("GADGET_KEYSTORE_PASS_FILE")
                ?: System.getProperty("user.home") + "/.hermes/gadget-release.pass")
            if (ks.exists() && pwFile.exists()) {
                storeFile = ks
                storePassword = pwFile.readText().trim()
                keyPassword = storePassword
                keyAlias = "gadget"
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(files("../libs/sherpa-onnx.aar"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
