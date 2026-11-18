package dev.yard.site.structure;

import dev.yard.site.YardSiteLog;
import dev.yard.site.schematic.Schematic;
import dev.yard.site.schematic.SchematicTransform;
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

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 落成动画要画的<b>面</b>：把结构里每个方块的外观摊平成一张静态表（世界坐标 + 顶点色）。
 *
 * <h2>几何从哪来</h2>
 * 优先吃 {@link SurfaceLooks} 给的<b>烘焙模型面</b>（客户端算出、能后台预读）：栅栏、栏杆、墙、
 * 玻璃板、楼梯这些方块真正的细节都在模型里，mod 方块只要用普通模型渲染也一样能出。
 * 模型拿不到时（没装对应 mod、方块实体自己画）退回形状盒，再不行给一小簇。
 *
 * <h2>为什么先排好序</h2>
 * 按面的最低点排一次，落成动画就只是"画前 N 个面"：扫到多高就画到多高，
 * 每帧不用重新挑、不用重新排，也不需要在着色器里剪裁。
 */
public final class SurfaceMesh {

    /** 每面 4 个顶点，顶点坐标 xyz 交替。 */
    public final float[] pos;
    /** 每面 4 个顶点各一个颜色（已含明暗和墙角遮蔽）。 */
    public final int[] color;
    /** 每面的法线（单位向量，count*3）：背面剔除和受光都用它。 */
    public final float[] normal;
    /** 1 = 做背面剔除；0 = 双面（十字花草这类模型）。 */
    public final byte[] cull;
    /** 每面所属方块的底面（世界 y）：扫描线扫到这里就露出这一面。 */
    public final float[] floorY;
    /** 面的数量。 */
    public final int count;
    /** 整份结构的最低点和最高点（世界 y），给扫描线定时长用。 */
    public final float lowY;
    public final float highY;

    private SurfaceMesh(float[] pos, int[] color, float[] normal, byte[] cull, float[] floorY, int count,
                        float lowY, float highY) {
        this.pos = pos;
        this.color = color;
        this.normal = normal;
        this.cull = cull;
        this.floorY = floorY;
        this.count = count;
        this.lowY = lowY;
        this.highY = highY;
    }

