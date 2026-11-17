package check;

import dev.yard.site.schematic.BlockShapes;
import dev.yard.site.schematic.Schematic;
import dev.yard.site.schematic.SchematicHiddenLines;
import dev.yard.site.schematic.SchematicOutline;
import dev.yard.site.schematic.SchematicReader;
import dev.yard.site.schematic.SchematicTransform;

import java.nio.file.Path;
import java.util.Map;

public final class Check {
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Schematic both = SchematicReader.read(dir.resolve("test.litematic"), 4_000_000);
        Schematic main = SchematicReader.read(dir.resolve("test-main.litematic"), 4_000_000);
        Schematic nbt = SchematicReader.read(dir.resolve("test.nbt"), 4_000_000);
        Schematic schem = SchematicReader.read(dir.resolve("test.schem"), 4_000_000);
        Schematic compound = SchematicReader.read(dir.resolve("test-compound.litematic"), 4_000_000);
        Schematic compoundNeg = SchematicReader.read(dir.resolve("test-compound-neg.litematic"), 4_000_000);

        report("litematic(双区域)", both);
        report("litematic(主厅)", main);
        report("litematic(Position/Size 复合)", compound);
        report("litematic(复合 + 负 Size)", compoundNeg);
        report("原版 nbt", nbt);
        report("sponge schem", schem);

        // 主厅：三份文件同一栋房子，逐格必须完全一致
        checkDims(main, 8, 4, 6, "litematic 主厅尺寸");
        checkDims(nbt, 8, 4, 6, "nbt 尺寸");
        int diff = 0;
        for (int y = 0; y < main.sizeY; y++)
            for (int z = 0; z < main.sizeZ; z++)
                for (int x = 0; x < main.sizeX; x++)
                    if (!main.blockId(x, y, z).equals(nbt.blockId(x, y, z))) diff++;
        check(diff == 0, "litematic 与 nbt 逐格一致（不一致 " + diff + " 格）");

        // Position/Size 写成 {x,y,z} 复合、BlockStates 写成 long 列表的文件也要读得出来，
        // 而且读出来的是同一栋房子（整合包作者手里的文件就是这种）
        checkDims(compound, 8, 4, 6, "复合 Position/Size 尺寸");
        checkDims(compoundNeg, 8, 4, 6, "复合 + 负 Size 尺寸（取绝对值）");
        int diffCompound = 0;
        for (int y = 0; y < main.sizeY; y++)
            for (int z = 0; z < main.sizeZ; z++)
                for (int x = 0; x < main.sizeX; x++)
                    if (!main.blockId(x, y, z).equals(compound.blockId(x, y, z))) diffCompound++;
        check(diffCompound == 0, "复合写法与原版写法逐格一致（不一致 " + diffCompound + " 格）");
        check(compound.blockId(5, 1, 3).equals("minecraft:oak_stairs"),
                "复合写法的楼梯认出来了（" + compound.blockId(5, 1, 3) + "）");
        check(compound.solidCount == main.solidCount,
                "复合写法方块数一致（" + compound.solidCount + " vs " + main.solidCount + "）");

        // 双区域：13x4x9，侧翼在 x=10..12、z=0..2（Position(10,0,-1) + Size(3,3,-3) → 最小角 z=-3）
        checkDims(both, 13, 4, 9, "双区域包围盒");
        check(both.solidCount == main.solidCount + 18, "双区域方块数 = 主厅 + 侧翼 18（" + both.solidCount + "）");
        check(both.blockId(10, 0, 0).equals("minecraft:stone"), "侧翼落点 (10,0,0) 是石头");
        check(both.blockId(9, 0, 0).isEmpty(), "主厅与侧翼之间 (9,0,0) 是空的");
        check(both.blockId(3, 3, 5).equals("minecraft:stone"), "屋顶 (3,3,5) 是石头（主厅局部 z=2 落在全局 z=5）");
        check(both.blockId(3, 1, 0).isEmpty(), "门洞 (3,1,0) 是空的");
        check(both.blockId(5, 1, 6).equals("minecraft:oak_stairs"), "楼梯 (5,1,6) 认出来了（属性 facing=north 也带上了）");

