# Yard Site · 群系选区

[English](README.md) | **简体中文**

在创建世界之前选择出生点或建筑落点，并在进入世界前播放一段落成动画。

## 功能概述

- **选区地图**：创建单人新世界时显示三维地形与群系沙盘，可在较大范围内确定出生点。
- **区块精读**：进入细视图后，一格对应一个真实区块，可按区块确定落点。
- **落成动画**：确认落点后，镜头从沙盘视角飞向落点，建筑以金色线框和表面模型逐步生成，随后交接真实地形。
- **结构模式**：以 `.litematic`、`.nbt` 或 `.schem` 文件作为选址目标，占地范围、出生点、落成动画与实际建筑均来自该文件。

## 使用流程

1. 点击「创建世界」，游戏显示选区地图。
2. 在大沙盘中选择大致位置；双击或点击「进入区块精读」，以区块为单位确定落点。
3. 点击「确认并创建」，播放落成动画并创建世界。

返回而不选择时，按原版流程生成出生点。修改世界种子后，选区地图会重新采样。

## 落成动画

- **镜头过渡**：使用落定机位的固定视野，接近过程由摄像机位移完成；距离按对数插值，中途抬升以避开高地形。
- **金色线框阶段**：按结构折角棱生成线框，自下而上扫描点亮，并按当前机位执行隐藏线消除。与庭院系列共同安装时，显示庭院建筑的线框。
- **表面阶段**：仅结构模式。按方块烘焙模型绘制建筑表面，自下而上逐层填充并显示扫描框。
- **自动取景**：镜头距离根据内容包围盒计算；结构模式下使用结构几何范围。
- **交接与淡出**：真实地形接手后，建筑效果保留一段可配置时间，再原地淡出。

可在配置中关闭落成动画（`site.revealAnim`）或表面阶段（`site.revealSurface`）。

## 结构模式

启用 `structure.enabled` 后，选区地图显示结构占地，落成动画与世界生成均以该结构为准。

```toml
[structure]
	enabled = true
	file = "config/yardsite/structures/tower.litematic"
	rotation = "CW90"   # NONE / CW90 / CW180 / CW270
	mirror = "NONE"     # NONE / X / Z / XZ
```

- 支持 `.litematic`、`.nbt`、`.schem` 格式，无需安装投影类 mod。
- `structure.file` 留空时，自动使用 `config/yardsite/structures/` 中第一个可读取的文件。
- 几何按方块烘焙模型生成，栅栏、墙、玻璃板、楼梯等连接状态由文件中的方块状态决定；颜色按方块贴图采样。
- 落成前可整平占地范围（`structure.flatten`）；出生点取结构内可站立且靠近门的方块。
- 旋转、镜像与偏移同时作用于占地范围、出生点、落成动画和实际方块。
- 选区地图中按 T 顺时针旋转 90°，按 M 切换镜像；该调整仅对本次创建世界生效，不写入配置文件。
- 与庭院系列共同安装时，结构模式优先，不会同时生成两套建筑。

## 与庭院系列共同安装

- 仅安装本 mod 时，选中位置作为出生点。
- 与庭院系列共同安装时，选中位置作为庭院占地，由庭院系列规划建筑与出生点。
- 两边无强制依赖，可单独安装或共同安装。

## 版本与下载

每个 jar 只对应一个「Minecraft 版本 + 加载器」组合，文件名里写得很清楚：

| 文件 | 适用 |
|---|---|
| `yardsite-0.2.1+1.21.1-neoforge.jar` | Minecraft 1.21.1 · NeoForge 21.1.x · Java 21 |
| `yardsite-0.2.1+1.20.1-forge.jar` | Minecraft 1.20.1 · Forge 47.x · Java 17 |

放进 `mods/` 即可，无必需前置。版本变更见 [CHANGELOG.md](CHANGELOG.md)。

## 环境要求

- 无必需前置；投影文件由本 mod 自行解析。
- 不依赖 Sodium（Sodium 支持已于 0.2.1 移除，原生渲染与装 Sodium 时表现一致）。
- 仅对单人/局域网创建世界生效；专用服务器没有此流程。

## 配置

配置文件：`config/yardsite-common.toml`。