    /**
     * 整份几何的世界包围盒，写进 {@code out = {minX,minY,minZ,maxX,maxY,maxZ}}。
     * 开场自动取景要用它：镜头距离按"这栋楼投影多大"来定，而不是拍脑袋一个常数。
     */
    public void bounds(float[] out) {
        float x0 = Float.MAX_VALUE;
        float y0 = Float.MAX_VALUE;
        float z0 = Float.MAX_VALUE;
        float x1 = -Float.MAX_VALUE;
        float y1 = -Float.MAX_VALUE;
        float z1 = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            for (int v = 0; v < 4; v++) {
                int at = i * 12 + v * 3;
                x0 = Math.min(x0, pos[at]);
                y0 = Math.min(y0, pos[at + 1]);
                z0 = Math.min(z0, pos[at + 2]);
                x1 = Math.max(x1, pos[at]);
                y1 = Math.max(y1, pos[at + 1]);
                z1 = Math.max(z1, pos[at + 2]);
            }
        }
        if (count == 0) {
            x0 = y0 = z0 = 0F;
            x1 = y1 = z1 = 0F;
        }
        out[0] = x0;
        out[1] = y0;
        out[2] = z0;
        out[3] = x1;
        out[4] = y1;
        out[5] = z1;
    }

    public int countBelow(float y) {
        int lo = 0;
        int hi = count;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (floorY[mid] <= y) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    // ── 构建 ────────────────────────────────────────────────────────────

    /** 主光方向（和离线预览、界面里其它金色面统一：从左上前方照下来）。 */
    private static final double LX = -0.40;
    private static final double LY = 0.80;
    private static final double LZ = -0.44;

    /** 没有外观表时的兜底缓存：同一个状态在结构里会出现几千次。 */
    private static final Map<String, SurfaceLook> CACHE = new java.util.concurrent.ConcurrentHashMap<>(512);

    /**
     * 建一张面表（没有外观表时用形状盒兜底）。
     *
     * @param schematic 结构内容（局部坐标）
     * @param transform 落地朝向（旋转/镜像，和铺方块时用的是同一套）
     * @param ox oy oz  世界坐标原点
     * @return 面表；结构是空的时 {@code count == 0}
     */
    public static SurfaceMesh build(Schematic schematic, SchematicTransform transform,
                                    int ox, int oy, int oz) {
        return build(schematic, transform, ox, oy, oz, null);
    }

    /**
     * 建一张面表。
     *
     * @param looks 按调色板下标的外观（{@link SurfaceLooks} 在客户端算好）；{@code null} =
     *              这里现场用形状盒 + 地图色兜底
     */
    public static SurfaceMesh build(Schematic schematic, SchematicTransform transform,
                                    int ox, int oy, int oz, SurfaceLook[] looks) {
        long started = System.nanoTime();
        int sx = schematic.sizeX;
        int sy = schematic.sizeY;
        int sz = schematic.sizeZ;
        int volume = sx * sy * sz;

        int[] cells = new int[volume];
        boolean[] occludes = new boolean[volume];
        Arrays.fill(cells, -1);
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int index = schematic.cell(x, y, z);
                    if (index < 0) continue;
                    int at = (y * sz + z) * sx + x;
                    cells[at] = index;
                    SurfaceLook look = looks != null && index < looks.length ? looks[index] : null;
                    if (look == null) look = fallbackLook(schematic.ids.get(index), schematic.props.get(index));
                    occludes[at] = look.occludes;
                }
            }
        }

        Grow g = new Grow();
        float lowY = Float.MAX_VALUE;
        float highY = -Float.MAX_VALUE;
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int at = (y * sz + z) * sx + x;
                    int index = cells[at];
                    if (index < 0) continue;
                    SurfaceLook look = looks != null && index < looks.length ? looks[index] : null;
                    if (look == null) look = fallbackLook(schematic.ids.get(index), schematic.props.get(index));
                    if (look.skip) continue;
                    // 同一块材料的墙如果整片一个颜色，看着像塑料板；按格子给 ±3% 的亮度抖动，
                    // 换成贴图后再让墙面有点"料子"的层次（同一格永远同一个值，不会闪）
                    double grain = 0.97 + 0.06 * noise(x, y, z);
                    for (SurfaceLook.Piece piece : look.pieces) {
                        if (culledByNeighbour(piece, occludes, sx, sy, sz, x, y, z)) continue;
                        g.piece(piece, grain, occludes, sx, sy, sz, x, y, z, transform, ox, oy, oz);
                        lowY = Math.min(lowY, oy + y);
                        highY = Math.max(highY, oy + y + 1);
                    }
                }
            }
        }
        if (g.count == 0) {
            return new SurfaceMesh(new float[0], new int[0], new float[0], new byte[0], new float[0],
                    0, oy, oy + 1);
        }
        SurfaceMesh mesh = g.toMesh(Math.max(lowY, oy), Math.max(highY, oy + 1));
        YardSiteLog.debug("落成面表：" + mesh.count + " 个面（" + schematic.solidCount + " 格，"
                + (System.nanoTime() - started) / 1_000_000L + " ms）");
        return mesh;
    }

    /** 稳定的 0..1 噪声（同一格永远同一个值）。 */
    private static double noise(int x, int y, int z) {
        int h = x * 73856093 ^ y * 19349663 ^ z * 83492791;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return (h & 0xFFFF) / 65535.0;
    }

    /**
     * 贴边且隔壁是"会遮挡"的满格方块：这张脸永远看不见，不提交。
     *
     * <p>模型面不一定正好落在格边界上（栏杆横杆就在格子里），只有整张面都贴在朝外那一面
     * 才和邻居的方块面共面，剔掉才安全。
     */
    private static boolean culledByNeighbour(SurfaceLook.Piece piece, boolean[] occludes,
                                             int sx, int sy, int sz, int x, int y, int z) {
        float nx = piece.nx();
        float ny = piece.ny();
        float nz = piece.nz();
        int axis;
        float side;
        if (Math.abs(nx) > 0.99F) {
            axis = 0;
            side = nx;
        } else if (Math.abs(ny) > 0.99F) {
            axis = 1;
            side = ny;
        } else if (Math.abs(nz) > 0.99F) {
            axis = 2;
            side = nz;
        } else {
            return false;
        }
        float plane = side > 0 ? 1.0F : 0.0F;
        float[] corners = piece.corners();
        for (int v = 0; v < 4; v++) {
            if (Math.abs(corners[v * 3 + axis] - plane) > 1e-3F) return false;
        }
        int dx = axis == 0 ? (side > 0 ? 1 : -1) : 0;
        int dy = axis == 1 ? (side > 0 ? 1 : -1) : 0;
        int dz = axis == 2 ? (side > 0 ? 1 : -1) : 0;
        int nxCell = x + dx;
        int nyCell = y + dy;
        int nzCell = z + dz;
        if (nxCell < 0 || nyCell < 0 || nzCell < 0 || nxCell >= sx || nyCell >= sy || nzCell >= sz) {
            return false;
        }
        return occludes[(nyCell * sz + nzCell) * sx + nxCell];
    }

    // ── 兜底外观（没走客户端外观表时）────────────────────────────────────

    private static SurfaceLook fallbackLook(String id, Map<String, String> props) {
        String key = props == null || props.isEmpty() ? id : id + "|" + props;
        SurfaceLook cached = CACHE.get(key);
        if (cached != null) return cached;
        SurfaceLook built = fallbackLookNow(id, props);
        CACHE.put(key, built);
        return built;
    }

    private static SurfaceLook fallbackLookNow(String id, Map<String, String> props) {
        BlockState state = null;
        try {
            ResourceLocation key = ResourceLocation.tryParse(id);
            Block block = key == null ? null : BuiltInRegistries.BLOCK.get(key);
            if (block != null) {
                state = block.defaultBlockState();
                if (props != null) {
                    for (Map.Entry<String, String> e : props.entrySet()) {
                        state = with(state, e.getKey(), e.getValue());
                    }
                }
                if (state.isAir()) state = null;
            }
        } catch (RuntimeException broken) {
            state = null;
        }
        if (state == null) return SurfaceLook.empty();

        int rgb = 0x9A9A9A;
        List<AABB> boxes = List.of();
        try {
            MapColor color = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (color != null && color.col != 0) rgb = color.col;
            VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
            if (!shape.isEmpty()) boxes = shape.toAabbs();
        } catch (RuntimeException broken) {
            boxes = List.of();
        }
        if (boxes.isEmpty()) {
            boxes = List.of(new AABB(0.3, 0, 0.3, 0.7, 0.55, 0.7));
        }
        boolean fullCube = boxes.size() == 1 && boxes.get(0).getXsize() > 0.999
                && boxes.get(0).getYsize() > 0.999 && boxes.get(0).getZsize() > 0.999;
        boolean occludes = fullCube && state.canOcclude();
        SurfaceLook.Piece[] pieces = new SurfaceLook.Piece[boxes.size() * 6];
        int n = 0;
        for (AABB box : boxes) {
            for (int face = 0; face < 6; face++) {
                pieces[n++] = new SurfaceLook.Piece(corners(box, face),
                        FACE_NORMALS[face][0], FACE_NORMALS[face][1], FACE_NORMALS[face][2], rgb, false);
            }
        }
        return new SurfaceLook(false, fullCube, occludes, pieces);
    }

    private static final float[][] FACE_NORMALS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private static float[] corners(AABB box, int face) {
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

    private static BlockState with(BlockState state, String name, String value) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty(name);
        if (property == null) return state;
        return set(state, property, value);
    }

    private static <T extends Comparable<T>> BlockState set(BlockState state, Property<T> property,
                                                            String value) {
        return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
    }

    // ── 拼几何 ──────────────────────────────────────────────────────────

    /** 会长的数组：面数事先不知道，先攒着最后一次性排序。 */
    private static final class Grow {
        float[] pos = new float[6 * 4 * 3 * 64];
        int[] color = new int[6 * 4 * 64];
        float[] normal = new float[6 * 3 * 64];
        byte[] cull = new byte[6 * 64];
        float[] floorY = new float[6 * 64];
        int count;

        void piece(SurfaceLook.Piece piece, double grain, boolean[] occludes,
                   int sx, int sy, int sz, int x, int y, int z,
                   SchematicTransform t, int ox, int oy, int oz) {
            if (count >= cull.length) grow();
            float[] c = piece.corners();
            // 法线跟着结构一起转（镜像/90° 旋转都是仿射，位移照转即可）
            float nx = piece.nx();
            float ny = piece.ny();
            float nz = piece.nz();
            float x0 = t.x(0.5F, 0.5F, sx, sz);
            float z0 = t.z(0.5F, 0.5F, sx, sz);
            float wnx = t.x(0.5F + nx * 0.5F, 0.5F + nz * 0.5F, sx, sz) - x0;
            float wnz = t.z(0.5F + nx * 0.5F, 0.5F + nz * 0.5F, sx, sz) - z0;
            float wny = ny;

            int at = count * 12;
            int rgb = piece.rgb();
            for (int i = 0; i < 4; i++) {
                float wx = x + c[i * 3];
                float wy = y + c[i * 3 + 1];
                float wz = z + c[i * 3 + 2];
                pos[at + i * 3] = ox + t.x(wx, wz, sx, sz);
                pos[at + i * 3 + 1] = oy + wy;
                pos[at + i * 3 + 2] = oz + t.z(wx, wz, sx, sz);
                color[count * 4 + i] = shade(rgb, grain * light(wnx, wny, wnz,
                        c[i * 3], c[i * 3 + 1], c[i * 3 + 2], piece.doubleSided(),
                        occludes, sx, sy, sz, x, y, z));
            }
            normal[count * 3] = wnx;
            normal[count * 3 + 1] = wny;
            normal[count * 3 + 2] = wnz;
            cull[count] = (byte) (piece.doubleSided() ? 0 : 1);
            floorY[count] = oy + y;
            count++;
        }

        void grow() {
            int n = cull.length;
            pos = Arrays.copyOf(pos, pos.length * 2);
            color = Arrays.copyOf(color, color.length * 2);
            normal = Arrays.copyOf(normal, normal.length * 2);
            cull = Arrays.copyOf(cull, n * 2);
            floorY = Arrays.copyOf(floorY, n * 2);
        }

        SurfaceMesh toMesh(float lowY, float highY) {
            Integer[] order = new Integer[count];
            for (int i = 0; i < count; i++) order[i] = i;
            Arrays.sort(order, Comparator.comparingDouble(a -> floorY[a]));
            float[] p = new float[count * 12];
            int[] col = new int[count * 4];
            float[] nrm = new float[count * 3];
            byte[] cl = new byte[count];
            float[] fy = new float[count];
            for (int i = 0; i < count; i++) {
                int src = order[i];
                System.arraycopy(pos, src * 12, p, i * 12, 12);
                System.arraycopy(color, src * 4, col, i * 4, 4);
                System.arraycopy(normal, src * 3, nrm, i * 3, 3);
                cl[i] = cull[src];
                fy[i] = floorY[src];
            }
            return new SurfaceMesh(p, col, nrm, cl, fy, count, lowY, highY);
        }

        /**
         * 面亮度：主光 + 墙角遮蔽（顶点级，块级近似），返回的是<b>倍率</b>不是颜色。
         */
        private static double light(float nx, float ny, float nz,
                                    float cx, float cy, float cz, boolean doubleSided,
                                    boolean[] occludes, int sx, int sy, int sz,
                                    int x, int y, int z) {
            double dot = nx * LX + ny * LY + nz * LZ;
            double lambert = doubleSided ? 0.35 + 0.65 * Math.abs(dot) : Math.max(0, dot);
            double ao = occlusion(nx, ny, nz, cx, cy, cz, occludes, sx, sy, sz, x, y, z);
            return (0.45 + 0.55 * lambert) * ao;
        }

        /** 顶点外侧那三个格子有没有实心方块：墙角、窗洞、台阶的层次全靠它。 */
        private static double occlusion(float nx, float ny, float nz,
                                        float cx, float cy, float cz,
                                        boolean[] occludes, int sx, int sy, int sz,
                                        int x, int y, int z) {
            int axis;
            int t1;
            int t2;
            if (Math.abs(nx) >= Math.abs(ny) && Math.abs(nx) >= Math.abs(nz)) {
                axis = 0;
                t1 = 1;
                t2 = 2;
            } else if (Math.abs(ny) >= Math.abs(nz)) {
                axis = 1;
                t1 = 0;
                t2 = 2;
            } else {
                axis = 2;
                t1 = 0;
                t2 = 1;
            }
            float[] corner = {cx, cy, cz};
            int s1 = corner[t1] >= 0.5F ? 1 : -1;
            int s2 = corner[t2] >= 0.5F ? 1 : -1;
            int[] d1 = {0, 0, 0};
            d1[t1] = s1;
            int[] d2 = {0, 0, 0};
            d2[t2] = s2;
            int[] n = {0, 0, 0};
            n[axis] = (axis == 0 ? nx : axis == 1 ? ny : nz) >= 0 ? 1 : -1;
            int bx = x + n[0];
            int by = y + n[1];
            int bz = z + n[2];
            boolean a = solid(occludes, sx, sy, sz, bx + d1[0], by + d1[1], bz + d1[2]);
            boolean b = solid(occludes, sx, sy, sz, bx + d2[0], by + d2[1], bz + d2[2]);
            boolean c = solid(occludes, sx, sy, sz, bx + d1[0] + d2[0], by + d1[1] + d2[1],
                    bz + d1[2] + d2[2]);
            if (a && b) return 0.55;
            int hits = (a ? 1 : 0) + (b ? 1 : 0) + (c ? 1 : 0);
            return switch (hits) {
                case 0 -> 1.0;
                case 1 -> 0.84;
                case 2 -> 0.70;
                default -> 0.60;
            };
        }

        private static boolean solid(boolean[] occludes, int sx, int sy, int sz, int x, int y, int z) {
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return false;
            return occludes[(y * sz + z) * sx + x];
        }
    }

    private static int shade(int rgb, double factor) {
        int r = clamp((int) (((rgb >> 16) & 255) * factor));
        int g = clamp((int) (((rgb >> 8) & 255) * factor));
        int b = clamp((int) ((rgb & 255) * factor));
        return (r << 16) | (g << 8) | b;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** 调试用：一张面表的数据量（日志/界面显示）。 */
    public String describe() {
        return count + " 面 / " + (count * 4) + " 顶点";
    }

    /** 空表（没开结构模式时用）。 */
    public static SurfaceMesh empty() {
        return new SurfaceMesh(new float[0], new int[0], new float[0], new byte[0], new float[0], 0, 0, 1);
    }
}
