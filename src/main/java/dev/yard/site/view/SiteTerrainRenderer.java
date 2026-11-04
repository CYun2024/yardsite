package dev.yard.site.view;

import dev.yard.site.YardSiteLog;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.yard.site.api.BlueprintBox;
import dev.yard.site.api.SitePlan;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;

import java.util.Arrays;
import java.util.List;

/**
 * 把 {@link SiteField} 画成三维沙盘：每个格子一根<b>方的</b>柱子（形状不做平滑，台阶就是台阶），
 * 一格一色（不混合），四面墙只按朝向调亮度，所以从哪边看都是实体。大沙盘和区块精读共用这一个
 * 渲染器，区别只在字段：格子多大、一步多少方块。
 *
 * <h2>柱子宽度（{@link #setWidthScale}）</h2>
 * 横向（地图平面，含格子间距、底板、占地框）按同一倍数撑开，<b>高度不跟着撑</b>：
 * 所以柱子始终正好拼合（不会挤成一团），但相对高度变矮变敦实；调小则相反，沙盘收小、
 * 柱子相对更尖细。滚轮缩放是等比放大整张图，和这个旋钮是两回事。
 *
 * <h2>坐标</h2>
 * 模型空间：地面 [-1,1]²（x=地图向东、y=地图向南），高度轴 z 向上。屏幕位置自己算：
 * {@code 屏幕 = 中心 + 缩放·(旋转后的 x,y)}，深度另给一个小系数（{@link #DEPTH_SCALE}）摆进 GUI
 * 的深度带里，不跟屏幕缩放挂钩，免得放大后跑出可视范围被裁掉。
 *
 * <h2>遮挡</h2>
 * GUI 合批结束时 {@link GuiGraphics#flush()} 会关掉深度测试，所以先后顺序就是覆盖顺序：每帧把
 * 柱子按旋转后的深度<b>从远到近</b>排好再提交（计数排序，4096 个桶）。这批改写还要关掉深度写入
 * 和背面剔除，否则后画的字会被沙盘挡、单面条片的墙会被剔掉。
 *
 * <h2>动画</h2>
 * 两层：① 加载时格子从中心一圈圈往外长（{@code drawnAt} 起算，一边淡入一边从底面长高）；
 * ② 大沙盘 ↔ 区块精读切换时，界面给两个字段各传一组<b>全局 alpha + 高度比例</b>，旧图淡出下沉、
 * 新图淡入长起来，再加一点镜头推近，就是那段过渡。
 */
final class SiteTerrainRenderer {

    /** 地形起伏在模型空间里的最大厚度（地面边长是 2）。 */
    static final float RELIEF = 0.40F;
    /** 水面也保证一层薄皮，海面和海岸才连得起来。 */
    private static final float WATER_SINK = 0.05F;
    /** 一根柱子长高 + 淡入的时长（毫秒）。 */
    private static final int FADE_MS = 520;
    /** 淡入期间至少还有这么不透明，否则新柱子完全看不见。 */
    private static final float FADE_FLOOR = 0.25F;
    /** 深度只用来占位（合批时深度测试是关的），压在小范围内避免被裁。 */
    static final float DEPTH_SCALE = 110.0F;
    static final float Z_BASE = 200.0F;
    /** 俯视 0.22 ≈ 正上方往下看，1.40 ≈ 快平视。 */
    private static final float PITCH_MIN = 0.22F;
    private static final float PITCH_MAX = 1.40F;
    /** 默认俯仰：0.95 rad ≈ 54°，斜着看立体感最好。 */
    private static final float DEFAULT_PITCH = 0.95F;
    private static final float DEFAULT_YAW = 0.65F;
    private static final int BUCKETS = 4096;
    /** 点选时沿视线推进的步长（模型单位）。 */
    private static final float MARCH_STEP = 0.007F;
    /** 临时数组容量：得装下最大的那张图（精读圆形窗口半径 64 → 129×129）。 */
    private static final int MAX_COLUMNS = Math.max(SiteMapSampler.HEIGHT_RES, ChunkSitePlanner.COLUMNS);
    private static final int MAX_CELLS = MAX_COLUMNS * MAX_COLUMNS;

    private static final int PLATE = 0xF2171A22;
    private static final int GOLD = 0xFFFFD24A;

    /** 每根柱子这一帧的柱顶（模型 z），负数 = 还没出现。 */
    private final float[] tops = new float[MAX_CELLS];
    /** 每根柱子的地表 y（采集后不变，动画只改它画多高）。 */
    private final int[] columnMax = new int[MAX_CELLS];
    /** 每根柱子这一帧的淡入透明度（0..255）。 */
    private final int[] colAlpha = new int[MAX_CELLS];
    /**
     * 每根柱子<b>不用每帧重算</b>的部分：模型空间的四条边、颜色。
     * 柱子宽度是靠 {@link #vertex} 整体撑开横向实现的，所以改宽度也不用重算这些。
     */
    private final float[] edgeX0 = new float[MAX_CELLS];
    private final float[] edgeX1 = new float[MAX_CELLS];
    private final float[] edgeY0 = new float[MAX_CELLS];
    private final float[] edgeY1 = new float[MAX_CELLS];
    private final int[] colRgb = new int[MAX_CELLS];
    /**
     * 一根柱子最终取自哪个细格（大沙盘 2×2 里占多数的那个）：颜色、重着色、群系高亮都问它。
     */
    private final int[] colCellX = new int[MAX_CELLS];
    private final int[] colCellY = new int[MAX_CELLS];
    /** 这根柱子取的群系在 {@link SiteField#biomes()} 里的下标（编辑模式判断是不是正在调色那种）。 */
    private final int[] colBiome = new int[MAX_CELLS];
    /** 这根柱子是不是「正在调色」那个群系（顶面提亮，一眼看到铺到哪）。 */
    private final boolean[] colHighlight = new boolean[MAX_CELLS];
    private final boolean[] colPrepared = new boolean[MAX_CELLS];
    /** 缓存属于哪一块地：柱子序号没变但它代表的区块变了（精读挪中心）时，缓存要重算。 */
    private final long[] colKey = new long[MAX_CELLS];
    /** 当前缓存属于哪张图；换图（大沙盘↔精读）时全部作废重算一次。 */
    private SiteField preparedField;
    /** 缓存算出来时的配色版本（编辑模式改了群系颜色就变）。 */
    private int preparedVersion;
    /** 正在调色的那个群系（null = 编辑模式没开面板），它的所有柱子顶面提亮。 */
    private ResourceLocation highlightBiome;
    private final int[] readyOrder = new int[MAX_CELLS];
    private final int[] bucket = new int[MAX_CELLS];
    private final int[] sorted = new int[MAX_CELLS];
    private final int[] counts = new int[BUCKETS];
    private final int[] write = new int[BUCKETS];
    private final Matrix3f rotation = new Matrix3f();
    /** {@link #rotation} 的列主序读数缓存，只在算 {@link #r} 时用。 */
    private final float[] raw = new float[9];
    /** 旋转矩阵按行摊平：{@code r[0..2]} = x 行，{@code r[3..5]} = y 行，{@code r[6..8]} = 深度行。 */
    private final float[] r = new float[9];

