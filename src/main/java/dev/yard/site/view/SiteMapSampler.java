package dev.yard.site.view;

import dev.yard.site.YardSiteConfig;
import dev.yard.site.YardSiteLog;
import dev.yard.site.compat.EpicTerrainCompat;
import dev.yard.site.compat.TerraBlenderCompat;
import dev.yard.site.worldgen.SurfaceProbe;
import dev.yard.site.worldgen.SurfaceTrend;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.BooleanSupplier;

/**
 * 建世界时的「群系 + 地形」大沙盘采样：完全在客户端算，不加载任何区块。
 *
 * <p>群系问 {@link TerraBlenderCompat#previewSource} 给的那个源：默认就是这个世界生成器自己的
 * {@code BiomeSource}（{@code getNoiseBiome}，和区块生成时同一套调用），所以改群系的 mod / 数据包
 * 自动生效；装了 TerraBlender 时它是同一份参数表按同一个种子做出来的<b>预览副本</b>，因为原版那份
 * 要等服务端启动才挂上 TerraBlender 的 mod 区域表，而这张图比服务端早。高度用
 * {@link ChunkGenerator#getBaseHeight}，只做大致的山体阴影，看不到树、村庄和结构。
 *
 * <h2>从中心向外扩散</h2>
 * 高度点（128×128 网格，约 31 格方块一个点）不按行从头扫到尾，而是<b>按离图中心（原版出生点
 * 猜测位置）的距离从近到远</b>排队，线程从队头领活。每采完一个高度点，就顺手检查它周围 3×3 的
 * 地块：<b>自己 + 上下左右这 5 个高度点齐了</b>，这块（2×2 个细格）立刻取地表群系并上色。
 * 所以 {@link View} 是从正中一圈一圈往外长的，图面和一次算完再上色完全一致（阴影要的邻居高度
 * 一定已经就位），界面可以边算边画。
 *
 * <h2>省时间的三个做法</h2>
 * <ol>
 *   <li><b>缩窄竖直扫描</b>：{@code getBaseHeight} 是从 accessor 上沿一路往下扫到第一个不透明方块，
 *       扫的空气层数就是成本。起点按<b>已经采到的邻居</b>估（{@link SurfaceTrend}：上坡把起点抬高、
 *       下坡压低），顶到上沿再一档档往上抬 —— 抬档只多一次判定，所以宁可从低处起。
 *       史诗地形那种把山头拉到四百多格的地形，上限由 {@link EpicTerrainCompat} 一起抬上去，
 *       不然 160 以上的山在图上全被削平。</li>
 *   <li><b>水域免采样</b>：每个高度点先问一次群系，凡是 ocean/river 直接把高度记成海平面，
 *       不调用高度采样（海洋占比通常三到五成）。原来这是独立的一趟预扫，现在并进同一趟里。</li>
 *   <li><b>群系几乎不加锁</b>：每格只写自己的槽位（写进去的是坡度光照；底色画的时候按当前配色现算，
 *       所以编辑模式改颜色不用重采），调色板只在出现新群系 id 时进锁
 *       （整张图通常几十种），多线程不会被掐住（见 {@link BiomePalette}）。</li>
 * </ol>
 * 网格大小随范围走：柱子 {@code 128×128} 根起、半径每翻倍升一档（一根柱子代表的地面宽度基本不变，
 * 见 {@link #columnsFor} 和 {@code site.sandboxColumns}），群系细格始终是柱子的 2 倍边长，
 * 所以海岸线、群系边界仍然比高度点精确一倍。
 */
public final class SiteMapSampler implements AutoCloseable {

    /**
     * 高度网格间隔（细格）：每 2 格采一个高度，一根柱子正好盖 2×2 细格 —— 所以柱子数和高度点数
     * 是同一个数，细格数是它们的 2 倍。
     */
    private static final int HEIGHT_STRIDE = 2;
    /**
     * 参考档位：±2000 格 = 128 根柱子、256×256 细格，一根柱子代表约 31 格方块。
     * 清晰度一直按这个量级定，所以范围翻倍时柱子数也跟着翻倍（见 {@link #columnsFor}）。
     */
    private static final int REFERENCE_RADIUS = 2000;
    /** 每边最少这么多根柱子：范围比参考档还小时没必要更稀。 */
    private static final int COLUMNS_MIN = 128;
    /** 每边最多这么多根柱子的硬顶：配置再高也不超（512 已经是 26 万根柱子、100 万个群系格）。 */
    private static final int COLUMNS_HARD_MAX = 512;

