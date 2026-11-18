package dev.yard.site.platform;

import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

import dev.yard.site.schematic.BlockShapes;

import java.nio.file.Path;

/**
 * 平台门面：核心逻辑只认这里，不直接引用任何加载器 API。
 *
 * <p>NeoForge 的实现在 {@code dev.yard.site.platform.neoforge}；Fabric 实现将来用同一组方法接进来，
 * 核心代码不用改。
 */
public final class Platform {

    public interface Backend {
        boolean isModLoaded(String modid);

        /** 是不是客户端：线框几何在客户端可以借模型数据兜底。 */
        boolean isClient();

        Path configDir();

        Path gameDir();

        ModelAccess models();

        ClientPlatform client();

        /** 1.20.1 用 {@code registryOrThrow}，1.21.1 用 {@code lookupOrThrow}；核心不碰这个区别。 */
        RandomState createRandomState(NoiseGeneratorSettings settings, RegistryAccess registries, long seed);

        /** 某个动态注册表里有没有指定命名空间的条目（史诗地形软兼容用）。 */
        boolean registryHasNamespace(RegistryAccess registries, ResourceKey<? extends Registry<?>> key,
                                     String namespace);
    }

    private static volatile Backend backend;

    private Platform() {}

    public static void install(Backend implementation) {
        backend = implementation;
        // 线框几何优先问方块自己的 getShape（mod 方块一起兼容）；离线工具不装，走手写表
        BlockShapes.setSource(McShapes.INSTANCE);
    }

    public static boolean isModLoaded(String modid) {
        return get().isModLoaded(modid);
    }

    public static boolean isClient() {
        return get().isClient();
    }

    public static Path configDir() {
        return get().configDir();
    }

    public static Path gameDir() {
        return get().gameDir();
    }

    public static ModelAccess models() {
        return get().models();
    }

    public static ClientPlatform client() {
        return get().client();
    }

    public static RandomState createRandomState(NoiseGeneratorSettings settings, RegistryAccess registries, long seed) {
        return get().createRandomState(settings, registries, seed);
    }

    public static boolean registryHasNamespace(RegistryAccess registries,
                                               ResourceKey<? extends Registry<?>> key, String namespace) {
        return get().registryHasNamespace(registries, key, namespace);
    }

    private static Backend get() {
        Backend current = backend;
        if (current == null) {
            throw new IllegalStateException("yardsite platform not installed");
        }
        return current;
    }
}