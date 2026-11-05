package dev.yard.site.worldgen;

import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * 不加载区块、只问生成器噪声「这一列的地表在哪」。选址图（大沙盘、区块精读）和建世界前的
 * 落点规划共用这一份，三条路径的 y 才是同一个数。
 *
 * <h2>成本全在「扫多少空气」</h2>
 * {@link ChunkGenerator#getBaseHeight} 从 accessor 的<b>上沿</b>逐格往下走，撞到第一个不透明方块
 * 就返回（水也算不透明），所以花的力气就是「上沿 - 地表」那么多格。反过来看：<b>起点定低了几乎
 * 不要钱</b> —— 一列本来是山的场合最上面那格就是实的，返回值等于上沿就说明「顶到了」，抬一档重扫
 * 只多花一次判定。于是这里不用固定两三把 accessor，而是：
 * <ul>
 *   <li>起点按 {@link SurfaceTrend} 估出来的地表定（上坡抬、下坡压，见那个类）；</li>
 *   <li>顶到上沿就抬档重扫，第一下抬 32 格、之后每次翻倍，抬到 {@link #capTop} 为止；
 *       最后仍顶在上沿的山头按截断算（和以前一样，只是以前截得早）；</li>
 *   <li>整列扫到空（返回值到了下沿）才补一把全高。</li>
 * </ul>
 * 上沿量化到 16 的整数倍，同档共用一把；阶梯在构造时一次造好，采样线程只读。
 *
 * <h2>上限为什么要夹</h2>
 * {@link #capTop} 取「调用方给的山头上限、维度上沿、噪声列自己的竖直上沿」最小那一个：原版主世界
 * 的噪声列到 320 就没了，accessor 再高也只是多造几把空梯子。史诗地形把噪声高度写成 512
 * （上沿 448），到这一档抬高上限才有实际意义。
 */
public final class SurfaceProbe {

    /** 原版量级的山头上限：比这还高的山头按截断算，也是没装高山类 mod 时的取值。 */
    public static final int PEAK_TOP_Y = 160;
    /** 完全没有起点提示时用的那把快扫的竖直跨度（格）：越小越便宜，超出部分靠抬档。 */
    public static final int SHALLOW_HEIGHT = 192;
    /** {@link #surfaceAt(int, int, int)} 的提示取值：这一列没有邻居可参考。 */
    public static final int NO_HINT = Integer.MIN_VALUE;

    /** accessor 上沿的量化间隔（格）：同档共用一把，起点往上靠。 */
    private static final int TOP_STEP = 16;
    /** 顶到上沿后第一次抬档的幅度（格）。 */
    private static final int RAISE_FIRST = 32;
    /** 抬档幅度的上限（格）：到这一步基本一步就够到维度顶，不必再多一轮。 */
    private static final int RAISE_MAX = 256;
    /**
     * 估计地表之上要留的空气（格）。刻意留得很薄：抬档重扫只要一次判定，多留 16 格却要每列
     * 实打实多扫 16 格 —— 按 128×128 高度点、三种量级的地形模拟过，留 24 格比完全不提示还费，
     * 留 8 格才两头都省（原版量级 19→16 格/点、调用 1.5→1.0 次/点；史诗量级 66→16 格/点、
     * 3.7→1.0 次/点）。
     */
    private static final int HINT_MARGIN = 8;

    private static final Heightmap.Types TYPE = Heightmap.Types.WORLD_SURFACE_WG;

    private final ChunkGenerator generator;
    private final RandomState randomState;
    private final int minY;
    private final int seaLevel;
    /** 全高 accessor：整列扫到空时补扫用，落点规划夹 y 边界也用它。 */
    private final LevelHeightAccessor full;
    /** 最多扫到这个上沿。 */
    private final int capTop;
    /** 没有提示时的起点上沿。 */
    private final int startTop;
    /** 第 i 把的上沿是 {@code minY + (i + 1) * TOP_STEP}。 */
    private final LevelHeightAccessor[] ladder;

    /**
     * @param peakTopY 山头上限（{@code 绝对 y}）：{@link #PEAK_TOP_Y} 或史诗地形那种更高的量级
     */
    public SurfaceProbe(ChunkGenerator generator, RandomState randomState,
                        int minY, int dimensionHeight, int seaLevel, int peakTopY) {
        this.generator = generator;
        this.randomState = randomState;
        this.seaLevel = seaLevel;
        this.minY = minY;
        this.full = LevelHeightAccessor.create(minY, dimensionHeight);
        int ceiling = Math.min(minY + dimensionHeight, noiseTop(generator, minY + dimensionHeight));
        this.capTop = Mth.clamp(Math.min(peakTopY, ceiling), minY + TOP_STEP, ceiling);
        this.startTop = Math.min(this.capTop, minY + SHALLOW_HEIGHT);
        int rungs = Math.max(1, (this.capTop - minY + TOP_STEP - 1) / TOP_STEP);
        this.ladder = new LevelHeightAccessor[rungs];
        for (int i = 0; i < rungs; i++) {
            this.ladder[i] = LevelHeightAccessor.create(minY, (i + 1) * TOP_STEP);
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
     * 同上，但按邻居估出来的地表定扫描起点。
     *
     * @param hintY {@link SurfaceTrend} 给的这一列地表 y 估计，没有就传 {@link #NO_HINT}
     */
    public int surfaceAt(int x, int z, int hintY) {
        LevelHeightAccessor accessor = accessorFor(hintY == NO_HINT ? this.startTop : hintY + HINT_MARGIN);
        int h = this.generator.getBaseHeight(x, z, TYPE, accessor, this.randomState);
        if (h <= this.minY) {
            // 整列扫到空：地表在快扫范围之下，按全高重来一次
            return this.generator.getBaseHeight(x, z, TYPE, this.full, this.randomState);
        }
        int top = accessor.getMaxBuildHeight();
        for (int step = RAISE_FIRST; h >= top && top < this.capTop; step = Math.min(step * 2, RAISE_MAX)) {
            accessor = accessorFor(top + step);
            int raised = accessor.getMaxBuildHeight();
            if (raised <= top) break;
            top = raised;
            h = Math.max(h, this.generator.getBaseHeight(x, z, TYPE, accessor, this.randomState));
        }
        return h;
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

    /** 取上沿不低于 {@code wantedTop} 的那把（往上量化到 {@link #TOP_STEP}）。 */
    private LevelHeightAccessor accessorFor(int wantedTop) {
        int clamped = Mth.clamp(wantedTop, this.minY + TOP_STEP, this.capTop);
        int rung = (clamped - this.minY + TOP_STEP - 1) / TOP_STEP - 1;
        return this.ladder[Mth.clamp(rung, 0, this.ladder.length - 1)];
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
