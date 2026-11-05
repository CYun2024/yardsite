package dev.yard.site;

import net.neoforged.neoforge.common.ModConfigSpec;

/** 群系选区的配置：开关、范围、采样线程、出生点区块半径、落成动画。 */
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

    public static final ModConfigSpec SPEC = BUILDER.build();

    private YardSiteConfig() {}
}
