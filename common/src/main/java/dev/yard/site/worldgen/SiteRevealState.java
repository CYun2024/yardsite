package dev.yard.site.worldgen;

import dev.yard.site.api.SitePlan;

/**
 * 建世界那一段「落成动画」的进程内状态：服务端写、客户端只读。
 *
 * <p>两样东西：建世界前定好的 {@link SitePlan}（落点/占地/出生点），以及 {@code built}
 * （建筑盖完了，联动方翻这个标记，动画拿它放"落成"那一下）。
 *
 * <p>不再记录每个区块的到货高度：动画不逐区块替换，等待段由蓝图自己演，
 * 所以这里没有并发高度表，也没有 {@code ChunkEvent.Load} 监听。
 */
public final class SiteRevealState {

    private static volatile SitePlan plan;
    private static volatile boolean built;

    private SiteRevealState() {}

    /** 服务端（建世界时）发布计划，顺带清掉上一局的残留。 */
    public static void publish(SitePlan published) {
        built = false;
        plan = published;
    }

    public static SitePlan plan() {
        return plan;
    }

    /** 联动方在建筑盖完时调用（独立安装没有建筑，没有人会翻这个标记）。 */
    public static void markBuilt() {
        if (plan != null) built = true;
    }

    public static boolean built() {
        return built;
    }

    /** 换存档时清空（客户端界面关掉时也会调）。 */
    public static void reset() {
        plan = null;
        built = false;
    }
}