    private float yaw = DEFAULT_YAW;
    private float pitch = DEFAULT_PITCH;
    private float roll;
    /** 起始缩放 = 大沙盘的默认值；进/出精读时界面会把它顺到新图自己的默认值。 */
    private float zoom = 2.0F;
    private float panX;
    private float panY;
    private int readyColumns;
    private long nextLogAt;
    /** 视图切换动画用：镜头整体推近/拉远（1 = 正常）。 */
    private float zoomBoost = 1.0F;
    /** 上一帧的屏幕缩放（像素/模型单位）：交接给落成动画，让它的第一帧和精读画面同大。 */
    private float lastScale = 1.0F;
    /** 柱子宽度：画出来的盒子是格子的几倍宽（1 = 正好拼合）。 */
    private float widthScale = 1.0F;
    /** 这一帧要平移多少（精读的中心停在两个区块之间；大沙盘恒为 0）。 */
    private float shiftX;
    private float shiftY;
    /** 揭示动画的旅社蓝图（null = 不画）；{@code blueprintElapsed} 是蓝图开演了多久。 */
    private SiteBlueprint blueprint;
    private long blueprintElapsed = -1L;
    private float blueprintAlpha = 1.0F;

    /** 上一次点选命中的细格。 */
    int pickCellX;
    int pickCellY;

    // ── 视角 / 显示设置 ──────────────────────────────────────────────────

    /** 拖动转视角：横=方向、竖=俯仰（跟手：往哪拖就往哪转）。 */
    void dragRotate(double dx, double dy, boolean rollMode) {
        if (rollMode) {
            roll += (float) (dx * 0.006);
            yaw += (float) (dy * 0.006);
        } else {
            yaw -= (float) (dx * 0.006);
            pitch = Mth.clamp(pitch - (float) (dy * 0.006), PITCH_MIN, PITCH_MAX);
        }
        yaw = wrap(yaw);
        roll = wrap(roll);
    }

    private static float wrap(float angle) {
        float twoPi = (float) (Math.PI * 2);
        if (angle > Math.PI) return angle - twoPi;
        if (angle < -Math.PI) return angle + twoPi;
        return angle;
    }

    /** 让镜头自己慢慢转（等出生点区块那段时间用）：只改方向，不碰俯仰。 */
    void orbit(float radians) {
        yaw = wrap(yaw + radians);
    }

    /**
     * 这套沙盘相机换成<b>原版视角</b>的方位角（度）：我们的模型空间是 x=东、y=南、z=上，
     * 深度行朝观察者，所以视线在世界里的方位是 {@code (sin yaw, -cos yaw)}（东、南），
     * 也就是原版的 {@code 180° - yaw}。交接给世界坐标那套柱子时用它，视角才不会跳。
     */
    float viewMcYawDegrees() {
        return 180.0F - (float) Math.toDegrees(yaw);
    }

    /** 这套沙盘相机换成原版视角的俯角（度，正=往下看）：{@code pitch} 是从正上方往下量的。 */
    float viewMcPitchDegrees() {
        return Mth.clamp(90.0F - (float) Math.toDegrees(pitch), 5.0F, 85.0F);
    }

    void dragPan(double dx, double dy) {
        panX += (float) dx;
        panY += (float) dy;
    }

    /** 直接指定屏幕平移（进世界前那段动画要让落点一直停在画面正中，不能靠累加）。 */
    void setPan(double x, double y) {
        panX = (float) x;
        panY = (float) y;
    }

    /**
     * 想让某个细格停在画面正中，屏幕平移该是多少：先按当前旋转和柱子宽度把它的模型坐标
     * 投影成屏幕偏移，再取反号。缩放、转视角每帧都在变，所以这个量每帧重算一次。
     */
    void panToCenterCell(SiteField field, int cellX, int cellY, int areaSize, double[] out) {
        float s = scaleOf(areaSize) * widthScale;
        float gx = model(field.fine(), cellX) + shiftX;
        float gy = model(field.fine(), cellY) + shiftY;
        out[0] = -(r[0] * gx + r[1] * gy) * s;
        out[1] = -(r[3] * gx + r[4] * gy) * s;
    }

    void zoomBy(int delta) {
        setZoom(zoom * (delta > 0 ? 1.12F : 1 / 1.12F));
    }

    /** 缩放（滚轮、切图动画都走这里），限制在 0.6×~8×。 */
    void setZoom(float value) {
        zoom = Mth.clamp(value, 0.6F, 8.0F);
    }

    /** 回到这张图自己的默认视角（默认缩放按图不同：精读窗口方块少，默认要比大沙盘小）。 */
    void resetView(SiteField field) {
        yaw = DEFAULT_YAW;
        pitch = DEFAULT_PITCH;
        roll = 0.0F;
        zoom = field.defaultZoom();
        panX = 0.0F;
        panY = 0.0F;
    }

    float panX() {
        return panX;
    }

    float panY() {
        return panY;
    }

    /** 窗口中心整格挪走后，把「中心已经替画面走过的那段」加回 pan，画面才不跳。 */
    void shiftPan(double dx, double dy) {
        panX += (float) dx;
        panY += (float) dy;
    }

