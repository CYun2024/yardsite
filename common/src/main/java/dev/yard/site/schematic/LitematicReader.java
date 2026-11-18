package dev.yard.site.schematic;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.yard.site.schematic.SchematicReader.Palette;

/**
 * 读 Litematica 的 {@code .litematic}。
 *
 * <p>格式（一层层往下）：
 * <pre>
 * { MinecraftDataVersion, Version, Metadata{Name, Author, Description, EnclosingSize},
 *   Regions: { 区域名: { Position:[x,y,z], Size:[x,y,z],
 *                        BlockStatePalette: [ {Name, Properties{...}}, ... ],
 *                        BlockStates: [long...], TileEntities:[], Entities:[] } } }
 * </pre>
 *
 * <h2>四个坑</h2>
 * <ul>
 *   <li><b>Size 可以是负数</b>：表示区域从 Position 往负方向铺开。所以真正的最小角是
 *       {@code Position + (Size<0 ? Size+1 : 0)}，边长取绝对值。</li>
 *   <li><b>Position/Size 有三种写法</b>：{@code TAG_Int_Array}（Litematica 自己）、数字列表、
 *       以及 {@code {x,y,z}} 复合标签（按 BlockPos 编解码写的工具）。统一走 {@link Nbt#vec3}。</li>
 *   <li><b>BlockStates 是连续位流</b>：每格 {@code bits = max(2, ceil(log2(调色板大小)))} 位，
 *       第 i 格从第 {@code i*bits} 位开始，<b>会跨 long 边界</b>（LitematicaBitArray 的做法），
 *       低位在前；long 数组长度是 {@code ceil(格数*bits/64)}。注意这和原版 BitStorage
 *       的「每个 long 装 64/bits 格、不跨 long」不同，按后者读会在 bits 不整除 64 时（如 6 位）
 *       从第 11 格起全错位。</li>
 *   <li><b>可以有多个区域</b>：正门、塔楼、地下室分开存很常见。这里全部并进同一份体素里，
 *       调色板也合成一份，于是对外只看得到一个完整的建筑。</li>
 * </ul>
 */
final class LitematicReader {

    private LitematicReader() {}

    static Schematic read(Map<String, Object> root, Path file, int voxelCap) throws IOException {
        Map<String, Object> regions = Nbt.compound(root, "Regions");
        if (regions.isEmpty()) throw new IOException("litematic 里没有 Regions（文件不完整？）");

        List<Region> parsed = new ArrayList<>();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        // 一块区域都没读成时，把第一块的原因带出去，别只留一句「没有可用的区域」
        String skipped = "";

        for (Map.Entry<String, Object> entry : regions.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> raw)) continue;
            Map<String, Object> region = SchematicReader.castMap(raw);
            // Position 有些工具干脆不写（= 原点），缺了不算错；Size 缺了才真读不了
            int[] position = Nbt.vec3(region, "Position");
            if (position == null) position = new int[]{0, 0, 0};
            int[] size = Nbt.vec3(region, "Size");
            if (size == null) {
                if (skipped.isEmpty()) skipped = "区域 " + entry.getKey() + " 缺少 Size（或写法不认识）";
                continue;
            }

            int[] dims = new int[3];
            int[] min = new int[3];
            for (int axis = 0; axis < 3; axis++) {
                dims[axis] = Math.abs(size[axis]);
                // 负的 Size = 从 Position 往负方向铺开，最小角要多让一格
                min[axis] = position[axis] + (size[axis] < 0 ? size[axis] + 1 : 0);
            }
            if (dims[0] <= 0 || dims[1] <= 0 || dims[2] <= 0) {
                if (skipped.isEmpty()) {
                    skipped = "区域 " + entry.getKey() + " 的 Size 有一个方向是 0（"
                            + size[0] + "," + size[1] + "," + size[2] + "）";
                }
                continue;
            }

            Palette palette = readPalette(region);
            long[] states = Nbt.longArray(region, "BlockStates");
            parsed.add(new Region(entry.getKey(), min, dims, palette, states));

