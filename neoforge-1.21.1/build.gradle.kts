import java.util.Properties

plugins {
    id("net.neoforged.moddev")
}

repositories {
    // neoforged 官方 CDN（直连间歇可用）→ forge/fabric 补位 → BMCLAPI 兜底
    maven("https://neoforged.forgecdn.net/releases")
    // minecraft-dependencies 挂在 neoforged 的 mojang-meta 仓库
    maven("https://neoforged.forgecdn.net/mojang-meta")
    maven("https://maven.minecraftforge.net")
    maven("https://maven.fabricmc.net")
    maven("https://bmclapi2.bangbang93.com/maven")
}

// ── 版本号：仓库根的 versions.properties 是唯一来源 ────────────────────────────
// projectDir 的上一级恒为 site 根（monorepo 子项目与独立仓库根两种布局都成立），
// 所以这里不能用 rootProject.projectDir —— 它在 monorepo 里指向 yardcreator/。
val siteRoot: File = projectDir.parentFile

val versionsFile = siteRoot.resolve("versions.properties")
if (!versionsFile.isFile) {
    throw GradleException("找不到 $versionsFile —— mod_version / mod_group 必须来自仓库根的 versions.properties")
}
val sharedVersions = Properties().apply { versionsFile.inputStream().use { load(it) } }
val mod_version: String = sharedVersions.getProperty("mod_version")
    ?: throw GradleException("versions.properties 缺 mod_version")
val mod_group: String = sharedVersions.getProperty("mod_group")
    ?: throw GradleException("versions.properties 缺 mod_group")

val neoforge_version: String by project
val parchment_minecraft_version: String by project
val parchment_mappings_version: String by project

group = mod_group
version = mod_version
base.archivesName = "yardsite"

neoForge {
    version = neoforge_version
    parchment {
        minecraftVersion = parchment_minecraft_version
        mappingsVersion = parchment_mappings_version
    }
    runs {
        register("client") {
            client()
        }
        register("server") {
            server()
        }
    }
    mods {
        register("yardsite") {
            sourceSet(sourceSets.main.get())
        }
    }
}

// ── 源码集：共享核心在本外壳的上一级 common/ ──────────────────────────────────
sourceSets.main {
    java {
        srcDir("../common/src/main/java")
    }
    resources {
        srcDir("../common/src/main/resources")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

// MIT 要求分发时附带许可证正文：把仓库根的 LICENSE 打一份进 jar 根目录。
tasks.processResources {
    from("../LICENSE")
}

afterEvaluate {
    // MDG 会自动把被墙的官方源加进项目仓库；Gradle 对连接错误不回退，必须移除
    val deadRepos = repositories
        .filterIsInstance<org.gradle.api.artifacts.repositories.MavenArtifactRepository>()
        .filter { it.url.host == "maven.neoforged.net" }
    deadRepos.forEach { repositories.remove(it) }

    // 产物落点：monorepo 里根构建（yardcreator/build.gradle.kts 的 subprojects 块）
    // 已经为所有子项目注册了同名 copyJarToDist → yard/dist，这里不能重复注册。
    // 独立仓库（yardsite 根）没有那个根构建，所以外壳自己补一份 → <repo>/dist。
    if (!tasks.names.contains("copyJarToDist")) {
        val twoUp = siteRoot.parentFile.parentFile
        val distTarget = if (File(twoUp, "yardcreator/settings.gradle.kts").isFile()) {
            File(twoUp, "dist")
        } else {
            siteRoot.resolve("dist")
        }
        tasks.register<Copy>("copyJarToDist") {
            group = "build"
            description = "Copy this shell's jar into dist/"
            from(layout.buildDirectory.dir("libs")) {
                include("*.jar")
                exclude("*-sources.jar", "*-javadoc.jar")
            }
            into(distTarget)
        }
        tasks.named("build") { finalizedBy("copyJarToDist") }
    }
}