    /**
     * 屏幕拖动 → 窗口中心该挪多少<b>格</b>（精读用）。先按屏幕缩放折回模型单位，再用当前旋转的
     * 地面两行解回地面两个轴，最后取反号（中心往反方向走，画面才跟着手走）——
     * 所以斜着拉也是顺的，不再是"横一下竖一下"。
     */
    void dragToCells(SiteField field, int areaSize, double dx, double dy, float[] out) {
        float s = scaleOf(areaSize) * widthScale;
        if (s <= 0.0F) {
            out[0] = 0.0F;
            out[1] = 0.0F;
            return;
        }
        float a = (float) (dx / s);
        float b = (float) (dy / s);
        // 地面两行：屏幕 = [r0 r1; r3 r4]·(模型 x,y)（高度那一项对水平拖动没贡献）
        float det = r[0] * r[4] - r[1] * r[3];
        float mx;
        float my;
        if (Math.abs(det) < 1.0E-4F) {
            mx = a;
            my = b;
        } else {
            mx = (r[4] * a - r[1] * b) / det;
            my = (r[0] * b - r[3] * a) / det;
        }
        float cellsPerModel = (field.fine() - 1) * 0.5F;
        out[0] = -mx * cellsPerModel;
        out[1] = -my * cellsPerModel;
    }

    float zoom() {
        return zoom;
    }

    /** 视图切换动画用：过渡期间镜头整体推近/拉远（1 = 正常）。 */
    void setZoomBoost(float boost) {
        zoomBoost = boost;
    }

    /**
     * 柱子宽度：横向撑开的倍数（间距、底板、占地框都跟着走，高度不跟着撑）。
     *
     * <p>因为间距和柱子一起按同倍数拉开，柱子<b>始终正好拼合</b>，不存在互相压住；
     * 高度保持不变，所以倍数大了看着更粗更敦实、小了更细更尖。跟滚轮缩放（整图等比放大）
     * 是两个独立的量。
     *
     * @param scale 0.5 = 收小一半（相对更细高），1 = 原样，2 = 横向撑开一倍（相对更粗矮）
     */
    void setWidthScale(float scale) {
        widthScale = Mth.clamp(scale, 0.5F, 2.0F);
    }

    float widthScale() {
        return widthScale;
    }

    /**
     * 揭示动画的旅社蓝图。
     *
     * @param elapsedMs 蓝图开演了多久；负数 = 还没开始（不画）
     * @param alpha     整张蓝图的不透明度
     */
    void setBlueprint(SiteBlueprint blueprint, long elapsedMs, float alpha) {
        this.blueprint = blueprint;
        this.blueprintElapsed = elapsedMs;
        this.blueprintAlpha = Mth.clamp(alpha, 0.0F, 1.0F);
    }

    /**
     * 相机状态，交接给下一段动画用（进世界前那段要接着这张图继续推镜头，不能跳）。
     * {@code scale} 是上一帧的屏幕缩放（像素/模型单位），{@code panX/panY} 和它同一套坐标系。
     */
    record View(float yaw, float pitch, float roll, float zoom, float panX, float panY,
                float widthScale, float scale) {
    }

    View snapshot() {
        return new View(yaw, pitch, roll, zoom, panX, panY, widthScale, lastScale);
    }

    /**
     * 沙盘正交画面的旋转三行，摊平成 {@code rows}：{@code [0..2]} = 屏幕 x 行、
     * {@code [3..5]} = 屏幕 y 行、{@code [6..8]} = 朝观察者那一行（只用来排绘制先后）。
     *
     * <p>落成动画开场要接着这张图往透视 morph，正交侧必须和这里<b>一字不差</b>，所以基算在
     * 这一处，别在别的地方再推一遍。
     */
    static void orthoRows(float yaw, float pitch, float roll, float[] rows) {
        Matrix3f rotation = new Matrix3f();
        rotation.identity();
        rotation.rotateZ(roll);
        rotation.rotateX(pitch);
        rotation.rotateZ(yaw);
        float[] raw = new float[9];
        rotation.get(raw);
        rows[0] = raw[0];
        rows[1] = raw[3];
        rows[2] = raw[6];
        rows[3] = raw[1];
        rows[4] = raw[4];
        rows[5] = raw[7];
        rows[6] = raw[2];
        rows[7] = raw[5];
        rows[8] = raw[8];
    }

    void restore(View view) {
        yaw = view.yaw();
        pitch = view.pitch();
        roll = view.roll();
        panX = view.panX();
        panY = view.panY();
        widthScale = view.widthScale();
        setZoom(view.zoom());
    }

    // ── 渲染 ────────────────────────────────────────────────────────────

