package dev.yard.site.view;

import java.util.List;

/**
 * 一张「能画成三维沙盘」的地形字段：大沙盘（一格 = 约 15.6 方块）和区块精读
 * （一格 = 1 区块 = 16 方块）都实现这个接口，渲染器只认接口，两种视图共用同一套
 * 柱子、渐入和点选逻辑。
 *
 * <p>格子分两层：<b>细格</b>是有颜色/群系的最小单位，<b>柱子</b>是画出来的一格
 * （大沙盘一根柱子盖 2×2 个细格，精读一格就是一根柱子）。接口里带 {@code Cell} 的方法
 * 用细格坐标，其余用柱子坐标。
 */
interface SiteField {

    /** 每边的柱子数。 */
    int columns();

    /** 一根柱子盖住几个细格（每边）。 */
    int perColumn();

    /** 每边的细格数（模型坐标换算的分母是 {@code fine() - 1}）。 */
    int fine();

    /** 一个细格代表多少方块。 */
    double step();

    /** 图中心的方块坐标。 */
    int centerX();

    int centerZ();

    int seaLevel();

    int minY();

    int maxY();

    /**
     * 这根柱子现在代表哪一块地（身份键）。精读的窗口中心会随拖动挪，同一根柱子对应的区块
     * 会变，渲染器靠这个键判断「柱子缓存（边、颜色、地表 y）」还能不能用。
     */
    long columnKey(int ci, int cj);

    /** 这根柱子算完了吗（颜色、地表高度都已经写好才能画）。 */
    boolean ready(int ci, int cj);

    /**
     * 这根柱子第一次被画面看见的时间（渐入动画的起点）。
     * 由字段自己记账：中心随拖动移动时，同一格不会因为换窗口而重新淡入。
     */
    long drawnAt(int ci, int cj, long now);

    /**
     * 细格颜色 0xRRGGBB（含山体阴影）。<b>画的时候现算</b>：底色调 {@link BiomePalette#colorFor}
     * 按当前配色（编辑模式可以改群系颜色）解析，所以改完色不用重采样，渲染器只要重算那几种柱子。
     */
    int colorAtCell(int cellX, int cellY);

    /** 细格在 {@link #biomes()} 里的下标。 */
    int biomeIndexAtCell(int cellX, int cellY);

    /** 细格所属柱子的地表 y。 */
    int surfaceAtCell(int cellX, int cellY);

    List<BiomeInfo> biomes();

    /** 进度条：已经算完多少 / 总共多少。 */
    int progress();

    int progressTotal();

    /** 全部采样完成了吗。 */
    boolean full();

    /** 日志/标题里叫什么（大沙盘、区块精读）。 */
    String label();

    /** 复位/切图时的默认缩放（精读窗口方块数少得多，默认要小，否则一屏装不下）。 */
    float defaultZoom();

    /**
     * 这张图当前要不要画底面板：精读是圆形窗口，方板子的四个角会戳在圆外面，所以不画。
     */
    boolean showsFloor();

    /**
     * 窗口中心的<b>连续</b>偏移（模型空间，地面 [-1,1]²）：精读把中心挪在两个区块之间时，
     * 整块地形按这个量平移，而不是"攒满一格弹一下"。大沙盘中心固定，返回 0。
     */
    float originShiftX();

    float originShiftY();

    /**
     * 这一格的边缘渐隐系数（0 = 不画/全透明，1 = 完全不透明）。
     * 精读是圆形窗口，外圈几格逐渐透明收边，拖动时新进来/快出去的区块都有过渡。
     */
    float edgeFade(int ci, int cj);

    /** 当前竖向比例：地表 y 到柱子顶厚的换算分母，跟着最高点慢慢涨，别一上来把地皮拉平。 */
    float reliefSpan();

    /** 把竖向比例朝 {@code target} 挪一小步（每帧一次，涨快降慢）。 */
    void easeReliefSpan(float target);
}
