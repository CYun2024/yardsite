package dev.yard.site.view;

import dev.yard.site.compat.TerraBlenderCompat;
import dev.yard.site.worldgen.SurfaceProbe;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
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
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BooleanSupplier;

/**
 * 区块精读：一格 = <b>一个区块（16×16 方块）</b>，窗口是<b>圆形</b>，半径 {@value #RADIUS} 区块。
 *
 * <h2>拖动只挪"中心点"，和渲染分开</h2>
 * 窗口中心是一个<b>连续的世界坐标</b>（区块单位的小数），拖动就是把这点推出去：
 * {@link #moveCenterByCells} 改中心，只有中心跨过 0.5 区块时锚点才进一位、顺带重排一次快照；
 * 那点不到半格的差额交给 {@link #originShiftX()} 直接平移画面 —— 所以拖动是<b>连续滑动</b>，
 * 不再有"攒满一格弹一下"的感觉，斜着拉也跟着手（屏幕位移→地面位移的换算在渲染器里做）。
 *
 * <h2>采样</h2>
 * 每格采区块中心列的地表 y + 那一格的群系（水域直接记海平面，省下最贵的高度采样），
 * 按离中心从近到远补，采过的进 {@code cache}（键是区块坐标），来回拖不重算；
 * 缓存超上限时只丢窗口外的区块。
 */
final class ChunkSitePlanner implements AutoCloseable {

    /** 圆形窗口半径（区块）。 */
    static final int RADIUS = 64;
    /** 网格每边格子数（方形网格装圆形窗口）。 */
    static final int COLUMNS = RADIUS * 2 + 1;
    private static final int CELLS = COLUMNS * COLUMNS;
    private static final int CELL_BLOCKS = 16;
    /** 圆环外沿几格渐隐的宽度（区块）。 */
    private static final float FADE_RING = 7.0F;
    /** 缓存上限（区块格）：约 1.3 万格的十几倍余量，够来回拖很久。 */
    private static final int CACHE_LIMIT = 200_000;
    /** 一格 = 16 方块。 */
    private static final double STEP_BLOCKS = CELL_BLOCKS;
    /** 还没采到的格子涂什么色（和原来的深色底板一致，正常情况画不到它）。 */
    private static final int EMPTY_RGB = 0x0A0C12;

    /** 一个区块的采样结果（颜色不烤在这里，画的时候按当前配色现算，见 {@link #colorOf}）。 */
    private static final class Cell {
        final int surface;
        final int biomeIndex;
        /** 这一格按水画吗（群系是水，或地表低于海平面）。 */
        final boolean wet;
        /** 第一次被画面看见的时间（渐入起点）；0 = 还没画过。 */
        volatile long bornAt;

        Cell(int surface, int biomeIndex, boolean wet) {
            this.surface = surface;
            this.biomeIndex = biomeIndex;
            this.wet = wet;
        }
    }

    /** 精读图（给渲染器/界面用），中心随拖动连续挪。 */
    private final class Field implements SiteField {
        @Override
        public int columns() {
            return COLUMNS;
        }

        @Override
        public int perColumn() {
            return 1;
        }

        @Override
        public int fine() {
            return COLUMNS;
        }

        @Override
        public double step() {
            return STEP_BLOCKS;
        }

        @Override
        public int centerX() {
            return Math.round((float) centerChunkX) * CELL_BLOCKS + CELL_BLOCKS / 2;
        }

        @Override
        public int centerZ() {
            return Math.round((float) centerChunkZ) * CELL_BLOCKS + CELL_BLOCKS / 2;
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
            return key(originChunkX + ci - RADIUS, originChunkZ + cj - RADIUS);
        }

        @Override
        public boolean ready(int ci, int cj) {
            int index = cj * COLUMNS + ci;
            return inside[index] && cells.get(index) != null;
        }

        @Override
        public long drawnAt(int ci, int cj, long now) {
            Cell cell = cells.get(cj * COLUMNS + ci);
            if (cell == null) return now;
            long born = cell.bornAt;
            if (born == 0L) {
                cell.bornAt = now;
                born = now;
            }
            return born;
        }

        @Override
        public int colorAtCell(int cellX, int cellY) {
            Cell cell = cells.get(cellY * COLUMNS + cellX);
            return cell == null ? 0 : colorOf(cell);
        }

        @Override
        public int biomeIndexAtCell(int cellX, int cellY) {
            Cell cell = cells.get(cellY * COLUMNS + cellX);
            return cell == null ? 0 : cell.biomeIndex;
        }