    /**
     * 画一帧沙盘。
     *
     * @param hoverColumn   鼠标所在柱子（-1 = 不在图上）
     * @param selectedCellX 已选落点的细格，没选时传 -1
     * @param globalAlpha   整张图的不透明度（切换动画用，1.0 = 正常）
     * @param heightScale   整张图的高度比例（切换动画用：旧的压下去、新的长起来）
     */
    void render(GuiGraphics g, SiteField field, int areaX, int areaY, int areaSize, long now,
                int hoverColumn, int selectedCellX, int selectedCellY,
                float globalAlpha, float heightScale) {
        int columns = field.columns();
        int per = field.perColumn();
        buildRotation();
        shiftX = field.originShiftX();
        shiftY = field.originShiftY();
        float scale = scaleOf(areaSize);
        lastScale = scale;
        float originX = areaX + areaSize / 2.0F + panX;
        float originY = areaY + areaSize / 2.0F + panY;
        VertexConsumer consumer = g.bufferSource().getBuffer(RenderType.gui());

        collect(field, now, columns, per, heightScale);
        sortByDepth(columns);

        if (field.showsFloor()) {
            floor(consumer, originX, originY, scale, globalAlpha);
        }

        boolean seeEast = r[6] > 0.0F;
        boolean seeWest = r[6] < 0.0F;
        boolean seeSouth = r[7] > 0.0F;
        boolean seeNorth = r[7] < 0.0F;
        boolean seeTop = r[8] > 0.0F;

        long t0 = System.nanoTime();
        int quads = 0;
        for (int k = 0; k < readyColumns; k++) {
            int column = sorted[k];
            float top = tops[column];
            if (top < 0.0F) continue;
            int alpha = (int) (colAlpha[column] * globalAlpha);
            if (alpha <= 0) continue;
            int ci = column % columns;
            int cj = column / columns;
            float x0 = edgeX0[column];
            float x1 = edgeX1[column];
            float y0 = edgeY0[column];
            float y1 = edgeY1[column];
            int rgb = colRgb[column];

            if (seeTop) {
                int flat = rgb | (alpha << 24);
                vertex(consumer, originX, originY, scale, x0, y0, top, flat);
                vertex(consumer, originX, originY, scale, x1, y0, top, flat);
                vertex(consumer, originX, originY, scale, x1, y1, top, flat);
                vertex(consumer, originX, originY, scale, x0, y1, top, flat);
                quads++;
            }
            if (seeEast) {
                float low = ci + 1 < columns ? Math.max(tops[column + 1], 0.0F) : 0.0F;
                if (top > low) {
                    quads += wall(consumer, originX, originY, scale, x1, y0, x1, y1,
                            low, top, rgb, alpha, 0.82F, 0.60F);
                }
            }
            if (seeWest) {
                float low = ci > 0 ? Math.max(tops[column - 1], 0.0F) : 0.0F;
                if (top > low) {
                    quads += wall(consumer, originX, originY, scale, x0, y1, x0, y0,
                            low, top, rgb, alpha, 0.56F, 0.40F);
                }
            }
            if (seeSouth) {
                float low = cj + 1 < columns ? Math.max(tops[column + columns], 0.0F) : 0.0F;
                if (top > low) {
                    quads += wall(consumer, originX, originY, scale, x0, y1, x1, y1,
                            low, top, rgb, alpha, 0.70F, 0.52F);
                }
            }
            if (seeNorth) {
                float low = cj > 0 ? Math.max(tops[column - columns], 0.0F) : 0.0F;
                if (top > low) {
                    quads += wall(consumer, originX, originY, scale, x1, y0, x0, y0,
                            low, top, rgb, alpha, 0.46F, 0.34F);
                }
            }
            if (colHighlight[column]) {
                // 编辑模式正在调色的那个群系：顶面压一层亮白，改一下就知道它铺到哪
                int mark = ((int) (0x44 * globalAlpha) << 24) | 0xFFFFFF;
                vertex(consumer, originX, originY, scale, x0, y0, top + 0.004F, mark);
                vertex(consumer, originX, originY, scale, x1, y0, top + 0.004F, mark);
                vertex(consumer, originX, originY, scale, x1, y1, top + 0.004F, mark);
                vertex(consumer, originX, originY, scale, x0, y1, top + 0.004F, mark);
                quads++;
            }
        }
        long buildNs = System.nanoTime() - t0;

        // 合批时深度测试是关的：遮挡全靠上面「从远到近」的提交顺序。
        // 柱面都是单面条片，绕序在任意角度下没法保证都对 → 背面剔除关掉。
        // 深度写入也要关掉：沙盘比 GUI 平面（z≈0）靠前，一写深度，后画的字、提示框、按钮
        // 就会因为「比已写入的深度远」被 LEQUAL 拒掉，整个被沙盘盖住。
        long t1 = System.nanoTime();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        g.flush();

        // 第二层：旅社蓝图和标记永远压在最上面
        VertexConsumer overlay = g.bufferSource().getBuffer(RenderType.gui());
        if (blueprint != null && blueprintElapsed >= 0L && blueprintAlpha > 0.0F) {
            drawBlueprint(overlay, originX, originY, scale, field);
        }

        if (hoverColumn >= 0 && hoverColumn < columns * columns && tops[hoverColumn] >= 0.0F) {
            quadFlat(overlay, originX, originY, scale, edgeX0[hoverColumn], edgeX1[hoverColumn],
                    edgeY0[hoverColumn], edgeY1[hoverColumn], tops[hoverColumn] + 0.006F,
                    ((int) (0x4C * globalAlpha) << 24) | 0xFFFFFF);
        }
        int selectedColumn = selectedCellX >= 0 ? columnOf(field, selectedCellX, selectedCellY) : -1;
        float selectedTop = selectedColumn >= 0 ? tops[selectedColumn] : -1.0F;
        if (selectedColumn >= 0 && selectedTop >= 0.0F) {
            footprint(overlay, originX, originY, scale, field, selectedCellX, selectedCellY,
                    selectedTop + 0.012F, globalAlpha);
            pin(overlay, originX, originY, scale, field, selectedColumn, selectedTop + 0.16F,
                    globalAlpha);
        }
        g.flush();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        long flushNs = System.nanoTime() - t1;

        // 每两秒一条的帧统计只给开发用（-Dyardsite.debug=true）；关着时连 GC 计数都不去查，
        // 也别每帧拼那串字符串 —— 这个是渲染热路径，一帧一帧地拼下来是白花的钱。
        if (YardSiteLog.debugEnabled() && now >= nextLogAt) {
            nextLogAt = now + 2000L;
            int frames = frameCountSinceLog;
            long gcCount = gcTotal();
            long gcTime = gcMillis();
            YardSiteLog.debug("沙盘(" + field.label() + ")：柱子 " + readyColumns
                    + "/" + columns * columns + "，宽度 ×"
                    + String.format(java.util.Locale.ROOT, "%.2f", widthScale) + "，四边形 " + quads
                    + "，顶点 " + (quads * 4) + "，每帧建模 " + (buildNs / 1_000_000L) + " ms"
                    + "，每帧上传绘制 " + (flushNs / 1_000_000L) + " ms"
                    + "，帧数 " + frames + "（约 " + (frames * 1000L / 2000L) + " fps）"
                    + "，GC 这 2 秒 +" + (gcCount - lastLogGcCount) + " 次/+"
                    + (gcTime - lastLogGcMillis) + " ms"
                    + "，缩放 " + String.format(java.util.Locale.ROOT, "%.2f", zoom));
            frameCountSinceLog = 0;
            lastLogGcCount = gcCount;
            lastLogGcMillis = gcTime;
        }
        frameCountSinceLog++;
    }

    private int frameCountSinceLog;
    private long lastLogGcCount;
    private long lastLogGcMillis;

    private static long gcTotal() {
        long sum = 0L;
        for (java.lang.management.GarbageCollectorMXBean bean
                : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = bean.getCollectionCount();
            if (count > 0L) sum += count;
        }
        return sum;
    }

    private static long gcMillis() {
        long sum = 0L;
        for (java.lang.management.GarbageCollectorMXBean bean
                : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            long time = bean.getCollectionTime();
            if (time > 0L) sum += time;
        }
        return sum;
    }

