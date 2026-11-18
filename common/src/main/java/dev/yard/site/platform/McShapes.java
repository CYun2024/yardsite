package dev.yard.site.platform;

import dev.yard.site.YardSiteLog;
import dev.yard.site.schematic.BlockShapes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 形状来源：先问 Minecraft 的 {@link BlockState#getShape}，形状是整格/为空时再借客户端模型推盒子。
 *
 * <h2>为什么两步</h2>
 * {@code getShape} 对<b>照常写形状</b>的方块（原版的、mod 里继承原版方块类的）最准，而且服务端也能用；
 * 但装饰类 mod 经常把碰撞形状直接报成整格、或者干脆没有体积——它们的视觉只存在模型里，
 * 只靠形状会让 C 阶段画成一整块。第二步用 {@link ModelAccess#quads} 把模型的面<b>体素化</b>
 * （1/16 网格）再合并成盒子：家具、窗框这类简单模型出来的形状和 D 阶段基本一致。
 *
 * <p>模型这一步只在客户端做（服务端没有烘焙模型），而且只对"形状不可用"的方块触发；
 * 普通满格方块模型也是满格，直接排除，不会退化成每面一块薄板。
 *
 * <p>拿不到任何几何时返回 {@code null}，交回 {@code BlockShapes} 的手写表。
 */
public final class McShapes implements BlockShapes.ShapeSource {

    public static final McShapes INSTANCE = new McShapes();

    /** 模型体素化的分辨率：1/16 格，和原版形状同一档。 */
    private static final int GRID = 16;
    /** 合并后超过这么多盒子就退回整体包围盒（形状够用即可，别让消隐的每格盒子爆炸）。 */
    private static final int MAX_MODEL_BOXES = 96;

    /** 每个方块只报一次（诊断开关打开时）。 */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private McShapes() {}

    @Override
    public float[] boxes(String id, Map<String, String> props) {
        ResourceLocation name = ResourceLocation.tryParse(id);
        if (name == null) return null;
        Block block = BuiltInRegistries.BLOCK.get(name);
        if (block == null) {
            report("未注册：" + id);
            return null;
        }

        BlockState state = block.defaultBlockState();
        if (props != null) {
            for (Map.Entry<String, String> entry : props.entrySet()) {
                state = with(state, entry.getKey(), entry.getValue());
            }
        }

        float[] shape = shapeBoxes(state, id);
        boolean coarse = shape == null || hasFullBox(shape);
        if (shape != null && !coarse) {
            reportShape(id, shape);
            return shape;
        }
        String reason = shape == null ? "形状为空/越界" : "形状含整格";
        float[] model = modelBoxes(state, id, reason);
        if (model != null && !isFullOnly(model)) {
            return model;
        }
        if (shape != null) {
            reportShape(id, shape);
            return shape;
        }
        report("没有可用形状：" + id);
        return null;
    }

    /**
     * {@code getShape} → 盒子。越出单元格的形状直接判为不可信返回 {@code null}：
     * 有的 mod 把家具形状按"跨两格"或负坐标写（yarddecor 的沙发就是 y=-1..0、z 跨 3 格），
     * 收下来会在线框里变成一个比方块还大的盒子；越界的不如交给模型体素。
     */
    private static float[] shapeBoxes(BlockState state, String id) {
        VoxelShape shape;
        try {
            shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
        } catch (RuntimeException | LinkageError broken) {
            return null;
        }
        if (shape == null || shape.isEmpty()) return null;
        List<AABB> boxes = shape.toAabbs();
        if (boxes.isEmpty()) return null;
        final float slack = 0.02F;                 // 允许一点点溢出（1/32 格），再多就不像"这一格的形状"了
        float[] out = new float[boxes.size() * 6];
        int at = 0;
        for (AABB box : boxes) {
            if (box.minX < -slack || box.minY < -slack || box.minZ < -slack
                    || box.maxX > 1F + slack || box.maxY > 1F + slack || box.maxZ > 1F + slack) {
                report("形状越出单元格：" + id);
                return null;
            }
            out[at++] = clamp01((float) box.minX);
            out[at++] = clamp01((float) box.minY);
            out[at++] = clamp01((float) box.minZ);
            out[at++] = clamp01((float) box.maxX);
            out[at++] = clamp01((float) box.maxY);
            out[at++] = clamp01((float) box.maxZ);
        }
        return out;
    }

    private static float clamp01(float value) {
        return Math.max(0F, Math.min(1F, value));
    }

    private static boolean isFullOnly(float[] boxes) {
        return boxes.length == 6 && isFullBox(boxes, 0);
    }

    /**
     * 形状里有没有一个<b>整格</b>盒子。家具类 mod 常用 {@code Shapes.or(Shapes.block(), 细节形状)}
     * 做碰撞（yarddecor 的沙发就是这样），只看"是不是单一整格"会把它当成有细节的形状收下，
     * C 阶段于是画成整个方块。
     */
    private static boolean hasFullBox(float[] boxes) {
        for (int i = 0; i + 5 < boxes.length; i += 6) {
            if (isFullBox(boxes, i)) return true;
        }
        return false;
    }

    private static boolean isFullBox(float[] boxes, int at) {
        return boxes[at] < 0.01F && boxes[at + 1] < 0.01F && boxes[at + 2] < 0.01F
                && boxes[at + 3] > 0.99F && boxes[at + 4] > 0.99F && boxes[at + 5] > 0.99F;
    }

    // ── 模型 → 体素盒 ───────────────────────────────────────────────────

    private static float[] modelBoxes(BlockState state, String id, String reason) {
        if (!Platform.isClient()) return null;
        List<ModelQuad> quads;
        try {
            quads = Platform.models().quads(state);
        } catch (RuntimeException | LinkageError broken) {
            return null;
        }
        if (quads.isEmpty()) {
            report("没有模型：" + id);
            return null;
        }
        if (quads.size() > 2048) {
            report("模型面数太多（" + quads.size() + "）：" + id);
            return null;
        }

        float minX = 1F;
        float minY = 1F;
        float minZ = 1F;
        float maxX = 0F;
        float maxY = 0F;
        float maxZ = 0F;
        for (ModelQuad quad : quads) {
            float[] c = quad.corners();
            for (int v = 0; v < 4; v++) {
                minX = Math.min(minX, c[v * 3]);
                minY = Math.min(minY, c[v * 3 + 1]);
                minZ = Math.min(minZ, c[v * 3 + 2]);
                maxX = Math.max(maxX, c[v * 3]);
                maxY = Math.max(maxY, c[v * 3 + 1]);
                maxZ = Math.max(maxZ, c[v * 3 + 2]);
            }
        }
        // 模型占满一格而且面数很少：就是一个普通满格方块（形状那条路已经给了整格），不要体素化成壳
        if (maxX - minX > 0.99F && maxY - minY > 0.99F && maxZ - minZ > 0.99F && quads.size() <= 8) {
            return null;
        }
        // 模型比一格大（家具常见）：只取落在这一格里的那部分，越界的部分会切掉
        boolean oversized = minX < -0.01F || minY < -0.01F || minZ < -0.01F
                || maxX > 1.01F || maxY > 1.01F || maxZ > 1.01F;

        boolean[] grid = new boolean[GRID * GRID * GRID];
        for (ModelQuad quad : quads) voxelize(quad.corners(), grid);
        float[] merged = mergeGrid(grid);
        if (merged == null) {
            report("模型体素为空：" + id);
            return null;
        }
        String bounds = " 范围 x " + fmt(minX) + ".." + fmt(maxX) + "，y " + fmt(minY) + ".." + fmt(maxY)
                + "，z " + fmt(minZ) + ".." + fmt(maxZ) + (oversized ? "（超出单元格，已裁掉）" : "");
        if (merged.length / 6 > MAX_MODEL_BOXES) {
            report("模型太碎，退回包围盒：" + id + "（" + reason + "）" + bounds);
            float[] fallback = {clamp01(minX), clamp01(minY), clamp01(minZ),
                    clamp01(maxX), clamp01(maxY), clamp01(maxZ)};
            if (fallback[3] - fallback[0] < 0.01F || fallback[4] - fallback[1] < 0.01F
                    || fallback[5] - fallback[2] < 0.01F) {
                return null;
            }
            return fallback;
        }
        report("模型体素：" + id + "（" + reason + "）→ " + merged.length / 6 + " 盒" + bounds);
        return merged;
    }

    /** 把一张面画进 1/{@link #GRID} 的占用网格。轴对齐的面只占一层，斜的面按到平面的距离画。 */
    private static void voxelize(float[] c, boolean[] grid) {
        float ax = c[3] - c[0];
        float ay = c[4] - c[1];
        float az = c[5] - c[2];
        float bx = c[6] - c[0];
        float by = c[7] - c[1];
        float bz = c[8] - c[2];
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1.0E-7F) return;
        nx /= len;
        ny /= len;
        nz /= len;

        float minX = Math.min(Math.min(c[0], c[3]), Math.min(c[6], c[9]));
        float minY = Math.min(Math.min(c[1], c[4]), Math.min(c[7], c[10]));
        float minZ = Math.min(Math.min(c[2], c[5]), Math.min(c[8], c[11]));
        float maxX = Math.max(Math.max(c[0], c[3]), Math.max(c[6], c[9]));
        float maxY = Math.max(Math.max(c[1], c[4]), Math.max(c[7], c[10]));
        float maxZ = Math.max(Math.max(c[2], c[5]), Math.max(c[8], c[11]));

        if (Math.abs(nx) > 0.99F) {
            int layer = layerOf(c[0]);
            fillPlane(grid, 0, layer, minY, maxY, minZ, maxZ);
        } else if (Math.abs(ny) > 0.99F) {
            int layer = layerOf(c[1]);
            fillPlane(grid, 1, layer, minX, maxX, minZ, maxZ);
        } else if (Math.abs(nz) > 0.99F) {
            int layer = layerOf(c[2]);
            fillPlane(grid, 2, layer, minX, maxX, minY, maxY);
        } else {
            // 斜的面：取离平面足够近、且落在四边形内部的格子中心
            float limit = 0.75F / GRID;
            int x0 = Math.max(0, (int) Math.floor(minX * GRID));
            int x1 = Math.min(GRID - 1, (int) Math.ceil(maxX * GRID));
            int y0 = Math.max(0, (int) Math.floor(minY * GRID));
            int y1 = Math.min(GRID - 1, (int) Math.ceil(maxY * GRID));
            int z0 = Math.max(0, (int) Math.floor(minZ * GRID));
            int z1 = Math.min(GRID - 1, (int) Math.ceil(maxZ * GRID));
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        float px = (x + 0.5F) / GRID;
                        float py = (y + 0.5F) / GRID;
                        float pz = (z + 0.5F) / GRID;
                        float dist = (px - c[0]) * nx + (py - c[1]) * ny + (pz - c[2]) * nz;
                        if (Math.abs(dist) > limit) continue;
                        float qx = px - dist * nx;
                        float qy = py - dist * ny;
                        float qz = pz - dist * nz;
                        if (inQuad(qx, qy, qz, c, nx, ny, nz)) {
                            grid[(y * GRID + z) * GRID + x] = true;
                        }
                    }
                }
            }
        }
    }

    /** 轴对齐的面落在哪一层：让格子中心尽量贴近平面。 */
    private static int layerOf(float constant) {
        int layer = Math.round(constant * GRID - 0.5F);
        return Math.max(0, Math.min(GRID - 1, layer));
    }

    /** 在一层平面上按另外两轴的范围铺满格子（复用的是格心落在范围内的那批）。 */
    private static void fillPlane(boolean[] grid, int axis, int layer,
                                  float minU, float maxU, float minV, float maxV) {
        int u0 = Math.max(0, (int) Math.floor(minU * GRID - 1.0E-4F));
        int u1 = Math.min(GRID - 1, (int) Math.ceil(maxU * GRID - 1.0E-4F) - 1);
        int v0 = Math.max(0, (int) Math.floor(minV * GRID - 1.0E-4F));
        int v1 = Math.min(GRID - 1, (int) Math.ceil(maxV * GRID - 1.0E-4F) - 1);
        for (int u = u0; u <= u1; u++) {
            float cu = (u + 0.5F) / GRID;
            if (cu < minU - 1.0E-4F || cu > maxU + 1.0E-4F) continue;
            for (int v = v0; v <= v1; v++) {
                float cv = (v + 0.5F) / GRID;
                if (cv < minV - 1.0E-4F || cv > maxV + 1.0E-4F) continue;
                int x;
                int y;
                int z;
                if (axis == 0) {
                    x = layer;
                    y = u;
                    z = v;
                } else if (axis == 1) {
                    x = u;
                    y = layer;
                    z = v;
                } else {
                    x = u;
                    y = v;
                    z = layer;
                }
                grid[(y * GRID + z) * GRID + x] = true;
            }
        }
    }

    /** 点（已投影到平面）在不在四边形里：按两个三角形做重心判定。 */
    private static boolean inQuad(float px, float py, float pz, float[] c,
                                  float nx, float ny, float nz) {
        return inTriangle(px, py, pz, c, 0, 3, 6, nx, ny, nz)
                || inTriangle(px, py, pz, c, 0, 6, 9, nx, ny, nz);
    }

    private static boolean inTriangle(float px, float py, float pz, float[] c,
                                      int a, int b, int d,
                                      float nx, float ny, float nz) {
        return sameSide(px, py, pz, c, a, b, nx, ny, nz)
                && sameSide(px, py, pz, c, b, d, nx, ny, nz)
                && sameSide(px, py, pz, c, d, a, nx, ny, nz);
    }

    private static boolean sameSide(float px, float py, float pz, float[] c,
                                    int a, int b, float nx, float ny, float nz) {
        float ex = c[b] - c[a];
        float ey = c[b + 1] - c[a + 1];
        float ez = c[b + 2] - c[a + 2];
        float vx = px - c[a];
        float vy = py - c[a + 1];
        float vz = pz - c[a + 2];
        float cx = ey * vz - ez * vy;
        float cy = ez * vx - ex * vz;
        float cz = ex * vy - ey * vx;
        return cx * nx + cy * ny + cz * nz >= -1.0E-5F;
    }

    /** 占用网格 → 尽量少的盒子：沿 x、再 z、再 y 贪心扩。 */
    private static float[] mergeGrid(boolean[] grid) {
        final int n = GRID;
        boolean[] used = new boolean[grid.length];
        float[] out = new float[6 * 64];
        int count = 0;
        for (int y = 0; y < n; y++) {
            for (int z = 0; z < n; z++) {
                for (int x = 0; x < n; x++) {
                    int idx = (y * n + z) * n + x;
                    if (!grid[idx] || used[idx]) continue;
                    int w = 1;
                    while (x + w < n && grid[idx + w] && !used[idx + w]) w++;
                    int d = 1;
                    expandZ:
                    while (z + d < n) {
                        for (int dx = 0; dx < w; dx++) {
                            int ii = (y * n + z + d) * n + x + dx;
                            if (!grid[ii] || used[ii]) break expandZ;
                        }
                        d++;
                    }
                    int h = 1;
                    expandY:
                    while (y + h < n) {
                        for (int dz = 0; dz < d; dz++) {
                            for (int dx = 0; dx < w; dx++) {
                                int ii = ((y + h) * n + z + dz) * n + x + dx;
                                if (!grid[ii] || used[ii]) break expandY;
                            }
                        }
                        h++;
                    }
                    for (int dy = 0; dy < h; dy++) {
                        for (int dz = 0; dz < d; dz++) {
                            for (int dx = 0; dx < w; dx++) {
                                used[((y + dy) * n + z + dz) * n + x + dx] = true;
                            }
                        }
                    }
                    if ((count + 1) * 6 > out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
                    int b = count * 6;
                    out[b] = x / (float) n;
                    out[b + 1] = y / (float) n;
                    out[b + 2] = z / (float) n;
                    out[b + 3] = (x + w) / (float) n;
                    out[b + 4] = (y + h) / (float) n;
                    out[b + 5] = (z + d) / (float) n;
                    count++;
                }
            }
        }
        return count == 0 ? null : java.util.Arrays.copyOf(out, count * 6);
    }

    // ── 诊断 ────────────────────────────────────────────────────────────

    private static void reportShape(String id, float[] boxes) {
        if (REPORTED.add(id)) {
            YardSiteLog.debug(() -> "线框几何 getShape：" + id + " → " + boxes.length / 6 + " 盒");
        }
    }

    private static String fmt(float value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static void report(String why) {
        if (REPORTED.add(why)) YardSiteLog.debug("线框几何交回形状表：" + why);
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
}
