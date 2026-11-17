package dev.yard.site.schematic;

import java.util.Arrays;

/**
 * 结构线框的<b>消隐</b>：按当前机位算出「这一刻真正看得见的那些棱」，并按粗细分成两档。
 *
 * <h2>要的效果</h2>
 * 只画线、不画面，但线要有遮挡：立方体底角那三条棱在实体渲染里看不见，这里也不画；
 * 两个盒子错开叠放，下面那个被盖住的一段轮廓不画、露出来的一段照画。说白了就是工程图里的
 * <b>隐藏线消除</b>（hidden line removal）。
 *
 * <h2>判据：从相机打过来，第一眼看到的是不是它</h2>
 * 对棱上的一点 P，从相机 C 朝 P 打一条射线，问<b>第一个实体的距离</b>（
 * {@link SchematicBoxes#firstHit}）：
 * <ul>
 *   <li>等于 {@code |CP|}：路上什么都没有，P 就露在最前面 → 看得见；</li>
 *   <li>小于 {@code |CP|}：路上有别的东西 → 被挡住。</li>
 * </ul>
 * 就这么一条。P 本来就落在盒子表面上，所以"看得见"的时候命中距离天然等于射线长度，
 * 不需要「把采样点挪进空气」「允许擦着表面走」这类补丁。
 *
 * <p>前两版都栽在补丁上：从棱上直接走会"起点在实心格内"，往外挪半格又会挪到物体轮廓外面
 * （空气看得见、棱其实被自己挡住），再补一条"擦过 0.12 格不算挡"就把真的遮挡也吃掉一部分。
 * 判据换成"第一眼看到谁"之后，这些情形全都自动正确——这也是几何上唯一说得通的说法。
 *
 * <h2>断口对齐</h2>
 * 按 {@value #SAMPLE_STEP} 格采样只能得到"这一段可见"的近似，切换点会落在半个采样步长以内，
 * 一栋楼的线头就会差半格、看着毛毛的。所以在每个"可见↔不可见"的切换处再做
 * {@value #BISECT} 次二分，把边界精确到 {@code 步长 / 32}（约 1.5 厘米），断口干净、线头对得上。
 *
 * <h2>三档线：外轮廓 / 结构线 / 细节</h2>
 * 看得见的棱按<b>几何角色 + 当下机位</b>分三档：
 * <ul>
 *   <li>{@link #OUTLINE}：<b>剪影</b>——凸棱，并且两个外法线此刻一正一背对着相机。这是建筑
 *       的外形，画最重。它<b>跟着镜头变</b>：转过去，旧的轮廓退回结构线，新的轮廓顶上来。</li>
 *   <li>{@link #MAIN}：<b>凸棱</b>但不是剪影——台阶、墙棱、方块外缘在内部的那些。</li>
 *   <li>{@link #FINE}：凹进去的拐角（门洞内侧、凹槽底），以及薄片（玻璃板、活板门、地毯、
 *       牌子）的边——数量多，画粗了整面墙会糊成网格。</li>
 * </ul>
 * 分档不按长短，所以一条连续的边不会忽粗忽细；半砖、楼梯的一级（0.5 格厚）是凸棱，
 * 和墙棱同一档，边缘自然接得上——这正是"按方块是否满格分档"做不到的。
 *
 * <p>纯 Java，不碰 Minecraft：{@code tools/schematic-check} 能直接喂投影文件验。
 */
public final class SchematicHiddenLines {

    /** 细节线：凹进去的拐角、薄片（玻璃板、活板门、地毯、牌子）的边，画细、画暗。 */
    public static final byte FINE = 1;
    /** 结构线：凸棱（墙边、台阶、方块外缘）但此刻不是剪影，画中等粗细。 */
    public static final byte MAIN = 2;
    /**
     * 外轮廓：凸棱，而且它两侧的朝向此刻一正一背对着相机——就是剪影边。
     *
     * <p>这一档是<b>跟着机位算</b>的，不是固定属性：镜头转过去，原本的轮廓线会退回结构线，
     * 原来在内部的棱会变成新的轮廓。这也是所有线稿的做法（silhouette 永远比内部折线重），
     * 靠它建筑的外形才立得住。
     */
    public static final byte OUTLINE = 3;

