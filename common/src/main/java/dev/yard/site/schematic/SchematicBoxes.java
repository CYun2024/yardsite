package dev.yard.site.schematic;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 一份结构的<b>盒子几何</b>：每个方块按 {@link BlockShapes} 拆成若干长方体，拼起来既是线框的来源，
 * 也是遮挡判定的对象。
 *
 * <h2>为什么不直接用「一格实/一格空」</h2>
 * 体素模型只知道"这一格有没有东西"，于是楼梯、半砖、活板门都成了整格立方体：线框画出来是一摞方块，
 * 遮挡判定也把半格高的台阶当整格挡视线。拆成盒子之后，这两件事同时变准——
 * 楼梯的台阶轮廓画得出来，台阶后面的线也不会被"整格"吞掉。
 *
 * <h2>格级索引</h2>
 * 盒子按格子顺序排好，所以某一格的盒子就是 {@code cellStart[cell] .. cellStart[cell+1]} 这一段。
 * 射线按格步进（DDA），走到哪格就只测那格里的几个盒子，不必遍历整份结构。
 *
 * <p>纯 Java，不碰 Minecraft。
 */
public final class SchematicBoxes {

    private final Schematic schematic;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;

    /** 全局（结构局部）坐标的盒子：每盒 6 个 float。 */
    private final float[] boxes;
    /** 盒子数。 */
    private final int count;
    /** 每格的盒子在 {@link #boxes} 里的起点（下标）；长度 = 格数 + 1，末位是盒子总数。 */
    private final int[] cellStart;

    /** 凸棱：只有 1 个象限实心 —— 外棱、台阶、方块边缘，是"这栋楼长什么样"。 */
    public static final byte CONVEX = 1;
    /** 凹棱：3 个象限实心 —— 凹进去的拐角（门洞内侧、凹槽底）。 */
    public static final byte CONCAVE = 2;
    /** 鞍点 / 薄片：2 个对角实心，或者形体太薄（玻璃板、活板门、地毯）。 */
    public static final byte SADDLE = 3;

    /**
     * 比这更薄的盒子（格）整条降成细节线。
     *
     * <p>0.2 格 ≈ 3/16：活板门、玻璃板、地毯、牌子、按钮都在下面——它们画粗了会把整面墙糊成网格；
     * 半砖（0.5）、楼梯的一级（0.5）在上面，所以和墙棱一样粗，边缘才连得上。
     */
    static final float THIN = 0.2F;

    /** 折角棱（未合并）：每段 6 个 float，局部坐标。 */
    private float[] edges;
    /** 每条棱的角色：{@link #CONVEX} / {@link #CONCAVE} / {@link #SADDLE}，和 {@link #edges} 一一对应。 */
    private byte[] edgeRoles;
    /**
     * 每条棱"敞口"朝哪一侧：bit0 = 垂直于棱的第一轴朝正，bit1 = 第二轴朝正，bit2 = 有效。
     *
     * <p>棱周围的四块空间里，那个"两边都空"的角就定义了这条棱的两个外法线方向。
     * 渲染时拿它和相机比：两条法线一条朝我一条背我 → 这条棱此刻正是<b>外轮廓</b>。
     * 鞍点（两块只在棱上相接）没有唯一敞口，bit2 置 0。
     */
    private byte[] edgeSides;
    private int edgeCount;