    private float scaleOf(int areaSize) {
        return areaSize * 0.46F * zoom * zoomBoost;
    }

    /** 一根柱子的四条边和颜色：等这一格采完算一次，之后每帧直接用（换图、换地块或改配色时重算）。 */
    private void prepareColumn(SiteField field, int column, int ci, int cj, int per, long key) {
        int fine = field.fine();
        int s0 = ci * per;
        int f0 = cj * per;
        int s1 = Math.min(fine - 1, s0 + per - 1);
        int f1 = Math.min(fine - 1, f0 + per - 1);
        float centerX = (s0 + s1) * 0.5F;
        float centerY = (f0 + f1) * 0.5F;
        float halfX = (s1 - s0 + 1) * 0.5F;
        float halfY = (f1 - f0 + 1) * 0.5F;
        // 横向撑开是 vertex() 统一做的，所以这里存的是数据空间的边 → 改柱子宽度也不用重算
        edgeX0[column] = model(fine, centerX - halfX);
        edgeX1[column] = model(fine, centerX + halfX);
        edgeY0[column] = model(fine, centerY - halfY);
        edgeY1[column] = model(fine, centerY + halfY);
        int cell = majorityCell(field, s0, f0, per);
        int cellX = cell >>> 16;
        int cellY = cell & 0xFFFF;
        colCellX[column] = cellX;
        colCellY[column] = cellY;
        colBiome[column] = field.biomeIndexAtCell(cellX, cellY);
        colRgb[column] = field.colorAtCell(cellX, cellY) & 0xFFFFFF;
        colHighlight[column] = matchesHighlight(field, colBiome[column]);
        columnMax[column] = field.surfaceAtCell(s0, f0);
        colKey[column] = key;
        colPrepared[column] = true;
    }

    /**
     * 只把某种群系的柱子颜色重算一遍，其余缓存原样留着。
     * 拖动取色方块时每帧都会改一次颜色，整张图重算（大沙盘 16384 根柱子）会看出掉帧。
     */
    private void retint(SiteField field, ResourceLocation id, int columns) {
        List<BiomeInfo> biomes = field.biomes();
        int cells = columns * columns;
        for (int column = 0; column < cells; column++) {
            if (!colPrepared[column]) continue;
            int b = colBiome[column];
            if (b < 0 || b >= biomes.size() || !biomes.get(b).id().equals(id)) continue;
            colRgb[column] = field.colorAtCell(colCellX[column], colCellY[column]) & 0xFFFFFF;
        }
    }

    /**
     * 编辑模式：把正在调色的那个群系在图上标出来（null = 不标）。
     * 标记跟着柱子缓存算，所以换目标时整张缓存作废重算一次（只在点吸管时发生，拖动时不动）。
     */
    void setHighlightBiome(@Nullable ResourceLocation id) {
        if (java.util.Objects.equals(highlightBiome, id)) return;
        highlightBiome = id;
        Arrays.fill(colPrepared, false);
    }

    /** 这根柱子是不是正在调色那个群系。 */
    private boolean matchesHighlight(SiteField field, int biomeIndex) {
        if (highlightBiome == null) return false;
        List<BiomeInfo> biomes = field.biomes();
        return biomeIndex >= 0 && biomeIndex < biomes.size()
                && highlightBiome.equals(biomes.get(biomeIndex).id());
    }

    /**
     * 每帧只做<b>会变</b>的那部分：竖向比例、每根柱子这一帧画多高（渐入 + 整图高度比例）、
     * 淡入透明度。柱子的边和颜色走 {@link #prepareColumn} 的缓存，不再每帧重算。
     */
    private void collect(SiteField field, long now, int columns, int per, float heightScale) {
        int prefsVersion = SiteEditorPrefs.version();
        if (field != preparedField) {
            Arrays.fill(colPrepared, false);
            preparedField = field;
            preparedVersion = prefsVersion;
        } else if (preparedVersion != prefsVersion) {
            // 编辑模式改了群系颜色：只动过一种就只重算那种柱子（拖着取色方块走色时才不卡），
            // 大面积变化（全部还原、载入配置）才整张图重算一次
            ResourceLocation single = SiteEditorPrefs.lastSingleChange();
            if (prefsVersion == preparedVersion + 1 && single != null) {
                retint(field, single, columns);
            } else {
                Arrays.fill(colPrepared, false);
            }
            preparedVersion = prefsVersion;
        }
        int cells = columns * columns;
        readyColumns = 0;
        int base = field.seaLevel() - 4;
        int highest = base;
        for (int column = 0; column < cells; column++) {
            int ci = column % columns;
            int cj = column / columns;
            if (!field.ready(ci, cj)) {
                tops[column] = -1.0F;
                colAlpha[column] = 0;
                continue;
            }
            long key = field.columnKey(ci, cj);
            if (!colPrepared[column] || colKey[column] != key) {
                prepareColumn(field, column, ci, cj, per, key);
            }
            readyOrder[readyColumns++] = column;
            if (columnMax[column] > highest) highest = columnMax[column];
        }
        field.easeReliefSpan(Math.max(60.0F, highest - base));
        float span = Math.max(20.0F, field.reliefSpan());
        for (int k = 0; k < readyColumns; k++) {
            int column = readyOrder[k];
            int ci = column % columns;
            int cj = column / columns;
            float t = Mth.clamp((now - field.drawnAt(ci, cj, now)) / (float) FADE_MS, 0.0F, 1.0F);
            float ratio = Math.max((columnMax[column] - base) / span, WATER_SINK);
            tops[column] = ratio * RELIEF * smooth(t) * heightScale;
            // 圆形窗口的外圈几格逐渐透明：新进来淡入、快出去淡出，不硬弹
            colAlpha[column] = (int) (255.0F * (FADE_FLOOR + (1.0F - FADE_FLOOR) * t)
                    * field.edgeFade(ci, cj));
        }
    }

    /** 计数排序，输出从远到近；桶比柱子细得多，同桶内的先后差别看不出来。 */
    private void sortByDepth(int columns) {
        Arrays.fill(counts, 0);
        for (int k = 0; k < readyColumns; k++) {
            int column = readyOrder[k];
            float mx = modelOf(columns, column % columns);
            float my = modelOf(columns, column / columns);
            float depth = r[6] * mx + r[7] * my + r[8] * Math.max(tops[column], 0.0F);
            int slot = Mth.clamp((int) ((depth + 1.8F) * (BUCKETS / 3.6F)), 0, BUCKETS - 1);
            bucket[k] = slot;
            counts[slot]++;
        }
        int sum = 0;
        for (int b = 0; b < BUCKETS; b++) {
            write[b] = sum;
            sum += counts[b];
        }
        for (int k = 0; k < readyColumns; k++) {
            sorted[write[bucket[k]]++] = readyOrder[k];
        }
    }