    /** 采样步长（格）：0.5 够"挡一半画一半"，再细只多花时间看不出差别。 */
    static final float SAMPLE_STEP = 0.5F;
    /** 每帧最多采样多少点；超了就把步长放大（大建筑宁可粗一点，也别掉帧）。 */
    static final int SAMPLE_BUDGET = 32_000;

    /**
     * 判定"看得见"的容差（格）。
     *
     * <p>理论上命中距离正好等于射线长度，实际有浮点误差（射线长度几百格时相对误差 1e-7 也会
     * 攒到 1e-4）。这个容差只为吸收它，远小于最薄的方块形状（1/16 = 0.0625 格），
     * 所以不会把"被一片薄板挡住"放过。
     */
    static final float REACH = 0.004F;

    /** 一条棱被挡住多少段时，切换点做几次二分（2^5 = 32 份）。 */
    static final int BISECT = 5;
    /**
     * 同一条棱上窄于这么多格的缺口直接接上（格）。
     *
     * <p>二分只能找到"采样精度范围内"的边界，两个可见段之间常常留下 1/30 格的缝——
     * 那是数值噪声，不是真的被挡住。真实遮挡（哪怕一片活板门）也远宽于 0.12 格。
     */
    static final float GAP_FILL = 0.2F;

    /** 一次重算超过这么久就把机位粒度放大一档（毫秒）。 */
    private static final long ADAPT_FAST_MS = 8L;
    /** 一次重算低于这么久就把机位粒度收回一档（毫秒）。 */
    private static final long ADAPT_SLOW_MS = 3L;
    /** 机位粒度的上下限（格）。 */
    private static final float QUANTUM_MIN = 0.25F;
    private static final float QUANTUM_MAX = 2.0F;
    /** 两次重算至少隔这么久（毫秒）；镜头一帧里跳出去远就不管这个。 */
    private static final long MIN_INTERVAL_MS = 16L;
    /** 镜头一帧里挪了这么远就无视节流，立刻重算（不然会看到线停在旧位置）。 */
    private static final float JUMP_BLOCKS = 3.0F;
    /** 棱和视线夹角小于这个余弦值就不画了（≈8°）：投影成几个像素，只有噪声没有形状。 */
    static final float EDGE_ON = 0.99F;
    /**
     * 投影短于这么多屏幕像素的棱不画。
     *
     * <p>一栋房子的窗格、栏杆、台阶棱在半格到一格之间，相机退到几十格外就只剩两三个像素，
     * 挤在一起全是噪点。按屏幕尺寸筛掉之后，远景自然"简化"，近景细节仍在——这也是线稿的
     * 一般做法（看不见的东西不必画）。设成 {@code <= 0} 关掉这层筛选。
     */
    public static final float MIN_PIXELS = 3.5F;

    private final Schematic schematic;
    private final SchematicBoxes geometry;
    private final SchematicTransform transform;
    private final int originX;
    private final int originY;
    private final int originZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;

    /** 候选棱总长（估算采样数用）。 */
    private final double totalLength;
    /** 候选棱（局部坐标，已按同一条线合并成尽量长的段）：每段 6 个 float。 */
    private final float[] segs;
    private final int segCount;
    /** 每段的静态档位：{@link #MAIN} 或 {@link #FINE}（由棱的几何角色决定，与机位、长短都无关）。 */
    private final byte[] tiers;
    /** 每段的敞口方向（见 {@link SchematicOutline#sides}），用来算剪影。 */
    private final byte[] sides;

    /** 这一帧的输出（世界坐标）：每段 6 个 float。装不下会换一个更大的（外部只该在 resolve 之后取一次）。 */
    private float[] out;
    /** 每段输出的档位。 */
    private byte[] flags;
    private int outCount;
    /** 复用缓冲：一条棱上各采样点看不看得见。 */
    private byte[] sampleVis = new byte[64];

