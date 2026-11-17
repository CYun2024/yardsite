# 投影解析自检

`dev.yard.site.schematic` 那一包是纯 Java（不 import 任何 Minecraft 类），所以可以脱离游戏直接验：
脚本先生成几份<b>构造好的</b>投影文件，再逐项断言解析出来的尺寸、方块、方块形状、折角棱、
消隐结果、出生格、旋转换算。

```bash
./run.sh          # Linux/macOS/Git Bash
run.bat           # Windows
```

生成的东西都在 `out/`（已被 `.gitignore` 忽略），不会进仓库。

## 覆盖了什么

| 文件 | 验的东西 |
|---|---|
| `test.litematic` | 两个区域合并、负的 `Size`（往负方向铺）、跨 long 的 2 位打包、调色板去重 |
| `test-main.litematic` | 只含主厅，用来和 `.nbt` **逐格**对比（两种格式必须读出一模一样的房子） |
| `test.nbt` | 原版结构方块格式（`size/palette/blocks`） |
| `test.schem` | Sponge/WorldEdit 的 varint 方块流；实心体素的外壳必须正好 12 条棱 |
| `test-hollow.nbt` | 封死的 5×5×5：候选棱里外各 12 条；斜角看只剩 9 条外棱，底角那三条不画；正对只剩 4 条 |
| `test-half.nbt` | 一根长条 + 半截挡板：被挡住的那半截不画，露出来的 5 格照画（"挡一半画一半"） |
| `test-stack.nbt` | 两个盒子错开叠：后面那堵墙的竖棱被前面的墙挡住，整根不画；没挡的那根整根画 |
| `test-hollow-decor.nbt` | 墙面 1 格凸起：出的是"装饰线"，长棱出的是"结构线" |
| `test-slab-occlusion.nbt` | 半砖挡在石柱前面：柱子竖棱只该被挡住下半格（按整格算会整根消失） |
| `big.litematic` | 120×60×120 空心塔：看读入与线框的耗时、以及合并效果（4752 单位棱 → 24 段） |

## 什么时候要跑

改 `SchematicReader` / `LitematicReader` / `SpongeReader` / `BlockShapes` / `SchematicBoxes` /
`SchematicOutline` / `SchematicHiddenLines` / `SchematicTransform` 之后跑一遍，比进游戏试快得多
（一共几百毫秒）。

分档断言（`立方体斜角看：6 条外轮廓 + 3 条内部折线`）验的是"几何角色 + 剪影"这套：
立方体从斜角看，六个边的框是剪影，从近角出发那三条是内部折线，八条外棱里另外三条看不见。
数字写死是有意的——分档依据一改，这两组数立刻对不上。

线框那几条断言是"几何题"，改判据时最容易碰上：`SchematicHiddenLines` 的入参是结构 + 机位，
断言看的是可见总长（比如立方体 9 条棱 × 5 格 = 45），不是段数——段数会被"挡一半切成两段"影响。
容差别放宽：`45` 和 `46.5` 的差别就是"棱末端露出半格线头"，那是二分把可见侧和不可见侧搞反了，
一放宽就再也发现不了。

`BlockShapes` 的断言也是同一类：楼梯 = 2 个盒子、半砖 y 到 0.5、活板门 y 到 3/16。
这些数字直接来自原版 VoxelShape，改了形状表对不上就是错。

离线看图用 `out/lab/Shot.java`（不在产物里）：
`java -cp out/lab/classes Shot <结构> <出图> <solid|lines|over> [yaw] [pitch] [线宽]`。
`solid` 拿同一份盒子几何做光线追踪，`over` 把线框叠在压暗的实体上——一眼就能看出哪条线没落在棱上。

新增一种格式时，照 `gen.py` 的写法再加一份样本和几条断言即可。
