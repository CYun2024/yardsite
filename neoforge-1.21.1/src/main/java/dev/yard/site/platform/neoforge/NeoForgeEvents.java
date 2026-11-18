package dev.yard.site.platform.neoforge;

import dev.yard.site.SiteLifecycle;
import dev.yard.site.structure.SchematicPlacer;
import dev.yard.site.worldgen.SiteSpawnHandler;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** 把 NeoForge 的服务器事件转成核心的静态入口；核心不引用这些事件类。 */
public final class NeoForgeEvents {

    private NeoForgeEvents() {}

    public static void onCreateSpawnPosition(LevelEvent.CreateSpawnPosition event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (SiteSpawnHandler.handle(level, event.getSettings()::setSpawn)) {
            event.setCanceled(true);
        }
    }

    public static void onServerStarted(ServerStartedEvent event) {
        SiteLifecycle.serverStarted(event.getServer());
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        SchematicPlacer.serverTick(event.getServer());
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        SiteLifecycle.serverStopping();
    }
}