    /**
     * 大沙盘每边多少根柱子：半径每翻一倍升一档，让一根柱子代表的地面宽度基本不变，
     * 夹在 {@link #COLUMNS_MIN} 和配置上限之间。只有粗过当前档一半以上才升档，
     * 免得 ±2500 就翻四倍花钱。
     *
     * @param maxColumns {@code site.sandboxColumns}（用户能接受的档位上限）
     */
    static int columnsFor(int radius, int maxColumns) {
        int cap = Mth.clamp(maxColumns, COLUMNS_MIN, COLUMNS_HARD_MAX);
        int need = (int) Math.ceil(radius / (REFERENCE_RADIUS / (double) COLUMNS_MIN));
        int columns = COLUMNS_MIN;
        while (columns < cap && need > columns + columns / 2) {
            columns *= 2;
        }
        return columns;
    }

    /** 还没采到的高度；{@code getBaseHeight} 的返回值不可能这么小。 */
    private static final int UNSET = Integer.MIN_VALUE;
    /**
     * 坡度光照的编码范围：{@code paintBlock} 把 light 夹在 0.55~1.30，这里按一个字节线性存进
     * {@link View#colors} 的最高字节（那个位置以前恒为 0xFF，没人读）。颜色本身改成画的时候
     * 现算，编辑模式改了群系颜色才不用重采整张图。
     */
    private static final double LIGHT_MIN = 0.55;
    private static final double LIGHT_RANGE = 0.75;

    /** 一个 2×2 地块的状态。 */
    private static final int BLOCK_PENDING = 0;
    private static final int BLOCK_WORKING = 1;
    /** 这块的像素已经写好，可以画了。 */
    private static final int BLOCK_READY = 2;

    /**
     * 边采边画的地图（大沙盘）：采样线程写，渲染线程读；对外只露 {@link SiteField} 那套接口，
     * 所以区块精读视图能用同一个渲染器。
     */
    static final class View implements SiteField {
        final int centerX;
        final int centerZ;
        final int radius;
        final double step;
        final int seaLevel;
        final int minY;
        final int maxY;
        /** 每边柱子数 = 每边高度点数（一根柱子就是一个高度点管的那 2×2 细格）。 */
        final int columns;
        /** 每边细格数（= {@code columns × 2}）。 */
        final int fine;
        /** 高度点总数（{@code columns²}）。 */
        final int heightPoints;
        /** 进度总量：高度点（群系顺带采）+ 逐格群系。 */
        final int progressTotal;
        /** 高度点下标（{@code cj * columns + ci}），按离中心的距离从近到远排好。 */
        final int[] order;
        /** 每个细格采样结果：最高字节 = 坡度光照编码，低 24 位留空（颜色画的时候现算）。 */
        final int[] colors;
        /** 每个细格的地表 y（跟着所属高度点）。 */
        final int[] heights;
        /** 每个细格在调色板里的下标。 */
        final int[] biomeIndex;
        /** 每个 2×2 地块的进度：0 未开始 / 1 有人正在算 / 2 可以画了。 */
        final AtomicIntegerArray blockState;
        /** 每个地块第一次被画面看见的时间（渐入用）。 */
        final long[] bornAt;
        /** 高度网格上每个点的地表 y，未采到是 {@code UNSET}。 */
        final AtomicIntegerArray pointHeights;
        final AtomicInteger progress = new AtomicInteger();
        final BiomePalette palette = new BiomePalette();
        /** 竖向比例的当前值（渲染器每帧 eased）。 */
        float shownSpan = 60.0F;
        /** 全部采样完成。 */
        volatile boolean full;

        View(int centerX, int centerZ, int radius, int columns, double step, int seaLevel, int minY, int maxY) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radius = radius;
            this.columns = columns;
            this.fine = columns * HEIGHT_STRIDE;
            this.heightPoints = columns * columns;
            this.progressTotal = this.heightPoints + this.fine * this.fine;
            this.step = step;
            this.seaLevel = seaLevel;
            this.minY = minY;
            this.maxY = maxY;
            this.colors = new int[this.fine * this.fine];
            this.heights = new int[this.fine * this.fine];
            this.biomeIndex = new int[this.fine * this.fine];
            this.blockState = new AtomicIntegerArray(this.heightPoints);
            this.bornAt = new long[this.heightPoints];
            this.pointHeights = new AtomicIntegerArray(this.heightPoints);
            for (int k = 0; k < this.heightPoints; k++) {
                this.pointHeights.set(k, UNSET);
            }
            this.order = centerOutOrder(columns);
        }

