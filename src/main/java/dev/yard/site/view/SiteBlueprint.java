package dev.yard.site.view;

import dev.yard.site.api.BlueprintBox;
import dev.yard.site.api.BlueprintLine;
import dev.yard.site.api.SitePlan;
import dev.yard.site.schematic.SchematicHiddenLines;
import dev.yard.site.structure.SchematicSite;
import dev.yard.site.structure.SurfaceMesh;

import java.util.List;

/**
 * 落成动画里的金色线框：一堆按时间先后升起来的盒子，和/或一份<b>真实几何</b>（线段）。
 *
 * <p>两种来源共用同一套时间轴：
 * <ul>
 *   <li>{@link #boxes}：联动方（庭院系列）给的房间盒，一根根往上长，适合"先粗后细"的示意；</li>
 *   <li>{@link #lines}：投影文件的外壳线（已经合并、已经转到世界坐标），按<b>高度扫过</b>，
 *       从地基一路点亮到屋顶——条数多、形状准，看起来像建筑在自下而上被打印出来。</li>
 * </ul>
 * 两者都没有时只画占地金面（独立安装）。
 *
 * <p>这里只做几何换算和时间轴整理，不知道建筑长什么样。
 */
final class SiteBlueprint {

    static final int GOLD = 0xFFFFD24A;
    static final int GOLD_SOFT = 0xFFE0A94A;
    static final int GOLD_BRIGHT = 0xFFFFF0C0;

    final List<BlueprintBox> boxes;
    final List<BlueprintLine> lines;
    /**
     * 按机位消隐：结构模式才有。有它的时候 {@link #lines} 只用来定时长，
     * 真正画哪些线由它按相机位置算（见 {@link SiteRevealWorldRenderer}）。
     */
    final SchematicHiddenLines hiddenLines;
    /**
     * 面表：结构模式下有它，落成动画就<b>画面</b>而不是画线（见 {@link SurfaceMesh}）。
     * 开关在 {@code site.revealSurface}，关掉或没有结构时是 {@code null}。
     */
    final SurfaceMesh surface;

    final float plotMinX;
    final float plotMinZ;
    final float plotMaxX;
    final float plotMaxZ;
    /** 地基/平台面（世界 y）。 */
    final float baseY;
    /** 最高一条线离地基多少格（扫描线用）。 */
    final float heightBlocks;
    /** 盒子长完需要多久（毫秒）。 */
    final int durationMs;
    /** 线扫完要多久（毫秒）。 */
    final int linesMs;
    /** 线从什么时候开始扫（毫秒）：先让地基金面出来，再点亮建筑。 */
    final int lineDelayMs;
    /** D 段时长（毫秒）：面从地基长到屋顶。 */
    final int settleMs;
    /** C 段（金线自下而上长出来）结束之后原地停多久，再开始 D 段（面自下而上长出来）。 */
    static final int HOLD_MS = 2_000;

    private SiteBlueprint(List<BlueprintBox> boxes, List<BlueprintLine> lines, SchematicHiddenLines hiddenLines,
                          SurfaceMesh surface, float plotMinX, float plotMinZ, float plotMaxX, float plotMaxZ,
                          float baseY, float heightBlocks, int durationMs, int linesMs, int lineDelayMs,
                          int settleMs) {
        this.boxes = boxes;
        this.lines = lines;
        this.hiddenLines = hiddenLines;
        this.surface = surface;
        this.plotMinX = plotMinX;
        this.plotMinZ = plotMinZ;
        this.plotMaxX = plotMaxX;
        this.plotMaxZ = plotMaxZ;
        this.baseY = baseY;
        this.heightBlocks = heightBlocks;
        this.durationMs = durationMs;
        this.linesMs = linesMs;
        this.lineDelayMs = lineDelayMs;
        this.settleMs = settleMs;
    }

    /**
     * 拼出这一局的施工图。
     *
     * @param boxes 联动方给的盒子；空 = 只画占地金面
     * @param lines 真实几何线段（世界坐标）；空 = 只画盒子
     * @return {@code null} = 这次落座没有建筑，什么都别画
     */
    static SiteBlueprint of(SitePlan plan, List<BlueprintBox> boxes, List<BlueprintLine> lines) {
        SitePlan.Plot plot = plan.plot();
        if (plot == null) return null;
        int x0 = plan.originX();
        int y0 = plan.originY();
        int z0 = plan.originZ();
        float maxX = x0 + plot.width();
        float maxZ = z0 + plot.innerDepth();
        float plotMinZ = z0 - plot.porch();

        int boxMs = 0;
        float top = 0.0F;
        for (BlueprintBox b : boxes) {
            boxMs = Math.max(boxMs, b.delayMs() + b.riseMs());
            top = Math.max(top, b.maxY() - y0);
        }
        for (BlueprintLine l : lines) {
            top = Math.max(top, Math.max(l.y1(), l.y2()) - y0);
        }
        // 画面的时候不用再算一遍折角棱的消隐（那是给线框用的，一栋房子要几十毫秒）
        SurfaceMesh surface = dev.yard.site.YardSiteConfig.REVEAL_SURFACE.get()
                ? SchematicSite.surfaceFor(plan) : null;
        if (surface != null) {
            // 高度以面表为准：线一条都没有（或短于屋脊）时也能按时长长上去
            top = Math.max(top, surface.highY - y0);
        }
        if (top <= 0.0F) top = plot.height();

        // 扫线时长按高度给：矮房子一秒出头，塔楼也不会扫半分钟
        boolean timed = surface != null || !lines.isEmpty();
        // C 段速度：一格约 190ms（作者反馈 95ms 还是太快，再放慢一倍）。
        // 矮房子（12 格）约 3 秒，塔楼（50 格）约 9.5 秒
        int linesMs = timed ? (int) Math.max(3_000, Math.min(11_000, top * 190.0F)) : 0;
        int lineDelay = timed ? 260 : 0;
        // D 段：面自下而上长出来，一格约 70ms，夹在 1.2~4 秒
        int settleMs = timed ? (int) Math.max(1_200, Math.min(4_000, top * 70.0F)) : 0;
        // 时间轴：C 金线生长 → 停 HOLD_MS（完整线框站在原地）→ D 面自下而上长出来。
        // 落机（交接真实区块）会等这条时间轴走完，顺序才是"先长、再停、再落定、最后交给真方块"。
        int duration = Math.max(boxMs, timed ? lineDelay + linesMs + HOLD_MS + settleMs : 0);
        // 两段式：先当蓝图长出来（要金线），长完再渐变到真材料色。
        // 消隐那套只在"蓝图期"用，建成后渲染阶段就不再调它了。
        return new SiteBlueprint(List.copyOf(boxes), List.copyOf(lines),
                SchematicSite.hiddenLinesFor(plan),
                surface,
                x0, plotMinZ, maxX, maxZ,
                y0, Math.max(1.0F, top), duration, linesMs, lineDelay, settleMs);
    }
}
