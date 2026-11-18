package dev.yard.site.worldgen;

import java.lang.ref.WeakReference;

/**
 * 建世界界面（客户端）→ 新世界初始化（同进程的集成服务端）的一次性交接。
 *
 * <p>单人世界在同一个 JVM 里创建，客户端采样完把选点放在这里，服务端
 * {@link SiteSpawnHandler} 在 {@code CreateSpawnPosition} 时取走。取用即消费；
 * 种子对不上或放太久（10 分钟）就当没有，避免玩家改了种子 / 中途取消后误用旧选点。
 *
 * <p>专用服务器的世界在服务器上生成、没有这个界面，所以不参与。
 */
public final class SiteSelection {
    private static final long MAX_AGE_MS = 10L * 60L * 1000L;

    private static volatile Pending pending;
    private static volatile WeakReference<Object> passScreen;
    private static volatile long passSeed;

    /**
     * 玩家在选址图上确认的落点（方块坐标）。
     *
     * @param exact true = 区块精读里选的，落座钉在这个区块上，服务端不再就近找平地挪位
     */
    public record Pending(int x, int z, long seed, long createdAtMs, boolean exact) {}

    private SiteSelection() {}

    public static void offer(int x, int z, long seed) {
        offer(x, z, seed, false);
    }

    public static void offer(int x, int z, long seed, boolean exact) {
        pending = new Pending(x, z, seed, System.currentTimeMillis(), exact);
    }

    public static void clear() {
        pending = null;
    }

    /** 服务端取用：种子不符 / 过期 → 丢弃并返回 null。取用即消费。 */
    public static Pending take(long seed) {
        Pending p = pending;
        pending = null;
        if (p == null) return null;
        if (p.seed() != seed) return null;
        if (System.currentTimeMillis() - p.createdAtMs() > MAX_AGE_MS) return null;
        return p;
    }

    /**
     * 这个建世界界面已经选过（或明确不干涉）这个种子：再次调用 onCreate 时不再弹图。
     * 玩家回去改种子后种子变化，会重新弹图。
     */
    public static void setPass(Object screen, long seed) {
        passScreen = new WeakReference<>(screen);
        passSeed = seed;
    }

    public static boolean hasPass(Object screen, long seed) {
        WeakReference<Object> ref = passScreen;
        return ref != null && ref.get() == screen && passSeed == seed;
    }
}
