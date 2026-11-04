# Yard Site · 群系选区

Choose where you spawn before the world is generated.

On the *Create New World* screen this mod draws a 3D map of the biomes and terrain around the
spawn area. You can zoom down to one cell per chunk and click the cell you want; that spot becomes
the centre of the new world. Instead of a loading bar there is a short camera move: the map tilts
into perspective, the camera circles the site while nearby chunks load, the map dissolves, and the
camera drops to your spawn point.

If a Yard-series mod is installed, the same click selects a building plot instead of a bare spawn
point. Nothing else changes.

## How it works

1. Click *Create New World* — the map appears. It is built from the worldgen noise on the client,
   so no chunks are loaded. The world seed is used, which means modded biomes appear correctly
   (TerraBlender is handled).
2. Click a cell to place the marker. Double-click or press *Enter chunk view* to see one cell per
   chunk; the marker snaps to a whole chunk there.
3. Confirm. The vanilla loading screen is replaced by the camera sequence, and you land on the
   chosen spot.

## The two modes

**On its own** the chosen cell becomes your spawn point. After the spawn chunks load, the Y value is
checked once against the real heightmap so you do not end up inside a tree.

**With the Yard series** (`yardcore` and friends) the cell becomes a yard plot. The Yard mod plans
the building origin around it, puts the spawn inside the yard, and the camera sequence draws the
building as a wireframe on the way down.

The link between the two is optional in both directions: this mod runs fine without the Yard mods,
and they run fine without it.

## Requirements

- Minecraft 1.21.1, NeoForge 21.1.x, Java 21
- No required dependencies
- Sodium is optional. With it, real terrain stays hidden until each chunk column is actually being
  drawn, so it cannot show through the map. Without it the hand-off still works, just a little rougher.
- A Yard-series mod is optional (adds the plot + wireframe mode)

## Config

`config/yardsite-common.toml`:

| Key | Default | What it does |
|---|---|---|
| `site.spawnSiteSelect` | `true` | show the map at all; `false` gives vanilla spawn behaviour |
| `site.selectRadius` | `2000` | map size, ±N blocks around the spawn area |
| `site.sampleThreads` | `0` | sampling threads; `0` = half the CPU cores |
| `site.spawnChunkRadius` | `3` | spawn chunk radius kept loaded after you enter |
| `site.earlyEntry` | `false` | log in early, keep generating chunks afterwards |
| `site.revealAnim` | `true` | the camera sequence; `false` keeps the vanilla loading screen |

Biome colours changed in the in-game editor are saved to `config/yardsite/site_biome_colors.json`.

## Worth knowing

- Only for single-player and LAN world creation. Dedicated servers do not have this screen, so
  nothing happens there.
- While the camera sequence plays, this mod takes over the world-creation loading screen. If another
  mod does the same, they may fight; set `site.revealAnim = false` to compare.
- The map is drawn by the mod's own renderer. No assets from the base game or from other mods are
  included.

## Build

```bash
./gradlew build                  # jar → build/libs/yardsite-<version>.jar
./gradlew runClient              # dev client
```

Sodium is only needed at compile time for the optional culling hooks. Put
`sodium-neoforge-0.8.13+mc1.21.1-mod.jar` in `libs/` before building if you want them; without it
the build skips those classes and the mod still works.

## License

MIT, see [LICENSE](LICENSE). Use it, change it, ship it, sell it — just keep the copyright notice.

---

# 中文

在生成世界之前选好出生点。

点「创建世界」时会先出现一张三维的群系/地形图，可以放大到一格 = 一个区块，点哪一格，新世界就
围着哪一格生成。之后不是加载条，而是一段镜头：地图推成透视、绕着落点转一圈，附近的区块加载完
之后地图散掉，镜头落到出生点上。

如果装了庭院系列，同一次点击选出来的是庭院占地，不是单纯的出生点。其它没有区别。

## 用法

1. 点「创建世界」。地图是用世界生成的噪声在客户端算出来的，不加载区块，种子按你设的那个走，
   所以改群系的 mod 也能正确显示（TerraBlender 已兼容）。
2. 点一格放标记。双击或点「进入区块精读」可以看一格 = 一区块的细节，落点会钉在整区块上。
3. 点「确认并创建」，看完镜头就落在选好的地方。

## 两种装法

**只装这一个**：选中的那一格就是出生点。出生点区块加载完之后，会按真实高度图再校一次 y，
免得落在树里。

**和庭院系列一起装**：这一格变成庭院占地，庭院那边负责规划建筑原点、把出生点放进院内，
落成镜头会顺路把建筑画成金色线框。

两边都不硬依赖：没有庭院系列它能单独跑，没有它庭院系列也照常跑。

## 要求

- Minecraft 1.21.1 / NeoForge 21.1.x / Java 21
- 没有必需前置
- Sodium 可选：装了以后真实地形要等所在区块列真的在画才露出来，不会从地图缝里透出来；
  没装也能用，只是接缝处理粗一点
- 庭院系列可选（才有占地和线框）

## 配置

`config/yardsite-common.toml`：是否弹图、地图范围、采样线程数、出生点区块半径、早进世界、
落成镜头。界面里调过的群系颜色存在 `config/yardsite/site_biome_colors.json`。

## 注意

- 只对单人/局域网建世界生效，专用服务器没有这一步；
- 镜头期间会接管原版建世界的加载界面，和其它改加载界面的 mod 可能冲突（`site.revealAnim=false` 可关）；
- 包里不含任何游戏素材，也不含第三方 mod 的代码。

## 许可

MIT，见 [LICENSE](LICENSE)。

---

作者：**CYun_CYER**（Modrinth: CYun2024）
