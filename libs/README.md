# libs — 仅编译期使用的第三方 jar

放进这里的 jar **不会**被打进 `yardsite`，只是让构建能编过。

目前只有一项（可选）：

| 文件 | 用途 | 没有它会怎样 |
|---|---|---|
| `sodium-neoforge-0.8.13+mc1.21.1-mod.jar` | 编译 `mixin/sodium/` 那组「按区块列过滤真实地形」的钩子 | 构建照常成功，但这组 mixin 与它的配置会被排除；游戏里退回「只按遮罩＋区块到货」的判据，落成动画仍然能用，只是接缝处理粗一点 |

下载：Sodium 官方发布页（CurseForge / Modrinth）选 **1.21.1 / NeoForge** 版本即可，文件名不必完全一致，
改名成上表的名字最省事；改别的名字的话，同步改 `build.gradle.kts` 里的 `sodiumName`。

> `libs/*.jar` 已在 `.gitignore` 里，别提交上来。
