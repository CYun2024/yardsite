package dev.yard.site.view;

import dev.yard.site.YardSite;
import dev.yard.site.structure.SurfaceLooks;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/**
 * 资源包 / 模型重载后把落成外观缓存清掉：贴图平均色和模型面都可能已经换了。
 */
@EventBusSubscriber(modid = YardSite.MODID, value = Dist.CLIENT)
public final class SurfaceReload {

    private SurfaceReload() {}

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new SimplePreparableReloadListener<Void>() {
            @Override
            protected Void prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
                return null;
            }

            @Override
            protected void apply(Void unused, ResourceManager resourceManager, ProfilerFiller profiler) {
                SurfaceLooks.invalidate();
            }
        });
    }
}
