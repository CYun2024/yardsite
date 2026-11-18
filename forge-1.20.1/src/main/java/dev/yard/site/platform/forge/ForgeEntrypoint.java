package dev.yard.site.platform.forge;

import dev.yard.site.SiteLifecycle;
import dev.yard.site.YardSite;
import dev.yard.site.YardSiteConfig;
import dev.yard.site.platform.Platform;
import dev.yard.site.structure.SchematicPlacer;
import dev.yard.site.structure.SchematicStructure;
import dev.yard.site.worldgen.SiteSpawnHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

/** Forge 1.20.1 的入口：装平台、装配置、注册服务器事件。 */
@Mod(YardSite.MODID)
public class ForgeEntrypoint {

    public ForgeEntrypoint() {
        Platform.install(new ForgePlatform());

        ForgeConfig config = new ForgeConfig();
        YardSiteConfig.install(config);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, config.spec());

        SchematicStructure.ensureStructuresDir();

        MinecraftForge.EVENT_BUS.addListener(ForgeEntrypoint::onCreateSpawnPosition);
        MinecraftForge.EVENT_BUS.addListener(ForgeEntrypoint::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(ForgeEntrypoint::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(ForgeEntrypoint::onServerStopping);
    }

    private static void onCreateSpawnPosition(LevelEvent.CreateSpawnPosition event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (SiteSpawnHandler.handle(level, event.getSettings()::setSpawn)) {
            event.setCanceled(true);
        }
    }

    private static void onServerStarted(ServerStartedEvent event) {
        SiteLifecycle.serverStarted(event.getServer());
    }

    private static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            SchematicPlacer.serverTick(event.getServer());
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        SiteLifecycle.serverStopping();
    }
}