        /** 从正中向外一圈圈的队列；同距离按下标排，保证顺序稳定。 */
        private static int[] centerOutOrder(int columns) {
            int points = columns * columns;
            long[] keyed = new long[points];
            double half = (columns - 1) / 2.0;
            for (int cj = 0; cj < columns; cj++) {
                for (int ci = 0; ci < columns; ci++) {
                    double dx = ci - half;
                    double dz = cj - half;
                    long dist = Math.round(Math.sqrt(dx * dx + dz * dz) * 1024.0);
                    int index = cj * columns + ci;
                    keyed[index] = (dist << 32) | (index & 0xFFFFFFFFL);
                }
            }
            Arrays.sort(keyed);
            int[] order = new int[points];
            for (int k = 0; k < order.length; k++) {
                order[k] = (int) keyed[k];
            }
            return order;
        }

        @Override
        public int columns() {
            return columns;
        }

        @Override
        public int perColumn() {
            return HEIGHT_STRIDE;
        }

        @Override
        public int fine() {
            return fine;
        }

        @Override
        public double step() {
            return step;
        }

        @Override
        public int centerX() {
            return centerX;
        }

        @Override
        public int centerZ() {
            return centerZ;
        }

        @Override
        public int seaLevel() {
            return seaLevel;
        }

        @Override
        public int minY() {
            return minY;
        }

        @Override
        public int maxY() {
            return maxY;
        }

        @Override
        public long columnKey(int ci, int cj) {
            // 大沙盘不挪中心：柱子序号就是它的身份，缓存一次算好就一直能用
            return cj * columns + ci;
        }

        @Override
        public boolean ready(int ci, int cj) {
            return blockState.get(cj * columns + ci) == BLOCK_READY;
        }

        @Override
        public long drawnAt(int ci, int cj, long now) {
            int index = cj * columns + ci;
            long born = bornAt[index];
            if (born == 0L) {
                bornAt[index] = now;
                born = now;
            }
            return born;
        }

        @Override
        public int colorAtCell(int cellX, int cellY) {
            int slot = cellY * fine + cellX;
            List<BiomeInfo> biomes = palette.biomes();
            int b = biomeIndex[slot];
            if (b < 0 || b >= biomes.size()) return 0;
            BiomeInfo info = biomes.get(b);
            int surface = heights[slot];
            double light = SiteMapSampler.decodeLight(colors[slot]);
            boolean wet = BiomePalette.isWaterBiome(info.id()) || surface <= seaLevel;
            return BiomePalette.colorFor(info.id(), info.rgb(), wet, seaLevel, surface, light);
        }

        @Override
        public int biomeIndexAtCell(int cellX, int cellY) {
            return biomeIndex[cellY * fine + cellX];
        }

        @Override
        public int surfaceAtCell(int cellX, int cellY) {
            return heights[cellY * fine + cellX];
        }

        @Override
        public List<BiomeInfo> biomes() {
            return palette.biomes();
        }

        @Override
        public int progress() {
            return progress.get();
        }

        @Override
        public int progressTotal() {
            return progressTotal;
        }

        @Override
        public boolean full() {
            return full;
        }

        @Override
        public String label() {
            return "大沙盘";
        }

        @Override
        public float defaultZoom() {
            return 2.0F;
        }

        @Override
        public boolean showsFloor() {
            return true;
        }

        @Override
        public float originShiftX() {
            return 0.0F;
        }

        @Override
        public float originShiftY() {
            return 0.0F;
        }

        @Override
        public float edgeFade(int ci, int cj) {
            return 1.0F;
        }

        @Override
        public float reliefSpan() {
            return shownSpan;
        }