        // 实心方块的外壳必须正好是 12 条棱
        SchematicOutline boxOutline = schem.creaseEdges(12000);
        check(boxOutline.count == 12, "实心 3x2x3 外壳 = 12 条棱（实际 " + boxOutline.count + "）");

        // ── 候选棱：折角全给（里外都算），谁挡谁交给机位去判 ──────────────────
        Schematic hollow = SchematicReader.read(dir.resolve("test-hollow.nbt"), 4_000_000);
        Schematic partition = SchematicReader.read(dir.resolve("test-hollow-partition.nbt"), 4_000_000);
        check(hollow.creaseEdges(12000).count == 24,
                "封死的 5x5x5 盒子候选棱 = 内外各 12 条（实际 " + hollow.creaseEdges(12000).count + "）");
        check(partition.creaseEdges(12000).count > hollow.creaseEdges(12000).count,
                "盒子里加一道隔墙，候选棱变多（里面那些交给机位判，实际 "
                        + partition.creaseEdges(12000).count + " 条）");

        // ── 消隐：按机位把看不见的段剔掉 ────────────────────────────────────
        // 封死的 5x5x5：从斜角看，12 条外棱里看得见 9 条（底角那三条被自己挡住），每条 5 格长
        Schematic box = SchematicReader.read(dir.resolve("test-hollow.nbt"), 4_000_000);
        SchematicTransform none = SchematicTransform.NONE;
        SchematicHiddenLines diag = new SchematicHiddenLines(box, none, 0, 0, 0, box.creaseEdges(12000));
        diag.resolve(100, 100, 100);
        check(Math.abs(visibleLength(diag) - 45F) < 0.6F,
                "斜角看立方体：9 条棱 × 5 格 = 45（实际 " + visibleLength(diag) + "）");
        // 底角那三条棱（x=0/y=0/z=0 两两相交的三条）被立方体自己挡住，一条都不该画
        float bottomBack = lineLength(diag, 1, 0.0F, 2, 0.0F)
                + lineLength(diag, 0, 0.0F, 2, 0.0F)
                + lineLength(diag, 0, 0.0F, 1, 0.0F);
        check(bottomBack < 0.6F, "底角那三条看不见的棱一条都不画（实际 " + bottomBack + " 格）");

        // 正对 +x：整个立方体只剩正对那一面的方框
        SchematicHiddenLines front = new SchematicHiddenLines(box, none, 0, 0, 0, box.creaseEdges(12000));
        front.resolve(100, 2.5, 2.5);
        check(Math.abs(visibleLength(front) - 20F) < 1.2F,
                "正对 +x：只剩正对那一面的 4 条棱 = 20 格（实际 " + visibleLength(front) + "）");

        // 挡一半就画一半：长条正面那条沿 z 的棱（x=3, y=2）本该 0..11，
        // 挡板盖住 z=0..5，只剩 z=6..11 这 5 格
        Schematic halfBar = SchematicReader.read(dir.resolve("test-half.nbt"), 4_000_000);
        SchematicHiddenLines halfOcc = new SchematicHiddenLines(halfBar, none, 0, 0, 0,
                halfBar.creaseEdges(12000));
        halfOcc.resolve(-100, 1.0F, 5);
        check(Math.abs(lineLength(halfOcc, 0, 3.0F, 1, 2.0F) - 5F) < 0.6F,
                "被挡掉半截的墙线只画露出的 5 格（实际 " + lineLength(halfOcc, 0, 3.0F, 1, 2.0F) + " 格）");

        // 外轮廓（剪影）是跟着机位算的：立方体从斜角看，9 条可见棱里 6 条是轮廓、3 条是内部折线
        int outline = 0;
        int inner = 0;
        int fine = 0;
        for (int i = 0; i < diag.count(); i++) {
            switch (diag.flag(i)) {
                case SchematicHiddenLines.OUTLINE -> outline++;
                case SchematicHiddenLines.MAIN -> inner++;
                default -> fine++;
            }
        }
        check(outline == 6, "立方体斜角看：6 条外轮廓（实际 " + outline + " / 共 " + diag.count() + " 段）");
        check(inner == 3, "另外 3 条是内部折线（实际 " + inner + "）");
        check(fine == 0, "立方体全是凸棱，没有细节线（实际 " + fine + "）");