| 键 | 默认值 | 说明 |
|---|---|---|
| `site.spawnSiteSelect` | `true` | 创建新世界时显示选区界面；关闭后按原版流程生成出生点 |
| `site.selectRadius` | `2000` | 选区地图覆盖半径（格） |
| `site.selectRadiusEpic` | `8000` | 史诗地形下的选区半径（格） |
| `site.sandboxColumns` | `256` | 选区地图单边柱体数量上限 |
| `site.sampleThreads` | `0` | 采样线程数；0 表示自动 |
| `site.spawnChunkRadius` | `3` | 进入世界后保留的出生点区块半径 |
| `site.earlyEntry` | `false` | 仅等待 9 个出生点区块即进入世界 |
| `site.instantReveal` | `false` | 选区柱体不播放入场生长动画 |
| `site.revealAnim` | `true` | 进入世界前播放落成动画 |
| `site.revealSurface` | `true` | 落成动画以表面模型绘制建筑 |
| `site.revealSurfaceScan` | `true` | 表面自下而上逐层填充并显示扫描框 |
| `site.revealFadeDelayMs` | `1000` | 中心区块交接后建筑效果保留时间（毫秒） |
| `site.revealLineWidth` | `1.5` | 金色线框宽度（界面像素） |
| `site.revealLineGlow` | `true` | 金色线框光晕 |
| `structure.enabled` | `false` | 启用结构模式 |
| `structure.file` | `""` | 结构文件路径；留空时自动读取 |
| `structure.rotation` | `NONE` | 落地时顺时针旋转角度 |
| `structure.mirror` | `NONE` | 落地前镜像方式 |
| `structure.offsetX/Y/Z` | `0` | 结构整体偏移（格） |
| `structure.place` | `true` | 创建世界时生成建筑 |
| `structure.flatten` | `true` | 落成前整平占地范围 |
| `structure.maxVoxels` | `4000000` | 可读取结构的体积上限 |
| `structure.maxLines` | `12000` | 落成线框线段数量上限 |
| `structure.showInfo` | `false` | 在选区地图显示结构信息 |

群系颜色调整保存在 `config/yardsite/site_biome_colors.json`。

## 注意事项

- 落成动画会接管原版建世界加载界面；与其他修改该界面的 mod 可能冲突，可通过 `site.revealAnim` 关闭。
- 选区与建筑落点的地面扫描上限为 y=300（史诗地形为 500），高山地形按实际高度处理。
- 本 mod 不包含任何游戏素材或第三方 mod 代码。
- 金色线框宽度为屏幕像素常数，不随距离变化。

## 构建

仓库根只是容器，**每个版本外壳是独立的构建**，用根 wrapper 加 `-p` 指定：

```bash
./gradlew -p neoforge-1.21.1 build     # 1.21.1 NeoForge → dist/
./gradlew -p forge-1.20.1    build     # 1.20.1 Forge     → dist/
./gradlew -p neoforge-1.21.1 runClient # 开发客户端
```

目录：

- `common/` —— 加载器与版本无关的共享核心（唯一一份源码）
- `neoforge-1.21.1/`、`forge-1.20.1/` —— 版本外壳：平台实现、加载器资源、构建脚本

没有第三方编译期依赖，不需要 `libs/`。

### 1.20.1 Forge（legacy）

`forge-1.20.1/` 是**独立的 Gradle 构建**（ForgeGradle 6 + MixinGradle），但它不复制核心代码，而是共用同一份源码：

1. `generateForgeSources` 把 `common/src/main/java` 复制到 `forge-1.20.1/build/generated/forge-src`；
2. `forge-1.20.1/build.gradle` 里一张精确字符串替换表把 1.20.1 与 1.21.1 的 API 差异改掉；
3. 替换后的源码和 `forge-1.20.1/src/main/java/dev/yard/site/platform/forge` 里的 Forge 平台实现一起编译。

所以**核心代码仍然只有一份**：在 `common/` 里改一次，两个 jar 都会生效。只动某个外壳目录的改动（mods.toml、mixin、平台类、替换表）不影响别的 jar。产物落在 `dist/`，不进版本库。


## 开发说明

功能设计、取舍与实机测试由作者完成。部分代码在 AI 工具辅助下编写，提交前均经过人工检查与整理。mod 运行时不依赖任何 AI 服务。

## 许可

MIT 许可证，见 [LICENSE](LICENSE)。允许使用、修改、再发布与商业使用，需保留版权声明。

---

作者：**CYun_CYER**（Modrinth: CYun2024）