        @Override
        public void easeReliefSpan(float target) {
            shownSpan += (target - shownSpan) * (target > shownSpan ? 0.08F : 0.02F);
            if (Math.abs(target - shownSpan) < 0.6F) shownSpan = target;
        }
    }

    private final ChunkGenerator generator;
    /** 取群系问这个：装了 TerraBlender 时它是按同一份参数表+同一个种子做出来的预览副本。 */
    private final BiomeSource biomeSource;
    private final RandomState randomState;
    private final Climate.Sampler climateSampler;
    private final SurfaceProbe probe;
    private final View view;
    private final int threads;
    private final ExecutorService pool;

    public SiteMapSampler(WorldCreationContext context, int radius) {
        this.generator = context.selectedDimensions().overworld();
        LevelStem stem = context.selectedDimensions().get(LevelStem.OVERWORLD)
                .orElseThrow(() -> new IllegalStateException("缺少主世界生成设置"));
        DimensionType dimension = stem.type().value();
        int minY = dimension.minY();
        int maxY = minY + dimension.height();

        NoiseGeneratorSettings settings = generator instanceof NoiseBasedChunkGenerator noise
                ? noise.generatorSettings().value()
                : NoiseGeneratorSettings.dummy();
        RegistryAccess.Frozen registries = context.worldgenLoadContext();
        long seed = context.options().seed();
        this.randomState = RandomState.create(settings, registries.lookupOrThrow(Registries.NOISE), seed);
        this.biomeSource = TerraBlenderCompat.previewSource(generator, stem.type(), registries, seed);
        this.climateSampler = randomState.sampler();
        this.probe = new SurfaceProbe(generator, randomState, minY, dimension.height(), generator.getSeaLevel(),
                EpicTerrainCompat.peakCeiling(registries));

        // 原版开局就是拿这个找出生点的，用它当图中心才叫「出生点附近」
        BlockPos spawnGuess = climateSampler.findSpawnPosition();
        // 柱子档位随范围走：半径翻倍柱子也翻倍，一根柱子代表的地面宽度基本不变
        int columns = columnsFor(radius, YardSiteConfig.SITE_SANDBOX_COLUMNS.get());
        double step = (2.0 * radius) / (columns * HEIGHT_STRIDE - 1);
        this.view = new View(spawnGuess.getX(), spawnGuess.getZ(), radius, columns, step,
                generator.getSeaLevel(), minY, maxY);

        // 只用一半核，另一半留给渲染线程和系统（默认核数 ÷ 2，config/yardsite-common.toml 可改）
        this.threads = SiteThreadBudget.resolve();
        this.pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "yard-site-sample");
            thread.setDaemon(true);
            // 采样期间压一点优先级，别把渲染线程饿到掉帧（卡顿主要来自这里）
            thread.setPriority(Thread.NORM_PRIORITY - 2);
            return thread;
        });
        YardSiteLog.debug("选址图采样线程：" + threads + SiteThreadBudget.describe(threads)
                + "，柱子 " + columns + "×" + columns + " 根、群系细格 " + (columns * HEIGHT_STRIDE) + "×"
                + (columns * HEIGHT_STRIDE) + "（从中心向外，一根柱子约盖 "
                + Math.round(step * HEIGHT_STRIDE) + " 格方块，共 " + this.view.heightPoints
                + "+" + this.view.fine * this.view.fine + " 个采样点）"
                + "，竖直扫描默认上沿 " + probe.startTop() + "，最多抬到 " + probe.capTop()
                + "（起点按邻居趋势挪，顶到上沿就抬档重扫，超过上限的山头按截断算）");
    }

    /** 采样还没跑完也能拿到的活数据；界面拿它边算边画。 */
    public View view() {
        return view;
    }

    /** 高度点按中心向外领活，每个点采完顺手把四周凑齐的地块上色。 */
    public void build(BooleanSupplier cancelled) {
        View view = this.view;
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger cursor = new AtomicInteger();
        long cpuStart = processCpuNanos();
        long started = System.currentTimeMillis();

        List<Future<?>> futures = new ArrayList<>(threads);
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                int[] order = view.order;
                while (!cancelled.getAsBoolean()) {
                    int k = cursor.getAndIncrement();
                    if (k >= order.length) return;
                    int index = order[k];
                    int ci = index % view.columns;
                    int cj = index / view.columns;
                    samplePoint(ci, cj, skipped);
                    settleNearby(ci, cj);
                }
            }));
        }
        awaitAll(futures);

        long ms = System.currentTimeMillis() - started;
        long cpuNanos = processCpuNanos() - cpuStart;
        String cpuInfo = cpuNanos > 0 && ms > 0
                ? String.format(java.util.Locale.ROOT, "，CPU %d ms（平均 %.1f 核）",
                cpuNanos / 1_000_000L, cpuNanos / 1_000_000.0 / ms)
                : "";
        YardSiteLog.debug("选址图中心向外采样：" + ms + " ms（"
                + throughput(view.heightPoints, ms) + " 点/秒，跳过水域 " + skipped.get() + " 点）"
                + cpuInfo + "；地块 " + readyBlocks() + "/" + view.heightPoints
                + "，群系 " + view.palette.size() + " 种");
        if (cancelled.getAsBoolean()) throw new IllegalStateException("已取消");
        view.full = true;
    }

    private int readyBlocks() {
        int count = 0;
        for (int k = 0; k < view.heightPoints; k++) {
            if (view.blockState.get(k) == BLOCK_READY) count++;
        }
        return count;
    }

    private static long processCpuNanos() {
        try {
            return ((com.sun.management.OperatingSystemMXBean)
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
        } catch (Throwable t) {
            return -1L;
        }
    }

    private long throughput(int points, long ms) {
        return points * 1000L / Math.max(1L, ms);
    }

    /** 一个高度点：群系顺带问一次，是水就直接记海平面，省下最贵的高度采样。 */
    private void samplePoint(int ci, int cj, AtomicInteger skipped) {
        int index = ci + cj * view.columns;
        Holder<Biome> biome = biomeAt(ci * HEIGHT_STRIDE, cj * HEIGHT_STRIDE, view.seaLevel + 1);
        boolean water = BiomePalette.isWaterBiome(biome);
        int height = water ? view.seaLevel
                : baseHeight(ci * HEIGHT_STRIDE, cj * HEIGHT_STRIDE, trendHint(ci, cj));
        view.pointHeights.set(index, height);
        if (water) skipped.incrementAndGet();
        view.progress.incrementAndGet();
    }

    /**
     * 这一列的高度采样起点提示：只看已经采到的邻居。从中心往外采，靠内一侧的邻居必定有值，
     * 外圈那侧常常还没有（送 {@link SurfaceTrend#NO_HINT}）；正中心那格一个邻居都没有，就照旧
     * 用默认快扫起点。坡度按「离中心越来越远」这个方向取，所以上山时起点跟着抬、下山时压。
     */
    private int trendHint(int ci, int cj) {
        AtomicIntegerArray points = view.pointHeights;
        int index = ci + cj * view.columns;
        int west = ci > 0 ? known(points.get(index - 1)) : SurfaceTrend.NO_HINT;
        int east = ci < view.columns - 1 ? known(points.get(index + 1)) : SurfaceTrend.NO_HINT;
        int north = cj > 0 ? known(points.get(index - view.columns)) : SurfaceTrend.NO_HINT;
        int south = cj < view.columns - 1 ? known(points.get(index + view.columns)) : SurfaceTrend.NO_HINT;
        return SurfaceTrend.predict(west, east, north, south,
                ci - (view.columns - 1) / 2.0, cj - (view.columns - 1) / 2.0);
    }

    /** 邻居还没采到时要按「没有提示」传，不能把 {@link #UNSET} 当成真实地表。 */
    private static int known(int height) {
        return height == UNSET ? SurfaceTrend.NO_HINT : height;
    }

    /**
     * 我采完这一点后，周围 3×3 里可能有地块的邻居高度刚好凑齐了，逐个试着上色。
     *
     * <p>每个地块的「最后一个邻居高度」落定时一定会轮到它自己被检查一次，所以不用兜底扫描。
     */
    private void settleNearby(int ci, int cj) {
        for (int dj = -1; dj <= 1; dj++) {
            int nj = cj + dj;
            if (nj < 0 || nj >= view.columns) continue;
            for (int di = -1; di <= 1; di++) {
                int ni = ci + di;
                if (ni < 0 || ni >= view.columns) continue;
                int index = ni + nj * view.columns;
                if (view.blockState.get(index) != BLOCK_PENDING) continue;
                if (!heightsReady(ni, nj, index)) continue;
                if (view.blockState.compareAndSet(index, BLOCK_PENDING, BLOCK_WORKING)) {
                    paintBlock(ni, nj, index);
                    view.blockState.set(index, BLOCK_READY);
                }
            }
        }
    }

    /** 地块上色要看 5 个高度：自己定地表 y，上下左右定山体阴影（图上边缘按夹边算）。 */
    private boolean heightsReady(int ci, int cj, int index) {
        if (view.pointHeights.get(index) == UNSET) return false;
        if (ci > 0 && view.pointHeights.get(index - 1) == UNSET) return false;
        if (ci < view.columns - 1 && view.pointHeights.get(index + 1) == UNSET) return false;
        if (cj > 0 && view.pointHeights.get(index - view.columns) == UNSET) return false;
        if (cj < view.columns - 1 && view.pointHeights.get(index + view.columns) == UNSET) return false;
        return true;
    }

    /** 把一个 2×2 地块（4 个细格）按真实地表 y 取群系并写进 {@link View}。 */
    private void paintBlock(int ci, int cj, int index) {
        int surface = view.pointHeights.get(index);
        int left = ci > 0 ? view.pointHeights.get(index - 1) : surface;
        int right = ci < view.columns - 1 ? view.pointHeights.get(index + 1) : surface;
        int up = cj > 0 ? view.pointHeights.get(index - view.columns) : surface;
        int down = cj < view.columns - 1 ? view.pointHeights.get(index + view.columns) : surface;
        double slopeX = (right - left) / (2.0 * view.step * HEIGHT_STRIDE);
        double slopeZ = (down - up) / (2.0 * view.step * HEIGHT_STRIDE);
        double light = Mth.clamp(1.0 - (slopeX * 0.55 + slopeZ * 0.55), 0.55, 1.30);
        int sampleY = Mth.clamp(surface - 1, view.minY, view.maxY - 1);

        for (int dj = 0; dj < HEIGHT_STRIDE; dj++) {
            for (int di = 0; di < HEIGHT_STRIDE; di++) {
                int i = ci * HEIGHT_STRIDE + di;
                int j = cj * HEIGHT_STRIDE + dj;
                int slot = j * view.fine + i;
                Holder<Biome> holder = biomeAt(i, j, sampleY);
                ResourceLocation id = BiomePalette.idOf(holder);
                boolean water = BiomePalette.isWaterBiome(id) || surface <= view.seaLevel;
                int biomeIdx = view.palette.indexOf(id, holder);
                // 只把「这一格受多少光」记下来；底色由 BiomePalette 在画的时候按当前配色解析
                double blockLight = water ? 1.0 + (light - 1.0) * 0.35 : light;
                view.colors[slot] = encodeLight(blockLight);
                view.heights[slot] = surface;
                view.biomeIndex[slot] = biomeIdx;
                view.progress.incrementAndGet();
            }
        }
    }

    /** 坡度光照 → 一个字节（写进 {@link View#colors} 的最高字节）。 */
    private static int encodeLight(double light) {
        double t = Math.max(0.0, Math.min(1.0, (light - LIGHT_MIN) / LIGHT_RANGE));
        return (int) Math.round(t * 255.0) << 24;
    }

    static double decodeLight(int packed) {
        return LIGHT_MIN + ((packed >>> 24) & 0xFF) / 255.0 * LIGHT_RANGE;
    }

    private Holder<Biome> biomeAt(int fi, int fj, int y) {
        int x = blockX(fi);
        int z = blockZ(fj);
        int sampleY = Mth.clamp(y, view.minY, view.maxY - 1);
        return biomeSource.getNoiseBiome(
                QuartPos.fromBlock(x), QuartPos.fromBlock(sampleY), QuartPos.fromBlock(z), climateSampler);
    }

    /**
     * 高度取点：{@link SurfaceProbe} 从 {@code hintY} 估出来的上沿开始往下扫（下坡的列只多扫十几格
     * 空气），顶到上沿就抬档重扫，所以上限之内的山都不会被截断；上限按世界定（见
     * {@link EpicTerrainCompat}）。
     */
    private int baseHeight(int fi, int fj, int hintY) {
        return probe.surfaceAt(blockX(fi), blockZ(fj), hintY);
    }

    private int blockX(int cell) {
        return view.centerX + (int) Math.round((cell - (view.fine - 1) / 2.0) * view.step);
    }

    private int blockZ(int cell) {
        return view.centerZ + (int) Math.round((cell - (view.fine - 1) / 2.0) * view.step);
    }

    private void awaitAll(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("采样被中断", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new IllegalStateException("采样失败：" + cause, cause);
            }
        }
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
