import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The release signing identity. Local builds read key.properties (git-ignored,
// at the repo root); CI provides the same values through ANDROID_* env vars.
// Neither present means a contributor build: it falls back to the debug key,
// which runs fine but cannot update a released install.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signing(name: String): String? =
    keystoreProperties.getProperty(name) ?: System.getenv(
        "ANDROID_" + name.replace(Regex("([A-Z])"), "_$1").uppercase()
    )

android {
    namespace = "com.cfox.kiosksatelliteupdater"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cfox.kiosksatelliteupdater"
        minSdk = 28
        targetSdk = 29
        versionCode = 2
        versionName = "0.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = signing("storeFile")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = signing("storePassword")
                keyAlias = signing("keyAlias")
                keyPassword = signing("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (signing("storeFile") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    lint {
        // Play Store publish gate; this app is sideloaded onto Portal
        // devices and never goes through Play, so it doesn't apply.
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // HTTP Client for GitHub Releases & APK Streaming
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Embedded HTTP Server for Headless Trigger (:2325)
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
