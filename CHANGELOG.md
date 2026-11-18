# 更新记录 / Changelog

> 按版本倒序记录。更早的版本见 GitHub Releases。

## 0.2.2 · 2026-11-18 — 相对 0.2.1

### 中文

**投影文件读取出错**

调色板超过 32 种方块时，`.litematic` 里的方块数据是一段连续位流（一个方块的值可以跨两个 long），
0.2.1 按「每个 long 装固定几格」读取，从第 11 格起整体错位。表现是预览里的方块位置和形状错乱，
实际落成的方块也跟着错。现在按连续位流读取；12×10×11 的测试结构读出 381 个方块，与文件记录一致。

**落成动画阶段的方块外观**

- 楼梯的内拐角、外拐角按原版形状表绘制，动画阶段不再把它们画成直梯。
- 花草（草、蕨、花、树苗等）画成对角交叉的两块平面，不再是整格方块。
- mod 方块：动画阶段的几何优先取方块自身的形状，朝向、连接、内外角等状态由方块自己解释；
  形状为空、直接报整格或越出单元格时，改用客户端烘焙模型按 1/16 体素化出盒子。

**已知限制**

动画阶段对 mod 方块的兼容还不完整：形状报整格、模型跨多个方块、或由方块实体自行绘制的方块，
在动画里可能只显示一部分，或退化为一个近似盒。影响只在落成动画，实际落成的方块与文件一致。
排查可用 `-Dyardsite.debug=true`，日志会为每个方块打印所用的几何来源。

0.2.1 的配置文件可以直接沿用。

---

### English

**Blueprint files were read incorrectly**