    private void buildRotation() {
        rotation.identity();
        rotation.rotateZ(roll);
        rotation.rotateX(pitch);
        rotation.rotateZ(yaw);
        rotation.get(raw);
        // JOML 按列存，这里摊成按行，省掉每次取数时的下标换算
        r[0] = raw[0];
        r[1] = raw[3];
        r[2] = raw[6];
        r[3] = raw[1];
        r[4] = raw[4];
        r[5] = raw[7];
        r[6] = raw[2];
        r[7] = raw[5];
        r[8] = raw[8];
    }

    /** 细格序号（可以是半格）→ 模型坐标。 */
    private static float model(int fine, float cell) {
        return cell / (fine - 1) * 2.0F - 1.0F;
    }

    /** 柱子中心 → 模型坐标（排序算深度用）。 */
    private static float modelOf(int columns, int c) {
        return (c + 0.5F) / columns * 2.0F - 1.0F;
    }

    private void vertex(VertexConsumer c, float ox, float oy, float s,
                        float mx, float my, float mz, int argb) {
        // 横向（地图平面）按 widthScale 撑开：柱子变粗的同时间距、底板、占地框一起拉开，
        // 所以永远正好拼合，不会挤成一团；高度轴不跟着撑，所以粗了就相对更矮更敦实，
        // 细了就相对更尖 —— 这才是一个看得见的「粗细」旋钮（滚轮缩放是整体等比放大，两者不同）。
        // shiftX/Y 是精读「中心停在两个区块之间」的那点差额：直接平移整块地形，拖动因此是
        // 连续滑动而不是攒满一格弹一下。
        // GUI 深度仍按原始尺寸算，免得撑开后跑出可视深度带被裁。
        float w = widthScale;
        float gx = (mx + shiftX) * w;
        float gy = (my + shiftY) * w;
        c.addVertex(ox + s * (r[0] * gx + r[1] * gy + r[2] * mz),
                oy + s * (r[3] * gx + r[4] * gy + r[5] * mz),
                Z_BASE + DEPTH_SCALE * (r[6] * mx + r[7] * my + r[8] * mz)).setColor(argb);
    }

    /**
     * 一面从 {@code low} 到 {@code high} 的墙：{@code (ax,ay)→(bx,by)} 是它的水平走向。
     *
     * @return 固定 1，方便调用处累加四边形数量
     */
    private int wall(VertexConsumer c, float ox, float oy, float s,
                     float ax, float ay, float bx, float by,
                     float low, float high, int rgb, int alpha, float litTop, float litLow) {
        int top = shade(rgb, litTop, alpha);
        int bottom = shade(rgb, litLow, alpha);
        vertex(c, ox, oy, s, ax, ay, high, top);
        vertex(c, ox, oy, s, bx, by, high, top);
        vertex(c, ox, oy, s, bx, by, low, bottom);
        vertex(c, ox, oy, s, ax, ay, low, bottom);
        return 1;
    }

    /** 底面薄板（金边已经不要了，画着不好看）。 */
    private void floor(VertexConsumer c, float ox, float oy, float s, float alpha) {
        int plate = shade(PLATE & 0xFFFFFF, 1.0F, (int) (0xF2 * alpha));
        quadFlat(c, ox, oy, s, -1.0F, 1.0F, -1.0F, 1.0F, 0.0F, plate);
    }

    private void quadFlat(VertexConsumer c, float ox, float oy, float s,
                          float x0, float x1, float y0, float y1, float z, int argb) {
        vertex(c, ox, oy, s, x0, y0, z, argb);
        vertex(c, ox, oy, s, x1, y0, z, argb);
        vertex(c, ox, oy, s, x1, y1, z, argb);
        vertex(c, ox, oy, s, x0, y1, z, argb);
    }

    /**
     * 落点处画出建筑真正要占的地块，让玩家看得见这一块放不放得下；一层金面 + 四边立起一点边。
     *
     * <p>没有联动方（独立安装，只选出生点）时不画框：占地尺寸由
     * {@link dev.yard.site.api.SiteLink#plot()} 提供，门廊往 -z 一侧多让出几格，
     * 所以框不是以落点为中心的对称矩形。
     */
    private void footprint(VertexConsumer c, float ox, float oy, float s, SiteField field,
                           int cellX, int cellY, float z, float alpha) {
        SitePlan.Plot plot = SiteText.plot();
        if (plot == null) return;
        int fine = field.fine();
        int centerBlockX = field.centerX() + (int) Math.round((cellX - (fine - 1) / 2.0) * field.step());
        int centerBlockZ = field.centerZ() + (int) Math.round((cellY - (fine - 1) / 2.0) * field.step());
        int x0 = centerBlockX - plot.width() / 2;
        int z0 = centerBlockZ - plot.innerDepth() / 2;
        float px0 = worldX(field, x0);
        float px1 = worldX(field, x0 + plot.width());
        float pz0 = worldZ(field, z0 - plot.porch());
        float pz1 = worldZ(field, z0 + plot.depth() - plot.porch());
        int fill = shade(GOLD & 0xFFFFFF, 1.0F, (int) (0x4C * alpha));
        int lip = shade(GOLD & 0xFFFFFF, 1.0F, (int) (0xB0 * alpha));
        quadFlat(c, ox, oy, s, px0, px1, pz0, pz1, z, fill);
        float height = 0.02F;
        edge(c, ox, oy, s, px0, pz0, px1, pz0, z, height, lip);
        edge(c, ox, oy, s, px1, pz0, px1, pz1, z, height, lip);
        edge(c, ox, oy, s, px1, pz1, px0, pz1, z, height, lip);
        edge(c, ox, oy, s, px0, pz1, px0, pz0, z, height, lip);
    }

    // ── 旅社蓝图 ────────────────────────────────────────────────────────

