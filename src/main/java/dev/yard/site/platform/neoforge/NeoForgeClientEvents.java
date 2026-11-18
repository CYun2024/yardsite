package dev.yard.site.platform.neoforge;

import dev.yard.site.YardSite;
import dev.yard.site.client.ChunkLoadDiagnostics;
import dev.yard.site.view.SiteRevealGuard;
import dev.yard.site.view.SurfaceReload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** 客户端事件的三处入口；核心只留 tick / 拦截判断 / 重载监听器。 */
@EventBusSubscriber(modid = YardSite.MODID, value = Dist.CLIENT)
public final class NeoForgeClientEvents {

    private NeoForgeClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ChunkLoadDiagnostics.tick();
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (SiteRevealGuard.shouldCancel(event.getScreen())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(SurfaceReload.listener());
    }
}