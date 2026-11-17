import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.uronic0x.fluorine"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.uronic0x.fluorine"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.3.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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
        debug {
            signingConfig = signingConfigs.getByName("debugConfig")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

val buildRustSo = tasks.register("buildRustSo") {
    group = "build"
    description = "Builds libfluorine.so (arm64-v8a) via cargo-ndk when Rust toolchain is available on host."
    doLast {
        val pathDirs = (System.getenv("PATH") ?: "").split(File.pathSeparator)
        val cargoBin = pathDirs
            .map { File(it, "cargo") }
            .firstOrNull { it.exists() && it.canExecute() }
            ?: File(System.getProperty("user.home"), ".cargo/bin/cargo").takeIf { it.exists() }

        val jniLibOut = file("src/main/jniLibs/arm64-v8a/libfluorine.so")
        if (cargoBin != null) {
            logger.lifecycle("Building libfluorine.so for aarch64-linux-android using ${cargoBin.absolutePath}...")
            val proc = ProcessBuilder(
                cargoBin.absolutePath,
                "ndk",
                "-t",
                "arm64-v8a",
                "-o",
                file("src/main/jniLibs").absolutePath,
                "build",
                "--release"
            )
                .directory(rootProject.projectDir)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            if (code == 0 && jniLibOut.exists()) {
                logger.lifecycle("Successfully built ${jniLibOut.absolutePath} (${jniLibOut.length()} bytes)")
            } else {
                logger.warn("cargo ndk build skipped or exited with code $code:\n$out")
            }
        } else {
            logger.lifecycle("cargo not found in PATH; skipping cargo-ndk step (runtime will use built-in native engine if libfluorine.so is absent).")
        }
    }
}

tasks.named("preBuild") {
    dependsOn(buildRustSo)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    // Unused heavy dependencies commented out to minimize APK size and external attack surface:
    // implementation(libs.okhttp)
    // implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation("org.json:json:20231013")
    debugImplementation(libs.androidx.ui.tooling)
}
