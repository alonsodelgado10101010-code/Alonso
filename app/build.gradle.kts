import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ─────────────────────────── Núcleo Rust (vintage_core) ───────────────────────────
//
// 1. cargoNdkBuild: compila libvintage_core.so para arm64-v8a, armeabi-v7a y x86_64
//    con cargo-ndk (perfil release) y las deja en app/src/main/jniLibs/.
// 2. cargoHostBuild + generateUniffiBindings: compila el crate para el host (sin
//    strip, para leer los metadatos de UniFFI) y genera los bindings Kotlin en
//    build/generated/uniffi/kotlin. Nada de JNI escrito a mano.
// Ambas corren automáticamente antes de preBuild.

val ndkVersionPinned = "27.2.12479018"
val rustCrateDir: File = rootProject.file("vintage_core")
val jniLibsOutDir: File = file("src/main/jniLibs")
val uniffiOutDir: File = layout.buildDirectory.dir("generated/uniffi/kotlin").get().asFile
val androidAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

fun androidSdkDir(): String? {
    val lp = rootProject.file("local.properties")
    if (lp.exists()) {
        val p = Properties()
        lp.inputStream().use { p.load(it) }
        p.getProperty("sdk.dir")?.let { return it }
    }
    return System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
}

fun androidNdkDir(): String? =
    System.getenv("ANDROID_NDK_HOME")
        ?: androidSdkDir()?.let { sdk ->
            val pinned = File(sdk, "ndk/$ndkVersionPinned")
            if (pinned.exists()) pinned.absolutePath
            else File(sdk, "ndk").listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }?.absolutePath
        }

val osName = System.getProperty("os.name").lowercase()
val hostLibName = when {
    osName.contains("mac") -> "libvintage_core.dylib"
    osName.contains("windows") -> "vintage_core.dll"
    else -> "libvintage_core.so"
}
val exeSuffix = if (osName.contains("windows")) ".exe" else ""

val cargoNdkBuild by tasks.registering(Exec::class) {
    group = "rust"
    description = "Compila vintage_core para Android (cargo-ndk) y copia las .so a src/main/jniLibs"
    workingDir = rustCrateDir
    inputs.dir(File(rustCrateDir, "src"))
    inputs.file(File(rustCrateDir, "Cargo.toml"))
    outputs.dir(jniLibsOutDir)
    doFirst {
        val ndk = androidNdkDir()
            ?: throw GradleException("No se encontró el NDK. Instálalo (sdkmanager \"ndk;$ndkVersionPinned\") o define ANDROID_NDK_HOME.")
        environment("ANDROID_NDK_HOME", ndk)
    }
    val args = mutableListOf("cargo", "ndk")
    androidAbis.forEach { args += listOf("-t", it) }
    args += listOf("--platform", "26", "-o", jniLibsOutDir.absolutePath, "build", "--release")
    commandLine(args)
}

val cargoHostBuild by tasks.registering(Exec::class) {
    group = "rust"
    description = "Compila vintage_core para el host (necesario para generar bindings)"
    workingDir = rustCrateDir
    inputs.dir(File(rustCrateDir, "src"))
    inputs.file(File(rustCrateDir, "Cargo.toml"))
    outputs.file(File(rustCrateDir, "target/debug/$hostLibName"))
    commandLine("cargo", "build", "--features", "cli")
}

val generateUniffiBindings by tasks.registering(Exec::class) {
    group = "rust"
    description = "Genera los bindings Kotlin de UniFFI"
    dependsOn(cargoHostBuild)
    workingDir = rustCrateDir
    inputs.file(File(rustCrateDir, "uniffi.toml"))
    inputs.file(File(rustCrateDir, "target/debug/$hostLibName"))
    outputs.dir(uniffiOutDir)
    doFirst { uniffiOutDir.deleteRecursively() }
    commandLine(
        File(rustCrateDir, "target/debug/uniffi-bindgen$exeSuffix").absolutePath,
        "generate",
        "--library", "target/debug/$hostLibName",
        "--language", "kotlin",
        "--no-format",
        "--out-dir", uniffiOutDir.absolutePath,
    )
}

tasks.named("preBuild") {
    dependsOn(cargoNdkBuild, generateUniffiBindings)
}

// Dependencias explícitas para las tareas que leen las salidas generadas.
tasks.configureEach {
    val n = name
    if (n.startsWith("ksp") || n.startsWith("compile") || n.startsWith("lint") ||
        (n.startsWith("generate") && n.contains("LintModel"))
    ) {
        dependsOn(generateUniffiBindings)
    }
    if (n.contains("JniLib") || n.contains("NativeLibs") || n.startsWith("lint")) {
        dependsOn(cargoNdkBuild)
    }
}

tasks.named<Delete>("clean") {
    delete(jniLibsOutDir)
}

// ─────────────────────────────────── Android ───────────────────────────────────

android {
    namespace = "cl.vintagemanager"
    compileSdk = 35
    ndkVersion = ndkVersionPinned

    defaultConfig {
        applicationId = "cl.vintagemanager"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += androidAbis
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Firmado con la clave de depuración para poder instalar el APK directamente.
            // Para publicar en Google Play, reemplazar por una clave propia.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    sourceSets {
        getByName("main") {
            java.srcDir(uniffiOutDir)
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Bindings UniFFI → JNA carga libvintage_core.so
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.print)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.vico.compose.m3)
    implementation(libs.coil.compose)
    implementation(libs.zxing.embedded)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
}
