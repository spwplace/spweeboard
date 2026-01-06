plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Rust crate configuration
val rustCrateDir = rootProject.projectDir.resolve("../../crates/spweeboard-core")
val rustTargetDir = rustCrateDir.resolve("target")
val jniLibsDir = projectDir.resolve("src/main/jniLibs")
val uniffiBindingsDir = projectDir.resolve("src/main/kotlin/uniffi")

// Map Android ABI to Rust target
val abiToTarget = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "armv7-linux-androideabi",
    "x86_64" to "x86_64-linux-android",
    "x86" to "i686-linux-android"
)

// Which ABIs to build (arm64 for now, add others as needed)
val targetAbis = listOf("arm64-v8a")

android {
    namespace = "com.github.spwplace.spweeboard"
    compileSdk = 35
    ndkVersion = "29.0.13846066"

    defaultConfig {
        applicationId = "com.github.spwplace.spweeboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Only package the ABIs we build
        ndk {
            abiFilters += targetAbis
        }

        // Feature flags (can be overridden per build type)
        buildConfigField("boolean", "FEATURE_STREAMING", "true")
        buildConfigField("boolean", "FEATURE_HISTORY", "true")
        buildConfigField("boolean", "FEATURE_CUSTOM_GROUNDS", "false")
        buildConfigField("boolean", "FEATURE_CANCEL", "true")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Point to jniLibs where we'll put the .so files
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs(jniLibsDir)
        }
    }
}

// Task to build Rust library with cargo-ndk
tasks.register<Exec>("buildRustRelease") {
    description = "Build Rust library for Android using cargo-ndk"
    workingDir = rustCrateDir

    // Find Android NDK
    val ndkDir = android.ndkDirectory.absolutePath
    val apiLevel = android.defaultConfig.minSdk?.toString() ?: "26"

    // Set multiple env vars for different build scripts
    environment("ANDROID_NDK", ndkDir)
    environment("ANDROID_NDK_HOME", ndkDir)
    environment("NDK_ROOT", ndkDir)

    // Build for each target ABI
    val targets = targetAbis.mapNotNull { abiToTarget[it] }

    commandLine = listOf(
        "cargo", "ndk",
        "-t", targets.joinToString(","),
        "-P", apiLevel,
        "-o", jniLibsDir.absolutePath,
        "--link-libcxx-shared",
        "--", "build", "--release",
        "--features", "uniffi,llama,vulkan"
    )

    // Ensure cargo-ndk output goes to the right place
    doFirst {
        println("Building Rust for targets: $targets")
        println("NDK: $ndkDir")
        println("Output: $jniLibsDir")
    }
}

// Task to build Rust library in debug mode (faster compilation)
tasks.register<Exec>("buildRustDebug") {
    description = "Build Rust library for Android (debug) using cargo-ndk"
    workingDir = rustCrateDir

    val ndkDir = android.ndkDirectory.absolutePath
    val apiLevel = android.defaultConfig.minSdk?.toString() ?: "26"

    // Set multiple env vars for different build scripts
    environment("ANDROID_NDK", ndkDir)
    environment("ANDROID_NDK_HOME", ndkDir)
    environment("NDK_ROOT", ndkDir)

    val targets = targetAbis.mapNotNull { abiToTarget[it] }

    commandLine = listOf(
        "cargo", "ndk",
        "-t", targets.joinToString(","),
        "-P", apiLevel,
        "-o", jniLibsDir.absolutePath,
        "--link-libcxx-shared",
        "--", "build",
        "--features", "uniffi,llama,vulkan"
    )
}

// Task to generate UniFFI Kotlin bindings
tasks.register<Exec>("generateUniffiBindings") {
    description = "Generate UniFFI Kotlin bindings from Rust"
    workingDir = rustCrateDir

    // First build the bindgen binary
    dependsOn("buildRustRelease")

    commandLine = listOf(
        "cargo", "run", "--release",
        "--features", "uniffi",
        "--bin", "uniffi-bindgen",
        "generate",
        "--library", jniLibsDir.resolve("arm64-v8a/libspweeboard_core.so").absolutePath,
        "--language", "kotlin",
        "--out-dir", uniffiBindingsDir.parentFile.absolutePath
    )

    doFirst {
        uniffiBindingsDir.mkdirs()
        println("Generating UniFFI bindings to: $uniffiBindingsDir")
    }
}

// Make preBuild depend on Rust build for debug builds
tasks.named("preBuild") {
    dependsOn("buildRustDebug")
}

// For release builds, use release Rust build
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn("buildRustRelease")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    // JNA for UniFFI native bindings
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // HTTP client for model downloads
    implementation(libs.okhttp)

    // WorkManager for background downloads
    implementation(libs.androidx.work.runtime.ktx)

    debugImplementation(libs.androidx.ui.tooling)
}
