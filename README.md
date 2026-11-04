# Yard Site · 群系选区

Pick the exact spot where your world begins — before the world exists.

**Yard Site** shows a 3D biome/terrain sandbox on the "Create New World" screen, lets you zoom down to a
one-chunk-per-cell view, and pins your choice. The world is then created around that spot and you are
dropped in through a short cinematic reveal instead of a loading bar.

Installed alone it simply **sets your spawn point**. Installed together with the *Yard* series it turns
the same pick into a **building plot** (the yard mod plans the origin and draws its blueprint).

---

## English

### What it does

1. Click **Create New World** → a biome/terrain map of the area around the simulated spawn point appears.
   Fully client-side: it samples the worldgen noise, no chunks are loaded.
2. Zoom into **chunk view** (one cell = one chunk) and click the cell you want. The world seed is respected,
   so what you see is what you get — including modded biomes (TerraBlender aware).
3. Confirm → the vanilla loading screen is replaced by a reveal: the sandbox is pushed into perspective,
   the camera slowly orbits while the spawn area loads, then the remaining sandbox dissolves at once and
   the camera descends to your spawn point.

### Two modes

| Installed with | What the pick becomes | Reveal |
|---|---|---|
| Nothing else | your **spawn point** (Y is fixed up against the real heightmap after the spawn chunks load) | sandbox → above spawn → eye level, no blueprint |
| The Yard series (`yardcore`) | a **yard plot**: origin planned around the pick, spawn inside the yard | sandbox → above the gate → through the doorway, with the building drawn as a golden wireframe |

The integration is optional and soft: this mod never hard-depends on anything, and the Yard mods never
require this one.

### Requirements

- Minecraft **1.21.1**, NeoForge **21.1.x**, Java 21
- **No required dependencies**
- Optional: **Sodium** — improves the hand-off (the sandbox is culled per chunk column so real terrain
  cannot peek through), but everything works without it
- Optional: a *Yard* series mod for the plot/blueprint mode

### Config — `config/yardsite-common.toml`

| Key | Default | Meaning |
|---|---|---|
| `site.spawnSiteSelect` | `true` | show the map at all; `false` = vanilla spawn behaviour |
| `site.selectRadius` | `2000` | map coverage, ±N blocks around the simulated spawn |
| `site.sampleThreads` | `0` | sampling threads; `0` = half of the CPU cores |
| `site.spawnChunkRadius` | `3` | spawn chunk radius kept loaded after entering the world |
| `site.earlyEntry` | `false` | let you log in early and keep generating chunks afterwards |
| `site.revealAnim` | `true` | the reveal cinematic; `false` = vanilla loading screen |

Biome colours edited in-game are stored in `config/yardsite/site_biome_colors.json`.

### Notes

- Only applies to **single-player / LAN world creation**; dedicated servers have no such screen.
- This mod **replaces the world-creation loading screen** while the reveal is playing. If another mod also
  takes over that screen, they may conflict — turn `site.revealAnim` off to compare.
- The sandbox is drawn with the mod's own renderer; nothing from the base game or any other mod is bundled.

### Build from source

```bash
# 1. (optional) drop sodium-neoforge-0.8.13+mc1.21.1-mod.jar into libs/
# 2. build
./gradlew build
# jar → build/libs/yardsite-<version>.jar
```

`./gradlew runClient` opens a dev client if you want to try it in-game.

### License

MIT — see [LICENSE](LICENSE). No warranty. You may use, modify, redistribute and sell it; just keep the
copyright notice.

---

## 中文

### 这是什么

建新世界的时候，先把出生点周围的地形和群系画成一张**三维沙盘**，让你把落点定在具体的那一格上，
再随一段落成镜头进世界，而不是对着加载条等。

- 只装这一个 mod：选出来的就是**出生点**（进世界后按真实高度图再校一次 y，避免落进树里）。
- 和庭院系列一起装：同一个落点变成**庭院占地**（庭院那边负责规划建筑原点，落成动画会画出旅社的金色线框）。

### 怎么用

1. 点「创建世界」→ 出现出生点附近 ±N 格的地形/群系沙盘（纯客户端采样，不加载区块，支持改群系的 mod）；
2. 左键点一格选定，双击或点「进入区块精读」看一格 = 一区块的细节；
3. 点「确认并创建」→ 沙盘推成透视、镜头缓慢绕着落点转，等视距内真实地形到位后沙盘一次性散掉，镜头落到出生点。

### 要求

- Minecraft 1.21.1 / NeoForge 21.1.x / Java 21；
- **没有必需前置**；
- Sodium 可选（装了更稳，没装也能用）；庭院系列可选（装了才有庭院占地与蓝图）。

### 配置

`config/yardsite-common.toml`，`site.*` 六个开关（是否弹图、覆盖范围、采样线程、出生点区块半径、
早进世界、落成动画）。调过的群系颜色存在 `config/yardsite/site_biome_colors.json`。

### 注意

- 只对**单人/局域网建世界**生效，专用服务器没有这一步；
- 落成动画期间会**接管原版建世界的加载界面**，和其它改加载界面的 mod 可能冲突（`site.revealAnim=false` 可关）；
- 包里不含任何游戏素材或第三方 mod 的代码。

### 许可

MIT，见 [LICENSE](LICENSE)。

---

作者 / Author: **CYun_CYER**（Modrinth: CYun2024）
