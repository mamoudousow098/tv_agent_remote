// Module app : plugin Android 9, Kotlin integre (pas de plugin org.jetbrains.kotlin.android)
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "sn.tvremote.agent"
    compileSdk = 36

    defaultConfig {
        applicationId = "sn.tvremote.agent"
        // Fleches et OK : Android 13+ requis (la TV est en Android 14)
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Installation manuelle sur une seule TV : la cle de debug suffit
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Aucune dependance : uniquement le framework Android (org.json inclus)
dependencies {}