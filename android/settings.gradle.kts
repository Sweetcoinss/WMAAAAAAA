pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SWAT4 Android"
include(":app")
// Application de diagnostic : lance des commandes shell sur le telephone et
// permet d'envoyer la sortie.  Module separe pour rester tres leger.
include(":shell")
