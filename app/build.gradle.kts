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

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)

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
