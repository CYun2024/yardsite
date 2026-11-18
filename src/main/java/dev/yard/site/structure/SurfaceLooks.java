package dev.yard.site.structure;

import dev.yard.site.YardSiteConfig;
import dev.yard.site.YardSiteLog;
import dev.yard.site.platform.ModelQuad;
import dev.yard.site.platform.Platform;
import dev.yard.site.schematic.Schematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把投影文件里的方块状态翻成"落成面表能用的面片"：<b>客户端</b>的方块模型 + 贴图。
 *
 * <h2>为什么不用方块形状</h2>
 * {@code BlockState.getShape()} 给的是碰撞/轮廓形状。原版满格方块还行，但装饰类方块经常只报
 * 一个粗形状（不少 mod 干脆报整格），真正的细节在<b>烘焙模型</b>里：栅栏和栏杆的横杆、墙的压顶、
 * 玻璃板的边、活板门的板缝。模型面还天然兼容 mod：只要方块用普通 JSON/NeoForge 模型渲染就能拿到；
 * 自己用方块实体渲染器画的（比如动态机械）拿不到，退回形状盒。
 *
 * <h2>颜色</h2>
 * 每张面取它贴图的<b>平均色</b>（跳过全透明像素），比地图色准得多；带 tint 的（树叶、草）再乘一层
 * 植被绿。贴图色按贴图名缓存，方块外观按"id|属性"缓存——同一份结构只算一次。
 *
 * <h2>为什么在后台线程</h2>
 * 选区沙盘阶段就把投影文件读了、外观算好（{@link #prewarm()}），落成动画开始时只剩按格子拼几何，
 * 不会在"确认并创建"那一下卡主线程。资源包重载后由 {@link #invalidate()} 清掉重算。
 */
public final class SurfaceLooks {

    /** 同一个状态最多被问到几千次：按 "id|属性" 缓存一份外观。 */
    private static final Map<String, SurfaceLook> LOOKS = new ConcurrentHashMap<>();

    private static final Object LOCK = new Object();
    private static volatile Schematic preparedFor;
    private static volatile SurfaceLook[] prepared;
    private static final AtomicBoolean PENDING = new AtomicBoolean();

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "yardsite-surface-look");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private SurfaceLooks() {}

    /**
     * 后台读当前投影文件并算好外观。选区沙盘一出现就调；重复调用只会在前一次做完后再补一次。
     *
     * <p>文件读取也在工作线程里（{@link SchematicStructure#get()} 已加锁），主线程那边每帧的
     * {@code plot()} 最多等这把锁，不会两处各读一遍。
     */
    public static void prewarm() {
        if (!enabled()) return;
        if (!PENDING.compareAndSet(false, true)) return;
        WORKER.execute(() -> {
            try {
                Schematic schematic = SchematicStructure.get();
                if (schematic != null) looksFor(schematic);
            } catch (Throwable broken) {
                YardSiteLog.debug("落成外观预读失败（不影响落成，退回形状盒）：" + broken);
            } finally {
                PENDING.set(false);
            }
        });
    }

    /**
     * 这份结构的全部方块外观（按调色板下标）。已算好就直接给；没算好就在当前线程算一遍。
     *
     * <p>沙盘阶段预读过了通常是一次命中；没命中时宁可卡一下也不让落成缺面。
     */
    public static SurfaceLook[] looksFor(Schematic schematic) {
        SurfaceLook[] ready = prepared;
        if (ready != null && preparedFor == schematic) return ready;
        synchronized (LOCK) {
            if (prepared != null && preparedFor == schematic) return prepared;
            long started = System.nanoTime();
            SurfaceLook[] looks = new SurfaceLook[schematic.ids.size()];
            int pieces = 0;
            for (int i = 0; i < looks.length; i++) {
                looks[i] = resolve(schematic.ids.get(i), schematic.props.get(i));
                pieces += looks[i].pieces.length;
            }
            preparedFor = schematic;
            prepared = looks;
            int totalPieces = pieces;
            int spriteCache = Platform.models().cachedSprites();
            YardSiteLog.debug(() -> "落成外观：" + looks.length + " 种状态 / " + totalPieces + " 张面片（"
                    + (System.nanoTime() - started) / 1_000_000L + " ms，贴图色 " + spriteCache + " 张）");
            return looks;
        }
    }

    /** 资源包/模型重载：贴图色和模型面都可能变了，整份重算。 */
    public static void invalidate() {
        synchronized (LOCK) {
            LOOKS.clear();
            Platform.models().invalidate();
            preparedFor = null;
            prepared = null;
        }
    }

    private static boolean enabled() {
        try {
            return YardSiteConfig.STRUCTURE_ENABLED.get() && YardSiteConfig.REVEAL_SURFACE.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    // ── 单个状态 ────────────────────────────────────────────────────────

    private static SurfaceLook resolve(String id, Map<String, String> props) {
        String key = props == null || props.isEmpty() ? id : id + "|" + props;
        SurfaceLook cached = LOOKS.get(key);
        if (cached != null) return cached;
        SurfaceLook built;
        try {
            built = resolveNow(id, props);
        } catch (Throwable broken) {
            // 一个方块解析失败不该让整栋楼没面：记一条，当"不画"
            built = SurfaceLook.empty();
        }
        LOOKS.put(key, built);
        return built;
    }

    private static SurfaceLook resolveNow(String id, Map<String, String> props) {
        ResourceLocation name = ResourceLocation.tryParse(id);
        Block block = name == null ? null : BuiltInRegistries.BLOCK.get(name);
        if (block == null) return SurfaceLook.empty();
        BlockState state = block.defaultBlockState();
        if (props != null) {
            for (Map.Entry<String, String> entry : props.entrySet()) {
                state = with(state, entry.getKey(), entry.getValue());
            }
        }
        if (state.isAir()) return SurfaceLook.empty();

        int mapColor = mapColor(state);
        List<AABB> boxes = boxes(state);
        boolean fullCube = isFullCube(boxes);
        boolean occludes = fullCube && state.canOcclude();

        List<SurfaceLook.Piece> pieces = modelPieces(state, id, mapColor);
        if (pieces.isEmpty()) pieces = boxPieces(boxes, mapColor);
        return new SurfaceLook(false, fullCube, occludes, pieces.toArray(new SurfaceLook.Piece[0]));
    }

    /**
     * 方块模型拆出来的面片。拿不到模型（缺模型、方块实体自己画、mod 抛异常）时返回空表，
     * 让调用方退回形状盒。
     */
    private static List<SurfaceLook.Piece> modelPieces(BlockState state, String id, int mapColor) {
        List<ModelQuad> quads = Platform.models().quads(state);
        if (quads.isEmpty()) return List.of();
        List<SurfaceLook.Piece> pieces = new ArrayList<>();
        for (ModelQuad quad : quads) {
            SurfaceLook.Piece piece = piece(quad, id, mapColor);
            if (piece != null) pieces.add(piece);
        }
        return pieces;
    }

    /** 一张模型四边面 → 面片；退化面（零面积）返回 {@code null}。 */
    private static SurfaceLook.Piece piece(ModelQuad quad, String id, int mapColor) {
        float[] corners = quad.corners();
        if (corners.length < 12) return null;
        for (int v = 0; v < 4; v++) {
            if (!Float.isFinite(corners[v * 3]) || !Float.isFinite(corners[v * 3 + 1])
                    || !Float.isFinite(corners[v * 3 + 2])) {
                return null;
            }
        }
        // 法线按绕序算（和 FaceBakery.calculateFacing 同一个叉积），方块的 "direction" 只是最近的轴
        float ax = corners[6] - corners[3];
        float ay = corners[7] - corners[4];
        float az = corners[8] - corners[5];
        float bx = corners[0] - corners[3];
        float by = corners[1] - corners[4];
        float bz = corners[2] - corners[5];
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-5F) return null;
        nx /= len;
        ny /= len;
        nz /= len;

        int rgb = quad.spriteColor();
        if (rgb == 0) rgb = mapColor;
        if (quad.tinted()) rgb = multiply(rgb, tint(id));

        // 非轴对齐的面（十字花草、斜撑）vanilla 本来就不做背面剔除，这里照样标成双面
        boolean doubleSided = Math.abs(nx) < 0.99F && Math.abs(ny) < 0.99F && Math.abs(nz) < 0.99F;
        return new SurfaceLook.Piece(corners, nx, ny, nz, rgb, doubleSided);
    }

    /** 形状盒的六个面，给拿不到模型的方块兜底。 */
    private static List<SurfaceLook.Piece> boxPieces(List<AABB> boxes, int rgb) {
        List<AABB> use = boxes;
        if (use.isEmpty()) {
            // 十字花草、火把这类没有"体"的：给一小簇，别让它在画面里凭空消失
            use = List.of(new AABB(0.3, 0, 0.3, 0.7, 0.55, 0.7));
        }
        List<SurfaceLook.Piece> pieces = new ArrayList<>(use.size() * 6);
        for (AABB box : use) {
            for (int face = 0; face < 6; face++) {
                pieces.add(new SurfaceLook.Piece(faceCorners(box, face),
                        FACE_NORMALS[face][0], FACE_NORMALS[face][1], FACE_NORMALS[face][2], rgb, false));
            }
        }
        return pieces;
    }

    private static final float[][] FACE_NORMALS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private static float[] faceCorners(AABB box, int face) {
        float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
        float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;
        return switch (face) {
            case 0 -> new float[]{x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0};
            case 1 -> new float[]{x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1};
            case 2 -> new float[]{x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0};
            case 3 -> new float[]{x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1};
            case 4 -> new float[]{x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1};
            default -> new float[]{x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0};
        };
    }

    // ── 方块状态小工具 ──────────────────────────────────────────────────

    private static List<AABB> boxes(BlockState state) {
        try {
            VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
            if (shape == null || shape.isEmpty()) return List.of();
            return shape.toAabbs();
        } catch (RuntimeException broken) {
            return List.of();
        }
    }

    private static boolean isFullCube(List<AABB> boxes) {
        return boxes.size() == 1 && boxes.get(0).getXsize() > 0.999
                && boxes.get(0).getYsize() > 0.999 && boxes.get(0).getZsize() > 0.999;
    }

    private static int mapColor(BlockState state) {
        try {
            MapColor color = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (color != null && color.col != 0) return color.col;
        } catch (RuntimeException ignored) {
            // 地图色拿不到就用中性灰，至少还能看出体块
        }
        return 0x9A9A9A;
    }

    private static BlockState with(BlockState state, String name, String value) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty(name);
        if (property == null) return state;
        return set(state, property, value);
    }

    private static <T extends Comparable<T>> BlockState set(BlockState state, Property<T> property,
                                                            String value) {
        return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
    }

    // ── 颜色 ────────────────────────────────────────────────────────────

    /** 带 tint 的面：树叶/草乘植被绿，水乘水色，其它原色。 */
    private static int tint(String id) {
        if (id.contains("water")) return 0x3F76E4;
        if (id.contains("leaves") || id.contains("vine") || id.contains("grass")
                || id.contains("fern") || id.contains("lily") || id.contains("azalea")) {
            return 0x91BD59;
        }
        return 0xFFFFFF;
    }

    private static int multiply(int rgb, int tint) {
        int r = ((rgb >> 16) & 255) * ((tint >> 16) & 255) / 255;
        int g = ((rgb >> 8) & 255) * ((tint >> 8) & 255) / 255;
        int b = (rgb & 255) * (tint & 255) / 255;
        return (r << 16) | (g << 8) | b;
    }
}
