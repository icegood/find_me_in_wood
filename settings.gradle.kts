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
        maven("https://jitpack.io")
    }
}
rootProject.name = "find_me_in_wood"

include(":app")
include(":core:model", ":core:crypto", ":core:session", ":core:auth", ":core:chat", ":core:p2p")
include(":transport:api", ":transport:wifidirect", ":transport:bluetooth", ":transport:lora",
        ":transport:share")
include(":feature:networks", ":feature:map")
