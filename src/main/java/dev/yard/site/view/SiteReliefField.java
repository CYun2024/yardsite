package dev.yard.site.view;

import net.minecraft.util.Mth;

import java.util.List;

/**
 * 揭示用的<b>细场</b>：一格 = <b>4 方块</b>，窗口 {@value #COLUMNS}×{@value #COLUMNS} 格（约 30 区块宽），
 * 网格和区块边界对齐（一格不会跨两个区块）。
 *
 * <h2>为什么要有它</h2>
 * 登录之前客户端还没有 {@code ClientLevel}、也收不到区块，原版渲染器更不会跑；开了早进世界后
 * 登录虽然很快，但从区块到货到客户端画成网格仍要一段时间 —— 这段时间的替换只能由沙盘自己做。
 * 数据不自己采（那要重跑一遍噪声，跟区块生成抢 CPU），全部从粗场（区块精读）拿。
 *
 * <p><b>2026-11-01 删掉「到货后把柱子从平台滑成真实起伏」与「逐区块交回」</b>：
 * 前者对观感没有帮助，后者节奏被发包/网格化拖着走、看起来像随机跳块。
 * 现在细场只当沙盘的底图，真地形交给落机段的白光一次性换掉。
 */
final class SiteReliefField implements SiteField {

    /** 一格代表多少方块。 */
    static final int CELL_BLOCKS = 4;
    /** 每边格子数（4 的倍数 + 1，中心那格压在落点上）。 */
    static final int COLUMNS = 121;
    /** 圆形窗口的渐隐圈宽（格）。 */
    private static final float FADE_RING = 6.0F;
    /** 一个区块拆成几×几根小柱（=每边的格数）。 */
    static final int PER_SIDE_CELLS = 16 / CELL_BLOCKS;
    /** 旧名字，类内部用。 */
    private static final int PER_SIDE = PER_SIDE_CELLS;

    private final ChunkSitePlanner source;
    private final SiteField coarse;
    /** 与区块边界对齐后的图中心方块坐标（离玩家点的落点最多差 8 格）。 */
    private final int focusX;
    private final int focusZ;
    private final int seaLevel;
    private final int half = (COLUMNS - 1) / 2;
    /** 0 号格子的起始方块（一定是 4 的倍数，所以一格不会跨区块）。 */
    private final int baseBlockX;
    private final int baseBlockZ;

    private final boolean[] inside = new boolean[COLUMNS * COLUMNS];
    private final float[] edgeFadeTable = new float[COLUMNS * COLUMNS];
    private final long[] bornAt = new long[COLUMNS * COLUMNS];
    /**
     * 一格的数据要问粗场（平台/颜色/群系），每帧每格都要问一遍；格子按行扫，
     * 同一个区块连着 4 格，所以留<b>一个区块的缓存</b>就能把查询砍到四分之一。
     */
    private int memoChunkX = Integer.MIN_VALUE;
    private int memoChunkZ = Integer.MIN_VALUE;
    private final ChunkSitePlanner.CellInfo memo = new ChunkSitePlanner.CellInfo();

    /** 这一格所属区块的数据（带单格缓存，返回的是同一个对象，用完就丢）。 */
    private ChunkSitePlanner.CellInfo chunk(int chunkX, int chunkZ) {
        if (chunkX != memoChunkX || chunkZ != memoChunkZ) {
            memoChunkX = chunkX;
            memoChunkZ = chunkZ;
            source.probe(chunkX, chunkZ, memo);
        }
        return memo;
    }

    SiteReliefField(ChunkSitePlanner source, int siteX, int siteZ) {
        this.source = source;
        this.coarse = source.field();
        this.seaLevel = coarse.seaLevel();
        // 网格钉到区块边界上：中心取落点所在区块的中心
        this.focusX = Math.floorDiv(siteX, 16) * 16 + 8;
        this.focusZ = Math.floorDiv(siteZ, 16) * 16 + 8;
        this.baseBlockX = focusX - half * CELL_BLOCKS;
        this.baseBlockZ = focusZ - half * CELL_BLOCKS;
        for (int cj = 0; cj < COLUMNS; cj++) {
            for (int ci = 0; ci < COLUMNS; ci++) {
                double dx = ci - half;
                double dz = cj - half;
                double dist = Math.sqrt(dx * dx + dz * dz);
                int index = cj * COLUMNS + ci;
                inside[index] = dist <= half + 0.5;
                edgeFadeTable[index] = Mth.clamp((float) ((half + 0.5 - dist) / FADE_RING), 0.0F, 1.0F);
            }
        }
    }

    /** 某一格属于哪个区块。 */
    int chunkXOf(int cellX) {
        return Math.floorDiv(baseBlockX + cellX * CELL_BLOCKS, 16);
    }

