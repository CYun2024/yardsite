# 更新记录 / Changelog

> 更早的版本（0.1.x）见 GitHub Releases。本文只记 0.2.0 起的变更。

## 0.2.1 · 2026-11-18

### 中文

**新增**

- **支持 Minecraft 1.20.1 + Forge 47.x**（`yardsite-0.2.1+1.20.1-forge.jar`）。
  同一套选区、区块精读、落成动画与结构模式，现在覆盖 1.20.1 / 1.21.1 两条线。

**修复**（都在 1.20.1 上暴露，1.21.1 同样受益）

- **选区界面的沙盘整层不画**：1.20.1 的 `Screen` 不会自己调 `renderBackground`（1.20.2+ 才由框架调），
  两个屏幕覆盖的那个方法成了死代码。改为在 `render` 里按帧补一次。
- **落成动画不推进、永远停在原地**：动画时钟正好写在 `renderBackground` 里，与上一条同因。
- **小窗口崩溃（`OutOfMemoryError`）**：窗口 854×480 + GUI 缩放 2 时，按宽度折行的一行放不下一个汉字，
  内层循环第一格就退出、起始位置不再前进，每帧往列表里塞空串直到堆爆。现在保证一行至少放一个字符，
  折行宽度也有下限。
- **界面文字显示成 `yardsite.xxx` 原始 key**：1.20.1 缺 `pack.mcmeta`，整个资源包被丢弃。已补上。

**变更**

- **产物命名统一**为 `yardsite-<版本>+<MC版本>-<加载器>.jar`：
  `yardsite-0.2.1+1.21.1-neoforge.jar` / `yardsite-0.2.1+1.20.1-forge.jar`。
  两个 jar 不再只能靠人记住哪个是哪个。
- **移除 Sodium 支持**：实测装与不装看不出差别，所以删掉了那组按区块列过滤的 mixin 和它的编译期依赖，
  落成动画改回"数据到货 + 网格编译余量"的单一路径。**不装 Sodium 效果完全一样，装了也不会冲突。**
- 版本号、产物文件名、`mods.toml` 里的版本统一由一处生成，不会再出现"文件名和游戏里显示的版本不一致"。
- （对贡献者）仓库结构改为 `common/` 共享核心 + 每个「MC 版本 × 加载器」一个外壳目录，
  加新版本只需新建一个外壳，不用复制核心代码。

**验证**

两个 jar 都在干净实例上实测：1.20.1-Forge 47.4.29、1.21.1 NeoForge。覆盖选区与采样、确认创建、
落成动画与落机、结构模式（`.litematic` / `.nbt` / `.schem`、旋转镜像、`flatten`、`place=false`）、
独立安装的出生点 y 修正、小窗口（854×480、GUI 缩放 2）中文与英文。

---

### English

**Added**

- **Minecraft 1.20.1 + Forge 47.x support** (`yardsite-0.2.1+1.20.1-forge.jar`).
  The same site picker, chunk-level fine view, reveal animation and structure mode now cover
  both 1.20.1 and 1.21.1.

**Fixed** (all surfaced on 1.20.1; 1.21.1 benefits from the same fixes)

- **The sandbox map was not drawn at all.** On 1.20.1 the vanilla `Screen` never calls
  `renderBackground` itself (that only became framework-driven in 1.20.2+), so the overridden method
  in both screens was dead code. It is now called once per frame from `render`.
- **The reveal animation never advanced.** Its clock happened to live inside `renderBackground` —
  same root cause as above.
- **Crash with a small window (`OutOfMemoryError`).** At 854×480 with GUI scale 2, the word-wrap loop
  could not fit a single CJK glyph on a line, exited immediately and stopped advancing, appending an
  empty string every frame until the heap ran out. A line now always holds at least one character,
  with a lower bound on the wrap width.
- **UI text showed raw `yardsite.xxx` keys on 1.20.1.** A missing `pack.mcmeta` made the whole resource
  pack get dropped. Added.

**Changed**

- **Unified artifact naming** to `yardsite-<version>+<mc>-<loader>.jar`:
  `yardsite-0.2.1+1.21.1-neoforge.jar` / `yardsite-0.2.1+1.20.1-forge.jar`.
  You no longer have to remember which jar is which.
- **Sodium support removed.** With and without it there was no perceptible difference, so the
  chunk-column culling mixins and their compile-time dependency are gone; the reveal animation now uses a
  single "chunk data arrived + mesh compile allowance" path. **Not installing Sodium behaves exactly the
  same, and installing it does not conflict.**
- Version number, artifact file name and the version inside `mods.toml` are now generated from one place,
  so "file name says one thing, in-game version says another" cannot happen.
- (For contributors) The repository is now `common/` shared core plus one shell directory per
  "MC version × loader"; adding a version means adding a shell, not copying the core.

**Verified**

Both jars were tested on clean instances: 1.20.1-Forge 47.4.29 and 1.21.1 NeoForge. Covered site
selection and sampling, world creation, reveal animation and landing, structure mode
(`.litematic` / `.nbt` / `.schem`, rotate/mirror, `flatten`, `place=false`), the standalone spawn
Y-correction, and a small window (854×480, GUI scale 2) in both Chinese and English.
