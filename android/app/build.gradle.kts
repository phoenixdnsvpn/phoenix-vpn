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
        versionCode = 14
        versionName = "1.6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    packaging {
        resources {
            val targetAbi = project.findProperty("android.injected.abi") as String?

            if (targetAbi == "arm64-v8a") {
                // If building for 64-bit, remove 32-bit junk
                excludes.add("lib/armeabi-v7a/*")
                excludes.add("lib/x86/*")
                excludes.add("lib/x86_64/*")
            } else if (targetAbi == "armeabi-v7a") {
                // If building for 32-bit, remove 64-bit junk
                excludes.add("lib/arm64-v8a/*")
                excludes.add("lib/x86/*")
                excludes.add("lib/x86_64/*")
            }
        }
        jniLibs {
            // This forces the APK to compress the Go library.
            // It makes the APK file smaller, but slightly slower to 'install'.
            useLegacyPackaging = true
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
