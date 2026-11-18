package dev.yard.site.worldgen;

import dev.yard.site.YardSiteLog;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * 不加载区块、只问生成器噪声「这一列的地表在哪」。选址图（大沙盘、区块精读）和建世界前的
 * 落点规划共用这一份，三条路径的 y 才是同一个数。
 *
 * <h2>成本全在「窗口有多高」</h2>
 * {@link ChunkGenerator#getBaseHeight} 不是边走边扫：它先一次性把整条<b>竖直窗口</b>的噪声插值
 * 格子算出来（{@code NoiseChunk#initializeForFirstCellX} 把整列格子填满），再从窗口上沿往下找
 * 第一个不透明方块（水也算不透明）。所以一次调用的代价 ≈ 窗口高度（格），和地表落在哪儿关系不大；
 * <b>窗口下沿越低越贵</b> —— 从维度下沿（-64）起算的窗口，哪怕只看两格，也要把 -64 到上沿之间
 * 全算一遍。
 *
 * <p>于是这里不再「下沿始终压在 minY、只抬上沿」，而是<b>在提示值上下开一条窄带</b>（约六十格高，
 * 折成噪声格子是八到十八个，看地形设置：原版格子高 8，Terralith 高 4）。地表在带里就一次命中，
 * 比从维度下沿起算的整段窗口省一半以上；不在带里就往上抬上沿重扫，抬满还不对才退回整列。
 *
 * <h2>提示怎么来</h2>
 * {@link SurfaceTrend} 按已经采到的邻居估这一列的地表（上坡抬、下坡压、只推一半）。带子做成
 * 「上宽下窄」（{@link #BAND_ABOVE} 大于 {@link #BAND_BELOW}）：趋势刻意估得偏低，实际地表往上
 * 偏的更多，所以上面要留得宽些。
 *
 * <p>没有提示（图正中心那几个点）时还是老路子：上沿量化到 16 的整数倍、同档共用一把，
 * 顶到上沿就抬档重扫，整列扫到空再补一把全高。
 *
 * <h2>上限为什么要夹</h2>
 * {@link #capTop} 取「调用方给的山头上限、维度上沿、噪声列自己的竖直上沿」最小那一个：原版主世界
 * 的噪声列到 320 就没了，accessor 再高也只是多造几把空梯子。史诗地形把噪声高度写成 512
 * （上沿 448），到这一档抬高上限才有实际意义。
 */
public final class SurfaceProbe {

    /**
     * 普通世界的山头上限（绝对 y）：300 覆盖原版与常见地形 mod 的高山。
     *
     * <p>以前是 160，高于 160 的山头会被拍平：选址图和落点规划都把山顶当成 160，
     * 建筑就定在半山腰、大半埋进山里。史诗地形另有更高的上限（见 {@code EpicTerrainCompat}）。
     */
    public static final int PEAK_TOP_Y = 300;
    /** 完全没有邻居可参考时用的窗口上沿跨度（格，从维度下沿算）。 */
    public static final int SHALLOW_HEIGHT = 192;
    /** {@link #surfaceAt(int, int, int)} 的提示取值：这一列没有邻居可参考。 */
    public static final int NO_HINT = Integer.MIN_VALUE;

    /** 没有提示时窗口上沿的量化间隔（格）：同档共用一把，起点往上靠。 */
    private static final int TOP_STEP = 16;
    /** 顶到上沿后第一次抬档的幅度（格）。 */
    private static final int RAISE_FIRST = 32;
    /** 抬档幅度的上限（格）：到这一步基本一步就够到维度顶，不必再多一轮。 */
    private static final int RAISE_MAX = 256;

    /** 提示带：提示值以下留多少格（邻居估高了、下坡）。 */
    private static final int BAND_BELOW = 24;
    /** 提示带：提示值以上留多少格（趋势刻意估低、上坡）。 */
    private static final int BAND_ABOVE = 40;
    /** 提示带最矮高度（格）：再窄就要多扫一轮，反而更贵。 */
    private static final int BAND_MIN = 64;
    /** 提示带每次没命中往里长多少格。 */
    private static final int BAND_GROW = 64;
    /** 提示带最多试几次；再不就整列扫一遍兜底。 */
    private static final int BAND_TRIES = 4;
    /** 诊断自检的抽样间隔（2 的幂减一）：每 256 个点跟整列全高对一次。 */
    private static final int VERIFY_MASK = 0xFF;

    private static final Heightmap.Types TYPE = Heightmap.Types.WORLD_SURFACE_WG;

    private final ChunkGenerator generator;
    private final RandomState randomState;
    private final int minY;
    private final int seaLevel;
    /** 噪声竖直格子高度（格）：原版 8。提示带必须对齐到它，插值格子和整列扫描才是同一套。 */
    private final int cellHeight;
    /** 无提示时窗口上沿的量化间隔：不小于 {@link #TOP_STEP}，且是格子高度的整数倍。 */
    private final int topStep;
    /** 全高 accessor：整列扫到空时补扫用，落点规划夹 y 边界也用它。 */
    private final LevelHeightAccessor full;
    /** 最多扫到这个上沿。 */
    private final int capTop;
    /** 提示带能用的最高上沿：{@link #capTop} 按格子高度往下对齐（窗口高度不是格子整数倍时顶格会被截掉）。 */
    private final int bandTopLimit;
    /** 没有提示时的起点上沿。 */
    private final int startTop;
    /** 第 i 把的上沿是 {@code minY + (i + 1) * topStep}。 */
    private final LevelHeightAccessor[] ladder;

    /** 诊断：这个 probe 至今调过多少次高度查询、一共算了多少格窗口。 */
    private final LongAdder calls = new LongAdder();
    private final LongAdder windowCells = new LongAdder();
    /** 诊断自检的抽样计数器。 */
    private final AtomicInteger verifyCounter = new AtomicInteger();

    /**
     * @param peakTopY 山头上限（{@code 绝对 y}）：{@link #PEAK_TOP_Y} 或史诗地形那种更高的量级
     */
    public SurfaceProbe(ChunkGenerator generator, RandomState randomState,
                        int minY, int dimensionHeight, int seaLevel, int peakTopY) {
        this.generator = generator;
        this.randomState = randomState;
        this.seaLevel = seaLevel;
        this.minY = minY;
        this.cellHeight = cellHeight(generator);
        this.full = LevelHeightAccessor.create(minY, dimensionHeight);
        int ceiling = Math.min(minY + dimensionHeight, noiseTop(generator, minY + dimensionHeight));
        this.capTop = Mth.clamp(Math.min(peakTopY, ceiling), minY + TOP_STEP, ceiling);
        this.topStep = this.cellHeight * Math.max(1, (TOP_STEP + this.cellHeight - 1) / this.cellHeight);
        this.bandTopLimit = minY + Math.floorDiv(this.capTop - minY, this.cellHeight) * this.cellHeight;
        this.startTop = Math.min(this.bandTopLimit, minY + SHALLOW_HEIGHT);
        int rungs = Math.max(1, (this.capTop - minY + this.topStep - 1) / this.topStep);
        this.ladder = new LevelHeightAccessor[rungs];
        for (int i = 0; i < rungs; i++) {
            this.ladder[i] = LevelHeightAccessor.create(minY, (i + 1) * this.topStep);
        }
    }

    /**
     * 一列的地表 y（{@code WORLD_SURFACE_WG}：非空气的第一个方块，水域就是水面）。
     * 注意这是<b>雕刻与地物之前</b>的地形，真实区块到货后要以最终高度图为准。
     */
    public int surfaceAt(int x, int z) {
        return surfaceAt(x, z, NO_HINT);
    }

    /**
     * 同上，但按邻居估出来的地表开窗口。
     *
     * @param hintY {@link SurfaceTrend} 给的这一列地表 y 估计，没有就传 {@link #NO_HINT}
     */
    public int surfaceAt(int x, int z, int hintY) {
        int surface = hintY == NO_HINT ? scanFromTop(x, z) : scanBand(x, z, hintY);
        verify(x, z, surface);
        return surface;
    }

    /** 给只想要「水陆」判断的调用方用：地表是否在水面及以下。 */
    public boolean isWater(int surfaceY) {
        return surfaceY <= this.seaLevel;
    }

    /** 全高 accessor（盖房子要夹 y 边界时用）。 */
    public LevelHeightAccessor fullAccessor() {
        return this.full;
    }

    public int seaLevel() {
        return this.seaLevel;
    }

    /** 没有提示时的起点上沿，给日志用。 */
    public int startTop() {
        return this.startTop;
    }

    /** 实际会扫到的最高上沿（夹过维度和噪声范围的那个），给日志用。 */
    public int capTop() {
        return this.capTop;
    }

    /** 至今调过多少次高度查询（含重扫），给日志用。 */
    public long heightCalls() {
        return calls.sum();
    }

    /** 至今一共算了多少格竖直窗口（约等于成本），给日志用。 */
    public long windowCells() {
        return windowCells.sum();
    }

    // ── 扫描 ─────────────────────────────────────────────────────────────

    /**
     * 提示带：先在提示值上下一条窄带里扫。
     *
     * <p>带子里「找到实心方块」只有两种落点：带的上沿就压在实心地形里（返回值等于上沿），或者
     * 地表就在带里（正常命中）。整条带都没有实心方块时方向是<b>不可判</b>的 —— 地表可能在带下方，
     * 也可能是带子落进了山体里的空腔；盲猜一边会给出错的高度，所以直接交回整列扫描
     * （下沿压在 minY 的老路子，那种窗口不会出现这个歧义）。
     */
    private int scanBand(int x, int z, int hintY) {
        int limit = this.bandTopLimit;
        int bottom = Mth.clamp(alignDown(hintY - BAND_BELOW), minY, Math.max(minY, limit - BAND_MIN));
        int top = Math.min(limit, alignUp(Math.max(hintY + BAND_ABOVE, bottom + BAND_MIN)));
        if (top - bottom < cellHeight) top = Math.min(limit, bottom + cellHeight);
        for (int attempt = 0; attempt < BAND_TRIES; attempt++) {
            int h = height(x, z, LevelHeightAccessor.create(bottom, top - bottom));
            if (h <= bottom) {
                // 整条带都是空气：地表在带下方还是掉进空腔分不出来，交回整列
                return scanFromTop(x, z, hintY + BAND_ABOVE);
            }
            if (h < top) return h;
            // 返回值等于上沿：带上沿压在实心地形里，抬高上沿重扫
            int raised = Math.min(limit, top + BAND_GROW);
            if (raised <= top) return h;  // 到山头上限了，按截断算
            top = raised;
        }
        return scanFromTop(x, z, hintY + BAND_ABOVE);
    }

    /**
     * 没有提示（或提示带兜底）时的老路子：从 {@code wantedTop} 起一把快扫，顶到上沿就抬档重扫，
     * 整列扫到空再补一把全高。<b>下沿始终压在 minY</b>，所以「整列空气」就是真的没东西，不会和
     * 「地表在窗口上方」混在一起。
     */
    private int scanFromTop(int x, int z) {
        return scanFromTop(x, z, this.startTop);
    }

    private int scanFromTop(int x, int z, int wantedTop) {
        LevelHeightAccessor accessor = accessorFor(wantedTop);
        int h = height(x, z, accessor);
        if (h <= this.minY) {
            // 整列扫到空：地表在快扫范围之下，按全高重来一次
            return height(x, z, this.full);
        }
        int top = accessor.getMaxBuildHeight();
        for (int step = RAISE_FIRST; h >= top && top < this.capTop; step = Math.min(step * 2, RAISE_MAX)) {
            accessor = accessorFor(top + step);
            int raised = accessor.getMaxBuildHeight();
            if (raised <= top) break;
            top = raised;
            h = Math.max(h, height(x, z, accessor));
        }
        return h;
    }

    /** 唯一的高度查询出口：顺手记诊断计数。 */
    private int height(int x, int z, LevelHeightAccessor window) {
        calls.increment();
        windowCells.add((window.getHeight() + cellHeight - 1L) / cellHeight);
        return this.generator.getBaseHeight(x, z, TYPE, window, this.randomState);
    }

    /**
     * 诊断自检：抽样跟「整列全高」对一次。提示带把窗口压窄以后，哪儿算错都会在日志里直接出现
     * {@code 高度自检不一致}，不用等到进世界才发现地形对不上。
     * 默认关，只在 {@code -Dyardsite.debug=true} 时跑。
     */
    private void verify(int x, int z, int surface) {
        if (!YardSiteLog.debugEnabled()) return;
        if ((verifyCounter.getAndIncrement() & VERIFY_MASK) != 0) return;
        int expected = this.generator.getBaseHeight(x, z, TYPE, this.full, this.randomState);
        if (expected >= this.capTop) return;  // 超过山头上限的按截断算，本来就会不一样
        if (expected != surface) {
            YardSiteLog.debug("高度自检不一致：(x=" + x + ", z=" + z + ") 带窗口 " + surface
                    + " ≠ 整列 " + expected);
        }
    }

    /** 取上沿不低于 {@code wantedTop} 的那把（往上量化到 {@link #topStep}）。 */
    private LevelHeightAccessor accessorFor(int wantedTop) {
        int clamped = Mth.clamp(wantedTop, this.minY + this.topStep, this.capTop);
        int rung = (clamped - this.minY + this.topStep - 1) / this.topStep - 1;
        return this.ladder[Mth.clamp(rung, 0, this.ladder.length - 1)];
    }

    /** 往下对齐到噪声竖直格子（以维度下沿为原点）。 */
    private int alignDown(int y) {
        return this.minY + Math.floorDiv(y - this.minY, this.cellHeight) * this.cellHeight;
    }

    /** 往上对齐到噪声竖直格子（以维度下沿为原点）。 */
    private int alignUp(int y) {
        int rel = y - this.minY;
        return this.minY + Math.floorDiv(rel + this.cellHeight - 1, this.cellHeight) * this.cellHeight;
    }

    /** 噪声竖直格子高度（格）：原版的 {@code size_vertical}，非噪声生成器按 8 算。 */
    private static int cellHeight(ChunkGenerator generator) {
        if (generator instanceof NoiseBasedChunkGenerator noise) {
            return Math.max(1, noise.generatorSettings().value().noiseSettings().getCellHeight());
        }
        return 8;
    }

    /** 噪声列自己的竖直上沿：比它高的 accessor 扫不出更多东西。 */
    private static int noiseTop(ChunkGenerator generator, int fallback) {
        if (generator instanceof NoiseBasedChunkGenerator noise) {
            NoiseSettings settings = noise.generatorSettings().value().noiseSettings();
            return settings.minY() + settings.height();
        }
        return fallback;
    }
}
