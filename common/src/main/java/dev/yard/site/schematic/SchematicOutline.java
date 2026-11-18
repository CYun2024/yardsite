package dev.yard.site.schematic;

import java.util.Arrays;

/**
 * 结构线框：把盒子几何里<b>全部折角棱</b>合并成尽量长的直线段（不做任何遮挡判断）。
 *
 * <h2>棱从哪来</h2>
 * {@link SchematicBoxes#edges()} 已经把每个方块拆成盒子、逐条挑出"真的折角"的棱
 * （判据见那里）。这里只做一件事：<b>把共线且首尾相接的段接成长线</b>。
 * 一面 20×10 的墙，逐盒画是一千多条一格长的小段，合并之后只剩十几条长直线——
 * 落成动画里每帧提交的顶点少一个数量级，看着也干净（不会有一串接缝的亮点）。
 *
 * <h2>谁挡谁留给机位去判</h2>
 * 这里<b>不判</b>哪条棱看得见——同一份结构转一圈，看得见的棱本来就一直在变。
 * 交出去的是一份"这份结构的全部折角"，正面遮挡由 {@link SchematicHiddenLines} 按当前机位算。
 *
 * <h2>怎么合并</h2>
 * 每条棱先按 1/16 格<b>摊成单位段</b>，每段编成一个 long：
 * {@code 角色(2) | 轴(2) | 两个固定坐标(15+15) | 沿轴位置(15)}。
 * 角色也编进去，是为了让「凸棱」和「凹棱」分成两条：同一条墙上半截凸、下半截凹的话，
 * 合并成一条就只能给一个档位，画出来粗细会跳。之所以要摊开，是因为 key 里只放"起点"
 * 不放长度——"这一段后面还有没有"由<b>下一个单位段存不存在</b>隐含，排序之后扫一遍就知道。
 * 直接拿盒子棱的两个端点去编码是不行的：一条一格的棱会被当成"一格的起点"，后面接不上，
 * 结果每一小段都单独成线（第一版就踩了这个坑，9628 段平均长度只有 0.06 格）。
 *
 * <p>排序同时完成了去重（几个盒子共用的那条棱只会留一份）和分组（同一条线上的段挨在一起）。
 *
 * <p>全是纯 Java，不碰 Minecraft；{@code tools/schematic-check} 就是拿它对着投影文件验的。
 */
public final class SchematicOutline {

    /** 每个坐标 15 位：1/16 格为单位，最大 32767/16 ≈ 2048 格，比 {@link Schematic#MAX_SIDE} 还宽。 */
    private static final int SHIFT = 15;
    private static final int MASK = (1 << SHIFT) - 1;
    /** 坐标量化单位：一格分 {@value} 份。原版方块形状全是 1/16 的整数倍。 */
    static final int UNITS = 16;

    /** 每段 6 个 float：x1,y1,z1,x2,y2,z2（局部角点坐标，y 向上）。 */
    public final float[] segs;
    /** 每段的角色：{@link SchematicBoxes#CONVEX} / {@code CONCAVE} / {@code SADDLE}，和 {@link #segs} 一一对应。 */
    public final byte[] roles;
    /** 每段的敞口方向（见 {@link SchematicBoxes#edgeSides()}）：渲染时拿它和相机比出外轮廓。 */
    public final byte[] sides;
    public final int count;
    /** 实心方块占的 y 范围（局部坐标），落成动画按它扫过去。 */
    public final int minY;
    public final int maxY;
    /** 合并之前的折角棱条数（含重复），日志用。 */
    public final int rawCount;

    private SchematicOutline(float[] segs, byte[] roles, byte[] sides,
                             int count, int minY, int maxY, int rawCount) {
        this.segs = segs;
        this.roles = roles;
        this.sides = sides;
        this.count = count;
        this.minY = minY;
        this.maxY = maxY;
        this.rawCount = rawCount;
    }

