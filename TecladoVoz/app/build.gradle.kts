plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tecladovoz"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.tecladovoz"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                arguments("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Se firma con la clave de depuración para poder instalar el APK directamente.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