    int chunkZOf(int cellY) {
        return Math.floorDiv(baseBlockZ + cellY * CELL_BLOCKS, 16);
    }

    /** 某一格起始方块坐标（世界渲染阶段要按方块画，网格和区块边界对齐过所以是整除的）。 */
    int blockXOf(int cellX) {
        return baseBlockX + cellX * CELL_BLOCKS;
    }

    int blockZOf(int cellY) {
        return baseBlockZ + cellY * CELL_BLOCKS;
    }

    /** 某个区块在这一场里的起始格子（不在窗口里返回 -1）。 */
    int cellXOfChunk(int chunkX) {
        int cell = (chunkX * 16 - baseBlockX) / CELL_BLOCKS;
        return cell >= 0 && cell < COLUMNS ? cell : -1;
    }

    int cellZOfChunk(int chunkZ) {
        int cell = (chunkZ * 16 - baseBlockZ) / CELL_BLOCKS;
        return cell >= 0 && cell < COLUMNS ? cell : -1;
    }

    /** 落点在窗口里的格子下标。 */
    int centerCellX() {
        return half;
    }

    int centerCellY() {
        return half;
    }

    /** 落点所在区块。 */
    int centerChunkX() {
        return Math.floorDiv(focusX, 16);
    }

    /**
     * 柱子确实<b>完全不透明</b>能盖住的那圈半径（方块）：再往外就是边缘渐隐圈，拿来挡真世界会透出
     * 一圈发暗的边。落成动画用它判断「画面里的地形都在这张场里了」，那一刻才把粗场换成细场。
     */
    int opaqueRadiusBlocks() {
        return (int) ((half + 0.5F - FADE_RING) * CELL_BLOCKS);
    }

    int centerChunkZ() {
        return Math.floorDiv(focusZ, 16);
    }

    // ── SiteField ───────────────────────────────────────────────────────

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
        return CELL_BLOCKS;
    }

    @Override
    public int centerX() {
        return focusX;
    }

    @Override
    public int centerZ() {
        return focusZ;
    }

    @Override
    public int seaLevel() {
        return seaLevel;
    }

    @Override
    public int minY() {
        return coarse.minY();
    }

    @Override
    public int maxY() {
        return coarse.maxY();
    }

    /** 柱子身份键：这一格属于哪个区块。数据到货不再改柱子高度，所以键也不用跟着翻。 */
    @Override
    public long columnKey(int ci, int cj) {
        int chunkX = chunkXOf(ci);
        int chunkZ = chunkZOf(cj);
        return (chunkX * 73856093L) ^ (chunkZ * 19349663L) ^ ((long) cj * COLUMNS + ci + 1);
    }

    @Override
    public boolean ready(int ci, int cj) {
        int index = cj * COLUMNS + ci;
        return inside[index] && chunk(chunkXOf(ci), chunkZOf(cj)).known;
    }

    @Override
    public long drawnAt(int ci, int cj, long now) {
        int index = cj * COLUMNS + ci;
        long born = bornAt[index];
        if (born == 0L) {
            bornAt[index] = now;
            born = now;
        }
        return born;
    }

    @Override
    public int colorAtCell(int cellX, int cellY) {
        return chunk(chunkXOf(cellX), chunkZOf(cellY)).color;
    }

    @Override
    public int biomeIndexAtCell(int cellX, int cellY) {
        return chunk(chunkXOf(cellX), chunkZOf(cellY)).biomeIndex;
    }

    /** 粗场里这个区块的地表 y（噪声预读值；到货后不再改）。 */
    @Override
    public int surfaceAtCell(int cellX, int cellY) {
        return chunk(chunkXOf(cellX), chunkZOf(cellY)).surface;
    }

    @Override
    public List<BiomeInfo> biomes() {
        return coarse.biomes();
    }

    /** 粗场一格 = 细场 16 格，进度直接换算过去。 */
    @Override
    public int progress() {
        return coarse.progress() * PER_SIDE * PER_SIDE;
    }

    @Override
    public int progressTotal() {
        return coarse.progressTotal() * PER_SIDE * PER_SIDE;
    }

    @Override
    public boolean full() {
        return coarse.full();
    }

    @Override
    public String label() {
        return "进世界前·真实起伏";
    }

    @Override
    public float defaultZoom() {
        return 1.8F;
    }

    @Override
    public boolean showsFloor() {
        return false;
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
        return edgeFadeTable[cj * COLUMNS + ci];
    }

    @Override
    public float reliefSpan() {
        return coarse.reliefSpan();
    }

    @Override
    public void easeReliefSpan(float target) {
        coarse.easeReliefSpan(target);
    }
}
