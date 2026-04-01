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
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    packaging {
        resources {
            // Exclude everything that ISN'T arm64 to be 100% sure
            excludes.add("lib/armeabi-v7a/*")
            excludes.add("lib/x86/*")
            excludes.add("lib/x86_64/*")
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
    implementation(files("libs/vaydns-arm64.aar"))
}