        // 两个盒子错开叠：后墙左边那根竖棱被前面的墙挡住 → 整根不画；右边那根露着 → 整根画
        Schematic stack = SchematicReader.read(dir.resolve("test-stack.nbt"), 4_000_000);
        SchematicHiddenLines st = new SchematicHiddenLines(stack, none, 0, 0, 0, stack.creaseEdges(12000));
        st.resolve(3, 3, 100);
        float hiddenEdge = verticalAt(st, 0, 2);
        float shownEdge = verticalAt(st, 6, 2);
        check(hiddenEdge < 0.6F, "被前面那个盒子挡住的那半条轮廓不画（" + hiddenEdge + " 格）");
        check(shownEdge > 5.4F, "没被挡住的那半条轮廓照画（" + shownEdge + " 格 / 6）");
        check(shownEdge + hiddenEdge > 5.4F && shownEdge + hiddenEdge < 6.6F,
                "同一根竖棱：挡住的那段不画、露出的那段画，加起来还是整根（" + hiddenEdge + " + "
                        + shownEdge + "）");

        // 两档线：整栋只有大线框时全是结构线，1 格的小凸起算装饰线
        Schematic decor = SchematicReader.read(dir.resolve("test-hollow-decor.nbt"), 4_000_000);
        SchematicHiddenLines dec = new SchematicHiddenLines(decor, none, 0, 0, 0,
                decor.creaseEdges(12000));
        dec.resolve(60, 40, 40);
        int mainLines = 0;
        int fineLines = 0;
        for (int i = 0; i < dec.count(); i++) {
            if (dec.flag(i) == SchematicHiddenLines.MAIN) mainLines++;
            else fineLines++;
        }
        check(mainLines > 0, "墙面装饰那份有结构线（" + mainLines + " 条）");
        check(fineLines > 0, "1 格的凸起出的是装饰线（" + fineLines + " 条）");

        // ── 方块形状：楼梯/半砖/活板门不是满格立方体 ────────────────────────
        float[] stairShape = BlockShapes.of("minecraft:oak_stairs",
                Map.of("facing", "east", "half", "bottom"));
        check(stairShape.length == 12,
                "楼梯 = 底下一层 + 上面半块 = 2 个盒子（实际 " + stairShape.length / 6 + "）");
        // 朝向照客户端模型文件来：stairs.json 的上半级在 x 8..16，facing=east 不带旋转
        check(near(stairShape[6], 0.5F) && near(stairShape[9], 1F),
                "facing=east 的楼梯，上半级升在东半（x " + stairShape[6] + ".." + stairShape[9] + "）");
        float[] stairNorth = BlockShapes.of("minecraft:oak_stairs",
                Map.of("facing", "north", "half", "bottom"));
        check(near(stairNorth[8], 0F) && near(stairNorth[11], 0.5F),
                "facing=north 的楼梯，上半级升在北半（z " + stairNorth[8] + ".." + stairNorth[11] + "）");
        float[] slabShape = BlockShapes.of("minecraft:oak_slab", Map.of("type", "bottom"));
        check(slabShape.length == 6 && near(slabShape[4], 0.5F),
                "半砖只有半个高（y 到 " + slabShape[4] + "）");
        float[] trapShape = BlockShapes.of("minecraft:oak_trapdoor", Map.of("half", "bottom"));
        check(trapShape.length == 6 && near(trapShape[4], 0.1875F),
                "活板门是 3/16 厚的板（y 到 " + trapShape[4] + "）");
        float[] fenceShape = BlockShapes.of("minecraft:oak_fence",
                Map.of("north", "true", "south", "true"));
        check(fenceShape.length > 12,
                "栅栏 = 柱子 + 每方向两道横杆（" + fenceShape.length / 6 + " 个盒子）");
        check(BlockShapes.of("minecraft:short_grass", Map.of()).length == 0,
                "十字模型的草整格跳过（不画线也不挡视线）");
        check(BlockShapes.of("minecraft:potted_red_tulip", Map.of()).length == 18,
                "花盆 = 盆 + 盆里那棵（3 个盒子，" + BlockShapes.of("minecraft:potted_red_tulip", Map.of()).length / 6 + "）");
        float[] wallButton = BlockShapes.of("minecraft:oak_button",
                Map.of("face", "wall", "facing", "north"));
        check(wallButton.length == 6 && near(wallButton[2], 0F) && near(wallButton[5], 0.125F),
                "墙上的按钮贴在北面墙上、厚 2/16（z " + wallButton[2] + ".." + wallButton[5] + "）");
        float[] floorButton = BlockShapes.of("minecraft:oak_button",
                Map.of("face", "floor", "facing", "north"));
        check(floorButton.length == 6 && near(floorButton[4], 0.125F),
                "地上的按钮高 2/16（y 到 " + floorButton[4] + "）");
        float[] lanternShape = BlockShapes.of("minecraft:lantern", Map.of());
        check(lanternShape.length == 6 && near(lanternShape[4], 0.5F),
                "灯笼不是满格方块（y 到 " + lanternShape[4] + "）");
        float[] flowerShape = BlockShapes.of("minecraft:oxeye_daisy", Map.of());
        check(flowerShape.length == 6 && near(flowerShape[4], 0.625F),
                "花占一小块体积，不是空气（y 到 " + (flowerShape.length == 6 ? flowerShape[4] : -1) + "）");

