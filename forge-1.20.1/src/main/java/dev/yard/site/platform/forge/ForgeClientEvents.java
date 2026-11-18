package dev.yard.site.platform.forge;

import dev.yard.site.YardSite;
import dev.yard.site.client.ChunkLoadDiagnostics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Forge 客户端 tick；只在这个类被加载的客户端生效。 */
@Mod.EventBusSubscriber(modid = YardSite.MODID, value = Dist.CLIENT)
public final class ForgeClientEvents {

    private ForgeClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ChunkLoadDiagnostics.tick();
        }
    }
}