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

val neoforge_version: String by project
val parchment_minecraft_version: String by project
val parchment_mappings_version: String by project
val mod_version: String by project
val mod_group: String by project

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

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// MDG 会自动把被墙的官方源加进项目仓库；Gradle 对连接错误不回退，必须移除
afterEvaluate {
    val deadRepos = repositories
        .filterIsInstance<org.gradle.api.artifacts.repositories.MavenArtifactRepository>()
        .filter { it.url.host == "maven.neoforged.net" }
    deadRepos.forEach { repositories.remove(it) }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

// Sodium：落成动画期间按区块列过滤 section 的绘制（jar 在 yard/.deps/，已 gitignore）。
// 运行时由 mixin 配置的 "required": false 兜底，没装 Sodium 就自动关掉这组 mixin。
// 缺 jar 时只跳过 Sodium 相关源码，本模块照样能单独构建/发布。
val sodiumJar = rootProject.projectDir.parentFile.resolve(".deps")
    .resolve("sodium-neoforge-0.8.13+mc1.21.1-mod.jar")

dependencies {
    if (sodiumJar.exists()) {
        compileOnly(files(sodiumJar))
    } else {
        println("[yardsite] 未找到 Sodium jar，跳过 Sodium 渲染过滤 mixin 的编译（功能自动降级为纯遮罩）")
    }
}

if (!sodiumJar.exists()) {
    sourceSets.main {
        java {
            exclude("dev/yard/site/mixin/sodium/**")
        }
    }
}
