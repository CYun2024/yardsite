package dev.yard.site.schematic;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 读 WorldEdit / Sponge 的 {@code .schem}（v1～v3）。
 *
 * <p>格式：{@code Width/Height/Length} 三个尺寸 + {@code Palette}（"方块[属性]" → 下标）
 * + {@code BlockData}（一串 LEB128 varint 的下标，按 y→z→x 排列）。
 *
 * <p>属性是写在名字里的（{@code minecraft:oak_stairs[facing=north,half=bottom]}），
 * 这里顺手拆成 id + 属性表，放方块时才能把朝向还原回去。
 */
final class SpongeReader {

    private SpongeReader() {}

    static Schematic read(Map<String, Object> root, Path file, int voxelCap) throws IOException {
        int sx = Nbt.intValue(root, "Width", 0);
        int sy = Nbt.intValue(root, "Height", 0);
        int sz = Nbt.intValue(root, "Length", 0);
        SchematicReader.checkSize(sx, sy, sz, voxelCap);

        byte[] data = Nbt.byteArray(root, "BlockData");
        if (data == null) throw new IOException("schem 里没有 BlockData");
        Map<String, Object> paletteTag = Nbt.compound(root, "Palette");
        if (paletteTag.isEmpty()) throw new IOException("schem 里没有 Palette");

        // 调色板里的下标不一定连续，先铺成「下标 → 方块」的数组
        int maxIndex = 0;
        for (Object value : paletteTag.values()) {
            if (value instanceof Number n) maxIndex = Math.max(maxIndex, n.intValue());
        }
        String[] byIndex = new String[maxIndex + 1];
        for (Map.Entry<String, Object> entry : paletteTag.entrySet()) {
            if (!(entry.getValue() instanceof Number n)) continue;
            int index = n.intValue();
            if (index >= 0 && index < byIndex.length) byIndex[index] = entry.getKey();
        }

        SchematicReader.Palette palette = new SchematicReader.Palette();
        int[] remap = new int[byIndex.length];
        for (int i = 0; i < byIndex.length; i++) {
            String raw = byIndex[i];
            if (raw == null) {
                remap[i] = -1;
                continue;
            }
            String id = raw;
            Map<String, String> properties = Map.of();
            int bracket = raw.indexOf('[');
            if (bracket > 0 && raw.endsWith("]")) {
                id = raw.substring(0, bracket);
                properties = splitProperties(raw.substring(bracket + 1, raw.length() - 1));
            }
            remap[i] = SchematicReader.isAir(id) ? -1 : palette.index(id, properties);
        }

        int[] cells = SchematicReader.emptyCells(sx, sy, sz);
        int volume = sx * sy * sz;
        int cursor = 0;
        int index = 0;
        while (index < volume && cursor < data.length) {
            int value = 0;
            int shift = 0;
            while (true) {
                if (cursor >= data.length) break;
                int b = data[cursor++] & 0xFF;
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
                if (shift > 28) break;
            }
            if (value >= 0 && value < remap.length) {
                int global = remap[value];
                if (global >= 0) {
                    int x = index % sx;
                    int z = (index / sx) % sz;
                    int y = index / (sx * sz);
                    SchematicReader.put(cells, sx, sy, sz, x, y, z, global);
                }
            }
            index++;
        }

        String name = SchematicReader.stripExtension(file.getFileName().toString());
        return new Schematic(name, "", sx, sy, sz, cells, palette.ids, palette.props);
    }

    /** {@code facing=north,half=bottom} → Map。 */
    private static Map<String, String> splitProperties(String text) {
        if (text.isBlank()) return Map.of();
        Map<String, String> map = new LinkedHashMap<>();
        for (String part : text.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            map.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        return map;
    }
}
