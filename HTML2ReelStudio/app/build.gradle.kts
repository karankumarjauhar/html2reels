import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val rustCoreDir = rootProject.file("rust_core")
val jniLibsDir = file("src/main/jniLibs")
val uniffiOutDir = layout.buildDirectory.dir("generated/source/uniffi/main/kotlin")

android {
    namespace = "com.html2reel"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.html2reel.studio"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
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
            isMinifyEnabled = false
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

    sourceSets {
        getByName("main") {
            java.srcDir(uniffiOutDir)
            jniLibs.srcDir(jniLibsDir)
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

val skipCargoNdk = providers.gradleProperty("skipCargoNdk").map { it.toBoolean() }.getOrElse(false)
val rustAbis = providers.gradleProperty("rustAbis").getOrElse("arm64-v8a,armeabi-v7a")

val buildRustCore by tasks.registering(Exec::class) {
    onlyIf { !skipCargoNdk }
    workingDir = rustCoreDir
    inputs.files(fileTree(rustCoreDir) {
        include("Cargo.toml", "Cargo.lock", "build.rs", "uniffi-bindgen.rs", "src/**/*.rs", ".cargo/config.toml")
    })
    outputs.dir(jniLibsDir)

    val args = mutableListOf("ndk")
    rustAbis.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { abi ->
        args += listOf("-t", abi)
    }
    args += listOf("--platform", "26", "-o", jniLibsDir.absolutePath, "build", "--release")
    commandLine(listOf("cargo") + args)
}

val generateUniffiBindings by tasks.registering(Exec::class) {
    dependsOn(buildRustCore)
    workingDir = rustCoreDir
    inputs.files(fileTree(rustCoreDir) {
        include("Cargo.toml", "uniffi-bindgen.rs", "src/**/*.rs")
    })
    outputs.dir(uniffiOutDir)

    doFirst {
        uniffiOutDir.get().asFile.mkdirs()
    }

    val hostLibProp = providers.gradleProperty("uniffiHostLib").orNull
    val soFile = file("${jniLibsDir.absolutePath}/arm64-v8a/libhtml2reel_core.so")
    val libArg = hostLibProp ?: soFile.absolutePath

    commandLine(
        "cargo", "run", "--bin", "uniffi-bindgen", "--features", "bindgen", "--",
        "generate", "--library", libArg, "--language", "kotlin", "--no-format",
        "--config", "${rustCoreDir.absolutePath}/uniffi.toml",
        "--out-dir", uniffiOutDir.get().asFile.absolutePath
    )
}

tasks.named("preBuild") {
    dependsOn(generateUniffiBindings)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.androidx.webkit)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    // UniFFI requires JNA packaged as AAR on Android so libjnidispatch.so is bundled cleanly.
    implementation("net.java.dev.jna:jna:5.15.0@aar")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.ui.tooling)
}
