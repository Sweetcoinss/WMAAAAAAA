plugins {
    id("com.android.application")
}

// Runtime Linux (Box86 + Wine 32 bits + Mesa Turnip + Xvfb) : il n'est PAS embarque
// dans l'APK (il ferait ~80 Mo). Il est publie separement dans la release GitHub
// "runtime-v1" et telecharge par l'application a la premiere utilisation (voir
// Swat4Installer.ensureRuntime et la ressource runtime_url). Ni le jeu ni le moteur
// ne sont embarques dans l'APK : les donnees du jeu sont designees par l'utilisateur
// (carte SD le plus souvent) et montees dans le runtime, sans copie.

android {
    namespace = "fr.tibkpl.openswat4"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.tibkpl.openswat4"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("OPENSWAT4_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("OPENSWAT4_VERSION_NAME") ?: "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }

    // -------------------------------------------------------------------------
    // Editions :
    //   free  : edition limitee (resolution plafonnee, pas de jeu en reseau) --
    //           installee a cote de la version complete, elle cohabite car son
    //           applicationId est different.
    //   full  : edition complete (toutes les options, y compris le multijoueur
    //           local et la resolution native).
    // -------------------------------------------------------------------------
    flavorDimensions += "edition"
    productFlavors {
        create("full") {
            dimension = "edition"
            versionNameSuffix = "-full"
            buildConfigField("boolean", "EDITION_FREE", "false")
            buildConfigField("int", "MAX_RENDER_HEIGHT", "1080")
            buildConfigField("boolean", "EDITION_NETWORK", "true")
        }
        create("free") {
            dimension = "edition"
            applicationIdSuffix = ".free"
            versionNameSuffix = "-free"
            buildConfigField("boolean", "EDITION_FREE", "true")
            buildConfigField("int", "MAX_RENDER_HEIGHT", "720")
            buildConfigField("boolean", "EDITION_NETWORK", "false")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // Signature de l'APK release : cle fournie par variables d'environnement (secrets GitHub,
    // voir README) ; a defaut, cle de debug.
    val keystore = System.getenv("OPENSWAT4_KEYSTORE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("OPENSWAT4_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OPENSWAT4_KEY_ALIAS")
                keyPassword = System.getenv("OPENSWAT4_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Lecture du runtime .tgz (deja present dans l'APK, conserve pour extensibilite)
    implementation("org.apache.commons:commons-compress:1.27.1")
}
