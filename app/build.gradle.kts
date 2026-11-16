plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.voice.changer"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "org.voice.changer"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17 -O2 -fexceptions -frtti"
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DQNN_SDK_DIR=${project.file("src/main/cpp/third_party/qnn_sdk")}"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // Installs alongside anything else as <pkg>.tmp.
            applicationIdSuffix = ".tmp"
            isDebuggable = true
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    // Vendored: the offline cache has the extracted 1.20.0 artifacts but not
    // the AAR itself, so the API jar + .so files ship in the project.
    implementation(files("libs/onnxruntime-api.jar"))

    // Xposed API: compile-only. The framework supplies the real classes to
    // the module at runtime, so nothing of it is packaged.
    compileOnly(files("libs/xposed-api-stub.jar"))

    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