    private boolean cached;
    private float cachedX;
    private float cachedY;
    private float cachedZ;
    /** 上一次真正重算的机位和时刻（节流用）。 */
    private double lastCamX;
    private double lastCamY;
    private double lastCamZ;
    private long lastAt;
    /**
     * 机位量化粒度（格）：小于它的移动不重算。
     *
     * <p>会自适应：结构复杂、一次算得慢就把粒度放大，让重算次数降下来；算得快再收回去。
     * 镜头离建筑一百多格，1 格的机位差折算到画面上不到半度，看不出来，所以这条路比"掉帧"划算。
     */
    private float quantum;
    /** 最近一次重算耗时（纳秒）。 */
    private long lastCostNs;
    /** 这一帧用的采样步长。 */
    private float step = SAMPLE_STEP;
    /** 一格在屏幕上大约多少像素（固定值，正交预览用）；{@code <= 0} = 不做屏幕空间筛选。 */
    private float pixelsPerBlock = -1.0F;
    /** 屏幕高度 ÷ 垂直 FOV（弧度）：透视渲染时按"这条棱离相机多远"现算像素尺度。 */
    private float pixelsPerRadian = -1.0F;

    /**
     * 固定像素尺度（正交预览用）：一格 = 多少像素。
     *
     * <p>短于 {@link #MIN_PIXELS} 的棱就不画了：远景自动简化，近景细节照旧。
     */
    public void setPixelScale(float pixelsPerBlock) {
        this.pixelsPerBlock = pixelsPerBlock;
    }

    /**
     * 透视相机的像素尺度：{@code 屏幕高度(像素) / (2·tan(FOV/2))}。
     *
     * <p>给了这个之后，每条棱按它<b>自己</b>离相机多远算占几个像素——近处细看有细节，
     * 退远了自动只剩骨架，正是线稿该有的取舍。
     */
    public void setViewScale(float pixelsPerRadian) {
        this.pixelsPerRadian = pixelsPerRadian;
    }

    /** 这条棱在屏幕上大约占几个像素（不知道尺度时返回一个大数，等于不过滤）。 */
    private float pixelsOf(float x1, float y1, float z1, float x2, float y2, float z2,
                           double len, float camX, float camY, float camZ) {
        if (pixelsPerRadian > 0.0F) {
            float mx = (x1 + x2) * 0.5F - camX;
            float my = (y1 + y2) * 0.5F - camY;
            float mz = (z1 + z2) * 0.5F - camZ;
            float distance = (float) Math.sqrt(mx * mx + my * my + mz * mz);
            if (distance < 1.0F) distance = 1.0F;
            return (float) (len * pixelsPerRadian / distance);
        }
        return pixelsPerBlock > 0.0F ? (float) (len * pixelsPerBlock) : Float.MAX_VALUE;
    }

    /**
     * @param outline 候选棱：{@link Schematic#creaseEdges(int)}（只判"是不是折角"，不判遮挡），局部坐标
     */
    public SchematicHiddenLines(Schematic schematic, SchematicTransform transform,
                                int originX, int originY, int originZ, SchematicOutline outline) {
        this.schematic = schematic;
        this.geometry = schematic.geometry();
        this.transform = transform;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.sizeX = schematic.sizeX;
        this.sizeY = schematic.sizeY;
        this.sizeZ = schematic.sizeZ;
        this.segCount = outline.count;
        this.segs = Arrays.copyOf(outline.segs, outline.count * 6);
        this.tiers = new byte[segCount];
        double total = 0.0;
        for (int i = 0; i < segCount; i++) {
            // 档位只看"这条棱在形状里扮演什么角色"，不看长短——按长短分的话，一条墙边会
            // 因为它这一截长、下一截短而忽粗忽细，同一面墙的半砖边又会整片比墙棱细一号。
            tiers[i] = outline.roles[i] == SchematicBoxes.CONVEX ? MAIN : FINE;
            total += length(segs, i);
        }
        this.sides = Arrays.copyOf(outline.sides, segCount);
        this.quantum = QUANTUM_MIN;
        this.out = new float[Math.max(64, segCount * 2) * 6];
        this.flags = new byte[Math.max(64, segCount * 2)];
        this.totalLength = total;
    }

    /** 候选棱条数（日志用）。 */
    public int candidateCount() {
        return segCount;
    }

    /** 这一帧的采样步长（格）。 */
    public float step() {
        return step;
    }

    /** 最近一次 {@link #resolve} 的耗时（毫秒）。 */
    public long lastCostMs() {
        return lastCostNs / 1_000_000L;
    }