        @Override
        public int surfaceAtCell(int cellX, int cellY) {
            Cell cell = cells.get(cellY * COLUMNS + cellX);
            return cell == null ? seaLevel : cell.surface;
        }

        @Override
        public List<BiomeInfo> biomes() {
            return palette.biomes();
        }

        @Override
        public int progress() {
            return filled.get();
        }

        @Override
        public int progressTotal() {
            return discCells;
        }

        @Override
        public boolean full() {
            return filled.get() >= discCells;
        }

        @Override
        public String label() {
            return "区块精读";
        }

        @Override
        public float defaultZoom() {
            // 一屏只看中间一半（约 64 区块宽），要看得更细就再滚轮放大
            return 2.0F;
        }

        @Override
        public boolean showsFloor() {
            // 圆形窗口配方底板会有四个角戳在外面，不画
            return false;
        }

        @Override
        public float originShiftX() {
            return (float) (-(centerChunkX - originChunkX) * 2.0 / (COLUMNS - 1));
        }

        @Override
        public float originShiftY() {
            return (float) (-(centerChunkZ - originChunkZ) * 2.0 / (COLUMNS - 1));
        }

        @Override
        public float edgeFade(int ci, int cj) {
            return edgeFadeTable[cj * COLUMNS + ci];
        }

        @Override
        public float reliefSpan() {
            return shownSpan;
        }

        /** 拖动会带进新的地形，目标值有涨有跌：涨得快、跌得慢，看着不跳。 */
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
    private final BiomePalette palette = new BiomePalette();
    private final Map<Long, Cell> cache = new ConcurrentHashMap<>();
    private final int seaLevel;
    private final int minY;
    private final int maxY;
    private final int threads;
    private final ExecutorService pool;
    private final Thread pump;
    private final Field field = new Field();
    /** 圆内格子按离中心从近到远的顺序（采样领活用）。 */
    private final int[] order;
    private final int discCells;
    private final boolean[] inside = new boolean[CELLS];
    private final float[] edgeFadeTable = new float[CELLS];
    /** 当前窗口的格子快照（渲染线程读，锚点变化和后台 pass 都会写）。 */
    private final AtomicReferenceArray<Cell> cells = new AtomicReferenceArray<>(CELLS);
    private final AtomicInteger filled = new AtomicInteger();
    /** 竖向比例的当前值（渲染器每帧 eased）。 */
    private float shownSpan = 60.0F;
    /** 连续窗口中心（区块单位）与它的整数锚点，都只由渲染线程写。 */
    private double centerChunkX;
    private double centerChunkZ;
    private int originChunkX;
    private int originChunkZ;
    private volatile boolean cancelled;