        // ── 遮挡精度细到盒子：半砖只挡住柱子下半格 ──────────────────────────
        // 按整格算的话，半砖会把后面整根竖棱吞掉；按盒子算只该吞下半格。
        Schematic slabOcclusion = SchematicReader.read(dir.resolve("test-slab-occlusion.nbt"), 4_000_000);
        SchematicHiddenLines slabHide = new SchematicHiddenLines(slabOcclusion, none, 0, 0, 0,
                slabOcclusion.creaseEdges(12000));
        slabHide.resolve(0.5, 0.5, -20);
        float slabShown = lineLength(slabHide, 0, 0F, 2, 1F);
        check(Math.abs(slabShown - 0.5F) < 0.2F,
                "半砖只挡住柱子下半格，露出上半格（实际 " + slabShown + " 格 / 0.5）");

        // 出生点：主厅中心附近、脚下一格实心
        Schematic.Spawn spawn = nbt.spawnCandidate();
        check(spawn != null, "主厅找到出生格");
        if (spawn != null) {
            check(nbt.solid(spawn.x(), spawn.y() - 1, spawn.z()), "出生格脚下是实心");
            check(!nbt.solid(spawn.x(), spawn.y(), spawn.z()), "出生格本身是空的");
        }

        // 旋转：占地互换、四个角一一对应
        SchematicTransform t = new SchematicTransform(1, false, false);
        check(t.width(8, 6) == 6 && t.depth(8, 6) == 8, "转 90° 占地 6x8");
        check(t.blockX(0, 0, 8, 6) == 5 && t.blockZ(0, 0, 8, 6) == 0, "角点 (0,0) -> (5,0)");
        check(t.blockX(7, 0, 8, 6) == 5 && t.blockZ(7, 0, 8, 6) == 7, "角点 (7,0) -> (5,7)");
        check(t.blockX(0, 5, 8, 6) == 0 && t.blockZ(0, 5, 8, 6) == 0, "角点 (0,5) -> (0,0)");
        check(t.front(3) == 0, "出口 -z 顺时针转 90° 之后朝 +x（北面转到东面）");

