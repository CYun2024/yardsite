package dev.yard.site.view;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 选址图「编辑模式」的持久参数：两根柱子宽度 + 自定义群系颜色。
 *
 * <p>存在 {@code config/yardsite/site_biome_colors.json}，改完<b>下次建世界还在</b>。
 * 只影响选址图（和进世界前那段动画）画出来的颜色，<b>不动真实地形、不动群系本身</b>。
 *
 * <h2>渲染器怎么知道颜色变了</h2>
 * 每次改动把 {@link #version()} 加一，并在 {@link #lastSingleChange()} 留下「只动了哪个群系」
 * （大面积变化时是 null）。{@link SiteTerrainRenderer} 每帧比一下版本号：单个群系就只重算
 * 那种群系的柱子，其他情况整张图的柱子缓存作废。拖动取色方块时一秒能改几十次，靠这个才不卡。
 */
final class SiteEditorPrefs {

    /** 配置文件（本 mod 自己的配置目录）。 */
    static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("yardsite").resolve("site_biome_colors.json");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final float WIDTH_MIN = 0.5F;
    private static final float WIDTH_MAX = 2.0F;

    /** 群系 id → 自定义底色 0xRRGGBB（没有就是原版配色）。 */
    private static final Map<ResourceLocation, Integer> COLORS = new ConcurrentHashMap<>();
    /** 改动顺序（面板上「已改 N 种」按这个顺序列出来）。 */
    private static final List<ResourceLocation> ORDER = new ArrayList<>();
    private static final Object ORDER_LOCK = new Object();

    private static float sandboxWidth = 1.0F;
    private static float detailWidth = 1.0F;
    private static int version;
    @Nullable
    private static ResourceLocation lastSingleChange;
    private static boolean loaded;

    private SiteEditorPrefs() {}

    /** 第一次打开选区图时读一次；文件不存在/坏了都用默认值，不报错。 */
    static void load() {
        synchronized (ORDER_LOCK) {
            loaded = true;
            COLORS.clear();
            ORDER.clear();
            try {
                if (Files.isRegularFile(FILE)) {
                    JsonElement root = JsonParser.parseString(
                            Files.readString(FILE, StandardCharsets.UTF_8));
                    if (root.isJsonObject()) {
                        readFrom(root.getAsJsonObject());
                    }
                }
            } catch (Exception e) {
                System.out.println("[yardsite] 读取选址配色 " + FILE + " 失败，用默认配色：" + e);
            }
            bump(null);
        }
    }

    private static void readFrom(JsonObject root) {
        sandboxWidth = clampWidth(root.has("sandboxWidth") ? root.get("sandboxWidth").getAsFloat() : 1.0F);
        detailWidth = clampWidth(root.has("detailWidth") ? root.get("detailWidth").getAsFloat() : 1.0F);
        if (!root.has("biomeColors") || !root.get("biomeColors").isJsonObject()) return;
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("biomeColors").entrySet()) {
            ResourceLocation id = parse(entry.getKey());
            int rgb = parseRgb(entry.getValue().getAsString());
            if (id == null || rgb < 0) continue;
            COLORS.put(id, rgb);
            ORDER.add(id);
        }
        System.out.println("[yardsite] 选址自定义群系颜色：载入 " + ORDER.size() + " 种");
    }

    /** 写盘（拖动滑条/取色方块期间不用每次调，松手再调一次）。 */
    static void save() {
        JsonObject root = new JsonObject();
        root.addProperty("sandboxWidth", sandboxWidth);
        root.addProperty("detailWidth", detailWidth);
        JsonObject colors = new JsonObject();
        for (ResourceLocation id : orderedIds()) {
            Integer rgb = COLORS.get(id);
            if (rgb != null) colors.addProperty(id.toString(), hex(rgb));
        }
        root.add("biomeColors", colors);
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[yardsite] 写选址配色 " + FILE + " 失败：" + e);
        }
    }

    private static void ensureLoaded() {
        if (!loaded) load();
    }

    // ── 群系颜色 ────────────────────────────────────────────────────────

    /** 这个群系有没有自定义底色；没有返回 null（用原版配色）。 */
    @Nullable
    static Integer colorOf(ResourceLocation id) {
        return COLORS.get(id);
    }

    /** 设一个群系的底色；传回原版色就等于取消自定义。 */
    static void setColor(ResourceLocation id, int rgb) {
        ensureLoaded();
        Integer before = COLORS.put(id, rgb & 0xFFFFFF);
        if (before == null) {
            synchronized (ORDER_LOCK) {
                if (!ORDER.contains(id)) ORDER.add(id);
            }
        }
        bump(id);
    }

    /** 恢复某个群系的原版配色。 */
    static void clear(ResourceLocation id) {
        if (COLORS.remove(id) != null) {
            synchronized (ORDER_LOCK) {
                ORDER.remove(id);
            }
            bump(id);
        }
    }

    /** 全部恢复原版配色。 */
    static void clearAll() {
        ensureLoaded();
        if (COLORS.isEmpty()) return;
        COLORS.clear();
        synchronized (ORDER_LOCK) {
            ORDER.clear();
        }
        bump(null);
        save();
    }

    /** 改过色的群系（按改动顺序）。 */
    static List<ResourceLocation> orderedIds() {
        ensureLoaded();
        synchronized (ORDER_LOCK) {
            return List.copyOf(ORDER);
        }
    }

    static int customCount() {
        ensureLoaded();
        return COLORS.size();
    }

    // ── 柱子宽度 ────────────────────────────────────────────────────────

    static float sandboxWidth() {
        ensureLoaded();
        return sandboxWidth;
    }

    static float detailWidth() {
        ensureLoaded();
        return detailWidth;
    }

    static void setSandboxWidth(float value) {
        ensureLoaded();
        sandboxWidth = clampWidth(value);
    }

    static void setDetailWidth(float value) {
        ensureLoaded();
        detailWidth = clampWidth(value);
    }

    private static float clampWidth(float value) {
        return Math.max(WIDTH_MIN, Math.min(WIDTH_MAX, value));
    }

    // ── 版本号 ──────────────────────────────────────────────────────────

    /** 每次颜色改动加一，渲染器靠它作废柱子缓存。 */
    static int version() {
        return version;
    }

    /** 上一次变化只动了这一个群系（大面积变化 = null）。 */
    @Nullable
    static ResourceLocation lastSingleChange() {
        return lastSingleChange;
    }

    /** null = 大面积变化（清档、载入），整张图的柱子缓存都要重算。 */
    private static void bump(@Nullable ResourceLocation single) {
        version++;
        lastSingleChange = single;
    }

    // ── 小工具 ──────────────────────────────────────────────────────────

    @Nullable
    private static ResourceLocation parse(String text) {
        try {
            return ResourceLocation.parse(text);
        } catch (Exception e) {
            return null;
        }
    }

    /** 支持 {@code 6FAE4A} 和 {@code #6FAE4A}；读不出来返回 -1。 */
    private static int parseRgb(String text) {
        String body = text.startsWith("#") ? text.substring(1) : text;
        try {
            return (int) (Long.parseUnsignedLong(body, 16) & 0xFFFFFFL);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 0xRRGGBB → {@code RRGGBB}（写进配置文件用）。 */
    static String hex(int rgb) {
        return String.format(java.util.Locale.ROOT, "%06X", rgb & 0xFFFFFF);
    }
}
