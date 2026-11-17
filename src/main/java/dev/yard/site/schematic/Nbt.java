package dev.yard.site.schematic;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 最小 NBT 读取器：把整份文件读成 {@code Map/List/数组}，够读投影文件用。
 *
 * <p><b>为什么不直接用原版的 {@code NbtIo}</b>：投影解析是纯数据活，写成纯 Java 之后
 * 可以脱离游戏单独跑（{@code tools/schematic-check} 就是这么验的），改解析器不用起客户端。
 *
 * <p>返回值里的类型：
 * <ul>
 *   <li>复合 → {@link Map}（保持文件里的顺序，方便调试）</li>
 *   <li>列表 → {@link List}（元素可以是任意上面这些）</li>
 *   <li>byte/short/int/long → {@link Byte}/{@link Short}/{@link Integer}/{@link Long}</li>
 *   <li>float/double → {@link Float}/{@link Double}</li>
 *   <li>字符串 → {@link String}</li>
 *   <li>数组 → {@code byte[]/int[]/long[]}</li>
 * </ul>
 *
 * <p>解压按<b>头部魔数</b>判（0x1f 0x8b = gzip）：Litematica 与 Sponge 都是 gzip，
 * 而原版结构文件有些工具会存成未压缩的裸 NBT，两种都读。
 */
final class Nbt {

    private static final int TAG_END = 0;
    private static final int TAG_COMPOUND = 10;

    /**
     * 单个数组/列表的长度上限：坏文件或恶意文件别把内存撑爆。
     *
     * <p>8M 已经远超实际需要（{@code structure.maxVoxels} 默认 400 万格，按每格 4 字节的
     * BlockStates 算也就 2M 个 long），所以这里只是兜底，正常文件碰不到。
     */
    private static final int MAX_ELEMENTS = 8 * 1024 * 1024;

    private Nbt() {}

    static Map<String, Object> readFile(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return read(in);
        }
    }

    static Map<String, Object> read(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(decompress(raw));
        int type = in.readUnsignedByte();
        if (type != TAG_COMPOUND) {
            throw new IOException("不是 NBT 文件（首标签是 " + type + "，应当是 10）");
        }
        in.readUTF();   // 根标签的名字，投影文件里都是空的
        return readCompound(in);
    }

    /** 有 gzip 头就解压，没有就原样读（两个字节先看一眼再放回去）。 */
    private static InputStream decompress(InputStream raw) throws IOException {
        PushbackInputStream push = new PushbackInputStream(new BufferedInputStream(raw, 65536), 2);
        int b0 = push.read();
        int b1 = push.read();
        if (b1 >= 0) push.unread(b1);
        if (b0 >= 0) push.unread(b0);
        if (b0 == 0x1F && b1 == 0x8B) {
            return new GZIPInputStream(push, 65536);
        }
        return push;
    }

    private static Map<String, Object> readCompound(DataInputStream in) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == TAG_END) return map;
            String name = in.readUTF();
            map.put(name, readPayload(in, type));
        }
    }

    private static Object readPayload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1:
                return in.readByte();
            case 2:
                return in.readShort();
            case 3:
                return in.readInt();
            case 4:
                return in.readLong();
            case 5:
                return in.readFloat();
            case 6:
                return in.readDouble();
            case 7: {
                int n = length(in.readInt());
                byte[] data = new byte[n];
                in.readFully(data);
                return data;
            }
            case 8:
                return in.readUTF();
            case 9: {
                int elementType = in.readUnsignedByte();
                int n = length(in.readInt());
                List<Object> list = new ArrayList<>(Math.min(n, 4096));
                for (int i = 0; i < n; i++) {
                    list.add(readPayload(in, elementType));
                }
                return list;
            }
            case 10:
                return readCompound(in);
            case 11: {
                int n = length(in.readInt());
                int[] data = new int[n];
                for (int i = 0; i < n; i++) data[i] = in.readInt();
                return data;
            }
            case 12: {
                int n = length(in.readInt());
                long[] data = new long[n];
                for (int i = 0; i < n; i++) data[i] = in.readLong();
                return data;
            }
            default:
                throw new IOException("未知 NBT 标签类型 " + type);
        }
    }

    private static int length(int n) throws IOException {
        if (n < 0 || n > MAX_ELEMENTS) {
            throw new IOException("NBT 数组长度异常：" + n);
        }
        return n;
    }

    // ── 取值小工具：类型不对就退回默认值，不让一个字段毁掉整份文件 ──────────

    @SuppressWarnings("unchecked")
    static Map<String, Object> compound(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof List ? (List<Object>) value : List.of();
    }

    static int intValue(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    static long longValue(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    static int[] intArray(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof int[] ? (int[]) value : null;
    }

    /**
     * 取一个三维坐标/尺寸（litematic 的 {@code Position} / {@code Size}）。
     *
     * <p>同一个字段在实际文件里有<b>三种写法</b>，都得认：
     * <ol>
     *   <li>{@code [x, y, z]} —— {@code TAG_Int_Array}：原版 {@code NbtUtils.writeBlockPos} 的写法，
     *       Litematica 自己也这么存；</li>
     *   <li>{@code [x, y, z]} —— {@code TAG_List<TAG_Int>}：老版本和一些导出工具走的是数字列表；</li>
     *   <li>{@code {x: …, y: …, z: …}} —— {@code TAG_Compound}：按 {@code BlockPos} 编解码写出来的工具
     *       （实测有整合包作者手里的"樱花小屋"这类文件就是这种）。</li>
     * </ol>
     * 只认第一种的话，第三种文件的每个 region 都会被判成"字段缺失"整块跳过，
     * 最后只剩一句「没有可用的区域」——文件明明是好的，人却不知道哪里错了。
     *
     * @return 长度为 3 的数组；字段缺失或类型不认识返回 {@code null}
     */
    static int[] vec3(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof int[] array) {
            return array.length == 3 ? array : null;
        }
        if (value instanceof List<?> list) {
            if (list.size() != 3) return null;
            int[] out = new int[3];
            for (int i = 0; i < 3; i++) {
                if (!(list.get(i) instanceof Number number)) return null;
                out[i] = number.intValue();
            }
            return out;
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> compound = compound(map, key);
            Object x = compound.get("x");
            Object y = compound.get("y");
            Object z = compound.get("z");
            if (x instanceof Number && y instanceof Number && z instanceof Number) {
                return new int[]{((Number) x).intValue(), ((Number) y).intValue(),
                        ((Number) z).intValue()};
            }
        }
        return null;
    }

    /** long 数组；顺手认一下「数字列表」的写法（个别工具不写 {@code TAG_Long_Array}）。 */
    static long[] longArray(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof long[] array) return array;
        if (value instanceof List<?> list) {
            long[] out = new long[list.size()];
            for (int i = 0; i < out.length; i++) {
                if (!(list.get(i) instanceof Number number)) return null;
                out[i] = number.longValue();
            }
            return out;
        }
        return null;
    }

    static byte[] byteArray(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof byte[] ? (byte[]) value : null;
    }

    static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String ? (String) value : "";
    }
}
