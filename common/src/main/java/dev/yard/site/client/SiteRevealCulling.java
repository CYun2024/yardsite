package dev.yard.site.client;

import dev.yard.site.YardSiteLog;
import dev.yard.site.view.SiteRevealScreen;

/**
 * 落成动画期间「哪些真实区块还没轮到露出」的查询入口。
 *
 * <p>{@link SiteRevealScreen} 自己画的是盖在上面的沙盘遮罩；这里给 Sodium 的渲染钩子用：
 * 还没轮到的区块列连真实 section 都不提交绘制，所以真地形不会从遮罩的缝里提前露出来。
 * 落机一开始就整体放行（{@code descendAt != 0}），剩下的遮罩交给降落的整体淡出。
 *
 * <p>同一组钩子也反过来给落成动画当<b>证据</b>：{@link #sectionInRenderList} 只有 Sodium 真的把这个
 * section 迭代进绘制列表时才会被调到，落成动画用它判「这块地皮的真实地形现在画得出来吗」，
 * 没画出来就不让柱子淡掉。
 */
public final class SiteRevealCulling {

    /** 这一轮是否已经打过“过滤已接管”的日志（给测试确认 mixin 真的挂上了）。 */
    private static boolean announced;

    private SiteRevealCulling() {}

    /** 当前是否处于「按区块列隐藏真实地形」的阶段。 */
    public static boolean active() {
        boolean active = SiteRevealScreen.cullingActive();
        if (active && !announced) {
            announced = true;
            YardSiteLog.debug("Sodium 区块渲染过滤已接管：还没轮到的区块不提交绘制");
        } else if (!active) {
            announced = false;
        }
        return active;
    }

    /** 这个区块列（x/z 是 16×16 区块列坐标）的真实地形现在要不要藏起来。 */
    public static boolean hides(int chunkX, int chunkZ) {
        return SiteRevealScreen.hidesChunk(chunkX, chunkZ);
    }

    /** 这个区块列的真实网格进了 Sodium 的绘制列表（= 这一帧真的会提交绘制）。 */
    public static void sectionInRenderList(int chunkX, int chunkZ) {
        SiteRevealScreen.noteSectionDrawn(chunkX, chunkZ);
    }
}
