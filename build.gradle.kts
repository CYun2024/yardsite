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

// MIT 要求分发时附带许可证正文：把模块目录的 LICENSE 打一份进 jar 根目录。
// 仓库里那份是唯一的源文件（模块目录，因为 monorepo 里各 mod 许可证可以不同）。
tasks.processResources {
    from("LICENSE")
}

// Sodium：落成动画期间按区块列过滤 section 的绘制。jar **不打包**，只做编译期依赖；
// 运行时靠 mixin 配置里的 "required": false 兜底，没装 Sodium 就自动关掉这组 mixin。
//
// 两种目录布局都支持（同一份 build 文件既能当 monorepo 子项目，也能当独立仓库的根构建）：
//   · monorepo：<repo>/yardcreator/site  → 取 <repo>/.deps/
//   · 单独仓库：<repo>/                  → 取 <repo>/libs/
val sodiumName = "sodium-neoforge-0.8.13+mc1.21.1-mod.jar"
val sodiumJar = listOf(
    rootProject.projectDir.parentFile.resolve(".deps/$sodiumName"),   // monorepo: yard/.deps
    rootProject.projectDir.resolve("libs/$sodiumName"),               // 独立仓库: libs/
    rootProject.projectDir.resolve(".deps/$sodiumName"),              // 独立仓库: .deps/
).firstOrNull { it.isFile }

dependencies {
    if (sodiumJar != null) {
        compileOnly(files(sodiumJar))
    }
}

if (sodiumJar == null) {
    println("[yardsite] 未找到 $sodiumName（放到 libs/ 即可启用 Sodium 判据），本次构建跳过这组 mixin")
    sourceSets.main {
        java {
            exclude("dev/yard/site/mixin/sodium/**")
        }
        resources {
            // 配置里列的类没编进来的话，运行时会报一串"找不到 mixin"，干脆一起不带
            exclude("yardsite.sodium.mixins.json")
        }
    }
}
