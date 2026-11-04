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

    public static final ModConfigSpec.BooleanValue SITE_REVEAL_ANIM = BUILDER
            .comment("进世界前那段动画：确认落点后，把原版加载界面换成「沙盘推近 → 金色线框搭起 → 镜头落地」。"
                    + "和庭院系列一起装时线框是旅社，独立安装时只有地面和落点标记。"
                    + "关掉就是原版加载界面，生成流程不受影响")
            .define("site.revealAnim", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private YardSiteConfig() {}
}
