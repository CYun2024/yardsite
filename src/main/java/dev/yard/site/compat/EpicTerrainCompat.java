package dev.yard.site.compat;

import dev.yard.site.YardSiteLog;
import dev.yard.site.worldgen.SurfaceProbe;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

/**
 * 史诗地形（Epic Terrain）一家的软兼容：完整版 {@code mr_epicterrain} 和只保留地形改动的
 * TerraBlender 兼容版 {@code mr_epic_terrain_compatible}。两者都把主世界改成 -64..512、
 * 噪声竖直范围 512（上沿 448），山头完全不是原版的量级，山系也动不动上万格宽。
 *
 * <p>认到之后改两件事：
 * <ul>
 *   <li><b>山头不再截断在 160</b>：选址图和落点规划的山头上限抬到 {@link #TALL_PEAK_TOP_Y}
 *       （{@link SurfaceProbe} 会再按维度和噪声范围夹一次）。不抬的话 160 以上整座山被拍平，
 *       建筑原点也会定到半山腰。</li>
 *   <li><b>大沙盘按配置加宽</b>：见 {@link #selectRadius}，±2000 在这种地形上常常整张图都在
 *       同一座山上。</li>
 * </ul>
 *
 * <p><b>两路都认</b>：这俩包都是 Modrinth 把数据包打成 jar 的（jar 里一个类都没有，modid 是打包时
 * 起的），所以先按 modid 认——兼容版哪天换命名空间也还在；再按生成数据里的 {@code etn} 命名空间认——
 * 直接当数据包丢进 {@code world/datapacks} 的那些没有 modid，只有这条认得到。
 */
public final class EpicTerrainCompat {

    /** 认到之后的山头上限（格，绝对 y）：它的噪声上沿是 448，这里留够余量再夹。 */
    public static final int TALL_PEAK_TOP_Y = 500;
    /** 完整版 + 兼容版的 modid（Modrinth 打 jar 时起的名字）。 */
    private static final String[] MOD_IDS = {"mr_epicterrain", "mr_epic_terrain_compatible"};
    /** 它的数据命名空间（{@code data/etn/...}）。 */
    private static final String NAMESPACE = "etn";

    /** 同一次建世界流程里三份生成注册表是同一个对象：认一次记住，也免得日志重复打。 */
    private static volatile RegistryAccess probed;
    private static volatile boolean epic;

    private EpicTerrainCompat() {}

    /** 选址图和落点规划用的山头上限：史诗地形给 {@link #TALL_PEAK_TOP_Y}，其他世界照旧 160。 */
    public static int peakCeiling(RegistryAccess registries) {
        return isEpicTerrain(registries) ? TALL_PEAK_TOP_Y : SurfaceProbe.PEAK_TOP_Y;
    }

    /**
     * 选区图覆盖范围（±N 格）：史诗地形用加宽那一档配置，其他世界用常规值。
     * 只改图的物理跨度，采样点数不变，所以不额外花时间 —— 变的是一格代表的方块变多。
     */
    public static int selectRadius(RegistryAccess registries, int normal, int epicRadius) {
        return isEpicTerrain(registries) ? epicRadius : normal;
    }

    /** 这个世界装了史诗地形（完整版或兼容版）吗。 */
    public static boolean isEpicTerrain(RegistryAccess registries) {
        RegistryAccess already = probed;
        if (already == registries) return epic;
        String hit = hitFrom(registries);
        epic = hit != null;
        probed = registries;
        if (hit != null) {
            YardSiteLog.debug("选址：认到史诗地形（" + hit + "），山头上限抬到 " + TALL_PEAK_TOP_Y
                    + "（实际再按维度和噪声范围夹），图按配置加宽");
        }
        return epic;
    }

    /** 认到了就说一句从哪儿认出来的，没认到返回 {@code null}。 */
    @Nullable
    private static String hitFrom(RegistryAccess registries) {
        for (String modid : MOD_IDS) {
            if (ModList.get().isLoaded(modid)) return "mod " + modid;
        }
        if (hasNamespace(registries, Registries.BIOME) || hasNamespace(registries, Registries.DENSITY_FUNCTION)
                || hasNamespace(registries, Registries.PLACED_FEATURE)) {
            return "数据包命名空间 " + NAMESPACE;
        }
        return null;
    }

    /** 某个动态注册表里有没有 {@code etn} 命名空间的条目。 */
    private static <T> boolean hasNamespace(RegistryAccess registries, ResourceKey<? extends Registry<T>> registryKey) {
        return registries.lookupOrThrow(registryKey).listElements()
                .anyMatch(reference -> NAMESPACE.equals(reference.unwrapKey()
                        .map(key -> key.location().getNamespace()).orElse("")));
    }
}