    /** 这一帧可见线段数。 */
    public int count() {
        return outCount;
    }

    /** 可见线段缓冲：每段 6 个 float（世界坐标）。只在 {@link #resolve} 之后有效。 */
    public float[] segments() {
        return out;
    }

    /** 第 {@code index} 段的档位：{@link #MAIN} 或 {@link #FINE}。 */
    public byte flag(int index) {
        return flags[index];
    }

    /** 按机位重算可见棱（每次都算，测试/预览用）。 */
    public int resolve(double cameraX, double cameraY, double cameraZ) {
        return resolve(cameraX, cameraY, cameraZ, -1L);
    }

    /**
     * 按机位重算可见棱。
     *
     * @param nowMs 当前时刻（毫秒）；{@code <0} = 不做位置缓存，调用就是重算
     * @return 可见线段条数（{@link #segments()} 里的有效段数）
     */
    public int resolve(double cameraX, double cameraY, double cameraZ, long nowMs) {
        // 机位量化：镜头极缓慢地转时不必每帧重算，也不会看到"线停在旧位置"
        float qx = Math.round((float) cameraX / quantum);
        float qy = Math.round((float) cameraY / quantum);
        float qz = Math.round((float) cameraZ / quantum);
        if (nowMs >= 0L && cached) {
            if (qx == cachedX && qy == cachedY && qz == cachedZ) return outCount;
            double moved = Math.abs(cameraX - lastCamX) + Math.abs(cameraY - lastCamY)
                    + Math.abs(cameraZ - lastCamZ);
            // 镜头没怎么动就按最小间隔节流；推近、切机位这种大跳立刻重算
            if (moved < JUMP_BLOCKS && nowMs - lastAt < MIN_INTERVAL_MS) return outCount;
        }
        cached = true;
        cachedX = qx;
        cachedY = qy;
        cachedZ = qz;
        long startedAt = System.nanoTime();

        // 相机换到结构自己的坐标系（旋转/镜像过的结构也走同一套判定）
        float camX = transform.localX((float) cameraX - originX, (float) cameraZ - originZ, sizeX, sizeZ);
        float camY = (float) cameraY - originY;
        float camZ = transform.localZ((float) cameraX - originX, (float) cameraZ - originZ, sizeX, sizeZ);

        outCount = 0;
        if (geometry.insideSolid(camX, camY, camZ)) {
            // 相机埋在方块里（调试视角）：什么都挡，索性不消隐，免得整栋楼的线忽然全消失
            lastCostNs = System.nanoTime() - startedAt;
            return outCount;
        }

        // 棱特别多（大建筑）就放粗步长：宁可少半格精度，也别让每帧多花几十毫秒
        float chosen = SAMPLE_STEP;
        while (totalLength / chosen > SAMPLE_BUDGET) chosen *= 2.0F;
        this.step = chosen;

        for (int i = 0; i < segCount; i++) {
            int base = i * 6;
            float x1 = segs[base];
            float y1 = segs[base + 1];
            float z1 = segs[base + 2];
            float x2 = segs[base + 3];
            float y2 = segs[base + 4];
            float z2 = segs[base + 5];
            double len = length(segs, i);
            if (len < 1.0E-5) continue;
            // 投影后只有两三个像素：远处看就是噪点，别画
            if (pixelsOf(x1, y1, z1, x2, y2, z2, len, camX, camY, camZ) < MIN_PIXELS) continue;
            // 几乎顺着视线的棱投影不到一个像素：整条划掉（正对着看立方体时"朝自己戳过来"那 4 条）
            float vcx = camX - (x1 + x2) * 0.5F;
            float vcy = camY - (y1 + y2) * 0.5F;
            float vcz = camZ - (z1 + z2) * 0.5F;
            float vlen = (float) Math.sqrt(vcx * vcx + vcy * vcy + vcz * vcz);
            if (vlen > 1.0E-4F) {
                float cos = Math.abs((x2 - x1) * vcx + (y2 - y1) * vcy + (z2 - z1) * vcz)
                        / (float) (len * vlen);
                if (cos > EDGE_ON) continue;
            }
            int parts = Math.max(1, (int) Math.ceil(len / chosen));
            ensureSamples(parts + 1);
            for (int p = 0; p <= parts; p++) {
                float t = p / (float) parts;
                sampleVis[p] = (byte) (visible(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t,
                        z1 + (z2 - z1) * t, camX, camY, camZ) ? 1 : 0);
            }
            emitRuns(i, x1, y1, z1, x2, y2, z2, parts, camX, camY, camZ);
        }
        lastCostNs = System.nanoTime() - startedAt;
        if (nowMs >= 0L) {
            lastAt = nowMs;
            lastCamX = cameraX;
            lastCamY = cameraY;
            lastCamZ = cameraZ;
            long cost = lastCostNs / 1_000_000L;
            if (cost > ADAPT_FAST_MS) quantum = Math.min(QUANTUM_MAX, quantum * 1.5F);
            else if (cost < ADAPT_SLOW_MS) quantum = Math.max(QUANTUM_MIN, quantum / 1.5F);
        }
        return outCount;
    }