When a palette has more than 32 block states, the block data in a `.litematic` is a continuous bit
stream (one block's value can span two longs). 0.2.1 read it as a fixed number of cells per long,
which shifted everything from the 11th cell onward. Blocks appeared in the wrong places and shapes in
the preview, and the actually placed blocks were wrong as well. It now reads the stream continuously;
the 12×10×11 test structure yields 381 blocks, matching the file's own count.

**Block appearance during the reveal animation**

- Inner and outer stair corners follow the vanilla shape table; they are no longer drawn as straight
  stairs in the animation.
- Grass, ferns, flowers and saplings are drawn as two crossed diagonal planes instead of full blocks.
- Modded blocks: the animation geometry uses each block's own shape first, so facing, connections and
  corner states are interpreted by the block itself. When the shape is empty, a plain full cube, or
  extends outside the cell, the client baked model is voxelized at 1/16 resolution instead.

**Known limitation**

Mod compatibility during the animation is not complete: blocks whose shape is a full cube, whose model
spans several blocks, or that are drawn by a block entity renderer may appear partially or as a rough
box in the animation. This only affects the animation; the placed structure matches the file. Add
`-Dyardsite.debug=true` to log which geometry source each block used.

Existing 0.2.1 configs can be reused unchanged.

---

## 0.2.1 · 2026-11-18 — 相对 0.2.0

### 中文

**下载文件名变了**

| 0.2.0 | 0.2.1 |
|---|---|
| `yardsite-0.2.0.jar` | `yardsite-0.2.1+1.21.1-neoforge.jar` |
| `yardsite-0.2.0+1.20.1-forge.jar` | `yardsite-0.2.1+1.20.1-forge.jar` |

两个 jar 现在统一成 `yardsite-<版本>+<MC版本>-<加载器>.jar`。之前 1.21.1 那个叫
`yardsite-0.2.0.jar`，名字里既没写 MC 版本也没写加载器，全靠记；版本一多就一定拿错。

**移除 Sodium 支持**

装与不装实测看不出差别，所以删掉了那组「按区块列过滤真实 section」的 mixin 和它的编译期依赖，
落成动画改走单一路径（区块数据到货 + 网格编译余量）。**不装 Sodium 效果完全一样，装了也不会冲突。**
顺带把落机前的门口门槛改回原版 `LevelRenderer#isSectionCompiled` —— Sodium 在的时候这个接口永远报
未编译，当初就是为了绕开它才另起一套判据。

**游戏里显示的版本和文件名现在一致**

两个 `mods.toml` 里原本写死 `0.2.0`，和真正的构建版本是两处独立维护，升级时很容易只改一处。
现在版本号只在 `versions.properties` 一处，构建时注入到 `mods.toml`，注入失败会直接构建失败。

**（对贡献者）仓库结构变了**

`common/` 放加载器与版本无关的共享核心，每个「MC 版本 × 加载器」一个外壳目录
（`neoforge-1.21.1/`、`forge-1.20.1/`），各自是独立构建：`./gradlew -p neoforge-1.21.1 build`。
以后加 Fabric 或 1.21.11 只需新建一个外壳，不用复制核心代码。使用者无感。

**文档**

README 中英各加了「版本与下载」表（哪个 jar 对应哪个 MC / 加载器 / JDK）；
Sodium 从「可选前置」改为「不需要」；新增本文件。

**功能上相对 0.2.0 没有改动**：选区、采样、落成动画、结构模式、配置项都没动，
1.20.1 与 1.21.1 均在干净实例上复测通过。

---

### English

**The download file names changed**

| 0.2.0 | 0.2.1 |
|---|---|
| `yardsite-0.2.0.jar` | `yardsite-0.2.1+1.21.1-neoforge.jar` |
| `yardsite-0.2.0+1.20.1-forge.jar` | `yardsite-0.2.1+1.20.1-forge.jar` |

Both jars now follow `yardsite-<version>+<mc>-<loader>.jar`. The 1.21.1 build used to be plain
`yardsite-0.2.0.jar` — no MC version, no loader in the name, only memory to tell them apart.

**Sodium support removed**

With and without it there was no perceptible difference, so the chunk-column culling mixins and their
compile-time dependency are gone; the reveal animation now uses a single path (chunk data arrived +
mesh compile allowance). **Not installing Sodium behaves exactly the same, and installing it does not
conflict.** The pre-landing gate also went back to vanilla `LevelRenderer#isSectionCompiled` — with
Sodium present that API always reported "not compiled", which is why the workaround existed at all.

**In-game version and file name now agree**

Both `mods.toml` files hardcoded `0.2.0`, maintained separately from the actual build version — easy to
bump only one. The version now lives solely in `versions.properties` and is injected into `mods.toml`
at build time; a failed injection fails the build.

**(For contributors) the repository layout changed**

`common/` holds the loader- and version-agnostic core; each "MC version × loader" pair gets a shell
directory (`neoforge-1.21.1/`, `forge-1.20.1/`), each an independent build:
`./gradlew -p neoforge-1.21.1 build`. Adding Fabric or 1.21.11 means adding a shell, not copying the
core. No user-visible effect.

**Docs**

Both READMEs gained a "Versions and downloads" table (which jar targets which MC / loader / JDK);
Sodium moved from "optional dependency" to "not required"; this file is new.

**No functional changes versus 0.2.0**: site picking, sampling, the reveal animation, structure mode
and all config keys are untouched. Both 1.20.1 and 1.21.1 were re-tested on clean instances.

---

## 0.2.0 · 2026-11-17

**新增**

- **Minecraft 1.20.1 + Forge 47.x 支持**。同一套选区、区块精读、落成动画与结构模式，
  现在覆盖 1.20.1 / 1.21.1 两条线。

**修复**（都在 1.20.1 上暴露，1.21.1 同样受益）

- **选区界面的沙盘整层不画**：1.20.1 的 `Screen` 不会自己调 `renderBackground`（1.20.2+ 才由框架调），
  屏幕里覆盖的那个方法成了死代码。改为在 `render` 里按帧补一次。
- **落成动画不推进、永远停在原地**：动画时钟正好写在 `renderBackground` 里，与上一条同因。
- **小窗口崩溃（`OutOfMemoryError`）**：窗口 854×480 + GUI 缩放 2 时，按宽度折行的一行放不下一个汉字，
  内层循环第一格就退出、起始位置不再前进，每帧往列表里塞空串直到堆爆。现在保证一行至少放一个字符。
- **界面文字显示成 `yardsite.xxx` 原始 key**：1.20.1 缺 `pack.mcmeta`，整个资源包被丢弃。已补上。

**Added**

- **Minecraft 1.20.1 + Forge 47.x support.** The same site picker, chunk-level fine view, reveal
  animation and structure mode now cover both 1.20.1 and 1.21.1.

**Fixed** (all surfaced on 1.20.1; 1.21.1 benefits from the same fixes)

- **The sandbox map was not drawn at all.** On 1.20.1 the vanilla `Screen` never calls
  `renderBackground` itself (framework-driven only from 1.20.2+), so the overridden method was dead
  code. It is now called once per frame from `render`.
- **The reveal animation never advanced.** Its clock lived inside `renderBackground` — same cause.
- **Crash with a small window (`OutOfMemoryError`).** At 854×480 with GUI scale 2 the word-wrap loop
  could not fit a single CJK glyph on a line and stopped advancing, appending an empty string every
  frame until the heap ran out. A line now always holds at least one character.
- **UI text showed raw `yardsite.xxx` keys on 1.20.1.** A missing `pack.mcmeta` made the whole
  resource pack get dropped. Added.