        // 连转两次 90° 应当等于转 180°（把换算当成函数复合验一遍，比手算角点可靠）
        SchematicTransform half = new SchematicTransform(2, false, false);
        int mismatched = 0;
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 6; z++) {
                int x1 = t.blockX(x, z, 8, 6);
                int z1 = t.blockZ(x, z, 8, 6);
                int x2 = t.blockX(x1, z1, 6, 8);
                int z2 = t.blockZ(x1, z1, 6, 8);
                if (x2 != half.blockX(x, z, 8, 6) || z2 != half.blockZ(x, z, 8, 6)) mismatched++;
            }
        }
        check(mismatched == 0, "转 90° 两次 = 转 180°（不一致 " + mismatched + " 格）");

        // 大结构：耗时和线段数
        long t0 = System.currentTimeMillis();
        Schematic big = SchematicReader.read(dir.resolve("big.litematic"), 4_000_000);
        long tRead = System.currentTimeMillis() - t0;
        t0 = System.currentTimeMillis();
        SchematicOutline bigOutline = big.creaseEdges(12000);
        long tOut = System.currentTimeMillis() - t0;
        System.out.println("大塔 " + big.describe() + "：读 " + tRead + " ms，线框 " + tOut
                + " ms，" + bigOutline.rawCount + " 单位棱 → " + bigOutline.count + " 段"
                + "（受上限 12000 约束前应为 " + cut(bigOutline.rawCount) + "）");

        System.out.println(failures == 0 ? "==== 全部通过 ====" : "==== 失败 " + failures + " 项 ====");
    }

    /** 可见线段总长（格）。 */
    static float visibleLength(SchematicHiddenLines h) {
        float[] segs = h.segments();
        float total = 0.0F;
        for (int i = 0; i < h.count(); i++) {
            float dx = segs[i * 6 + 3] - segs[i * 6];
            float dy = segs[i * 6 + 4] - segs[i * 6 + 1];
            float dz = segs[i * 6 + 5] - segs[i * 6 + 2];
            total += (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return total;
    }

    /** 两个坐标都固定在给定值上的那条棱（沿第三轴的线）的可见总长。 */
    static float lineLength(SchematicHiddenLines h, int axisA, float valueA, int axisB, float valueB) {
        float[] segs = h.segments();
        float total = 0.0F;
        for (int i = 0; i < h.count(); i++) {
            if (Math.abs(segs[i * 6 + axisA] - valueA) > 1e-3F
                    || Math.abs(segs[i * 6 + 3 + axisA] - valueA) > 1e-3F) continue;
            if (Math.abs(segs[i * 6 + axisB] - valueB) > 1e-3F
                    || Math.abs(segs[i * 6 + 3 + axisB] - valueB) > 1e-3F) continue;
            float dx = segs[i * 6 + 3] - segs[i * 6];
            float dy = segs[i * 6 + 4] - segs[i * 6 + 1];
            float dz = segs[i * 6 + 5] - segs[i * 6 + 2];
            total += (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return total;
    }

    /** x/z 固定、沿 y 的可见总长。 */
    static float verticalAt(SchematicHiddenLines h, float px, float pz) {
        float[] segs = h.segments();
        float total = 0.0F;
        for (int i = 0; i < h.count(); i++) {
            if (Math.abs(segs[i * 6] - px) > 1e-3F || Math.abs(segs[i * 6 + 3] - px) > 1e-3F) continue;
            if (Math.abs(segs[i * 6 + 2] - pz) > 1e-3F || Math.abs(segs[i * 6 + 5] - pz) > 1e-3F) continue;
            total += Math.abs(segs[i * 6 + 4] - segs[i * 6 + 1]);
        }
        return total;
    }

    static boolean near(float a, float b) {
        return Math.abs(a - b) < 1.0E-3F;
    }

    static String cut(int n) {
        return String.valueOf(n);
    }

    static void report(String label, Schematic s) {
        SchematicOutline o = s.creaseEdges(12000);
        System.out.println(label + "：" + s.describe() + "，y " + s.minY + ".." + s.maxY
                + "，线框 " + o.rawCount + " → " + o.count + " 段");
    }

    static void checkDims(Schematic s, int x, int y, int z, String what) {
        check(s.sizeX == x && s.sizeY == y && s.sizeZ == z,
                what + "（实际 " + s.sizeX + "x" + s.sizeY + "x" + s.sizeZ + "）");
    }

    static void check(boolean ok, String what) {
        if (!ok) failures++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }
}