    /**
     * 把采样点序列写成输出线段：连续可见的一段出一条线，两端用二分精确定位。
     *
     * <p>不接"小缺口"：判据已经精确到盒子的表面，缺口就是真有东西挡着。硬接上等于把
     * 栏杆后面那段墙线画穿栏杆，看着比断线更假。
     */
    private void emitRuns(int index, float x1, float y1, float z1, float x2, float y2, float z2,
                          int parts, float camX, float camY, float camZ) {
        byte tier = tiers[index];
        if (tier == MAIN && isSilhouette(index, x1, y1, z1, x2, y2, z2, camX, camY, camZ)) {
            tier = OUTLINE;
        }
        double fullLength = length(segs, index);
        float gapT = fullLength < 1.0E-5 ? 0.0F : (float) (GAP_FILL / fullLength);
        int previousRun = -1;
        float previousEnd = 0.0F;
        int p = 0;
        while (p <= parts) {
            if (sampleVis[p] == 0) {
                p++;
                continue;
            }
            int from = p;
            while (p + 1 <= parts && sampleVis[p + 1] != 0) p++;
            int to = p;
            float t0 = from / (float) parts;
            float t1 = to / (float) parts;
            if (from > 0) t0 = bisect(from - 1, from, parts, x1, y1, z1, x2, y2, z2, camX, camY, camZ);
            if (to < parts) t1 = bisect(to + 1, to, parts, x1, y1, z1, x2, y2, z2, camX, camY, camZ);
            float ex = x1 + (x2 - x1) * t1;
            float ey = y1 + (y2 - y1) * t1;
            float ez = z1 + (z2 - z1) * t1;
            // 只看得见一个采样点时，两头二分都会收敛到同一个点：不要输出零长度的退化段
            if (t1 - t0 <= 1.0E-5F) {
                p++;
                continue;
            }
            if (previousRun >= 0 && t0 - previousEnd < gapT) {
                // 缝太窄，当同一条线：把上一段的终点推过来（不新增段，线才是连的）
                setEnd(previousRun, ex, ey, ez);
            } else {
                float sx = x1 + (x2 - x1) * t0;
                float sy = y1 + (y2 - y1) * t0;
                float sz = z1 + (z2 - z1) * t0;
                if (outCount * 6 + 6 > out.length) growOut();
                int run = outCount++;
                setStart(run, sx, sy, sz);
                setEnd(run, ex, ey, ez);
                flags[run] = tier;
                previousRun = run;
            }
            previousEnd = t1;
            p++;
        }
    }

    /**
     * 在"已知不可见"的 {@code hiddenIdx} 和"已知可见"的 {@code shownIdx} 之间二分，返回边界参数。
     *
     * <p>两头谁可见必须传对：一开始把可见那一侧当成不动点、往不可见那侧靠，边界就落在两者之间。
     * 上一版把这两个搞反了，结果"棱末端露出一个采样点"被放大成半格长的线头。
     */
    private float bisect(int hiddenIdx, int shownIdx, int parts, float x1, float y1, float z1,
                         float x2, float y2, float z2, float camX, float camY, float camZ) {
        float hidden = hiddenIdx / (float) parts;
        float shown = shownIdx / (float) parts;
        for (int k = 0; k < BISECT; k++) {
            float mid = (hidden + shown) * 0.5F;
            if (visible(x1 + (x2 - x1) * mid, y1 + (y2 - y1) * mid, z1 + (z2 - z1) * mid,
                    camX, camY, camZ)) {
                shown = mid;
            } else {
                hidden = mid;
            }
        }
        return shown;
    }