    /**
     * 把 {@link SiteBlueprint} 画成金色线框：每根线按自己的 delay/rise 从下往上长，
     * 底下压一块半透明金面表示占地。全部用世界坐标换到沙盘的模型空间，和柱子共用投影。
     */
    private void drawBlueprint(VertexConsumer c, float ox, float oy, float s, SiteField field) {
        float base = field.seaLevel() - 4;
        float span = Math.max(20.0F, field.reliefSpan());
        int alpha = (int) (255.0F * blueprintAlpha);
        // 建筑竖向用「方块等比」而不是地形的起伏比例：地形会按 reliefSpan 夸张，
        // 照搬会把旅社压成饼或拉成塔；1.5 倍只是让楼层分得开一点。
        float baseZ = worldY(blueprint.baseY, base, span);
        float perBlock = 2.0F / ((field.fine() - 1) * (float) field.step()) * 1.5F;

        // 占地金面 + 四边（比线框暗一档）
        float px0 = worldX(field, blueprint.plotMinX);
        float px1 = worldX(field, blueprint.plotMaxX);
        float pz0 = worldZ(field, blueprint.plotMinZ);
        float pz1 = worldZ(field, blueprint.plotMaxZ);
        float py = baseZ + 0.004F;
        int fill = ((int) (0x30 * blueprintAlpha) << 24) | (SiteBlueprint.GOLD & 0xFFFFFF);
        int lip = ((int) (0xB0 * blueprintAlpha) << 24) | (SiteBlueprint.GOLD & 0xFFFFFF);
        quadFlat(c, ox, oy, s, px0, px1, pz0, pz1, py, fill);
        float w = 0.003F;
        quadFlat(c, ox, oy, s, px0, px1, pz0 - w, pz0 + w, py, lip);
        quadFlat(c, ox, oy, s, px0, px1, pz1 - w, pz1 + w, py, lip);
        quadFlat(c, ox, oy, s, px0 - w, px0 + w, pz0, pz1, py, lip);
        quadFlat(c, ox, oy, s, px1 - w, px1 + w, pz0, pz1, py, lip);

        for (BlueprintBox b : blueprint.boxes) {
            if (blueprintElapsed < b.delayMs()) continue;
            float t = Mth.clamp((blueprintElapsed - b.delayMs()) / (float) Math.max(1, b.riseMs()),
                    0.0F, 1.0F);
            float topBlock = b.minY() + (b.maxY() - b.minY()) * smooth(t);
            float x0 = worldX(field, b.minX());
            float x1 = worldX(field, b.maxX());
            float z0 = worldZ(field, b.minZ());
            float z1 = worldZ(field, b.maxZ());
            float yb = baseZ + (b.minY() - blueprint.baseY) * perBlock;
            float yt = baseZ + (topBlock - blueprint.baseY) * perBlock;
            float h = Math.max(0.001F, yt - yb);
            int a = (int) (alpha * (0.55F + 0.45F * t));
            int argb = (a << 24) | (b.color() & 0xFFFFFF);
            float e = 0.0024F;
            // 四根角柱：横竖各一条薄片，任意角度都看得见
            for (int k = 0; k < 4; k++) {
                float cx = (k & 1) == 0 ? x0 : x1;
                float cz = (k & 2) == 0 ? z0 : z1;
                edge(c, ox, oy, s, cx, cz, cx + e, cz, yb, h, argb);
                edge(c, ox, oy, s, cx, cz, cx, cz + e, yb, h, argb);
            }
            // 顶面四条边，跟着上升的高度走
            if (t > 0.02F) {
                quadFlat(c, ox, oy, s, x0, x1, z0 - e, z0 + e, yt, argb);
                quadFlat(c, ox, oy, s, x0, x1, z1 - e, z1 + e, yt, argb);
                quadFlat(c, ox, oy, s, x0 - e, x0 + e, z0, z1, yt, argb);
                quadFlat(c, ox, oy, s, x1 - e, x1 + e, z0, z1, yt, argb);
            }
        }

        // 蓝图搭完之后，一层很淡的扫描线慢慢从地基扫到屋顶：等世界的时候画面还在动，
        // 但不是镜头绕圈。2.6 秒一轮，扫完从头开始。
        if (blueprintElapsed > blueprint.durationMs) {
            float sweep = ((blueprintElapsed - blueprint.durationMs) % 2600L) / 2600.0F;
            float scanZ = baseZ + sweep * blueprint.heightBlocks * perBlock;
            int plane = ((int) (0x12 * blueprintAlpha) << 24) | (SiteBlueprint.GOLD & 0xFFFFFF);
            int line = ((int) (0x66 * blueprintAlpha) << 24) | (SiteBlueprint.GOLD_BRIGHT & 0xFFFFFF);
            quadFlat(c, ox, oy, s, px0, px1, pz0, pz1, scanZ, plane);
            float e = 0.002F;
            quadFlat(c, ox, oy, s, px0, px1, pz0 - e, pz0 + e, scanZ, line);
            quadFlat(c, ox, oy, s, px0, px1, pz1 - e, pz1 + e, scanZ, line);
            quadFlat(c, ox, oy, s, px0 - e, px0 + e, pz0, pz1, scanZ, line);
            quadFlat(c, ox, oy, s, px1 - e, px1 + e, pz0, pz1, scanZ, line);
        }
    }

    /** 世界方块坐标 → 沙盘模型 x（横向，和柱子同一套换算）。 */
    private static float worldX(SiteField field, float blockX) {
        float cell = (blockX - field.centerX()) / (float) field.step()
                + (field.fine() - 1) * 0.5F;
        return model(field.fine(), cell);
    }

    private static float worldZ(SiteField field, float blockZ) {
        float cell = (blockZ - field.centerZ()) / (float) field.step()
                + (field.fine() - 1) * 0.5F;
        return model(field.fine(), cell);
    }

    /** 世界 y → 模型 z：和 {@link #collect} 里柱子高度用的 base/span 完全一致。 */
    private static float worldY(float blockY, float base, float span) {
        return (blockY - base) / span * RELIEF;
    }

    private void edge(VertexConsumer c, float ox, float oy, float s,
                      float ax, float ay, float bx, float by, float z, float lip, int argb) {
        vertex(c, ox, oy, s, ax, ay, z, argb);
        vertex(c, ox, oy, s, bx, by, z, argb);
        vertex(c, ox, oy, s, bx, by, z + lip, argb);
        vertex(c, ox, oy, s, ax, ay, z + lip, argb);
    }

