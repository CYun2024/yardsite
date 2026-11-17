package dev.yard.site;

import dev.yard.site.structure.StructureMirror;
import dev.yard.site.structure.StructureRotation;
import net.neoforged.neoforge.common.ModConfigSpec;

/** 群系选区的配置：开关、范围、采样线程、出生点区块半径、落成动画、投影结构。 */
public final class YardSiteConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue SPAWN_SITE_SELECT = BUILDER
            .comment("创建单人新世界时先弹群系/地形图挑选落点。"
                    + "独立安装时选的是出生点；和庭院系列一起装时选的是庭院落点。"
                    + "不想干涉就在图上点「返回」，走原版出生点")
            .define("site.spawnSiteSelect", true);

    public static final ModConfigSpec.IntValue SITE_SELECT_RADIUS = BUILDER
            .comment("选区图覆盖范围：以（模拟出来的）世界出生点为中心 ±N 格")
            .defineInRange("site.selectRadius", 2000, 256, 8000);

    public static final ModConfigSpec.IntValue SITE_SELECT_RADIUS_EPIC = BUILDER
            .comment("装了史诗地形（Epic Terrain，或只做地形的兼容版 Epic Terrain Compatible）时选区图的覆盖范围："
                    + "同样是以出生点为中心 ±N 格。这类地形的山系动辄上万格宽，常规的 ±2000 常常整张图都压在"
                    + "同一座山上、看不出哪里值得落脚，所以认到就把图拉宽。"
                    + "采样点数不变（还是 128×128 个高度点），变的只是每格代表更多方块：±8000 时一格约 62 格方块，"
                    + "要看细处仍然得进「区块精读」那一层（那里按真实区块 16 格一格，不受这个值影响）。"
                    + "不想加宽就把它改成和 site.selectRadius 一样")
            .defineInRange("site.selectRadiusEpic", 8000, 256, 16000);

    public static final ModConfigSpec.IntValue SITE_SANDBOX_COLUMNS = BUILDER
            .comment("大沙盘每边最多画多少根柱子（= 最多采多少个高度点，群系细格再乘 2 倍边长）："
                    + "范围越大档位自动越高，半径翻倍柱子也翻倍，这样一根柱子代表的地面宽度不变、"
                    + "大图也一样看得清，超过这个上限就不再升档。"
                    + "128 = 一直是老样子（±2000 时一根柱子约 31 格方块，±8000 时约 125 格）；"
                    + "256 = 柱子和采样量都是 128 档的 4 倍；512 = 16 倍（±8000 格也和现在一样清楚，"
                    + "但采样要多等一会儿、每帧画的柱子翻 16 倍，帧率会明显掉，机器弱别开）。"
                    + "程序内部硬顶 512")
            .defineInRange("site.sandboxColumns", 256, 128, 512);

    public static final ModConfigSpec.IntValue SITE_SAMPLE_THREADS = BUILDER
            .comment("选区图（群系 + 高度沙盘）后台采样线程数。0 = 自动：CPU 逻辑核数的一半"
                    + "（另一半留给渲染线程和系统）；1–64 = 手动指定。"
                    + "线程多了图出得快，但也越容易抢 CPU 掉帧；机器核少（4 核及以下）建议留 0")
            .defineInRange("site.sampleThreads", 0, 0, 64);

    public static final ModConfigSpec.IntValue SITE_SPAWN_CHUNK_RADIUS = BUILDER
            .comment("选区建世界时，进世界后保活的出生点区块半径（原版默认 2 = 5×5 = 25 个区块）。"
                    + "开着 earlyEntry 时加载期不按这个值等，剩下的区块在进世界后继续生成；"
                    + "关掉 earlyEntry 时它就是等待量：N=3（49 个）约 8 秒、N=5（121 个）约 19 秒才进世界。"
                    + "填 0 = 不干涉，按你世界设置里的值")
            .defineInRange("site.spawnChunkRadius", 3, 0, 8);

    public static final ModConfigSpec.BooleanValue SITE_EARLY_ENTRY = BUILDER
            .comment("选区建世界时加载期只等 9 个出生点区块（约 2～3 秒）就放行登录，"
                    + "剩下的在进世界后按 spawnChunkRadius 继续生成。"
                    + "默认关：原版先等完出生点区块再进世界，等待更久，但人在世界外、不会有声音")
            .define("site.earlyEntry", false);

    public static final ModConfigSpec.BooleanValue SITE_INSTANT_REVEAL = BUILDER
            .comment("选区图（大沙盘 + 区块精读）的柱子不等铺开动画：默认每根柱子被画到时要 520 毫秒"
                    + "一边淡入一边从底面长高，所以图是从中心一圈圈长出来的。打开这个就是"
                    + "采到哪一格立刻画满那一格（不淡入、不长高），图出得一样快但看着是直接铺满的。"
                    + "只管选区图自己的柱子；进世界前那段落成镜头动画是 site.revealAnim 管的，不受这里影响")
            .define("site.instantReveal", false);

    public static final ModConfigSpec.BooleanValue SITE_REVEAL_ANIM = BUILDER
            .comment("进世界前那段动画：确认落点后，把原版加载界面换成「沙盘推近 → 金色线框搭起 → 镜头落地」。"
                    + "和庭院系列一起装时线框是旅社，独立安装时只有地面和落点标记。"
                    + "关掉就是原版加载界面，生成流程不受影响")
            .define("site.revealAnim", true);

    public static final ModConfigSpec.DoubleValue REVEAL_LINE_WIDTH = BUILDER
            .comment("落成动画里那套金色线框的线宽（像素，按界面缩放后的像素算）。"
                    + "1.0～1.6 最像图纸，2.5 以上是粗荧光笔效果；和远近无关，始终一样粗")
            .defineInRange("site.revealLineWidth", 1.5D, 0.5D, 4.0D);

    public static final ModConfigSpec.BooleanValue REVEAL_LINE_GLOW = BUILDER
            .comment("线框外面再加一层很淡的晕（同色、三倍宽、约 10% 不透明）：细线在暗背景上更清楚，"
                    + "也不容易因为抗锯齿显得断断续续。性能紧张可以关掉（顶点数少一半）")
            .define("site.revealLineGlow", true);

    public static final ModConfigSpec.BooleanValue REVEAL_SURFACE = BUILDER
            .comment("落成动画画「面」而不是画「线」（默认开）。"
                    + "面按方块本色 + 光照明暗 + 墙角遮蔽，看着就是一栋小模型：院里的花草、花盆、地毯都在，"
                    + "也不会有「这条线是墙还是栏杆」的问题。关掉回到金色线框（原来那套）")
            .define("site.revealSurface", true);

    public static final ModConfigSpec.BooleanValue REVEAL_SURFACE_SCAN = BUILDER
            .comment("面从地基一圈圈往上长（默认开）：扫到哪一层画到哪一层，边界跟一圈亮框，"
                    + "像建筑在自下而上被打印出来。关掉 = 整栋一次出现（静止的模型，没有生长过程）。"
                    + "只在 site.revealSurface 打开时有用")
            .define("site.revealSurfaceScan", true);

    public static final ModConfigSpec.IntValue REVEAL_FADE_DELAY_MS = BUILDER
            .comment("落成动画的建筑效果（面/金线）在「最中心那块真实区块接手」之后等多久开始原地淡出"
                    + "（毫秒，默认 1000）。不再等所有区块都交接完；设为 0 = 中心区块一接手就开始淡。"
                    + "淡出本身约 0.7 秒")
            .defineInRange("site.revealFadeDelayMs", 1000, 0, 20000);

    // ── 投影文件（用一份 .litematic / .nbt / .schem 当选址目标）────────────────
    //
    // 这一组默认全关：装了庭院系列时它还是「给旅社选区」，只有打开 structure.enabled，
    // 才会改用下面这份投影文件——占地尺寸、出生点、落成线框、进世界后盖起来的建筑全都来自它。

    public static final ModConfigSpec.BooleanValue STRUCTURE_ENABLED = BUILDER
            .comment("用一份投影/结构文件当选址目标（整合包作者用）。"
                    + "打开后：选图上的占地框按这份结构的尺寸画，落成动画里的金色线框就是它的外壳，"
                    + "进世界后由本 mod 把方块铺到选中的位置上。"
                    + "装了庭院系列时会优先用这里的结构（两套建筑不会同时盖）")
            .define("structure.enabled", false);

    public static final ModConfigSpec.ConfigValue<String> STRUCTURE_FILE = BUILDER
            .comment("结构文件路径。支持 .litematic（Litematica）、.nbt（原版结构方块）、.schem（WorldEdit/Sponge）。"
                    + "写相对路径时相对游戏目录，例如 config/yardsite/structures/tower.litematic。"
                    + "不装任何投影 mod 也能读。"
                    + "【留空 = 自动】去 config/yardsite/structures/ 里按文件名取第一个能读的；"
                    + "那个目录里也没有文件就不投影，只在选区图上提示「未设置结构文件」")
            .define("structure.file", "");

    public static final ModConfigSpec.BooleanValue STRUCTURE_SHOW_INFO = BUILDER
            .comment("在选区图左上角显示一行投影信息（结构名、占地、高度、朝向，以及 T/M 提示）。"
                    + "默认关：这一行压在图上、图本来就要看细节，不显示更干净。"
                    + "注意「读不了这份文件」「未设置结构文件」这两类提示不受它控制——那是问题，一直显示，"
                    + "不然文件配错了会毫无动静")
            .define("structure.showInfo", false);

    public static final ModConfigSpec.EnumValue<StructureRotation> STRUCTURE_ROTATION = BUILDER
            .comment("落地时把结构顺时针转多少（NONE / CW90 / CW180 / CW270）。"
                    + "占地方向、出生点、落成线框、真正铺下去的方块一起转")
            .defineEnum("structure.rotation", StructureRotation.NONE);

    public static final ModConfigSpec.EnumValue<StructureMirror> STRUCTURE_MIRROR = BUILDER
            .comment("落地时先镜像再旋转：NONE / X（左右翻）/ Z（前后翻）/ XZ（都翻）")
            .defineEnum("structure.mirror", StructureMirror.NONE);

    public static final ModConfigSpec.IntValue STRUCTURE_OFFSET_X = BUILDER
            .comment("结构整体往 +x 挪几格（选址图上的落点不动；调试对位用）")
            .defineInRange("structure.offsetX", 0, -256, 256);

    public static final ModConfigSpec.IntValue STRUCTURE_OFFSET_Y = BUILDER
            .comment("结构整体往上抬几格（默认 0 = 贴地；地基厚一层的结构可以给 -1）")
            .defineInRange("structure.offsetY", 0, -64, 64);

    public static final ModConfigSpec.IntValue STRUCTURE_OFFSET_Z = BUILDER
            .comment("结构整体往 +z 挪几格")
            .defineInRange("structure.offsetZ", 0, -256, 256);

    public static final ModConfigSpec.BooleanValue STRUCTURE_PLACE = BUILDER
            .comment("进世界后真的把方块铺下去。关掉 = 只画线框、不盖东西（让别人/别的 mod 去盖，"
                    + "或者只想先看看这套结构放进世界是什么样）")
            .define("structure.place", true);

    public static final ModConfigSpec.BooleanValue STRUCTURE_FLATTEN = BUILDER
            .comment("铺方块前先把占地范围整平：地基以下补实、地基到屋顶高度之间清空。"
                    + "坡地、水边、树下也能盖得方正。关掉就是严格照着地形硬放（会有一半埋进土里）")
            .define("structure.flatten", true);

    public static final ModConfigSpec.IntValue STRUCTURE_MAX_VOXELS = BUILDER
            .comment("允许读进来的结构体积上限（长×宽×高，格）。超过就退回「只选出生点」并打一条警告")
            .defineInRange("structure.maxVoxels", 4_000_000, 8_000, 32_000_000);

    public static final ModConfigSpec.IntValue STRUCTURE_MAX_LINES = BUILDER
            .comment("落成线框最多画多少条线（合并之后）。大建筑超了会按长度保留长线（轮廓/檐口/楼层线），"
                    + "丢掉雕花栏杆这类密集短线。调大可看清细节，但每帧顶点数同步上升")
            .defineInRange("structure.maxLines", 12_000, 500, 60_000);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private YardSiteConfig() {}
}
