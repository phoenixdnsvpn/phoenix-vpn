import java.util.Properties
import java.io.File

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.net2share.vaydns"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.net2share.vaydns"
        minSdk = 24
        targetSdk = 36
        versionCode = 32
        versionName = "1.11.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }
    packaging {
        // 1. Keep 'resources' for non-native files only (like META-INF)
        resources {
            excludes.add("META-INF/*")
        }

        jniLibs {
            useLegacyPackaging = true

            val targetAbi = project.findProperty("abi") as String? ?: "armeabi-v7a"

            if (targetAbi == "arm64-v8a") {
                excludes.add("lib/armeabi-v7a/*")
                excludes.add("lib/x86/*")
                excludes.add("lib/x86_64/*")
            } else {
                excludes.add("lib/arm64-v8a/*")
                excludes.add("lib/x86/*")
                excludes.add("lib/x86_64/*")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // Dynamic AAR selection based on the architecture being built
    val targetAar = project.findProperty("targetAar") as String? ?: "vaydns-arm64.aar"
    implementation(files("libs/$targetAar"))
}
