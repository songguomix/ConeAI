import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Signing config lives OUTSIDE the project tree — this folder gets shared (with people and other
// AI tools), and a keystore + plaintext passwords must not travel with it. Lookup order:
//   1. $CONEAI_KEYSTORE_PROPS (explicit override, e.g. CI)
//   2. ~/.coneai-signing/keystore.properties (default local location)
//   3. ./keystore.properties (legacy fallback)
// storeFile inside the properties file resolves relative to that file's directory.
val keystorePropsFile: File =
    System.getenv("CONEAI_KEYSTORE_PROPS")?.let { File(it) }?.takeIf { it.exists() }
        ?: File(System.getProperty("user.home"), ".coneai-signing/keystore.properties").takeIf { it.exists() }
        ?: rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.cone.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cone.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 34
        versionName = "1.34"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        // Ship native libs (Vosk/JNA, ML Kit) only for real phone ABIs; drop x86/x86_64 emulator
        // variants to keep the APK smaller.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                val path = File(keystoreProps.getProperty("storeFile"))
                storeFile = if (path.isAbsolute) path else File(keystorePropsFile.parentFile, path.path)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
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
            signingConfig = signingConfigs.findByName("release")
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
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Networking + serialization
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // ML Kit OCR
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.chinese)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // DataStore (lightweight settings)
    implementation(libs.androidx.datastore.preferences)

    // Image loading
    implementation(libs.coil.compose)

    // Offline (on-device) speech recognition — runs locally with a bundled model, no network.
    implementation("com.alphacephei:vosk-android:0.3.47")

    // QR scanning for the desktop-remote pairing screen (self-contained scanner, no Google Play deps).
    implementation(libs.zxing.android.embedded)

    // Biometric unlock (fingerprint/face) for the saved desktop-remote login.
    implementation(libs.androidx.biometric)

    // Read JPEG EXIF orientation so uploaded photos aren't shown rotated.
    implementation(libs.androidx.exifinterface)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
}
