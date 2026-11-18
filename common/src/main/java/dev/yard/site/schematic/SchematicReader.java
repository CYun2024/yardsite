package dev.yard.site.schematic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 读投影/结构文件。<b>不需要装任何投影 mod</b>：这些格式都只是 gzip 过的 NBT，
 * 自己解就行（见 {@link Nbt}）。
 *
 * <p>认得的格式（按扩展名分派，不看内容，因为内容都是 NBT 复合标签、分不出来）：
 * <table border="1">
 *   <caption>支持的文件</caption>
 *   <tr><th>扩展名</th><th>来源</th><th>说明</th></tr>
 *   <tr><td>{@code .litematic}</td><td>Litematica</td><td>可以有多块区域（region），全部并进同一份体素里</td></tr>
 *   <tr><td>{@code .nbt}</td><td>原版结构方块</td><td>别人给你发的结构文件、数据包里的结构都是这个</td></tr>
 *   <tr><td>{@code .schem}</td><td>WorldEdit / Sponge</td><td>v1/v2/v3 的方块数据都是 varint 流</td></tr>
 * </table>
 *
 * <p>投影文件里的空气、洞穴空气、结构空位一律当空（不然整个外壳会被"空气块"包一层，
 * 线框就看不见了）。
 */
public final class SchematicReader {

    /** 总格数上限：超过就不读（几千万格的体素表会直接把内存吃光）。 */
    private static final int HARD_VOXEL_CAP = 64 * 1024 * 1024;

    private SchematicReader() {}

    /** 这个文件名看着像不像我们能读的格式。 */
    public static boolean supported(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".litematic") || name.endsWith(".nbt") || name.endsWith(".schem");
    }

    public static String formatName(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".litematic")) return "Litematica";
        if (name.endsWith(".schem")) return "Sponge/WorldEdit";
        if (name.endsWith(".nbt")) return "原版结构";
        return "未知";
    }

    /**
     * 读一份文件。
     *
     * @param voxelCap 体素总数上限（占地长×宽×高）；超了抛异常，由调用方退回「只选出生点」
     */
    public static Schematic read(Path file, int voxelCap) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("找不到文件：" + file);
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        Map<String, Object> root = Nbt.readFile(file);
        Schematic schematic;
        if (name.endsWith(".litematic")) {
            schematic = LitematicReader.read(root, file, voxelCap);
        } else if (name.endsWith(".schem")) {
            schematic = SpongeReader.read(root, file, voxelCap);
        } else {
            schematic = readStructure(root, file, voxelCap);
        }
        if (schematic.solidCount == 0) {
            throw new IOException("这份结构里一个方块都没有（" + file.getFileName() + "）");
        }
        return schematic;
    }

    /** 原版结构方块文件：{@code size / palette / blocks}。 */
    private static Schematic readStructure(Map<String, Object> root, Path file, int voxelCap)
            throws IOException {
        List<Object> sizeTag = Nbt.list(root, "size");
        if (sizeTag.size() != 3) throw new IOException("原版结构文件缺少 size");
        int sx = number(sizeTag.get(0));
        int sy = number(sizeTag.get(1));
        int sz = number(sizeTag.get(2));
        checkSize(sx, sy, sz, voxelCap);

        Palette palette = new Palette();
        for (Object entry : Nbt.list(root, "palette")) {
            if (!(entry instanceof Map<?, ?> raw)) continue;
            Map<String, Object> state = castMap(raw);
            palette.index(Nbt.string(state, "Name"), properties(Nbt.compound(state, "Properties")));
        }
        if (palette.ids.isEmpty()) throw new IOException("原版结构文件缺少 palette");

        int[] cells = emptyCells(sx, sy, sz);
        for (Object entry : Nbt.list(root, "blocks")) {
            if (!(entry instanceof Map<?, ?> raw)) continue;
            Map<String, Object> block = castMap(raw);
            int[] pos = new int[3];
            List<Object> posTag = Nbt.list(block, "pos");
            if (posTag.size() != 3) continue;
            pos[0] = number(posTag.get(0));
            pos[1] = number(posTag.get(1));
            pos[2] = number(posTag.get(2));
            int state = Nbt.intValue(block, "state", -1);
            if (state < 0 || state >= palette.ids.size()) continue;
            if (isAir(palette.ids.get(state))) continue;
            put(cells, sx, sy, sz, pos[0], pos[1], pos[2], state);
        }
        String name = file.getFileName().toString();
        return new Schematic(stripExtension(name), "", sx, sy, sz, cells, palette.ids, palette.props);
    }

    // ── 共用小工具（Litematic/Sponge 读的时候也用）────────────────────────

    static int number(Object tag) {
        return tag instanceof Number n ? n.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> castMap(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

    /** {@code Properties: {facing: "north"}} → 普通 Map（值统一成字符串）。 */
    static Map<String, String> properties(Map<String, Object> tag) {
        if (tag.isEmpty()) return Map.of();
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : tag.entrySet()) {
            map.put(e.getKey(), String.valueOf(e.getValue()));
        }
        return map;
    }

    static void checkSize(int sx, int sy, int sz, int voxelCap) throws IOException {
        if (sx <= 0 || sy <= 0 || sz <= 0) {
            throw new IOException("结构尺寸不合法：" + sx + "×" + sy + "×" + sz);
        }
        if (sx > Schematic.MAX_SIDE || sy > Schematic.MAX_SIDE || sz > Schematic.MAX_SIDE) {
            throw new IOException("结构单边超过 " + Schematic.MAX_SIDE + " 格（" + sx + "×" + sy + "×" + sz + "）");
        }
        long voxels = (long) sx * sy * sz;
        if (voxels > voxelCap) {
            throw new IOException("结构有 " + voxels + " 格，超过上限 " + voxelCap
                    + "（structure.maxVoxels 可以调大）");
        }
        if (voxels > HARD_VOXEL_CAP) {
            throw new IOException("结构有 " + voxels + " 格，超过硬上限 " + HARD_VOXEL_CAP);
        }
    }

    static int[] emptyCells(int sx, int sy, int sz) {
        int[] cells = new int[sx * sy * sz];
        java.util.Arrays.fill(cells, -1);
        return cells;
    }

    static void put(int[] cells, int sx, int sy, int sz, int x, int y, int z, int paletteIndex) {
        if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return;
        cells[(y * sz + z) * sx + x] = paletteIndex;
    }

    /** 这些方块等于「没有方块」：留空，不参与外壳。 */
    static boolean isAir(String id) {
        return id.equals("minecraft:air") || id.equals("minecraft:cave_air")
                || id.equals("minecraft:void_air") || id.equals("minecraft:structure_void");
    }

    static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    /**
     * 调色板：把「方块 id + 属性」去重成下标。不同区域（litematic 分 region）共用一份，
     * 这样同一个方块在整份结构里只有一个下标。
     */
    static final class Palette {
        final List<String> ids = new ArrayList<>();
        final List<Map<String, String>> props = new ArrayList<>();
        private final Map<String, Integer> lookup = new HashMap<>();

        int index(String id, Map<String, String> properties) {
            String key = id + properties;
            Integer existing = lookup.get(key);
            if (existing != null) return existing;
            int index = ids.size();
            ids.add(id);
            props.add(properties);
            lookup.put(key, index);
            return index;
        }
    }
}
