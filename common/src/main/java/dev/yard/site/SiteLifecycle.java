package dev.yard.site;

import dev.yard.site.structure.SchematicPlacer;
import dev.yard.site.worldgen.SiteFastLoad;
import dev.yard.site.worldgen.SiteRevealState;
import dev.yard.site.worldgen.SiteSpawnHandler;
import net.minecraft.server.MinecraftServer;

/**
 * 服务器生命周期的固定顺序：先恢复出生点区块半径，再校正出生点高度，最后排结构铺设。
 *
 * <p>平台层只负责把事件转成这三个方法，顺序集中在这里，换加载器时不会因为注册顺序不同而改变行为。
 */
public final class SiteLifecycle {

    private SiteLifecycle() {}

    public static void serverStarted(MinecraftServer server) {
        SiteFastLoad.serverStarted(server);
        SiteSpawnHandler.serverStarted(server);
        SchematicPlacer.serverStarted(server);
    }

    public static void serverStopping() {
        SiteRevealState.reset();
        SiteFastLoad.clear();
        SchematicPlacer.clear();
    }
}