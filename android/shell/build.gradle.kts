plugins {
    id("com.android.application")
}

// Application de diagnostic : lance des commandes shell sur le telephone et
// permet d'envoyer la sortie par n'importe quel moyen de partage (messagerie).
// Elle est independante du lanceur SWAT 4 : meme si celui-ci plante, elle
// reste utilisable pour comprendre pourquoi.
android {
    namespace = "fr.tibkpl.shell"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.tibkpl.shell"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("SHELL_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("SHELL_VERSION_NAME") ?: "1.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // Les chaines sont en arabe (langue de base) ; les autres locales
        // n'en traduisent qu'une partie.
        disable += "MissingTranslation"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
