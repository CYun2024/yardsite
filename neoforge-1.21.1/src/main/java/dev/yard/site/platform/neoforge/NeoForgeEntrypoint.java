package dev.yard.site.platform.neoforge;

import dev.yard.site.YardSite;
import dev.yard.site.YardSiteConfig;
import dev.yard.site.platform.Platform;
import dev.yard.site.structure.SchematicStructure;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge 1.21.1 的入口：装平台、装配置、注册服务器事件。
 *
 * <p>三种装法（独立出生点 / 庭院联动 / 结构模式）在核心侧没有变，这里只负责把加载器接进来。
 */
@Mod(YardSite.MODID)
public class NeoForgeEntrypoint {

    public NeoForgeEntrypoint(IEventBus modBus, ModContainer container) {
        Platform.install(new NeoForgePlatform());

        NeoForgeConfig config = new NeoForgeConfig();
        YardSiteConfig.install(config);
        container.registerConfig(ModConfig.Type.COMMON, config.spec());

        // 结构模式自动认文件用的目录先建出来，作者把投影丢进去就能用（建不出来也无所谓）
        SchematicStructure.ensureStructuresDir();

        NeoForge.EVENT_BUS.addListener(NeoForgeEvents::onCreateSpawnPosition);
        NeoForge.EVENT_BUS.addListener(NeoForgeEvents::onServerStarted);
        NeoForge.EVENT_BUS.addListener(NeoForgeEvents::onServerTick);
        NeoForge.EVENT_BUS.addListener(NeoForgeEvents::onServerStopping);
    }
}