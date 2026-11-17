package dev.yard.site.schematic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一份投影/结构文件的体素内容（纯 Java，不依赖 Minecraft）。
 *
 * <p>读进来之后只有两件东西：<b>每格是什么</b>（调色板下标）和<b>折角棱</b>（{@link #creaseEdges(int)}，
 * 懒算）。占地尺寸、出生点、线框都从这里推，放方块时才需要把调色板名字换成
 * 真正的 {@code BlockState}（那一步在 {@code dev.yard.site.structure} 里做）。
 *
 * <p>坐标是<b>文件自己的局部坐标</b>：x/y/z 都在 {@code [0, size)} 内，y=0 是结构的最底层。
 * 旋转/镜像/挪位都不在这里做，见 {@code SchematicTransform}。
 */
public final class Schematic {

    /** 单边上限：线框编码按 11 位存角点坐标，1024 已经够任何建筑用了。 */
    public static final int MAX_SIDE = 1024;

    public final String name;
    public final String author;
    public final int sizeX;
    public final int sizeY;
    public final int sizeZ;
    /** 每格的调色板下标；-1 = 空（空气/结构空位）。下标是 {@code (y * sizeZ + z) * sizeX + x}。 */
    final int[] cells;
    /** 调色板：方块 id（如 {@code minecraft:oak_stairs}）。 */
    public final List<String> ids;
    /** 调色板：方块状态属性（如 {@code facing=north}），可能为空表。 */
    public final List<Map<String, String>> props;
    /** 非空方块数（日志/界面用）。 */
    public final int solidCount;
    /** 实心方块占的 y 范围（含）；都空时 {@code minY=0, maxY=0}。 */
    public final int minY;
    public final int maxY;

    /** 折角棱：懒算一次，之后共用（同一份结构会被选图、落成动画和预览同时读到）。 */
    private SchematicOutline outline;
    /** 盒子几何：懒建一次，线框和消隐共用同一份。 */
    private SchematicBoxes geometry;

    Schematic(String name, String author, int sizeX, int sizeY, int sizeZ,
              int[] cells, List<String> ids, List<Map<String, String>> props) {
        this.name = name == null || name.isBlank() ? "未命名结构" : name;
        this.author = author == null ? "" : author;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.cells = cells;
        this.ids = List.copyOf(ids);
        this.props = List.copyOf(props);

        int solid = 0;
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        for (int y = 0; y < sizeY; y++) {
            int layer = y * sizeZ * sizeX;
            for (int i = 0; i < sizeZ * sizeX; i++) {
                if (cells[layer + i] >= 0) {
                    solid++;
                    if (y < low) low = y;
                    if (y > high) high = y;
                }
            }
        }
        this.solidCount = solid;
        this.minY = solid == 0 ? 0 : low;
        this.maxY = solid == 0 ? 0 : high;
    }

    /** 这一格的调色板下标；越界或空返回 -1。 */
    public int cell(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) return -1;
        return cells[(y * sizeZ + z) * sizeX + x];
    }

    public boolean solid(int x, int y, int z) {
        return cell(x, y, z) >= 0;
    }

    /** 这一格是什么方块（空返回空串）。 */
    public String blockId(int x, int y, int z) {
        int index = cell(x, y, z);
        return index < 0 || index >= ids.size() ? "" : ids.get(index);
    }

    /** 盒子几何（每个方块按 {@link BlockShapes} 拆成的长方体）。懒建，之后共用。 */
    SchematicBoxes geometry() {
        SchematicBoxes built = geometry;
        if (built != null) return built;
        synchronized (this) {
            if (geometry == null) geometry = SchematicBoxes.build(this);
            return geometry;
        }
    }

    /**
     * <b>全部</b>折角棱（屋里屋外都算，合并成长直线）：一份"这份结构的轮廓数据"，
     * <b>不判</b>哪条看得见——谁挡谁不挡由 {@link SchematicHiddenLines} 按当前机位算。
     *
     * <p>懒算一次：第一次要把整份体素扫一遍，大结构（几十万格）大概几十毫秒，之后完全免费。
     *
     * @param maxSegments 最多留多少段（合并之后），超了按长度留长的
     */
    public SchematicOutline creaseEdges(int maxSegments) {
        SchematicOutline built = outline;
        if (built != null && built.count <= maxSegments) return built;
        synchronized (this) {
            if (outline == null || outline.count > maxSegments) {
                outline = SchematicOutline.build(this, maxSegments);
            }
            return outline;
        }
    }

    /**
     * 进世界后把玩家放哪：找一格<b>站得住</b>的地方（脚下实心、头和脚两格空），
     * 优先靠近门，没有门就靠近占地中心。
     *
     * <p>不能直接用占地中心：一间屋子正中可能正好是承重墙或楼梯。也不能直接用门那一格：
     * 门是两格高、门框里常常还摆着台阶。所以按「离目标近 + 站得住」扫一遍再挑。
     *
     * @return 局部坐标 + 朝向（0=+x，1=-x，2=+z，3=-z，出口所在的那个方向），找不到返回 {@code null}
     */
    public Spawn spawnCandidate() {
        List<int[]> doors = new ArrayList<>();
        String[] doorIds = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            doorIds[i] = ids.get(i);
        }
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    int index = cells[(y * sizeZ + z) * sizeX + x];
                    if (index < 0) continue;
                    if (isDoor(doorIds[index])) doors.add(new int[]{x, y, z});
                }
            }
        }
        double targetX = (sizeX - 1) / 2.0;
        double targetZ = (sizeZ - 1) / 2.0;
        int[] best = null;
        double bestScore = Double.MAX_VALUE;
        for (int y = minY; y <= maxY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    if (!standable(x, y, z)) continue;
                    double score;
                    if (doors.isEmpty()) {
                        score = sqr(x - targetX) + sqr(z - targetZ);
                    } else {
                        double nearest = Double.MAX_VALUE;
                        for (int[] door : doors) {
                            // 门那一格本身站不住，所以是「离门多近」，同层优先
                            nearest = Math.min(nearest, sqr(x - door[0]) + sqr(z - door[2])
                                    + Math.abs(y - door[1]) * 4.0);
                        }
                        score = nearest;
                    }
                    if (score < bestScore) {
                        bestScore = score;
                        best = new int[]{x, y, z};
                    }
                }
            }
        }
        if (best == null) return null;
        return new Spawn(best[0], best[1], best[2], frontOf(best[0], best[2], doors));
    }

    /** 脚下实心、脚和头两格空 —— 玩家能站进去（不是气泡柱/水里的那种"能站"）。 */
    private boolean standable(int x, int y, int z) {
        return solid(x, y - 1, z) && !solid(x, y, z) && !solid(x, y + 1, z);
    }

    private static boolean isDoor(String id) {
        return id.endsWith("_door") || id.endsWith(":door");
    }

    /**
     * 出口大致在哪一侧：门最靠近哪条边界就往哪边出去；没有门就按占地短边（-z 一侧）当正面。
     */
    private int frontOf(int x, int z, List<int[]> doors) {
        int px = x;
        int pz = z;
        if (!doors.isEmpty()) {
            int[] nearest = doors.get(0);
            double best = Double.MAX_VALUE;
            for (int[] door : doors) {
                double d = sqr(door[0] - x) + sqr(door[2] - z);
                if (d < best) {
                    best = d;
                    nearest = door;
                }
            }
            px = nearest[0];
            pz = nearest[2];
        }
        int west = px;
        int east = sizeX - 1 - px;
        int north = pz;
        int south = sizeZ - 1 - pz;
        int min = Math.min(Math.min(west, east), Math.min(north, south));
        if (min == west) return 1;
        if (min == east) return 0;
        if (min == north) return 3;
        return 2;
    }

    private static double sqr(double v) {
        return v * v;
    }

    /**
     * 出生格（局部坐标）+ 出口方向。
     *
     * @param front 0=+x，1=-x，2=+z，3=-z：镜头从这一侧靠近（和结构朝向一起转）
     */
    public record Spawn(int x, int y, int z, int front) {}

    /** 调试/日志用的一行摘要。 */
    public String describe() {
        return name + " " + sizeX + "×" + sizeY + "×" + sizeZ
                + "，" + solidCount + " 格" + (author.isBlank() ? "" : "，作者 " + author);
    }
}
