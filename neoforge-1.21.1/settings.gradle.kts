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

// 本目录是一个独立 build root（外壳）。它的上一级 site/ 才是仓库根 / versions.properties 所在。
// 构建：./gradlew -p neoforge-1.21.1 build
rootProject.name = "yardsite-neoforge-1.21.1"
