import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The release signing key, read from a file that is never committed or shared.
//
// An app's update identity IS its signing key: Android allows an update only when the new
// APK is signed by the same key, so losing this keystore means the app can never be updated
// again — every reader would have to uninstall and lose their highlights and notes. Back up
// app/keystore/release.jks and keystore.properties somewhere off this machine.
//
// Builds fall back to debug signing when the file is absent, so a fresh clone still builds;
// that build just cannot update anyone's install.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "ca.lampandlight"
    compileSdk = 36

    defaultConfig {
        applicationId = "ca.lampandlight"
        minSdk = 29
        targetSdk = 36
        versionCode = 36
        versionName = "0.36"
        // Phonon ships arm64-v8a and x86_64 only; a 32-bit install would fail to load the .so.
        // ANDROID_ABIS=arm64-v8a builds a phone-only APK, about 9 MB smaller than one that
        // also carries the emulator's x86_64 library.
        ndk {
            abiFilters += (System.getenv("ANDROID_ABIS")?.split(",")
                ?: listOf("arm64-v8a", "x86_64"))
        }
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
        // The debug build is signed with the release key too, on purpose: the app is
        // delivered by sideloading, and a debug-signed build cannot update a release-signed
        // install (or the reverse) — the mismatch forces an uninstall, which destroys the
        // reader's marks. One key for every build we hand out means that can never happen.
        debug {
            signingConfig = signingConfigs.findByName("release") ?: signingConfig
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        // JNA ships every ABI; keep only ours.
        jniLibs { useLegacyPackaging = false }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-text-google-fonts")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