    /**
     * @param maxSegments 最多留多少段（合并之后）；超了按长度留长的（见 {@link #keepLongest}）
     */
    static SchematicOutline build(Schematic s, int maxSegments) {
        SchematicBoxes geometry = s.geometry();
        float[] raw = geometry.edges();
        int edgeCount = geometry.edgeCount();
        // 只有花草时盒子棱是 0，但斜棱还得出去
        if (edgeCount == 0 && geometry.extraEdgeCount() == 0) {
            return new SchematicOutline(new float[0], new byte[0], new byte[0], 0, s.minY, s.maxY, 0);
        }

        byte[] rawRoles = geometry.edgeRoles();
        byte[] rawSides = geometry.edgeSides();
        int units = 0;
        for (int i = 0; i < edgeCount; i++) units += unitsOf(raw, i * 6);
        long[] keys = new long[Math.max(64, units)];
        int n = 0;
        for (int i = 0; i < edgeCount; i++) {
            n = encode(keys, n, raw, i * 6, rawRoles[i], rawSides[i]);
        }
        Arrays.sort(keys, 0, n);

        float[] out = new float[Math.max(64, Math.min(n, 1 << 16)) * 6];
        byte[] outRoles = new byte[out.length / 6];
        byte[] outSides = new byte[out.length / 6];
        int outCount = 0;
        int i = 0;
        while (i < n) {
            long key = keys[i];
            long prefix = key & ~MASK;                 // 轴 + 两个固定坐标
            int end = (int) (key & MASK);
            int from = end;
            i++;
            // 同一条线上的单位段按位置升序排在一起：连续的就接成一段
            while (i < n && (keys[i] & ~MASK) == prefix) {
                int next = (int) (keys[i] & MASK);
                if (next <= end) {
                    i++;                               // 几个盒子共用的那条棱：排序之后挨在一起，跳过重复
                    continue;
                }
                if (next != end + 1) break;
                end = next;
                i++;
            }
            if (outCount * 6 + 6 > out.length) {
                out = Arrays.copyOf(out, Math.max(64, out.length * 2));
                outRoles = Arrays.copyOf(outRoles, out.length / 6);
                outSides = Arrays.copyOf(outSides, out.length / 6);
            }
            outRoles[outCount] = (byte) ((prefix >>> ROLE_SHIFT) & 0x3);
            outSides[outCount] = (byte) ((prefix >>> SIDE_SHIFT) & 0x7);
            outCount = emit(out, outCount, prefix, from, end);
        }

        int kept = outCount;
        if (maxSegments > 0 && outCount > maxSegments) {
            kept = keepLongest(out, outCount, outRoles, outSides, maxSegments);
        }
        // 十字花草的斜棱（对角平面）编码不进上面那套"沿单轴"的合并，原样接在最后，按细节线画
        float[] extra = geometry.extraEdges();
        int extraCount = geometry.extraEdgeCount();
        int total = kept + extraCount;
        float[] finalSegs = new float[total * 6];
        byte[] finalRoles = new byte[total];
        byte[] finalSides = new byte[total];
        System.arraycopy(out, 0, finalSegs, 0, kept * 6);
        System.arraycopy(outRoles, 0, finalRoles, 0, kept);
        System.arraycopy(outSides, 0, finalSides, 0, kept);
        if (extraCount > 0) {
            System.arraycopy(extra, 0, finalSegs, kept * 6, extraCount * 6);
            java.util.Arrays.fill(finalRoles, kept, total, SchematicBoxes.SADDLE);
        }
        return new SchematicOutline(finalSegs, finalRoles, finalSides, total, s.minY, s.maxY, edgeCount);
    }

    /** 这条棱量化之后覆盖几个 1/16 单位。 */
    private static int unitsOf(float[] raw, int base) {
        span(raw, base, SPAN);
        return Math.max(0, SPAN[4] - SPAN[3]);
    }

    /** 把一条棱摊成单位段写进 key 缓冲；返回新的写入位置。 */
    private static int encode(long[] keys, int n, float[] raw, int base, byte role, byte side) {
        span(raw, base, SPAN);
        long prefix = ((long) SPAN[2] << SHIFT) | ((long) SPAN[1] << (2 * SHIFT))
                | ((long) SPAN[0] << (3 * SHIFT)) | ((long) (role & 0x3) << ROLE_SHIFT)
                | ((long) (side & 0x7) << SIDE_SHIFT);
        for (int p = SPAN[3]; p < SPAN[4]; p++) keys[n++] = prefix | p;
        return n;
    }

    /** {@link #span} 的输出（避免每条棱都分配一个小数组：几万条棱挺费的）。 */
    private static final int[] SPAN = new int[5];

