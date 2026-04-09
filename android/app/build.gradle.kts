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
        versionCode = 4
        versionName = "1.4.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }

    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"        // You can change to newer if you have it
        }
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

// =============================================================================
// TASK: Generate default_configs.json from local.properties
// =============================================================================
tasks.register("generateDefaultConfigs") {
    group = "generation"
    description = "Extracts JSON from Environment, local.properties, or template and saves it to assets"

    doLast {
        // 1. Check System Environment (For GitHub CI)
        var jsonContent = System.getenv("DEFAULT_CONFIGS_JSON")

        // 2. If not in Environment, check local.properties
        if (jsonContent.isNullOrBlank()) {
            val localPropsFile = rootProject.file("local.properties")
            if (localPropsFile.exists()) {
                try {
                    val props = Properties()
                    localPropsFile.inputStream().use { props.load(it) }
                    val rawJson = props.getProperty("DEFAULT_CONFIGS_JSON")
                    if (!rawJson.isNullOrBlank()) {
                        jsonContent = rawJson.trim()
                    }
                } catch (e: Exception) {
                    println("⚠️ Error: Could not parse local.properties: ${e.message}")
                }
            }
        }

        // 3. If still empty, use the MINIFIED Template Config
        val isTemplate = if (jsonContent.isNullOrBlank()) {
            // Keep this strictly on one line for the C++ parser
            jsonContent = "[{\"name\":\"Template Config\",\"domain\":\"t.example.com\",\"pubkey\":\"0000000000000000000000000000000000000000000000000000000000000000\"}]"
            true
        } else {
            false
        }

        // Ensure assets directory exists
        val assetsDir = file("src/main/assets")
        if (!assetsDir.exists()) assetsDir.mkdirs()

        // Write to file (Overwrite existing)
        val jsonFile = File(assetsDir, "default_configs.json")
        jsonFile.writeText(jsonContent!!)

        // Status Reporting
        when {
            isTemplate -> println("ℹ️ INFO: No configs found. Generated MINIFIED Dummy Template.")
            System.getenv("DEFAULT_CONFIGS_JSON") != null -> println("✅ SUCCESS: Generated from GitHub Secrets (${jsonFile.length()} bytes).")
            else -> println("✅ SUCCESS: Generated from local.properties (${jsonFile.length()} bytes).")
        }
    }
}

// =============================================================================
// Hook into the Android Build Lifecycle (Top Level)
// =============================================================================
tasks.named("preBuild") {
    dependsOn("generateDefaultConfigs")
}
