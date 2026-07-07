import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
        versionCode = 4
        versionName = "0.4.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