    /**
     * 把一条棱量化成「轴、u、v、起点、终点」（单位都是 1/16 格），写进 {@code out}。
     *
     * <p>轴向看哪一维在变：两端量化之后只有一维不同，另外两维就是这条线所在的平面。
     * 盒子棱可能比一格短（活板门 3/16、台阶 1/2），也可能长得多，所以起点终点都得留着。
     */
    private static void span(float[] raw, int base, int[] out) {
        int ux1 = q(raw[base]);
        int uy1 = q(raw[base + 1]);
        int uz1 = q(raw[base + 2]);
        int ux2 = q(raw[base + 3]);
        int uy2 = q(raw[base + 4]);
        int uz2 = q(raw[base + 5]);
        if (ux1 != ux2) {                       // 沿 x：y、z 固定
            out[0] = 0;
            out[1] = uy1;
            out[2] = uz1;
            out[3] = Math.min(ux1, ux2);
            out[4] = Math.max(ux1, ux2);
        } else if (uy1 != uy2) {                // 沿 y：x、z 固定
            out[0] = 1;
            out[1] = ux1;
            out[2] = uz1;
            out[3] = Math.min(uy1, uy2);
            out[4] = Math.max(uy1, uy2);
        } else {                                // 沿 z：x、y 固定
            out[0] = 2;
            out[1] = ux1;
            out[2] = uy1;
            out[3] = Math.min(uz1, uz2);
            out[4] = Math.max(uz1, uz2);
        }
    }

    /** 把合并好的一段写成 6 个 float。{@code prefix} 里带着轴和两个固定坐标。 */
    private static int emit(float[] out, int count, long prefix, int from, int to) {
        int v = (int) ((prefix >>> SHIFT) & MASK);
        int u = (int) ((prefix >>> (2 * SHIFT)) & MASK);
        int a = (int) ((prefix >>> (3 * SHIFT)) & 0x3);
        float fu = u / (float) UNITS;
        float fv = v / (float) UNITS;
        float f0 = from / (float) UNITS;
        float f1 = (to + 1) / (float) UNITS;
        int base = count * 6;
        switch (a) {
            case 0 -> {     // 沿 x：u = y，v = z
                out[base] = f0;
                out[base + 1] = fu;
                out[base + 2] = fv;
                out[base + 3] = f1;
                out[base + 4] = fu;
                out[base + 5] = fv;
            }
            case 1 -> {     // 沿 y：u = x，v = z
                out[base] = fu;
                out[base + 1] = f0;
                out[base + 2] = fv;
                out[base + 3] = fu;
                out[base + 4] = f1;
                out[base + 5] = fv;
            }
            default -> {    // 沿 z：u = x，v = y
                out[base] = fu;
                out[base + 1] = fv;
                out[base + 2] = f0;
                out[base + 3] = fu;
                out[base + 4] = fv;
                out[base + 5] = f1;
            }
        }
        return count + 1;
    }

    /** 角色在 key 里的起始位：轴(45-46) 之上。 */
    private static final int ROLE_SHIFT = 3 * SHIFT + 2;
    /** 敞口方向在 key 里的起始位。 */
    private static final int SIDE_SHIFT = ROLE_SHIFT + 2;

    /**
     * 线段太多时按长度从长到短保留：留下的长线正好是轮廓、檐口、楼层线这些「看得出形状」的，
     * 丢掉的是雕花、栏杆这类密集短线。等比抽稀会让整体变成虚线，不如直接留长的。
     */
    private static int keepLongest(float[] segs, int count, byte[] roles, byte[] sides, int keep) {
        Integer[] order = new Integer[count];
        for (int i = 0; i < count; i++) order[i] = i;
        float[] fixed = segs;
        Arrays.sort(order, (a, b) -> Float.compare(length(fixed, b), length(fixed, a)));
        float[] picked = new float[keep * 6];
        byte[] pickedRoles = new byte[keep];
        byte[] pickedSides = new byte[keep];
        for (int i = 0; i < keep; i++) {
            System.arraycopy(segs, order[i] * 6, picked, i * 6, 6);
            pickedRoles[i] = roles[order[i]];
            pickedSides[i] = sides[order[i]];
        }
        System.arraycopy(picked, 0, segs, 0, keep * 6);
        System.arraycopy(pickedRoles, 0, roles, 0, keep);
        System.arraycopy(pickedSides, 0, sides, 0, keep);
        return keep;
    }

    private static float length(float[] segs, int index) {
        int base = index * 6;
        float dx = segs[base + 3] - segs[base];
        float dy = segs[base + 4] - segs[base + 1];
        float dz = segs[base + 5] - segs[base + 2];
        return dx * dx + dy * dy + dz * dz;
    }

    /** 量化到 1/16 格。超出范围就夹住（单边超过 2048 格才会，正常结构碰不到）。 */
    private static int q(float v) {
        return Math.max(0, Math.min(MASK, Math.round(v * UNITS)));
    }
}
