plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "net.lunartear.server"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.lunartear.server"
        // API 30 is the floor because MANAGE_EXTERNAL_STORAGE ("All files access")
        // only exists from Android 11. Supporting API 29 would mean a second
        // permission path via requestLegacyExternalStorage.
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Every device that can run the game client is arm64, and both Go
        // artifacts are large; a second ABI would double the APK for nothing.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures {
        compose = true
        aidl = true // AGP 8 defaults this off
    }

    packaging {
        jniLibs {
            // MANDATORY. libauthserver.so is a Go executable, not a library: it
            // must be extracted to nativeLibraryDir with the execute bit so the
            // service can exec it. Android 10+ W^X blocks exec from app data
            // dirs, but nativeLibraryDir is explicitly allowed.
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    // Produced by scripts/build-go.sh (gomobile bind).
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // collectAsStateWithLifecycle / LocalLifecycleOwner
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Fail early with a clear message instead of a confusing "unresolved reference:
// mobile" from the Kotlin compiler.
val checkGoArtifacts by tasks.registering {
    doFirst {
        val aar = file("libs").listFiles { f -> f.extension == "aar" }
        if (aar == null || aar.isEmpty()) {
            throw GradleException(
                "app/libs/lunartear.aar is missing. Run ./scripts/build-go.sh first."
            )
        }
        val authBin = file("src/main/jniLibs/arm64-v8a/libauthserver.so")
        if (!authBin.exists()) {
            throw GradleException(
                "jniLibs/arm64-v8a/libauthserver.so is missing. Run ./scripts/build-go.sh first."
            )
        }
    }
}

tasks.matching { it.name.startsWith("pre") && it.name.endsWith("Build") }.configureEach {
    dependsOn(checkGoArtifacts)
}
