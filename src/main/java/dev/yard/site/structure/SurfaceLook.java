package dev.yard.site.structure;

/**
 * 一个方块状态在落成面表里的外观：<b>已经拆成面片</b>的局部几何（0..1）+ 每片的颜色。
 *
 * <p>和 {@link SurfaceMesh} 的区别：这里只有"这一格长什么样"，不知道它在结构里的第几格、
 * 也不知道世界坐标。同一份结构里同一个状态会出现几千次，按调色板下标算一次就够。
 *
 * <p>面片的来源优先是客户端<b>烘焙模型</b>（{@code BakedModel} 的四边面）：
 * 栅栏/栏杆的横杆、墙的压顶、玻璃板的边、楼梯的斜面，模型里本来就有；
 * 模型拿不到时（方块实体自己画、模型缺失）才退回碰撞/轮廓形状盒。
 * 颜色优先是贴图平均色（{@code SpriteContents} 像素平均），拿不到才用地图色。
 */
public final class SurfaceLook {

    /** 这一格不画（空气、未安装的 mod 方块）。 */
    public final boolean skip;
    /** 满格方块：隔壁是它时贴边的那张脸直接省掉。 */
    public final boolean fullCube;
    /** 会遮住邻居：只有它才允许把邻居的贴边脸剔掉（树叶是满格但不遮）。 */
    public final boolean occludes;
    /** 面片（局部 0..1 的四个角，颜色已按贴图/地图色定好，明暗留给面表按世界光照算）。 */
    public final Piece[] pieces;

    public SurfaceLook(boolean skip, boolean fullCube, boolean occludes, Piece[] pieces) {
        this.skip = skip;
        this.fullCube = fullCube;
        this.occludes = occludes;
        this.pieces = pieces == null ? new Piece[0] : pieces;
    }

    public static SurfaceLook empty() {
        return new SurfaceLook(true, false, false, null);
    }

    /**
     * 一张四边面片。
     *
     * @param corners     四个角的局部坐标（0..1），12 个 float：x,y,z 依次
     * @param nx ny nz    向外法线（单位向量；来自模型绕序，可能不是轴对齐）
     * @param rgb         基础颜色（贴图平均色 / 地图色，已含 tint）
     * @param doubleSided 双面片（十字花草这类模型）：背面也要画，渲染时不做背面剔除
     */
    public record Piece(float[] corners, float nx, float ny, float nz, int rgb, boolean doubleSided) {}
}