            minX = Math.min(minX, min[0]);
            minY = Math.min(minY, min[1]);
            minZ = Math.min(minZ, min[2]);
            maxX = Math.max(maxX, min[0] + dims[0]);
            maxY = Math.max(maxY, min[1] + dims[1]);
            maxZ = Math.max(maxZ, min[2] + dims[2]);
        }
        if (parsed.isEmpty()) {
            throw new IOException("litematic 里没有可用的区域" + (skipped.isEmpty() ? "" : "（" + skipped + "）"));
        }

        int sx = maxX - minX;
        int sy = maxY - minY;
        int sz = maxZ - minZ;
        SchematicReader.checkSize(sx, sy, sz, voxelCap);

        SchematicReader.Palette palette = new SchematicReader.Palette();
        int[] cells = SchematicReader.emptyCells(sx, sy, sz);

        for (Region region : parsed) {
            int dx = region.min[0] - minX;
            int dy = region.min[1] - minY;
            int dz = region.min[2] - minZ;
            int rx = region.dims[0];
            int ry = region.dims[1];
            int rz = region.dims[2];
            int paletteSize = region.palette.ids.size();
            if (paletteSize == 0) continue;

            // 这份区域自己的调色板下标 → 全局下标（空气直接 -1）
            int[] remap = new int[paletteSize];
            for (int i = 0; i < paletteSize; i++) {
                String id = region.palette.ids.get(i);
                remap[i] = SchematicReader.isAir(id) ? -1
                        : palette.index(id, region.palette.props.get(i));
            }

            if (paletteSize == 1) {
                // 没有 BlockStates 时整块区域都是同一种方块
                int global = remap[0];
                if (global < 0) continue;
                for (int y = 0; y < ry; y++) {
                    for (int z = 0; z < rz; z++) {
                        for (int x = 0; x < rx; x++) {
                            SchematicReader.put(cells, sx, sy, sz, dx + x, dy + y, dz + z, global);
                        }
                    }
                }
                continue;
            }

            long[] states = region.states;
            if (states == null) {
                throw new IOException("区域 " + region.name + " 有 " + paletteSize
                        + " 种方块却没有 BlockStates（文件被截断了？）");
            }
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
            long mask = (1L << bits) - 1L;
            int volume = rx * ry * rz;
            for (int index = 0; index < volume; index++) {
                long bitStart = (long) index * bits;
                int word = (int) (bitStart >>> 6);
                if (word >= states.length) break;
                int offset = (int) (bitStart & 63);
                long value;
                if (offset + bits <= 64) {
                    value = states[word] >>> offset;
                } else {
                    // 这一格横跨两个 long
                    if (word + 1 >= states.length) break;
                    value = states[word] >>> offset | states[word + 1] << (64 - offset);
                }
                int paletteIndex = (int) (value & mask);
                if (paletteIndex >= paletteSize) continue;
                int global = remap[paletteIndex];
                if (global < 0) continue;
                int x = index % rx;
                int z = (index / rx) % rz;
                int y = index / (rx * rz);
                SchematicReader.put(cells, sx, sy, sz, dx + x, dy + y, dz + z, global);
            }
        }

        Map<String, Object> metadata = Nbt.compound(root, "Metadata");
        String name = Nbt.string(metadata, "Name");
        if (name.isBlank()) name = SchematicReader.stripExtension(file.getFileName().toString());
        String author = Nbt.string(metadata, "Author");
        return new Schematic(name, author, sx, sy, sz, cells, palette.ids, palette.props);
    }

    private static Palette readPalette(Map<String, Object> region) {
        Palette palette = new Palette();
        for (Object entry : Nbt.list(region, "BlockStatePalette")) {
            if (!(entry instanceof Map<?, ?> raw)) continue;
            Map<String, Object> state = SchematicReader.castMap(raw);
            palette.index(Nbt.string(state, "Name"),
                    SchematicReader.properties(Nbt.compound(state, "Properties")));
        }
        return palette;
    }

    private record Region(String name, int[] min, int[] dims, Palette palette, long[] states) {}
}
