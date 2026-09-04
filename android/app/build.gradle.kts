import java.util.Properties
import java.io.File

plugins {
    alias(libs.plugins.android.application)
}

// 1. Read injected properties from GitHub Actions CI
val targetAbi = project.findProperty("android.injected.abi") as String?
val targetAar = project.findProperty("targetAar") as String? ?: "vaydns-arm64.aar"

android {
    namespace = "net.vaydns.phoenix"
    
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "net.vaydns.phoenix"
        minSdk = 24
        targetSdk = 36
        versionCode = 56
        versionName = "2.8.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 2. Strictly filter the APK to only include the targeted native architecture (.so files)
        if (targetAbi != null) {
            ndk {
                abiFilters.clear()
                abiFilters.add(targetAbi)
            }
        }
    }

    // 3. Removed the broken resources.excludes logic, keeping only jniLibs compression setting
    packaging {
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // 4. Load ONLY the designated architecture AAR (never both simultaneously)
    implementation(files("libs/$targetAar"))
}
