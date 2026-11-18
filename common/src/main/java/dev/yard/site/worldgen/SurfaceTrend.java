package dev.yard.site.worldgen;

import net.minecraft.util.Mth;

/**
 * 用「已经采到的邻居」估这一列的地表 y，只用来定 {@link SurfaceProbe} 的扫描起点，
 * 不参与选址判断本身 —— 估准还是估偏都不改变最后那个 y（顶到上沿就抬档重扫，会把它纠正回来）。
 *
 * <p>两张图都是从中心一圈圈往外采的，所以任一点<b>靠中心一侧</b>的邻居通常已经有值，外圈那侧常常还
 * 没有。三步：
 * <ol>
 *   <li>已知邻居取平均，当地面高度；</li>
 *   <li>沿「往外扩」的那个方向看坡度：上坡把估计抬高、下坡压低。坡用中心差分，只有一侧有值时
 *       （外圈还没采到是常态）用单边差；</li>
 *   <li>坡只推一半（{@link #DAMP}），并且单个点之间的涨跌先夹到 ±48 格 —— 山脊和峡谷边上宁可
 *       比实际<b>低</b>一点：起点偏低只多走一轮抬档（一次判定而已），偏高就要实打实多扫一段空气。</li>
 * </ol>
 */
public final class SurfaceTrend {

    /** 没有可用邻居时的取值，和 {@link SurfaceProbe#NO_HINT} 是同一个数。 */
    public static final int NO_HINT = SurfaceProbe.NO_HINT;

    /**
     * 趋势往前推的阻尼：只推一半，剩下交给 probe 抬档。阻尼和 {@code SurfaceProbe} 的留量是一起
     * 模拟调出来的（推六成 + 留 24 格的话，比不给提示还多扫一倍空气）。
     */
    private static final double DAMP = 0.5;
    /** 相邻两个高度点之间允许的最大涨跌（格）：再陡就不外推了，避免悬崖边一把估飞。 */
    private static final double SLOPE_LIMIT = 48.0;

    private SurfaceTrend() {}

    /**
     * @param west     这一列 -X 侧邻居已采到的地表 y，没采到给 {@link #NO_HINT}
     * @param east     +X 侧邻居，同上
     * @param north    -Z 侧邻居，同上
     * @param south    +Z 侧邻居，同上
     * @param outwardX 这一点相对图中心的 X 偏移（不用归一化）
     * @param outwardZ 这一点相对图中心的 Z 偏移（不用归一化）
     * @return 这一列地表 y 的估计；四个邻居一个都没有就返回 {@link #NO_HINT}
     */
    public static int predict(int west, int east, int north, int south, double outwardX, double outwardZ) {
        double sum = 0.0;
        int known = 0;
        if (west != NO_HINT) {
            sum += west;
            known++;
        }
        if (east != NO_HINT) {
            sum += east;
            known++;
        }
        if (north != NO_HINT) {
            sum += north;
            known++;
        }
        if (south != NO_HINT) {
            sum += south;
            known++;
        }
        if (known == 0) return NO_HINT;
        double level = sum / known;
        double length = Math.sqrt(outwardX * outwardX + outwardZ * outwardZ);
        if (length < 1.0E-4) return (int) Math.round(level);
        // 外扩方向上的每格涨跌 = 梯度点乘单位方向
        double outward = (gradient(west, east, level) * outwardX + gradient(north, south, level) * outwardZ) / length;
        return (int) Math.round(level + DAMP * Mth.clamp(outward, -SLOPE_LIMIT, SLOPE_LIMIT));
    }

    /** 一个轴上每格的高度变化：两侧都有值用中心差分，只有一侧就用它和均值的差。 */
    private static double gradient(int back, int forward, double level) {
        boolean hasBack = back != NO_HINT;
        boolean hasForward = forward != NO_HINT;
        if (hasBack && hasForward) return (forward - back) / 2.0;
        if (hasForward) return forward - level;
        if (hasBack) return level - back;
        return 0.0;
    }
}
