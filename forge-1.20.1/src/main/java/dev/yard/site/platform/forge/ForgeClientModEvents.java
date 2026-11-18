package dev.yard.site.platform.forge;

import dev.yard.site.YardSite;
import dev.yard.site.view.SurfaceReload;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Forge 客户端资源重载监听器；MOD 总线。 */
@Mod.EventBusSubscriber(modid = YardSite.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ForgeClientModEvents {

    private ForgeClientModEvents() {}

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(SurfaceReload.listener());
    }
}