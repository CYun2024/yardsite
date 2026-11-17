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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 落成动画要画的<b>面</b>：把结构里每个方块的可见面摊平成一张静态表（世界坐标 + 顶点色）。
 *
 * <h2>为什么不再画线</h2>
 * 线框那套（{@code SchematicHiddenLines}）画的是"棱"。一栋小房子里 40% 的方块是薄片
 * （楼梯、半砖、玻璃板、活板门、挂牌），每块都要出 6~12 个盒子、每个盒子 12 条棱，
 * 于是屏幕上是一团谁压着谁的线——看着不像房子，也分不出哪条线是墙、哪条是栏杆。
 * 换成"面"之后：有体积就有块，装饰自然在里面，明暗自己说明结构，一条线都不用画。
 *
 * <h2>几何从哪来</h2>
 * 客户端有真正的 {@link BlockState}，所以形状直接问 {@code state.getShape(...)}：
 * 楼梯的 inner/outer、栅栏和墙的连接、玻璃板的朝向、花草的高度，全都是原版给的，
 * 不需要维护一张手写形状表（{@code BlockShapes} 那张表的楼梯朝向就是自己写反的）。
 * 颜色用 {@link BlockState#getMapColor}——一个方块一个色块，不读贴图，够看出材料和颜色关系。
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
    /** 每面的朝向：0=+X 1=-X 2=+Y 3=-Y 4=+Z 5=-Z（背面剔除用）。 */
    public final byte[] normal;
    /** 每面所属方块的底面（世界 y）：扫描线扫到这里就露出这一面。 */
    public final float[] floorY;
    /** 面的数量。 */
    public final int count;
    /** 整份结构的最低点和最高点（世界 y），给扫描线定时长用。 */
    public final float lowY;
    public final float highY;

    private SurfaceMesh(float[] pos, int[] color, byte[] normal, float[] floorY, int count,
                        float lowY, float highY) {
        this.pos = pos;
        this.color = color;
        this.normal = normal;
        this.floorY = floorY;
        this.count = count;
        this.lowY = lowY;
        this.highY = highY;
    }

    /**
     * 按扫描高度找到"画到这里为止"的面数（{@link #floorY} 已按升序排好）。
     *
     * @param y 扫描线当前高度（世界 y），比它高的面还不该露出来
     */
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

    private static final double[][] NORMALS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    /** 方块状态 → 形状/颜色的解析缓存：同一份结构里同一个状态会出现几千次。 */
    private static final Map<String, Resolved> CACHE = new java.util.HashMap<>(512);

    private record Resolved(BlockState state, int rgb, List<AABB> boxes, boolean fullCube, boolean skip) {}

    /**
     * 建一张面表。
     *
     * @param schematic 结构内容（局部坐标）
     * @param transform 落地朝向（旋转/镜像，和铺方块时用的是同一套）
     * @param ox oy oz  世界坐标原点
     * @return 面表；结构是空的时 {@code count == 0}
     */
    public static SurfaceMesh build(Schematic schematic, SchematicTransform transform,
                                    int ox, int oy, int oz) {
        long started = System.nanoTime();
        int sx = schematic.sizeX;
        int sy = schematic.sizeY;
        int sz = schematic.sizeZ;

        int[] cells = new int[sx * sy * sz];
        boolean[] full = new boolean[sx * sy * sz];
        Arrays.fill(cells, -1);
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int index = schematic.cell(x, y, z);
                    if (index < 0) continue;
                    int at = (y * sz + z) * sx + x;
                    cells[at] = index;
                    full[at] = resolve(schematic.ids.get(index), schematic.props.get(index)).fullCube;
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
                    Resolved r = resolve(schematic.ids.get(index), schematic.props.get(index));
                    if (r.skip) continue;
                    // 同一块材料的墙如果整片一个颜色，看着像塑料板；按格子给 ±3% 的亮度抖动，
                    // 换成贴图之前先让墙面有点"料子"的层次（同一格永远同一个值，不会闪）
                    int rgb = shade(r.rgb, 0.97 + 0.06 * noise(x, y, z));
                    for (AABB box : r.boxes) {
                        for (int f = 0; f < 6; f++) {
                            // 贴在世界边界上的那一面，如果隔壁是满格方块就永远看不见：不提交
                            if (touches(box, f) && neighbourFull(full, sx, sy, sz, x, y, z, f)) continue;
                            g.face(box, f, rgb, x, y, z, cells, full, sx, sy, sz, transform, ox, oy, oz);
                            lowY = Math.min(lowY, oy + y);
                            highY = Math.max(highY, oy + y + 1);
                        }
                    }
                }
            }
        }
        if (g.count == 0) {
            return new SurfaceMesh(new float[0], new int[0], new byte[0], new float[0], 0, oy, oy + 1);
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

    /** 面贴在这一格的第 f 个边界上（0/1 而不是 0.37 这种内部面）。 */
    private static boolean touches(AABB box, int f) {
        return switch (f) {
            case 0 -> box.maxX >= 0.9999;
            case 1 -> box.minX <= 0.0001;
            case 2 -> box.maxY >= 0.9999;
            case 3 -> box.minY <= 0.0001;
            case 4 -> box.maxZ >= 0.9999;
            default -> box.minZ <= 0.0001;
        };
    }

    private static boolean neighbourFull(boolean[] full, int sx, int sy, int sz,
                                         int x, int y, int z, int f) {
        int nx = x + (f == 0 ? 1 : f == 1 ? -1 : 0);
        int ny = y + (f == 2 ? 1 : f == 3 ? -1 : 0);
        int nz = z + (f == 4 ? 1 : f == 5 ? -1 : 0);
        if (nx < 0 || ny < 0 || nz < 0 || nx >= sx || ny >= sy || nz >= sz) return false;
        return full[(ny * sz + nz) * sx + nx];
    }

    private static Resolved resolve(String id, Map<String, String> props) {
        String key = props == null || props.isEmpty() ? id : id + "|" + props;
        Resolved cached = CACHE.get(key);
        if (cached != null) return cached;
        Resolved built = resolveNow(id, props);
        CACHE.put(key, built);
        return built;
    }

    private static Resolved resolveNow(String id, Map<String, String> props) {
        int rgb = 0x9A9A9A;
        BlockState state = null;
        boolean skip = false;
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
                if (state.isAir()) {
                    // 认不出来的方块（另一套 mod 的方块没装）：不画，铺下去也会是空的
                    skip = true;
                }
                net.minecraft.world.level.material.MapColor mapColor =
                        state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                if (mapColor != null && mapColor.col != 0) rgb = mapColor.col;
            }
        } catch (RuntimeException broken) {
            state = null;
        }
        List<AABB> boxes = List.of();
        if (state != null) {
            try {
                VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                if (!shape.isEmpty()) boxes = shape.toAabbs();
            } catch (RuntimeException broken) {
                boxes = List.of();
            }
        }
        if (boxes.isEmpty()) {
            // 十字模型的花草、火把这类没有"体"的：给一小簇，别让它在画面里凭空消失
            boxes = List.of(new AABB(0.3, 0, 0.3, 0.7, 0.55, 0.7));
        }
        boolean fullCube = boxes.size() == 1 && boxes.get(0).getXsize() > 0.999
                && boxes.get(0).getYsize() > 0.999 && boxes.get(0).getZsize() > 0.999;
        return new Resolved(state, rgb, boxes, fullCube, skip);
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

    /** 会长的数组：面数事先不知道，先攒着最后一次性排序。 */
    private static final class Grow {
        float[] pos = new float[6 * 4 * 3 * 64];
        int[] color = new int[6 * 4 * 64];
        byte[] normal = new byte[6 * 64];
        float[] floorY = new float[6 * 64];
        int count;

        void face(AABB box, int f, int rgb, int x, int y, int z,
                  int[] cells, boolean[] full, int sx, int sy, int sz,
                  SchematicTransform t, int ox, int oy, int oz) {
            if (count >= normal.length) grow();
            int at = count * 12;
            // 面的四个角（局部 0..1），按法线逆时针
            float[][] c = corners(box, f);
            for (int i = 0; i < 4; i++) {
                float wx = x + c[i][0];
                float wy = y + c[i][1];
                float wz = z + c[i][2];
                pos[at + i * 3] = ox + t.x(wx, wz, sx, sz);
                pos[at + i * 3 + 1] = oy + wy;
                pos[at + i * 3 + 2] = oz + t.z(wx, wz, sx, sz);
                color[count * 4 + i] = shade(rgb, light(f, c[i], box, cells, full, sx, sy, sz, x, y, z));
            }
            normal[count] = (byte) f;
            floorY[count] = oy + y;
            count++;
        }

        void grow() {
            int n = normal.length;
            pos = Arrays.copyOf(pos, pos.length * 2);
            color = Arrays.copyOf(color, color.length * 2);
            normal = Arrays.copyOf(normal, n * 2);
            floorY = Arrays.copyOf(floorY, n * 2);
        }

        SurfaceMesh toMesh(float lowY, float highY) {
            Integer[] order = new Integer[count];
            for (int i = 0; i < count; i++) order[i] = i;
            Arrays.sort(order, Comparator.comparingDouble(a -> floorY[a]));
            float[] p = new float[count * 12];
            int[] col = new int[count * 4];
            byte[] nrm = new byte[count];
            float[] fy = new float[count];
            for (int i = 0; i < count; i++) {
                int src = order[i];
                System.arraycopy(pos, src * 12, p, i * 12, 12);
                System.arraycopy(color, src * 4, col, i * 4, 4);
                nrm[i] = normal[src];
                fy[i] = floorY[src];
            }
            return new SurfaceMesh(p, col, nrm, fy, count, lowY, highY);
        }

        private static float[][] corners(AABB box, int f) {
            float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
            float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;
            return switch (f) {
                case 0 -> new float[][]{{x1, y0, z0}, {x1, y0, z1}, {x1, y1, z1}, {x1, y1, z0}};
                case 1 -> new float[][]{{x0, y0, z1}, {x0, y0, z0}, {x0, y1, z0}, {x0, y1, z1}};
                case 2 -> new float[][]{{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}};
                case 3 -> new float[][]{{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}};
                case 4 -> new float[][]{{x1, y0, z1}, {x0, y0, z1}, {x0, y1, z1}, {x1, y1, z1}};
                default -> new float[][]{{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}};
            };
        }

        /**
         * 面亮度：主光 + 墙角遮蔽（顶点级，块级近似），返回的是<b>倍率</b>不是颜色。
         *
         * <p>（上一版这里返回的是 {@code shade(0xFFFFFF, …)}——一个 0xFFFFFF 附近的金白色。
         * 调用方 {@code shade(rgb, factor)} 拿它当倍率去乘，每个通道都乘了一千六百万倍、钳成 255，
         * 于是 D 阶段整栋楼全白。土色/木头全被冲掉，只有贴图里本来就极绿的草因为……其实草也白了，
         * 作者看到的绿是外面的真地形。）
         */
        private static double light(int f, float[] corner, AABB box,
                                    int[] cells, boolean[] full, int sx, int sy, int sz,
                                    int x, int y, int z) {
            double[] n = NORMALS[f];
            double lambert = Math.max(0, n[0] * LX + n[1] * LY + n[2] * LZ);
            double ao = occlusion(f, corner, box, full, sx, sy, sz, x, y, z);
            return (0.45 + 0.55 * lambert) * ao;
        }

        /** 顶点外侧那三个格子有没有实心方块：墙角、窗洞、台阶的层次全靠它。 */
        private static double occlusion(int f, float[] corner, AABB box,
                                        boolean[] full, int sx, int sy, int sz,
                                        int x, int y, int z) {
            double[] n = NORMALS[f];
            // 面内两个切向：±X 面是 (y,z)，±Y 面是 (x,z)，±Z 面是 (x,y)
            int t1 = n[0] != 0 ? 1 : 0;
            int t2 = (f == 4 || f == 5) ? 1 : 2;
            float cx = (float) (box.minX + box.maxX) * 0.5F;
            float cy = (float) (box.minY + box.maxY) * 0.5F;
            float cz = (float) (box.minZ + box.maxZ) * 0.5F;
            float[] mid = {cx, cy, cz};
            int s1 = corner[t1] >= mid[t1] ? 1 : -1;
            int s2 = corner[t2] >= mid[t2] ? 1 : -1;
            int[] d1 = {0, 0, 0};
            d1[t1] = s1;
            int[] d2 = {0, 0, 0};
            d2[t2] = s2;
            int bx = x + (int) Math.round(n[0]);
            int by = y + (int) Math.round(n[1]);
            int bz = z + (int) Math.round(n[2]);
            boolean a = solid(full, sx, sy, sz, bx + d1[0], by + d1[1], bz + d1[2]);
            boolean b = solid(full, sx, sy, sz, bx + d2[0], by + d2[1], bz + d2[2]);
            boolean c = solid(full, sx, sy, sz, bx + d1[0] + d2[0], by + d1[1] + d2[1],
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

        private static boolean solid(boolean[] full, int sx, int sy, int sz, int x, int y, int z) {
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return false;
            return full[(y * sz + z) * sx + x];
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
        return new SurfaceMesh(new float[0], new int[0], new byte[0], new float[0], 0, 0, 1);
    }
}
