plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.zig.notificationfilter"
    compileSdk = 34
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "dev.zig.notificationfilter"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
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
    }

    // Prevent AGP from compressing the TFLite model inside the APK.
    // Without this, memory-mapping the model via AssetFileDescriptor fails at runtime.
    aaptOptions {
        noCompress.add("tflite")
    }
}

ksp {
    // Room writes a versioned schema JSON to this directory on each build.
    // These files must be committed — they are the source of truth for explicit migrations.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation("androidx.compose.material:material-icons-core") // BOM-managed; ~200 KB; bell, chevron, menu icons
    implementation(libs.hilt.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)

    // Phase 1: MediaPipe / LiteRT dependency disabled (kept as reference).
    // implementation("com.google.mediapipe:tasks-genai:0.10.35")

    // Phase 2: LiteRT (formerly TFLite) runtime + Support Library for on-device classification.
    // litert: Interpreter, model loading, tensor I/O. Google rebranded TFLite → LiteRT in 2024;
    // 1.0.1 is the first stable release with 16 KB page-aligned .so files for Android 15.
    // litert-support: TensorBuffer and label utilities. Class names remain org.tensorflow.lite.*
    // for backward compatibility — LocalModelEngine.kt requires no import changes.
    implementation("com.google.ai.edge.litert:litert:1.0.1")
    implementation("com.google.ai.edge.litert:litert-support:1.0.1")

    // Force the 16 KB page-aligned build of this AndroidX library.
    // The version pulled in transitively by Compose is not aligned; 1.0.1 is.
    implementation("androidx.graphics:graphics-path:1.0.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// ── Rust native library ───────────────────────────────────────────────────────

// Ensures cargo-ndk is installed on the build machine, installing it if absent.
// cargo-ndk wraps cargo to cross-compile for Android ABIs and place .so files
// in the correct jniLibs subdirectories automatically.
// ~/.cargo/bin is not on Gradle's PATH; resolve it explicitly so Exec tasks
// can find cargo and cargo-ndk regardless of how Gradle was launched.
val cargoBinDir = "${System.getProperty("user.home")}/.cargo/bin"
val gradlePath = "${System.getenv("PATH") ?: ""}:$cargoBinDir"

val ensureCargoNdk by tasks.registering(Exec::class) {
    environment("PATH", gradlePath)
    commandLine(
        "sh", "-c",
        "cargo ndk --version >/dev/null 2>&1 || cargo install cargo-ndk"
    )
}

// Compiles the Rust filter engine for both required ABIs and places the output
// .so files under app/src/main/jniLibs/, where AGP picks them up for packaging.
val buildRustLib by tasks.registering(Exec::class) {
    dependsOn(ensureCargoNdk)
    environment("PATH", gradlePath)
    workingDir(rootProject.file("native/rust_filter"))
    commandLine(
        "$cargoBinDir/cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "x86_64",
        "-o", layout.projectDirectory.dir("src/main/jniLibs").asFile.absolutePath,
        "--",
        "build", "--release"
    )
}

tasks.named("preBuild").configure {
    dependsOn(buildRustLib)
}
