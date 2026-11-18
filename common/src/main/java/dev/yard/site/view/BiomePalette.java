package dev.yard.site.view;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 选址图上色用的共用调色板：大沙盘和区块精读都问同一套颜色，两边画出来才对得上。
 *
 * <p>采样是多线程的，所以只有<b>第一次出现某个群系 id</b> 时才进锁（一张图通常几十种），
 * 读侧走 {@link CopyOnWriteArrayList}，渲染线程按下标取颜色不用加锁。
 */
final class BiomePalette {

    /** 水面颜色：浅滩到深海的两端，按 (海平面 - 地表 y) / 30 插值。 */
    private static final int WATER_SHALLOW = 0x6FA8D6;
    private static final int WATER_DEEP = 0x1C3F6B;
    private static final int UNKNOWN_RGB = 0x505050;

    private final List<BiomeInfo> biomes = new CopyOnWriteArrayList<>();
    private final Map<ResourceLocation, Integer> index = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    /** 这个群系在 {@link #biomes} 里的下标；第一次出现时算好颜色并登记。 */
    int indexOf(ResourceLocation id, Holder<Biome> holder) {
        Integer existing = index.get(id);
        if (existing != null) return existing;
        synchronized (lock) {
            Integer now = index.get(id);
            if (now != null) return now;
            int at = biomes.size();
            biomes.add(new BiomeInfo(id, holder == null ? UNKNOWN_RGB : colorOf(id, holder.value())));
            index.put(id, at);
            return at;
        }
    }

    /** 渲染线程按这个取颜色（写时会替换整个数组快照，读不用锁）。 */
    List<BiomeInfo> biomes() {
        return biomes;
    }

    int size() {
        return biomes.size();
    }

    // ── 静态取色 ────────────────────────────────────────────────────────

    static ResourceLocation idOf(Holder<Biome> holder) {
        return holder == null
                ? ResourceLocation.fromNamespaceAndPath("minecraft", "unknown")
                : holder.unwrapKey().map(key -> key.location())
                .orElse(ResourceLocation.fromNamespaceAndPath("minecraft", "unknown"));
    }

    /** 群系本身能不能当水看：海岸线跟着群系边界走。 */
    static boolean isWaterBiome(Holder<Biome> holder) {
        return holder != null && holder.unwrapKey().map(key -> isWaterBiome(key.location())).orElse(false);
    }

    static boolean isWaterBiome(ResourceLocation id) {
        String path = id.getPath();
        return path.contains("ocean") || path.equals("river") || path.equals("frozen_river");
    }

    /**
     * 一格的最终颜色 —— 每次画的时候现算，所以改了群系颜色不用重采样。
     *
     * <p>自定义底色（编辑模式吸管调的那个）优先于原版底色。<b>水面例外处理</b>：把自定义色当
     * 浅水色，深水压到它的 55%，保留深浅渐变 —— 否则深海浅海一个色，就读不出这块水能不能过。
     *
     * @param id       群系 id（用来查自定义色）
     * @param baseRgb  原版底色（{@link BiomeInfo#rgb()}，已含同类群系的亮度微调）
     * @param wet      这一格按水画吗
     * @param seaLevel 海平面
     * @param surface  这一格的地表 y
     * @param light    坡度光照（0.55~1.30）
     */
    static int colorFor(@Nullable ResourceLocation id, int baseRgb, boolean wet,
                        int seaLevel, int surface, double light) {
        Integer custom = id == null ? null : SiteEditorPrefs.colorOf(id);
        int rgb;
        if (wet) {
            float depth = Mth.clamp((seaLevel - surface) / 30.0F, 0.0F, 1.0F);
            int shallow = custom != null ? custom : WATER_SHALLOW;
            int deep = custom != null ? scale(custom, 0.55) : WATER_DEEP;
            rgb = lerp(shallow, deep, depth);
        } else {
            rgb = custom != null ? custom : baseRgb;
        }
        return scale(rgb, light);
    }

