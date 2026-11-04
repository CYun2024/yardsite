pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://neoforged.forgecdn.net/releases")
        mavenCentral()
    }
}

plugins {
    id("net.neoforged.moddev") version("2.0.148") apply false
    id("org.gradle.toolchains.foojay-resolver-convention") version("0.8.0")
}

dependencyResolutionManagement {
    repositories {
        maven("https://neoforged.forgecdn.net/releases")
        maven("https://maven.minecraftforge.net")
        maven("https://maven.parchmentmc.org") {
            content { includeGroup("org.parchmentmc.data") }
        }
        mavenCentral()
    }
}

rootProject.name = "yardsite"
