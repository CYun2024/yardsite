package dev.yard.site.compat;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * TerraBlender 软兼容：让建世界时的选址图也能看到靠 TerraBlender 加进来的群系。
 *
 * <p><b>为什么图上会少</b>：TerraBlender 把 {@code MultiNoiseBiomeSource#getNoiseBiome} 改成
 * 「先按坐标算出落在哪个 mod 的区域，再只在那个区域的参数表里找最近的群系」，可它那份区域表要等
 * 服务端启动（{@code ServerAboutToStartEvent} → {@code LevelUtils#initializeOnServerStart}）才挂上；
 * 没挂上时那次改写是<b>静默退回原版参数表</b>的。选址图在点「创建世界」之前就弹，比服务端早，
 * 于是图上画的是原版群系，进世界才发现同一块地方被 mod 换成了另一种群系。
 *
 * <p><b>这里怎么绕</b>：不动真世界那份。把生成器自己的参数表<b>复制</b>一份，只在副本上按
 * 同一个种子调 TerraBlender 的初始化，采样问副本。副本内容和服务端那份逐字相同
 * （TerraBlender 初始化只读原表建索引，不往原表里写），而真正要生成的那份一点没变，
 * 服务端照旧自己初始化 —— 所以玩家中途改了种子也不会把旧种子的区域分布带进世界。
 * TerraBlender 自己生成区块时（{@code MixinNoiseBasedChunkGenerator}）也是克隆一份来用的。
 *
 * <p>用反射而不是编译依赖：没装 TerraBlender 的包必须完全不报错，那就按原版群系画。
 */
public final class TerraBlenderCompat {

    private static boolean resolved;
    private static Method regionTypeForDimension;
    private static Method initializeForTerraBlender;
    private static Method parametersGetter;

    private TerraBlenderCompat() {}

    /**
     * 选址图用的群系查询源。
     *
     * @param generator     这个世界预设的主世界生成器（选址图就是拿它采样）
     * @param dimensionType 对应的维度类型，TerraBlender 靠它判断该用哪种区域表
     * @param registries    建世界界面那份注册表（里面要有 mod 加进去的群系）
     * @param seed          这个世界预设的种子，和服务端最终用的是同一个
     */
    public static BiomeSource previewSource(ChunkGenerator generator, Holder<DimensionType> dimensionType,
                                           RegistryAccess registries, long seed) {
        BiomeSource source = generator.getBiomeSource();
        if (!(source instanceof MultiNoiseBiomeSource) || !resolve()) return source;
        long started = System.currentTimeMillis();
        try {
            Object regionType = regionTypeForDimension.invoke(null, dimensionType);
            if (regionType == null) {
                System.out.println("[yardsite] 选址图：这个维度类型没登记进 TerraBlender 的区域表，按原版群系画");
                return source;
            }
            Climate.ParameterList<Holder<Biome>> original = readParameters(source);
            Climate.ParameterList<Holder<Biome>> copy = new Climate.ParameterList<>(original.values());
            initializeForTerraBlender.invoke(copy, registries, regionType, seed);
            MultiNoiseBiomeSource preview = MultiNoiseBiomeSource.createFromList(copy);
            System.out.println("[yardsite] 选址图接管 TerraBlender 群系：区域类型 " + regionType
                    + "，种子 " + seed + "，参数表复制自 " + original.values().size() + " 条（"
                    + (System.currentTimeMillis() - started) + " ms）");
            return preview;
        } catch (ReflectiveOperationException | RuntimeException | Error t) {
            System.out.println("[yardsite] 选址图没接上 TerraBlender（" + t + "），按原版群系画");
            return source;
        }
    }

    /** 读生成器那份噪声参数表：原版这个 getter 是私有的，而且名字随版本变，所以按返回类型找。 */
    @SuppressWarnings("unchecked")
    private static Climate.ParameterList<Holder<Biome>> readParameters(BiomeSource source)
            throws ReflectiveOperationException {
        return (Climate.ParameterList<Holder<Biome>>) parametersGetter.invoke(source);
    }

    private static synchronized boolean resolve() {
        if (resolved) return initializeForTerraBlender != null;
        resolved = true;
        try {
            Class<?> levelUtils = Class.forName("terrablender.util.LevelUtils");
            regionTypeForDimension = levelUtils.getMethod("getRegionTypeForDimension", Holder.class);
            Class<?> regionType = Class.forName("terrablender.api.RegionType");
            Class<?> extendedList = Class.forName("terrablender.worldgen.IExtendedParameterList");
            initializeForTerraBlender = extendedList.getMethod("initializeForTerraBlender",
                    RegistryAccess.class, regionType, long.class);
            parametersGetter = findParametersMethod();
            System.out.println("[yardsite] TerraBlender 兼容已启用（选址图按它的区域表取群系，只作用于预览副本）");
        } catch (Throwable ignored) {
            // 没装 TerraBlender，或版本对不上这些内部类名：整套静默关闭
            regionTypeForDimension = null;
            initializeForTerraBlender = null;
            parametersGetter = null;
        }
        return initializeForTerraBlender != null;
    }

    private static Method findParametersMethod() throws NoSuchMethodException {
        for (Method method : MultiNoiseBiomeSource.class.getDeclaredMethods()) {
            if (method.getParameterCount() == 0
                    && Climate.ParameterList.class.isAssignableFrom(method.getReturnType())
                    && !Modifier.isStatic(method.getModifiers())) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException("MultiNoiseBiomeSource 里找不到返回 Climate$ParameterList 的无参方法");
    }
}