    ChunkSitePlanner(WorldCreationContext context, int blockX, int blockZ) {
        this.generator = context.selectedDimensions().overworld();
        LevelStem stem = context.selectedDimensions().get(LevelStem.OVERWORLD)
                .orElseThrow(() -> new IllegalStateException("缺少主世界生成设置"));
        DimensionType dimension = stem.type().value();
        this.minY = dimension.minY();
        this.maxY = minY + dimension.height();
        this.seaLevel = generator.getSeaLevel();

        NoiseGeneratorSettings settings = generator instanceof NoiseBasedChunkGenerator noise
                ? noise.generatorSettings().value()
                : NoiseGeneratorSettings.dummy();
        RegistryAccess.Frozen registries = context.worldgenLoadContext();
        long seed = context.options().seed();
        this.randomState = RandomState.create(settings, registries.lookupOrThrow(Registries.NOISE), seed);
        this.biomeSource = TerraBlenderCompat.previewSource(generator, stem.type(), registries, seed);
        this.climateSampler = randomState.sampler();
        this.probe = new SurfaceProbe(generator, randomState, minY, dimension.height(), seaLevel);

        this.centerChunkX = (blockX - CELL_BLOCKS / 2) / (double) CELL_BLOCKS;
        this.centerChunkZ = (blockZ - CELL_BLOCKS / 2) / (double) CELL_BLOCKS;
        this.originChunkX = (int) Math.round(centerChunkX);
        this.originChunkZ = (int) Math.round(centerChunkZ);

        int count = 0;
        for (int cj = 0; cj < COLUMNS; cj++) {
            for (int ci = 0; ci < COLUMNS; ci++) {
                double dx = ci - RADIUS;
                double dz = cj - RADIUS;
                double dist = Math.sqrt(dx * dx + dz * dz);
                int index = cj * COLUMNS + ci;
                boolean in = dist <= RADIUS + 0.5;
                inside[index] = in;
                edgeFadeTable[index] = Mth.clamp((float) ((RADIUS + 0.5 - dist) / FADE_RING), 0.0F, 1.0F);
                if (in) count++;
            }
        }
        this.discCells = count;
        this.order = discOrder();

        // 跟大沙盘用同一份预算：只用一半核，另一半留给渲染线程和系统
        this.threads = SiteThreadBudget.resolve();
        this.pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "yard-site-chunk");
            thread.setDaemon(true);
            // 采样线程压一点优先级：别让渲染线程被抢到掉帧
            thread.setPriority(Thread.NORM_PRIORITY - 2);
            return thread;
        });
        this.pump = new Thread(this::pumpLoop, "yard-site-chunk-pump");
        this.pump.setDaemon(true);
        this.pump.setPriority(Thread.NORM_PRIORITY - 2);
        System.out.println("[yardsite] 区块精读：圆形窗口半径 " + RADIUS + " 区块（" + discCells
                + " 格），线程 " + threads + SiteThreadBudget.describe(threads));
    }

    SiteField field() {
        return field;
    }

    /** 开始后台补采（界面进精读模式时调一次）。 */
    void start() {
        syncWindow();
        pump.start();
    }

    /** 进入精读时把中心钉到选中的那一格。 */
    void setCenter(int blockX, int blockZ) {
        centerChunkX = (blockX - CELL_BLOCKS / 2) / (double) CELL_BLOCKS;
        centerChunkZ = (blockZ - CELL_BLOCKS / 2) / (double) CELL_BLOCKS;
        reanchor();
    }

    /**
     * 拖动：把窗口中心按「区块格」为单位推开（可以是小数）。锚点跨过 0.5 区块才进一位，
     * 进了就当场重排快照，免得画面先移动、数据后补，看着像往反方向弹。
     */
    void moveCenterByCells(double dCellX, double dCellY) {
        centerChunkX += dCellX;
        centerChunkZ += dCellY;
        reanchor();
    }

    private void reanchor() {
        int nx = (int) Math.round(centerChunkX);
        int nz = (int) Math.round(centerChunkZ);
        if (nx == originChunkX && nz == originChunkZ) return;
        originChunkX = nx;
        originChunkZ = nz;
        syncWindow();
    }

    private static int[] discOrder() {
        long[] keyed = new long[CELLS];
        int n = 0;
        for (int cj = 0; cj < COLUMNS; cj++) {
            for (int ci = 0; ci < COLUMNS; ci++) {
                int index = cj * COLUMNS + ci;
                if (!insideAt(ci, cj)) continue;
                double dx = ci - RADIUS;
                double dz = cj - RADIUS;
                long dist = Math.round(Math.sqrt(dx * dx + dz * dz) * 1024.0);
                keyed[n++] = (dist << 32) | (index & 0xFFFFFFFFL);
            }
        }
        int[] order = new int[n];
        java.util.Arrays.sort(keyed, 0, n);
        for (int k = 0; k < n; k++) {
            order[k] = (int) keyed[k];
        }
        return order;
    }

    private static boolean insideAt(int ci, int cj) {
        double dx = ci - RADIUS;
        double dz = cj - RADIUS;
        return dx * dx + dz * dz <= (RADIUS + 0.5) * (RADIUS + 0.5);
    }

    /** 按当前锚点重排窗口快照，返回圆内已有数据的格子数。 */
    private int syncWindow() {
        int cx = originChunkX;
        int cz = originChunkZ;
        int ready = 0;
        for (int index : order) {
            int ci = index % COLUMNS;
            int cj = index / COLUMNS;
            Cell cell = cache.get(key(cx + ci - RADIUS, cz + cj - RADIUS));
            cells.set(index, cell);
            if (cell != null) ready++;
        }
        filled.set(ready);
        return ready;
    }

    /** 后台循环：按离中心从近到远补缺口，然后等下一次拖动。 */
    private void pumpLoop() {
        BooleanSupplier alive = () -> !cancelled;
        while (alive.getAsBoolean()) {
            try {
                runPass();
                Thread.sleep(30L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                System.out.println("[yardsite] 区块精读采样失败：" + t);
                return;
            }
        }
    }

    private void runPass() {
        int cx = originChunkX;
        int cz = originChunkZ;
        if (syncWindow() >= discCells) {
            trimCache(cx, cz);
            return;
        }
        AtomicInteger cursor = new AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>(threads);
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                while (!cancelled) {
                    int k = cursor.getAndIncrement();
                    if (k >= order.length) return;
                    int index = order[k];
                    int ci = index % COLUMNS;
                    int cj = index / COLUMNS;
                    int chunkX = cx + ci - RADIUS;
                    int chunkZ = cz + cj - RADIUS;
                    long lookup = key(chunkX, chunkZ);
                    Cell existing = cache.get(lookup);
                    if (existing != null) continue;
                    Cell sampled = sample(chunkX, chunkZ);
                    cache.put(lookup, sampled);
                    // 锚点在采样期间挪过就不动快照（下一次 syncWindow 会带上新中心）
                    if (originChunkX == cx && originChunkZ == cz) {
                        if (cells.compareAndSet(index, null, sampled)) {
                            filled.incrementAndGet();
                        }
                    }
                }
            }));
        }
        for (java.util.concurrent.Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new IllegalStateException("区块精读采样失败：" + cause, cause);
            }
        }
        trimCache(cx, cz);
    }

    /** 缓存太大时丢掉离当前中心太远的区块（窗口里的永远留着，来回拖不重算）。 */
    private void trimCache(int cx, int cz) {
        if (cache.size() <= CACHE_LIMIT) return;
        int keep = RADIUS * 3;
        cache.entrySet().removeIf(entry -> {
            long lookup = entry.getKey();
            int chunkX = (int) lookup;
            int chunkZ = (int) (lookup >> 32);
            return Math.abs(chunkX - cx) > keep || Math.abs(chunkZ - cz) > keep;
        });
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkZ << 32) | (chunkX & 0xFFFFFFFFL);
    }

    /** 一格 = 一个区块：采区块中心列的地表 y，再按那个高度取群系。 */
    private Cell sample(int chunkX, int chunkZ) {
        int x = chunkX * CELL_BLOCKS + CELL_BLOCKS / 2;
        int z = chunkZ * CELL_BLOCKS + CELL_BLOCKS / 2;
        Holder<Biome> coarse = biomeAt(x, seaLevel + 1, z);
        boolean water = BiomePalette.isWaterBiome(coarse);
        int surface = water ? seaLevel : baseHeight(x, z);
        Holder<Biome> at = biomeAt(x, surface - 1, z);
        ResourceLocation id = BiomePalette.idOf(at);
        int index = palette.indexOf(id, at);
        // 颜色不烤在这里：画的时候按当前配色现算（见 colorOf），编辑模式改色才不用重采
        return new Cell(surface, index, water || surface <= seaLevel);
    }

    /**
     * 这一格现在的颜色（0xRRGGBB）：按采样记下的群系 + 地表 y 现算，所以编辑模式改了配色立刻生效。
     * 和大沙盘 {@link SiteMapSampler.View#colorAtCell} 用的是同一个 {@link BiomePalette#colorFor}。
     */
    int colorOf(@Nullable Cell cell) {
        if (cell == null) return EMPTY_RGB;
        List<BiomeInfo> biomes = palette.biomes();
        if (cell.biomeIndex < 0 || cell.biomeIndex >= biomes.size()) return EMPTY_RGB;
        BiomeInfo info = biomes.get(cell.biomeIndex);
        return BiomePalette.colorFor(info.id(), info.rgb(), cell.wet, seaLevel, cell.surface, 1.0);
    }

    private Holder<Biome> biomeAt(int blockX, int y, int blockZ) {
        int sampleY = Mth.clamp(y, minY, maxY - 1);
        return biomeSource.getNoiseBiome(
                QuartPos.fromBlock(blockX), QuartPos.fromBlock(sampleY), QuartPos.fromBlock(blockZ),
                climateSampler);
    }

    private int baseHeight(int x, int z) {
        return probe.surfaceAt(x, z);
    }

    // ── 给细场用的按区块查询（进世界前那段动画）───────────────────────────

    /**
     * 一个区块的可画数据：粗场采到的那一份。
     * 复用同一个对象填，免得每帧每格造一个（细场一帧要问上万次）。
     */
    static final class CellInfo {
        int surface;
        int color;
        int biomeIndex;
        /** 粗场这一块采过了没有 —— 没采过就什么都别画。 */
        boolean known;
    }

    /** 查一个区块的数据填进 {@code out}（缓存里没有就把 {@code known} 置假，其他字段别用）。 */
    CellInfo probe(int chunkX, int chunkZ, CellInfo out) {
        Cell cell = cache.get(key(chunkX, chunkZ));
        out.known = cell != null;
        out.surface = cell == null ? seaLevel : cell.surface;
        out.color = 0xFF000000 | colorOf(cell);
        out.biomeIndex = cell == null ? 0 : cell.biomeIndex;
        return out;
    }

    @Override
    public void close() {
        cancelled = true;
        pump.interrupt();
        pool.shutdownNow();
    }
}
