package dev.yard.site.platform.neoforge;

import dev.yard.site.platform.ClientPlatform;
import dev.yard.site.platform.ModelAccess;
import dev.yard.site.platform.Platform;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/** NeoForge 1.21.1 的平台门面实现。 */
public final class NeoForgePlatform implements Platform.Backend {

    @Override
    public boolean isModLoaded(String modid) {
        return ModList.get().isLoaded(modid);
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public ModelAccess models() {
        return NeoForgeModels.INSTANCE;
    }

    @Override
    public ClientPlatform client() {
        return NeoForgeClientPlatform.INSTANCE;
    }

    @Override
    public RandomState createRandomState(NoiseGeneratorSettings settings, RegistryAccess registries, long seed) {
        return RandomState.create(settings, registries.lookupOrThrow(Registries.NOISE), seed);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean registryHasNamespace(RegistryAccess registries, ResourceKey<? extends Registry<?>> key,
                                        String namespace) {
        HolderLookup.RegistryLookup<Object> lookup = registries.lookupOrThrow((ResourceKey) key);
        return lookup.listElements().anyMatch(reference -> namespace.equals(reference.unwrapKey()
                .map(registryKey -> registryKey.location().getNamespace()).orElse("")));
    }
}