    private SchematicBoxes(Schematic schematic) {
        this.schematic = schematic;
        this.sizeX = schematic.sizeX;
        this.sizeY = schematic.sizeY;
        this.sizeZ = schematic.sizeZ;

        int cells = sizeX * sizeY * sizeZ;
        // 每个调色板条目只翻一次形状：同一份结构里同一个状态会出现几千次
        List<String> ids = schematic.ids;
        List<Map<String, String>> props = schematic.props;
        float[][] shapes = new float[ids.size()][];
        for (int i = 0; i < ids.size(); i++) {
            shapes[i] = BlockShapes.of(ids.get(i), props.get(i));
        }

        // 先数格子各挂几个盒子，做前缀和，再一遍填进去（避免用 List 装箱）
        int[] start = new int[cells + 1];
        for (int i = 0; i < cells; i++) {
            int pal = schematic.cells[i];
            start[i + 1] = start[i] + (pal < 0 ? 0 : shapes[pal].length / 6);
        }
        int total = start[cells];
        // 按格子顺序填：盒子下标正好就是 cellStart[cell]..cellStart[cell+1]，
        // 所以格级索引就是这组前缀和本身，不用再存一份映射表
        float[] boxData = new float[total * 6];
        int at = 0;
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                int row = (y * sizeZ + z) * sizeX;
                for (int x = 0; x < sizeX; x++) {
                    int pal = schematic.cells[row + x];
                    if (pal < 0) continue;
                    float[] shape = shapes[pal];
                    for (int k = 0; k < shape.length; k += 6) {
                        int b = at * 6;
                        boxData[b] = x + shape[k];
                        boxData[b + 1] = y + shape[k + 1];
                        boxData[b + 2] = z + shape[k + 2];
                        boxData[b + 3] = x + shape[k + 3];
                        boxData[b + 4] = y + shape[k + 4];
                        boxData[b + 5] = z + shape[k + 5];
                        at++;
                    }
                }
            }
        }
        this.cellStart = start;
        this.boxes = boxData;
        this.count = total;
    }

    /** 懒建（同一份结构只建一次，缓存挂在 {@link Schematic} 上）。 */
    public static SchematicBoxes of(Schematic schematic) {
        return schematic.geometry();
    }

    /** 真正建一份（{@link Schematic} 内部用）。 */
    static SchematicBoxes build(Schematic schematic) {
        return new SchematicBoxes(schematic);
    }

    /** 盒子数（日志用）。 */
    public int boxCount() {
        return count;
    }

    // ── 棱提取 ──────────────────────────────────────────────────────────

    /** 每条折角棱的角色（和 {@link #edges()} 一一对应，先调 {@code edges()}）。 */
    byte[] edgeRoles() {
        edges();
        return edgeRoles;
    }

    /** 每条折角棱的敞口方向（同上，见 {@link #edgeSides}）。 */
    byte[] edgeSides() {
        edges();
        return edgeSides;
    }

    /**
     * 全部<b>折角棱</b>（未合并、未消隐）：每盒 12 条边，逐条判是不是真的折角。
     *
     * <p>判据和体素版一样，只是用盒子之后更准：一条棱周围有 4 个"象限"（垂直于棱的平面上
     * 2×2 块空间），数其中几块是<b>实心</b>的——
     * <ol>
     *   <li>1 或 3 块：凸棱 / 凹棱，画；</li>
     *   <li>2 块对角：两块只在这一条棱上相接，是折角，画；</li>
     *   <li>2 块相邻：一块平面从中间穿过去，是墙面接缝，<b>不画</b>——不然一面 20×10 的墙会画出
     *       几十条方格线，整栋楼像贴了张方格纸；</li>
     *   <li>0 或 4 块：不画。</li>
     * </ol>
     *
     * <p>同一位置的棱会被相邻的几个盒子各贡献一次，合并那一步（{@link SchematicOutline}）会去重。
     */
    float[] edges() {
        if (edges != null) return edges;
        EdgeSink sink = new EdgeSink(Math.max(256, count * 8));
        for (int i = 0; i < count; i++) {
            int b = i * 6;
            float x0 = boxes[b];
            float y0 = boxes[b + 1];
            float z0 = boxes[b + 2];
            float x1 = boxes[b + 3];
            float y1 = boxes[b + 4];
            float z1 = boxes[b + 5];
            // 退化的盒子（某一维厚 0）没有棱
            if (x1 - x0 < 1.0E-4F || y1 - y0 < 1.0E-4F || z1 - z0 < 1.0E-4F) continue;
            float thinnest = Math.min(x1 - x0, Math.min(y1 - y0, z1 - z0));
            for (int corner = 0; corner < 4; corner++) {
                boolean hiY = (corner & 1) != 0;
                boolean hiZ = (corner & 2) != 0;
                float y = hiY ? y1 : y0;
                float z = hiZ ? z1 : z0;
                addIfCrease(sink, x0, y, z, x1, y, z, 0, thinnest);
            }
            for (int corner = 0; corner < 4; corner++) {
                boolean hiX = (corner & 1) != 0;
                boolean hiZ = (corner & 2) != 0;
                float x = hiX ? x1 : x0;
                float z = hiZ ? z1 : z0;
                addIfCrease(sink, x, y0, z, x, y1, z, 1, thinnest);
            }
            for (int corner = 0; corner < 4; corner++) {
                boolean hiX = (corner & 1) != 0;
                boolean hiY = (corner & 2) != 0;
                float x = hiX ? x1 : x0;
                float y = hiY ? y1 : y0;
                addIfCrease(sink, x, y, z0, x, y, z1, 2, thinnest);
            }
        }
        edges = Arrays.copyOf(sink.data, sink.n);
        edgeRoles = Arrays.copyOf(sink.roles, sink.n / 6);
        edgeSides = Arrays.copyOf(sink.sides, sink.n / 6);
        edgeCount = sink.n / 6;
        return edges;
    }

    /** 棱的条数。 */
    int edgeCount() {
        edges();
        return edgeCount;
    }

    /**
     * 一条棱是不是折角（见 {@link #edges()}）：在棱的中点周围，垂直于棱的平面上取 2×2 四块，
     * 数有几块是实心的。
     */
    private void addIfCrease(EdgeSink sink, float ax, float ay, float az,
                             float bx, float by, float bz, int axis, float thinnest) {
        // 一条棱可能有一截和别的方块齐平、另一截露着（柱子立在半砖上：下半段和半砖侧面共面，
        // 上半段才是真的折角）。只看中点会被"中点正好撞在接缝那一层"骗过去，所以沿棱取三点，
        // 任一处是折角就整条画出来。
        for (int k = 1; k <= 3; k++) {
            float t = k * 0.25F;
            byte role = roleAt(ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t, axis);
            if (role != 0) {
                // 薄片（玻璃板、活板门、地毯、牌子）不管凸凹都算细节：画粗了整面墙就是网格
                sink.add(ax, ay, az, bx, by, bz, thinnest < THIN ? SADDLE : role, probeSides);
                return;
            }
        }
    }

    /**
     * 这一点周围的四个象限里有几个是实心的，按「这条棱扮演什么角色」给出结论。
     *
     * @param axis 棱的方向（0=x，1=y，2=z）
     * @return 0 = 不是棱（平面接缝或什么都没有）；否则是 {@link #CONVEX}/{@link #CONCAVE}/{@link #SADDLE}
     */
    private byte roleAt(float mx, float my, float mz, int axis) {
        probeSides = 0;
        float e = PROBE;
        float ux;
        float uy;
        float uz;
        float vx;
        float vy;
        float vz;
        switch (axis) {
            case 0 -> { ux = 0; uy = 1; uz = 0; vx = 0; vy = 0; vz = 1; }
            case 1 -> { ux = 1; uy = 0; uz = 0; vx = 0; vy = 0; vz = 1; }
            default -> { ux = 1; uy = 0; uz = 0; vx = 0; vy = 1; vz = 0; }
        }
        // 四个象限 = 2×2 的四块（必须探"角"，不能探十字：探十字分不出"两块相邻"和"两块对角"，
        // 底面内部的接缝会被当成凹棱画出来）
        float ue = ux * e;
        float uy2 = uy * e;
        float uz2 = uz * e;
        float ve = vx * e;
        float vy2 = vy * e;
        float vz2 = vz * e;
        boolean q00 = solidAt(mx - ue - ve, my - uy2 - vy2, mz - uz2 - vz2);
        boolean q01 = solidAt(mx - ue + ve, my - uy2 + vy2, mz - uz2 + vz2);
        boolean q10 = solidAt(mx + ue - ve, my + uy2 - vy2, mz + uz2 - vz2);
        boolean q11 = solidAt(mx + ue + ve, my + uy2 + vy2, mz + uz2 + vz2);
        int solid = (q00 ? 1 : 0) + (q01 ? 1 : 0) + (q10 ? 1 : 0) + (q11 ? 1 : 0);
        if (solid == 0 || solid == 4) return 0;
        if (solid == 2) {
            // 两块相邻 = 一个平面从中间穿过去，那是墙面接缝；两块对角才是折角
            if (!((q00 && q11) || (q01 && q10))) return 0;
            return SADDLE;                      // 鞍点没有唯一敞口，轮廓判定用不上
        }
        // 两条外法线指向"空气那一侧"：
        //   凸棱（1 实心）-> 敞口在实心角的对角；凹棱（3 实心）-> 敞口就是那个唯一的空气角。
        int su;
        int sv;
        if (solid == 1) {
            if (q00) { su = 1; sv = 1; }            // 实心 (-u,-v) -> 敞口 (+u,+v)
            else if (q01) { su = 1; sv = -1; }
            else if (q10) { su = -1; sv = 1; }
            else { su = -1; sv = -1; }
        } else {
            if (!q00) { su = -1; sv = -1; }
            else if (!q01) { su = -1; sv = 1; }
            else if (!q10) { su = 1; sv = -1; }
            else { su = 1; sv = 1; }
        }
        probeSides = (byte) ((su > 0 ? 1 : 0) | (sv > 0 ? 2 : 0) | 4);
        return solid == 1 ? CONVEX : CONCAVE;
    }

    /** 最近一次 {@link #roleAt} 算出的敞口方向（供 {@link #addIfCrease} 一起存下来）。 */
    private byte probeSides;

    /** 这个点是不是在某块实心盒子里。 */
    boolean solidAt(float px, float py, float pz) {
        int x = fastFloor(px);
        int y = fastFloor(py);
        int z = fastFloor(pz);
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) return false;
        int cell = (y * sizeZ + z) * sizeX + x;
        int to = cellStart[cell + 1] * 6;
        for (int b = cellStart[cell] * 6; b < to; b += 6) {
            if (px >= boxes[b] && px <= boxes[b + 3]
                    && py >= boxes[b + 1] && py <= boxes[b + 4]
                    && pz >= boxes[b + 2] && pz <= boxes[b + 5]) return true;
        }
        return false;
    }

    // ── 遮挡：从相机看过去，第一个挡路的东西有多远 ──────────────────────

    /**
     * 从相机 {@code (ox,oy,oz)} 朝 {@code (tx,ty,tz)} 看，一路上第一个实体的距离（格）；
     * 什么也没撞到就返回 {@link Float#MAX_VALUE}。
     *
     * <p>这就是"可见"的判据本体：采样点自己就在盒子表面上，所以<b>看得见时返回值正好等于
     * 射线长度</b>，被挡住时小于它。不需要"把采样点挪进空气""允许擦着表面走"这类补丁——
     * 上一版正是靠这些补丁过活，也正因为它们才一会儿断一会儿连。
     */
    public float firstHit(float ox, float oy, float oz, float tx, float ty, float tz) {
        lastHitBox = -1;
        float dx = tx - ox;
        float dy = ty - oy;
        float dz = tz - oz;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-5F) return 0.0F;
        float inv = 1.0F / len;
        dx *= inv;
        dy *= inv;
        dz *= inv;

        // 相机往往在结构外面几十上百格（落成动画从远处推近），不先跳到包围盒入口的话，
        // DDA 要空走几百步才碰到第一个方块。先做一次射线-AABB，把起点挪到入口。
        float skip = entry(ox, oy, oz, dx, dy, dz, len);
        if (skip < 0.0F || skip > len) return Float.MAX_VALUE;
        // 终点正好落在包围盒边界上（采样点就在结构最外层）：它自己就是第一个碰到的东西
        if (len - skip < 2.0E-3F) return len;
        // 起点从"终点往回退 room"算，而不是"原点往前推 skip"：
        // 相机在几百格开外时，ox 本身已是 2000 多的大数，再加减会把 float 的精度
        // 放大成一格以上的误差（实体图里立方体的边就歪了）。终点坐标是结构内的小数，稳。
        float room = len - skip;
        float sx = tx - dx * room;
        float sy = ty - dy * room;
        float sz = tz - dz * room;

        int x = fastFloor(sx);
        int y = fastFloor(sy);
        int z = fastFloor(sz);
        int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        int stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        float tDeltaX = stepX == 0 ? Float.MAX_VALUE : Math.abs(1.0F / dx);
        float tDeltaY = stepY == 0 ? Float.MAX_VALUE : Math.abs(1.0F / dy);
        float tDeltaZ = stepZ == 0 ? Float.MAX_VALUE : Math.abs(1.0F / dz);
        float tMaxX = stepX == 0 ? Float.MAX_VALUE
                : (stepX > 0 ? (x + 1 - sx) : (sx - x)) * tDeltaX;
        float tMaxY = stepY == 0 ? Float.MAX_VALUE
                : (stepY > 0 ? (y + 1 - sy) : (sy - y)) * tDeltaY;
        float tMaxZ = stepZ == 0 ? Float.MAX_VALUE
                : (stepZ > 0 ? (z + 1 - sz) : (sz - z)) * tDeltaZ;

        float nearest = Float.MAX_VALUE;
        int nearestBox = -1;
        // 进了包围盒之后最多走 sizeX+sizeY+sizeZ 格就必然出去
        int guard = sizeX + sizeY + sizeZ + 8;
        while (guard-- > 0) {
            if (x >= 0 && y >= 0 && z >= 0 && x < sizeX && y < sizeY && z < sizeZ) {
                int cell = (y * sizeZ + z) * sizeX + x;
                int to = cellStart[cell + 1] * 6;
                for (int b = cellStart[cell] * 6; b < to; b += 6) {
                    float hit = rayBox(b, sx, sy, sz, dx, dy, dz, nearest);
                    if (hit < nearest) {
                        nearest = hit;
                        nearestBox = b;
                    }
                }
            }
            // 已经找到交点，且它就在当前格之前 -> 后面都不必看了
            float exit = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            if (nearest <= exit) {
                lastHitBox = nearestBox / 6;
                return nearest + skip;
            }
            if (exit >= room) break;
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
        }
        lastHitBox = nearestBox < 0 ? -1 : nearestBox / 6;
        return nearest == Float.MAX_VALUE ? Float.MAX_VALUE : nearest + skip;
    }

    /**
     * 射线进入结构整体包围盒的距离；{@code -1} = 不相交。
     * 起点已经在盒子里时返回 0。
     */
    private float entry(float ox, float oy, float oz, float dx, float dy, float dz, float len) {
        float t0 = 0.0F;
        float t1 = len;
        for (int axis = 0; axis < 3; axis++) {
            float o = axis == 0 ? ox : axis == 1 ? oy : oz;
            float d = axis == 0 ? dx : axis == 1 ? dy : dz;
            float hi = axis == 0 ? sizeX : axis == 1 ? sizeY : sizeZ;
            if (Math.abs(d) < 1.0E-8F) {
                if (o < 0.0F || o > hi) return -1.0F;
                continue;
            }
            float a = (0.0F - o) / d;
            float c = (hi - o) / d;
            if (a > c) { float t = a; a = c; c = t; }
            if (a > t0) t0 = a;
            if (c < t1) t1 = c;
            if (t0 > t1 + 1.0E-4F) return -1.0F;
        }
        return t0;
    }

    /**
     * 射线打这个盒子：返回进入距离（&lt; {@code limit} 才算），没打中就返回 {@link Float#MAX_VALUE}。
     *
     * <p>起点在盒子表面上（棱就在几个盒子的公共边界上）时返回 0，正是想要的：
     * 那不是"挡在路上的东西"，是采样点自己。
     */
    private float rayBox(int b, float ox, float oy, float oz,
                         float dx, float dy, float dz, float limit) {
        float t0 = 0.0F;
        float t1 = limit;
        // x
        if (Math.abs(dx) < 1.0E-8F) {
            if (ox < boxes[b] || ox > boxes[b + 3]) return Float.MAX_VALUE;
        } else {
            float a = (boxes[b] - ox) / dx;
            float c = (boxes[b + 3] - ox) / dx;
            if (a > c) { float t = a; a = c; c = t; }
            if (a > t0) t0 = a;
            if (c < t1) t1 = c;
            if (t0 > t1) return Float.MAX_VALUE;
        }
        // y
        if (Math.abs(dy) < 1.0E-8F) {
            if (oy < boxes[b + 1] || oy > boxes[b + 4]) return Float.MAX_VALUE;
        } else {
            float a = (boxes[b + 1] - oy) / dy;
            float c = (boxes[b + 4] - oy) / dy;
            if (a > c) { float t = a; a = c; c = t; }
            if (a > t0) t0 = a;
            if (c < t1) t1 = c;
            if (t0 > t1) return Float.MAX_VALUE;
        }
        // z
        if (Math.abs(dz) < 1.0E-8F) {
            if (oz < boxes[b + 2] || oz > boxes[b + 5]) return Float.MAX_VALUE;
        } else {
            float a = (boxes[b + 2] - oz) / dz;
            float c = (boxes[b + 5] - oz) / dz;
            if (a > c) { float t = a; a = c; c = t; }
            if (a > t0) t0 = a;
            if (c < t1) t1 = c;
            if (t0 > t1) return Float.MAX_VALUE;
        }
        return t0 > t1 ? Float.MAX_VALUE : t0;
    }

    /** 最近一次 {@link #firstHit} 打中的盒子下标；{@code -1} = 没打中。离线渲染取法线用。 */
    private int lastHitBox = -1;

    /** 最近一次 {@link #firstHit} 打中的盒子下标；{@code -1} = 没打中（调完 {@code firstHit} 立刻取）。 */
    public int lastHitBox() {
        return lastHitBox;
    }

    /** 盒子第 {@code index} 个的 6 个坐标（x0,y0,z0,x1,y1,z1），写进 {@code dst}。 */
    public void box(int index, float[] dst) {
        System.arraycopy(boxes, index * 6, dst, 0, 6);
    }

    /** 相机自己是不是在某块实心盒子里（在里面就不做消隐，不然什么都看不见）。 */
    public boolean insideSolid(float px, float py, float pz) {
        return solidAt(px, py, pz);
    }

    // ── 小工具 ──────────────────────────────────────────────────────────

    /** 折角判定的探测距离（格）：贴着棱往四个象限各探这么远，看有没有实心。 */
    private static final float PROBE = 0.008F;

    static int fastFloor(float v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    /** 可长的 float 缓冲（棱数事先估不准，少了直接翻倍）。 */
    private static final class EdgeSink {
        float[] data;
        byte[] roles;
        int n;

        EdgeSink(int capacity) {
            this.data = new float[capacity * 6];
            this.roles = new byte[capacity];
            this.sides = new byte[capacity];
        }

        byte[] sides;

        void add(float x0, float y0, float z0, float x1, float y1, float z1, byte role, byte side) {
            if ((n + 6) > data.length) {
                data = Arrays.copyOf(data, data.length * 2);
                roles = Arrays.copyOf(roles, roles.length * 2);
                sides = Arrays.copyOf(sides, sides.length * 2);
            }
            data[n] = x0;
            data[n + 1] = y0;
            data[n + 2] = z0;
            data[n + 3] = x1;
            data[n + 4] = y1;
            data[n + 5] = z1;
            roles[n / 6] = role;
            sides[n / 6] = side;
            n += 6;
        }
    }
}