    private static int colorOf(ResourceLocation id, Biome biome) {
        Optional<Integer> overridden = biome.getSpecialEffects().getGrassColorOverride();
        int rgb = overridden.isPresent() ? overridden.get() & 0xFFFFFF : baseColor(id, biome);
        return jitter(rgb, id);
    }

    /** 原版常见群系给固定色，其余按气候猜；再怎么不准也不会把海画成沙漠。 */
    private static int baseColor(ResourceLocation id, Biome biome) {
        if (id.getNamespace().equals("minecraft")) {
            switch (id.getPath()) {
                case "snowy_plains", "snowy_taiga", "ice_spikes", "snowy_beach",
                        "frozen_peaks", "jagged_peaks", "grove", "snowy_slopes":
                    return 0xE7F0F5;
                case "desert", "badlands", "eroded_badlands", "wooded_badlands":
                    return 0xD9C08A;
                case "beach":
                    return 0xE2D3A0;
                case "swamp", "mangrove_swamp":
                    return 0x3F5B33;
                case "jungle", "bamboo_jungle", "sparse_jungle":
                    return 0x2E7A35;
                case "mushroom_fields":
                    return 0x8E7BA8;
                case "stony_shore", "windswept_hills", "windswept_gravelly_hills",
                        "stony_peaks", "windswept_savanna":
                    return 0x8A8A85;
                case "lush_caves":
                    return 0x3F7D3F;
                case "dripstone_caves":
                    return 0x8B6B4A;
                case "deep_dark":
                    return 0x2A2A3A;
                case "forest", "birch_forest", "dark_forest", "flower_forest",
                        "old_growth_birch_forest", "windswept_forest",
                        "old_growth_pine_taiga", "old_growth_spruce_taiga", "taiga":
                    return 0x3D6B32;
                case "plains", "sunflower_plains", "meadow", "cherry_grove":
                    return 0x6FAE4A;
                case "savanna", "savanna_plateau":
                    return 0xB39B4C;
                default:
                    break;
            }
        }
        Biome.ClimateSettings climate = biome.getModifiedClimateSettings();
        float temperature = climate.temperature();
        float downfall = climate.downfall();
        if (!climate.hasPrecipitation() || downfall < 0.05F) return 0xD8C48C;
        if (temperature < -0.2F) return 0xE7F0F5;
        if (temperature < 0.3F) return Mth.hsvToRgb(0.33F, 0.28F, 0.55F);
        if (temperature < 0.9F) return Mth.hsvToRgb(0.30F, 0.30F + 0.25F * downfall, 0.62F);
        return Mth.hsvToRgb(0.22F, 0.42F, 0.68F);
    }

    /** 同色群系（尤其是模组群系）靠 id 哈希做一点点亮度差，边界才看得出来。 */
    private static int jitter(int rgb, ResourceLocation id) {
        int hash = id.hashCode();
        float factor = 0.94F + ((hash & 0xFF) / 255.0F) * 0.12F;
        return scale(rgb, factor);
    }

    static int lerp(int from, int to, float t) {
        int r = (int) Mth.lerp(t, (from >> 16) & 0xFF, (to >> 16) & 0xFF);
        int g = (int) Mth.lerp(t, (from >> 8) & 0xFF, (to >> 8) & 0xFF);
        int b = (int) Mth.lerp(t, from & 0xFF, to & 0xFF);
        return (r << 16) | (g << 8) | b;
    }

    static int scale(int rgb, double factor) {
        int r = Mth.clamp((int) (((rgb >> 16) & 0xFF) * factor), 0, 255);
        int g = Mth.clamp((int) (((rgb >> 8) & 0xFF) * factor), 0, 255);
        int b = Mth.clamp((int) ((rgb & 0xFF) * factor), 0, 255);
        return (r << 16) | (g << 8) | b;
    }
}
