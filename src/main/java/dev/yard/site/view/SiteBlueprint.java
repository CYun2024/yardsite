package dev.yard.site.view;

import dev.yard.site.api.BlueprintBox;
import dev.yard.site.api.SitePlan;

import java.util.List;

/**
 * 落成动画里的金色线框施工图：一堆按时间先后升起来的盒子，由联动方给出
 * （庭院系列给的是旅社的房间盒；独立安装时一个盒子都没有，只剩地面和落点标记）。
 *
 * <p>这里只做几何换算和时间轴整理，不知道建筑长什么样。
 */
final class SiteBlueprint {

    static final int GOLD = 0xFFFFD24A;
    static final int GOLD_SOFT = 0xFFE0A94A;
    static final int GOLD_BRIGHT = 0xFFFFF0C0;

    final List<BlueprintBox> boxes;
    final float plotMinX;
    final float plotMinZ;
    final float plotMaxX;
    final float plotMaxZ;
    /** 地基/平台面（世界 y）。 */
    final float baseY;
    /** 最高一条线离地基多少格（扫描线用）。 */
    final float heightBlocks;
    /** 最后一条线升完需要多久（毫秒）。 */
    final int durationMs;

    private SiteBlueprint(List<BlueprintBox> boxes, float plotMinX, float plotMinZ,
                          float plotMaxX, float plotMaxZ, float baseY,
                          float heightBlocks, int durationMs) {
        this.boxes = boxes;
        this.plotMinX = plotMinX;
        this.plotMinZ = plotMinZ;
        this.plotMaxX = plotMaxX;
        this.plotMaxZ = plotMaxZ;
        this.baseY = baseY;
        this.heightBlocks = heightBlocks;
        this.durationMs = durationMs;
    }

    /**
     * 拼出这一局的施工图。
     *
     * @param boxes 联动方给的盒子；空 = 只画占地金面（或连占地都没有，见 {@link #of})
     * @return {@code null} = 这次落座没有建筑，什么都别画
     */
    static SiteBlueprint of(SitePlan plan, List<BlueprintBox> boxes) {
        SitePlan.Plot plot = plan.plot();
        if (plot == null) return null;
        int x0 = plan.originX();
        int y0 = plan.originY();
        int z0 = plan.originZ();
        float maxX = x0 + plot.width();
        float maxZ = z0 + plot.innerDepth();
        float plotMinZ = z0 - plot.porch();

        int duration = 0;
        float top = 0.0F;
        for (BlueprintBox b : boxes) {
            duration = Math.max(duration, b.delayMs() + b.riseMs());
            top = Math.max(top, b.maxY() - y0);
        }
        if (top <= 0.0F) top = plot.height();
        return new SiteBlueprint(List.copyOf(boxes), x0, plotMinZ, maxX, maxZ, y0,
                Math.max(1.0F, top), duration);
    }
}
