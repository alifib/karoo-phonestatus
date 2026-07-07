import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Base64

plugins {
    id("com.android.application")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "io.alifib.karoophonestatus"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.alifib.karoophonestatus"
        minSdk = 26      // Karoo 2 = Android 8 (API 26), Karoo 3 = Android 12
        targetSdk = 33   // < 34 keeps foreground-service-type rules relaxed; matches karoo-powerbar
        versionCode = 5
        versionName = "0.4.1"
    }

    // Release signing. The keystore is injected from CI secrets (base64) so no
    // key material lives in the repo. Local builds without the env vars produce
    // an unsigned release APK — use the debug build for local installs.
    signingConfigs {
        create("release") {
            val keystoreBase64 = System.getenv("KEYSTORE_BASE64")
            if (!keystoreBase64.isNullOrBlank()) {
                val keystoreFile = File.createTempFile("release-keystore", ".jks")
                keystoreFile.deleteOnExit()
                keystoreFile.writeBytes(Base64.getDecoder().decode(keystoreBase64))
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only attach the signing config when the keystore is actually
            // available (CI); otherwise the release build stays unsigned.
            if (!System.getenv("KEYSTORE_BASE64").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true // BuildConfig.VERSION_NAME is passed to KarooExtension
    }
}

dependencies {
    implementation("io.hammerhead:karoo-ext:1.1.9")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
