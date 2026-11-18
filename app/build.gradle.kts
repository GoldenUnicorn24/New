plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.goldenunicorn.kronenkrieg"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.goldenunicorn.kronenkrieg"
        minSdk = 24
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        create("shared") {
            // Öffentlicher Schlüssel nur für Testversionen, damit Updates ohne Deinstallation klappen.
            storeFile = file("kronenkrieg-test.keystore")
            storePassword = "kronenkrieg"
            keyAlias = "kronenkrieg"
            keyPassword = "kronenkrieg"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
