package dev.yard.site;

import dev.yard.site.structure.StructureMirror;
import dev.yard.site.structure.StructureRotation;
import net.neoforged.neoforge.common.ModConfigSpec;

/** 群系选区的配置：开关、范围、采样线程、出生点区块半径、落成动画、投影结构。 */
public final class YardSiteConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue SPAWN_SITE_SELECT = BUILDER
            .comment("创建新世界时显示地形选区界面。独立安装时为出生点选择；与庭院系列共同安装时为庭院位置选择。"
                    + "关闭后按原版流程生成出生点。")
            .define("site.spawnSiteSelect", true);

    public static final ModConfigSpec.IntValue SITE_SELECT_RADIUS = BUILDER
            .comment("选区地图的覆盖半径：以出生点为中心，向四周各扩展 N 格。"
                    + "半径越大，覆盖区域越广，单格分辨率越低。")
            .defineInRange("site.selectRadius", 2000, 256, 8000);

    public static final ModConfigSpec.IntValue SITE_SELECT_RADIUS_EPIC = BUILDER
            .comment("安装史诗地形（Epic Terrain 或其地形兼容版本）时使用的选区半径。"
                    + "此类地形的山脉规模较大，常规 ±2000 格往往整体位于同一座山上，因此默认放宽至 ±8000 格。"
                    + "采样点数量不变，仅增大单格代表的方块数；查看细节仍需进入「区块精读」。"
                    + "不需要加宽时，设为与 selectRadius 相同即可。")
            .defineInRange("site.selectRadiusEpic", 8000, 256, 16000);

    public static final ModConfigSpec.IntValue SITE_SANDBOX_COLUMNS = BUILDER
            .comment("选区地图单边最多绘制的柱体数量，即高度采样点数量上限。数值越大图像越精细："
                    + "128 为粗档，256 为默认档，512 最精细但性能开销最大。"
                    + "覆盖范围增大时会自动提升档位，超过此值后不再提升。")
            .defineInRange("site.sandboxColumns", 256, 128, 512);

    public static final ModConfigSpec.IntValue SITE_SAMPLE_THREADS = BUILDER
            .comment("选区地图后台采样线程数。0 表示自动，取 CPU 逻辑核心数的一半；也可手动指定 1～64。"
                    + "线程数越多采样越快，但会占用渲染线程的 CPU 资源；核心数较少时建议保持 0。")
            .defineInRange("site.sampleThreads", 0, 0, 64);

    public static final ModConfigSpec.IntValue SITE_SPAWN_CHUNK_RADIUS = BUILDER
            .comment("进入世界后保留的出生点区块半径（原版默认值为 2，即 5×5 区块）。"
                    + "启用 earlyEntry 时，其余区块在进入世界后继续生成；"
                    + "未启用时需等待对应数量的区块生成完成后再进入世界。0 表示使用世界设置。")
            .defineInRange("site.spawnChunkRadius", 3, 0, 8);

    public static final ModConfigSpec.BooleanValue SITE_EARLY_ENTRY = BUILDER
            .comment("创建世界时仅等待 9 个出生点区块（约 2～3 秒）即进入世界，其余区块在进入后继续生成。"
                    + "默认关闭：等待时间较长，但加载界面中不会播放世界音效。")
            .define("site.earlyEntry", false);

    public static final ModConfigSpec.BooleanValue SITE_INSTANT_REVEAL = BUILDER
            .comment("选区地图的柱体不播放入场生长动画，采样完成后直接显示。出图速度不变，仅改变显示方式。"
                    + "此项仅影响选区地图，不影响落成动画。")
            .define("site.instantReveal", false);

    public static final ModConfigSpec.BooleanValue SITE_REVEAL_ANIM = BUILDER
            .comment("进入世界前播放落成动画：镜头飞向庭院，建筑线框或模型随之生成。"
                    + "与庭院系列共同安装时显示旅社，单独安装时仅显示地面与落点。"
                    + "关闭后使用原版加载界面。")
            .define("site.revealAnim", true);

    public static final ModConfigSpec.DoubleValue REVEAL_LINE_WIDTH = BUILDER
            .comment("落成动画中金色线框的线宽（界面像素）。1.0～1.6 接近工程图效果，2.5 以上为粗线效果。"
                    + "线宽不随距离变化。")
            .defineInRange("site.revealLineWidth", 1.5D, 0.5D, 4.0D);

    public static final ModConfigSpec.BooleanValue REVEAL_LINE_GLOW = BUILDER
            .comment("为金色线框叠加淡光晕，以提升暗背景下的可见性。性能不足时可关闭。")
            .define("site.revealLineGlow", true);

    public static final ModConfigSpec.BooleanValue REVEAL_SURFACE = BUILDER
            .comment("落成动画以「面」而非线框绘制建筑：可显示方块外观、花草、花盆、栏杆等细节。"
                    + "关闭后恢复金色线框。")
            .define("site.revealSurface", true);

    public static final ModConfigSpec.BooleanValue REVEAL_SURFACE_SCAN = BUILDER
            .comment("建筑表面自下而上逐层填充，并显示扫描框。关闭后整栋建筑一次性出现。"
                    + "仅在 revealSurface 启用时生效。")
            .define("site.revealSurfaceScan", true);

    public static final ModConfigSpec.IntValue REVEAL_FADE_DELAY_MS = BUILDER
            .comment("中心位置的真实地形显示后，建筑效果保留多久再开始淡出（毫秒）。"
                    + "0 表示立即开始淡出；淡出过程约 0.7 秒。")
            .defineInRange("site.revealFadeDelayMs", 1000, 0, 20000);

    // ── 投影结构：以建筑文件作为选址目标 ──────────────────────────────
    //
    // 默认全部关闭。启用 structure.enabled 后，占地范围、出生点、落成动画以及实际落成的建筑
    // 均以该文件为准；与庭院系列共同安装时优先使用此文件。

    public static final ModConfigSpec.BooleanValue STRUCTURE_ENABLED = BUILDER
            .comment("以投影/结构文件作为选址目标。启用后，选区地图的占地框、落成动画以及创建世界时落成的建筑"
                    + "均以该文件为准。与庭院系列共同安装时优先使用此文件，不会同时生成两套建筑。")
            .define("structure.enabled", false);

    public static final ModConfigSpec.ConfigValue<String> STRUCTURE_FILE = BUILDER
            .comment("结构文件路径，支持 .litematic、.nbt、.schem 格式，无需安装投影类 mod。"
                    + "相对路径基于游戏目录。留空时自动使用 config/yardsite/structures/ 中第一个可读取的文件。")
            .define("structure.file", "");

    public static final ModConfigSpec.BooleanValue STRUCTURE_SHOW_INFO = BUILDER
            .comment("在选区地图左上角显示结构信息（名称、占地、高度、朝向，以及 T/M 旋转与镜像操作提示）。"
                    + "默认关闭以保持画面简洁。文件读取失败或未设置文件的提示不受此开关控制，始终显示。")
            .define("structure.showInfo", false);

    public static final ModConfigSpec.EnumValue<StructureRotation> STRUCTURE_ROTATION = BUILDER
            .comment("落地时对结构执行顺时针旋转（0° / 90° / 180° / 270°）。"
                    + "占地范围、出生点、落成动画与实际方块一同旋转。")
            .defineEnum("structure.rotation", StructureRotation.NONE);

    public static final ModConfigSpec.EnumValue<StructureMirror> STRUCTURE_MIRROR = BUILDER
            .comment("旋转之前先执行镜像：X 为左右翻转，Z 为前后翻转，XZ 为同时翻转。")
            .defineEnum("structure.mirror", StructureMirror.NONE);

    public static final ModConfigSpec.IntValue STRUCTURE_OFFSET_X = BUILDER
            .comment("将结构整体沿 +X 方向移动对应格数。用于调试对位，选区地图上的落点标记不受影响。")
            .defineInRange("structure.offsetX", 0, -256, 256);

    public static final ModConfigSpec.IntValue STRUCTURE_OFFSET_Y = BUILDER
            .comment("将结构整体沿 Y 轴移动对应格数。默认 0 为贴地；地基较厚的结构可设为 -1。")
            .defineInRange("structure.offsetY", 0, -64, 64);

    public static final ModConfigSpec.IntValue STRUCTURE_OFFSET_Z = BUILDER
            .comment("将结构整体沿 +Z 方向移动对应格数。用于调试对位。")
            .defineInRange("structure.offsetZ", 0, -256, 256);

    public static final ModConfigSpec.BooleanValue STRUCTURE_PLACE = BUILDER
            .comment("创建世界时真实落成建筑。false 时不生成方块，仅播放落成动画。")
            .define("structure.place", true);

    public static final ModConfigSpec.BooleanValue STRUCTURE_FLATTEN = BUILDER
            .comment("落成建筑前将占地范围整平：地基以下填充，地基以上清空，使坡地也能平整落成。"
                    + "关闭后严格按原始地形放置，可能部分埋入地下。")
            .define("structure.flatten", true);

    public static final ModConfigSpec.IntValue STRUCTURE_MAX_VOXELS = BUILDER
            .comment("可读取结构的最大体积（长×宽×高，单位：格）。"
                    + "超过此值时不读取该文件，退回仅选择出生点的模式。")
            .defineInRange("structure.maxVoxels", 4_000_000, 8_000, 32_000_000);

    public static final ModConfigSpec.IntValue STRUCTURE_MAX_LINES = BUILDER
            .comment("落成线框最多保留的线段数量。超过上限时优先保留轮廓、檐口等长线，丢弃栏杆等密集短线。"
                    + "数值越大细节越完整，但每帧绘制的线段也越多。")
            .defineInRange("structure.maxLines", 12_000, 500, 60_000);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private YardSiteConfig() {}
}
