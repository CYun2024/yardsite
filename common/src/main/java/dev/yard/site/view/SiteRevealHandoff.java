package dev.yard.site.view;

import dev.yard.site.YardSiteLog;
import dev.yard.site.client.ChunkLoadDiagnostics;
import net.minecraft.client.gui.screens.Screen;

/**
 * 选址界面 → 落成动画的交接（一次性）。
 *
 * <p>「确认并创建」按下之后，界面要把两样东西交给下一段：已经采好的<b>精读场</b>（含缓存和
 * 后台线程，重开一份要等好几秒）和<b>当前相机</b>（否则动画一开场就从大沙盘视角跳过来）。
 * 交接内容存在这里，由 {@code MinecraftMixin} 在原版准备打开 {@code LevelLoadingScreen}
 * 的那一刻取走；取不到、过期、或者建世界过程本身失败了，就原样用原版界面。
 *
 * <p>取走之后规划器归 {@link SiteRevealScreen} 管（它负责关线程）。
 */
public final class SiteRevealHandoff {

    /** 交接的有效期（毫秒）：过了就作废，免得下次建世界还翻出旧落点。 */
    private static final long VALID_MS = 60_000L;

    private static volatile Payload payload;

    /** 交接内容：精读场 + 相机（含沙盘在屏幕上的位置）+ 落点。 */
    public static final class Payload implements AutoCloseable {
        final ChunkSitePlanner planner;
        final SiteTerrainRenderer.View view;
        /** 精读那帧沙盘占的屏幕区域：正交画面压在这块区域的中心，不是屏幕正中，开场要照搬。 */
        final int mapX;
        final int mapY;
        final int mapSize;
        final int siteX;
        final int siteZ;
        final long createdAtMs;
        /** 原版那个加载界面：动画在登录之前被跳过时交回给它（此时 {@code level} 还是 null）。 */
        Screen fallback;

        Payload(ChunkSitePlanner planner, SiteTerrainRenderer.View view,
                int mapX, int mapY, int mapSize, int siteX, int siteZ) {
            this.planner = planner;
            this.view = view;
            this.mapX = mapX;
            this.mapY = mapY;
            this.mapSize = mapSize;
            this.siteX = siteX;
            this.siteZ = siteZ;
            this.createdAtMs = System.currentTimeMillis();
        }

        @Override
        public void close() {
            planner.close();
        }
    }

    private SiteRevealHandoff() {}

    /**
     * 选址界面「确认并创建」时调用（只有区块精读能走到这里，精读场必定已经采着）。
     *
     * @param planner 已经采好的精读场（所有权交给动画界面，动画收尾时由它关线程）
     * @param view    精读那帧的相机快照（含屏幕缩放）
     * @param mapX    精读沙盘图区左上角，和 {@code view} 的缩放/平移同一套像素坐标
     */
    public static void offer(ChunkSitePlanner planner, SiteTerrainRenderer.View view,
                             int mapX, int mapY, int mapSize, int siteX, int siteZ) {
        // 诊断用：这是「确认并创建」真正按下的时刻
        ChunkLoadDiagnostics.markCreateAt(System.currentTimeMillis());
        Payload previous = payload;
        payload = new Payload(planner, view, mapX, mapY, mapSize, siteX, siteZ);
        if (previous != null) previous.close();
        YardSiteLog.debug("选址交接给生成动画：落点 x=" + siteX + " z=" + siteZ
                + "，格子采样 " + planner.field().progress() + "/" + planner.field().progressTotal());
    }

    /**
     * 原版准备显示加载界面时取走交接，换成生成动画界面；不满足条件就返回传进来的原版界面。
     * 已经有一张落成界面在播（比如创建阶段提前接手）就原样留着。
     */
    public static Screen orReveal(Screen vanilla) {
        Screen current = net.minecraft.client.Minecraft.getInstance().screen;
        if (current instanceof SiteRevealScreen) {
            return current;
        }
        Payload currentPayload = payload;
        if (currentPayload == null) return vanilla;
        payload = null;
        if (System.currentTimeMillis() - currentPayload.createdAtMs > VALID_MS) {
            currentPayload.close();
            YardSiteLog.debug("选址交接已过期，按原版加载界面走");
            return vanilla;
        }
        try {
            currentPayload.fallback = vanilla;
            return new SiteRevealScreen(currentPayload);
        } catch (Throwable t) {
            currentPayload.close();
            YardSiteLog.warn("生成动画界面起不来，退回原版加载界面", t);
            return vanilla;
        }
    }
}