    /**
     * 这条棱此刻是不是<b>外轮廓</b>（剪影边）。
     *
     * <p>棱的两个外法线（敞口方向，见 {@link SchematicBoxes#edgeSides()}）里，
     * 一条朝着相机、一条背着相机，说明从这个角度看过去它正好压在外形转折处——这就是剪影。
     * 两面都朝着相机是内部折线，都背着相机说明它压根看不见（早被消隐了）。
     */
    private boolean isSilhouette(int index, float x1, float y1, float z1,
                                 float x2, float y2, float z2, float camX, float camY, float camZ) {
        byte side = sides[index];
        if ((side & 4) == 0) return false;                 // 鞍点没有唯一敞口
        int axis;
        int ua;
        int va;
        if (Math.abs(x1 - x2) > 1.0E-4F) {
            axis = 0;
            ua = 1;
            va = 2;
        } else if (Math.abs(y1 - y2) > 1.0E-4F) {
            axis = 1;
            ua = 0;
            va = 2;
        } else {
            axis = 2;
            ua = 0;
            va = 1;
        }
        float midU = (component(x1, y1, z1, ua) + component(x2, y2, z2, ua)) * 0.5F;
        float midV = (component(x1, y1, z1, va) + component(x2, y2, z2, va)) * 0.5F;
        float camU = component(camX, camY, camZ, ua);
        float camV = component(camX, camY, camZ, va);
        // 法线朝正就取 +(相机 - 中点)，朝负就取反；两条异号 = 剪影
        float du = ((side & 1) != 0 ? 1.0F : -1.0F) * (camU - midU);
        float dv = ((side & 2) != 0 ? 1.0F : -1.0F) * (camV - midV);
        return du * dv < 0.0F;
    }

    private static float component(float x, float y, float z, int axis) {
        return axis == 0 ? x : axis == 1 ? y : z;
    }

    /**
     * 这个点看得见吗：从相机朝它看，第一个实体是不是就在它身上。
     *
     * <p>采样点本身落在盒子表面（棱是几个盒子的公共边界），所以看得见时命中距离等于射线长度；
     * 被挡住时小于它。容差见 {@link #REACH}。
     */
    private boolean visible(float px, float py, float pz, float cx, float cy, float cz) {
        float dx = px - cx;
        float dy = py - cy;
        float dz = pz - cz;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-5F) return true;
        return geometry.firstHit(cx, cy, cz, px, py, pz) >= len - REACH;
    }

    private void ensureSamples(int size) {
        if (sampleVis.length >= size) return;
        sampleVis = new byte[Math.max(size, sampleVis.length * 2)];
    }

    private void growOut() {
        int size = Math.max(64, out.length / 6 * 2);
        out = Arrays.copyOf(out, size * 6);
        flags = Arrays.copyOf(flags, size);
    }

    // ── 输出段的读写：局部坐标进去，世界坐标出来 ─────────────────────────

    private void setStart(int index, float x, float y, float z) {
        int base = index * 6;
        out[base] = worldX(x, z);
        out[base + 1] = y + originY;
        out[base + 2] = worldZ(x, z);
    }

    private void setEnd(int index, float x, float y, float z) {
        int base = index * 6;
        out[base + 3] = worldX(x, z);
        out[base + 4] = y + originY;
        out[base + 5] = worldZ(x, z);
    }

    private float worldX(float localX, float localZ) {
        return originX + transform.x(localX, localZ, sizeX, sizeZ);
    }

    private float worldZ(float localX, float localZ) {
        return originZ + transform.z(localX, localZ, sizeX, sizeZ);
    }

    // ── 小工具 ─────────────────────────────────────────────────────────

    private static double length(float[] segs, int index) {
        int base = index * 6;
        double dx = segs[base + 3] - segs[base];
        double dy = segs[base + 4] - segs[base + 1];
        double dz = segs[base + 5] - segs[base + 2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