    /** 落点标：两根交叉的金柱，从底面立到柱子顶上。 */
    private void pin(VertexConsumer c, float ox, float oy, float s, SiteField field, int column,
                     float top, float alpha) {
        int per = field.perColumn();
        int fine = field.fine();
        int ci = column % field.columns();
        int cj = column / field.columns();
        float mx = model(fine, ci * per + (per - 1) * 0.5F);
        float my = model(fine, cj * per + (per - 1) * 0.5F);
        float w = 0.012F;
        int gold = shade(GOLD & 0xFFFFFF, 1.0F, (int) (0xFF * alpha));
        vertex(c, ox, oy, s, mx - w, my, 0.0F, gold);
        vertex(c, ox, oy, s, mx + w, my, 0.0F, gold);
        vertex(c, ox, oy, s, mx + w, my, top, gold);
        vertex(c, ox, oy, s, mx - w, my, top, gold);
        vertex(c, ox, oy, s, mx, my - w, 0.0F, gold);
        vertex(c, ox, oy, s, mx, my + w, 0.0F, gold);
        vertex(c, ox, oy, s, mx, my + w, top, gold);
        vertex(c, ox, oy, s, mx, my - w, top, gold);
    }

    /**
     * 一根柱子取自哪个细格：大沙盘一根柱子盖 2×2 细格，取其中占多数的群系（打平取左上）；
     * 精读一格就是一格，直接取。不做混合，所以边界是硬边。
     *
     * @return 高 16 位 = cellX，低 16 位 = cellY
     */
    private static int majorityCell(SiteField field, int i, int j, int per) {
        if (per <= 1) {
            return (i << 16) | (j & 0xFFFF);
        }
        int b0 = field.biomeIndexAtCell(i, j);
        int b1 = field.biomeIndexAtCell(i + 1, j);
        int b2 = field.biomeIndexAtCell(i, j + 1);
        int b3 = field.biomeIndexAtCell(i + 1, j + 1);
        int c0 = 1 + eq(b0, b1) + eq(b0, b2) + eq(b0, b3);
        int c1 = eq(b1, b0) + 1 + eq(b1, b2) + eq(b1, b3);
        int c2 = eq(b2, b0) + eq(b2, b1) + 1 + eq(b2, b3);
        int c3 = eq(b3, b0) + eq(b3, b1) + eq(b3, b2) + 1;
        int best = c0;
        int x = i;
        int y = j;
        if (c1 > best) {
            best = c1;
            x = i + 1;
            y = j;
        }
        if (c2 > best) {
            best = c2;
            x = i;
            y = j + 1;
        }
        if (c3 > best) {
            x = i + 1;
            y = j + 1;
        }
        return (x << 16) | (y & 0xFFFF);
    }

    private static int eq(int a, int b) {
        return a == b ? 1 : 0;
    }

    private static int shade(int rgb, float factor, int alpha) {
        int rr = Mth.clamp((int) (((rgb >> 16) & 255) * factor), 0, 255);
        int gg = Mth.clamp((int) (((rgb >> 8) & 255) * factor), 0, 255);
        int bb = Mth.clamp((int) ((rgb & 255) * factor), 0, 255);
        return (rr << 16) | (gg << 8) | bb | (Mth.clamp(alpha, 0, 255) << 24);
    }

    // ── 点选 ────────────────────────────────────────────────────────────

    /**
     * 从鼠标位置沿视线往模型里走，第一个撞上的柱子就是玩家看到的这一格。
     * 命中按<b>格子</b>判定（跟柱子画多宽无关），所以宽度调成细的时候点缝里也能选中那一格。
     *
     * @return 命中时写出细格坐标（{@link #pickCellX} / {@link #pickCellY}）
     */
    boolean pick(SiteField field, double guiX, double guiY, int areaX, int areaY, int areaSize) {
        int columns = field.columns();
        int per = field.perColumn();
        int fine = field.fine();
        float scale = scaleOf(areaSize);
        if (scale <= 0.0F) return false;
        float w = widthScale;
        float a = (float) ((guiX - (areaX + areaSize / 2.0F + panX)) / scale);
        float b = (float) ((guiY - (areaY + areaSize / 2.0F + panY)) / scale);
        // 屏幕点 = 中心 + 缩放·M·(横向撑开后的模型点)：先按撑开空间反算，再把横向除回来
        float qx = r[0] * a + r[3] * b;
        float qy = r[1] * a + r[4] * b;
        float qz = r[2] * a + r[5] * b;
        // 深度行 = 视线方向（单位长度），t 越大越靠近观察者
        float dx = r[6];
        float dy = r[7];
        float dz = r[8];
        for (float t = 3.2F; t > -3.2F; t -= MARCH_STEP) {
            // 画面是数据空间先加 originShift、再撑 w，所以反算要先把这两步退回去
            float mx = (qx + dx * t) / w - shiftX;
            float my = (qy + dy * t) / w - shiftY;
            // 最外圈柱子的边超出地面正方形半格，边界放宽一点才点得到
            if (mx < -1.02F || mx > 1.02F || my < -1.02F || my > 1.02F) continue;
            float mz = qz + dz * t;
            if (mz < 0.0F) continue;
            float fx = (mx + 1.0F) * 0.5F * (fine - 1);
            float fy = (my + 1.0F) * 0.5F * (fine - 1);
            int ci = Mth.clamp((int) Math.floor((fx + 0.5F) / per), 0, columns - 1);
            int cj = Mth.clamp((int) Math.floor((fy + 0.5F) / per), 0, columns - 1);
            int column = cj * columns + ci;
            float top = tops[column];
            if (top >= 0.0F && mz <= top) {
                pickCellX = Mth.clamp(Math.round(fx), 0, fine - 1);
                pickCellY = Mth.clamp(Math.round(fy), 0, fine - 1);
                return true;
            }
        }
        return false;
    }

    /** 细格 → 所属柱子。 */
    int columnOf(SiteField field, int cellX, int cellY) {
        int per = field.perColumn();
        return Mth.clamp(cellY / per, 0, field.columns() - 1) * field.columns()
                + Mth.clamp(cellX / per, 0, field.columns() - 1);
    }

    private static float smooth(float t) {
        return t * t * (3.0F - 2.0F * t);
    }
}
