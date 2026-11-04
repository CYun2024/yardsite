package dev.yard.site.worldgen;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * 不加载区块、只问生成器噪声「这一列的地表在哪」。选址图（大沙盘、区块精读）和建世界前的
 * 落点规划共用这一份，三条路径的 y 才是同一个数。
 *
 * <h2>为什么分三把 accessor</h2>
 * {@link ChunkGenerator#getBaseHeight} 是从 accessor 的<b>上沿</b>一路往下扫到第一个不透明方块，
 * 扫的空气层数就是成本 —— 所以先用窄的那把扫绝大多数列：
 * <ul>
 *   <li>扫到空（返回值 = accessor 下沿）：地表在下沿以下，换<b>全高</b>重扫；</li>
 *   <li>扫到贴着上沿：那是一座比上沿更高的山，返回值是<b>被截断</b>的，再补一把到
 *       {@link #PEAK_TOP_Y} 的扫，取两者里更高的；</li>
 *   <li>其余：一次就够。</li>
 * </ul>
 * 补扫只在「顶到上沿」的列上发生，而那些列本来就没什么空气要扫（从 160 扫到山头），所以多不了多少活。
 */
public final class SurfaceProbe {

    /** 快扫那把的竖直跨度（格）：越小越便宜，代价是超出部分要靠补扫。 */
    public static final int SHALLOW_HEIGHT = 192;
    /** 山头补扫扫到这个 y 为止；比这还高的山仍按这里截断（再往上就是每列都多扫了）。 */
    public static final int PEAK_TOP_Y = 160;

    private static final Heightmap.Types TYPE = Heightmap.Types.WORLD_SURFACE_WG;

    private final ChunkGenerator generator;
    private final RandomState randomState;
    private final LevelHeightAccessor shallow;
    private final LevelHeightAccessor full;
    private final LevelHeightAccessor peak;
    private final int seaLevel;

    public SurfaceProbe(ChunkGenerator generator, RandomState randomState,
                        int minY, int dimensionHeight, int seaLevel) {
        this.generator = generator;
        this.randomState = randomState;
        this.seaLevel = seaLevel;
        this.full = LevelHeightAccessor.create(minY, dimensionHeight);
        this.shallow = LevelHeightAccessor.create(minY, Math.min(dimensionHeight, SHALLOW_HEIGHT));
        this.peak = LevelHeightAccessor.create(minY, Math.min(dimensionHeight, PEAK_TOP_Y - minY));
    }

    /**
     * 一列的地表 y（{@code WORLD_SURFACE_WG}：非空气的第一个方块，水域就是水面）。
     * 注意这是<b>雕刻与地物之前</b>的地形，真实区块到货后要以最终高度图为准。
     */
    public int surfaceAt(int x, int z) {
        int h = this.generator.getBaseHeight(x, z, TYPE, this.shallow, this.randomState);
        if (h <= this.shallow.getMinBuildHeight()) {
            h = this.generator.getBaseHeight(x, z, TYPE, this.full, this.randomState);
        } else if (h >= this.shallow.getMaxBuildHeight() - 1 && this.peak.getMaxBuildHeight() > this.shallow.getMaxBuildHeight()) {
            h = Math.max(h, this.generator.getBaseHeight(x, z, TYPE, this.peak, this.randomState));
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
}
