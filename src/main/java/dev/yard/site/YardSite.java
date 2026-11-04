package dev.yard.site;

import dev.yard.site.worldgen.SiteFastLoad;
import dev.yard.site.worldgen.SiteRevealState;
import dev.yard.site.worldgen.SiteSpawnHandler;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * 群系选区：创建单人世界前先看一张出生点附近的地形/群系图，把落点定准，再走一段落成镜头进世界。
 *
 * <h2>两种装法</h2>
 * <ul>
 *   <li><b>独立安装</b>：选出来的就是一个出生点。没有建筑、没有蓝图，落机直接落到出生点。</li>
 *   <li><b>和庭院系列一起装</b>：庭院核心会注册一个 {@link dev.yard.site.api.SiteLink}，
 *       同一个落点就变成庭院占地——服务端按院子尺寸规划原点、出生点落在院内，
 *       落成动画会画出旅社的金色线框。</li>
 * </ul>
 *
 * <p>本 mod 不依赖任何其它 mod 的类：联动方实现 {@code SiteLink} 接口并注册，反向用反射兜底
 * （见 {@link dev.yard.site.api.SiteLinks}）。
 */
@Mod(YardSite.MODID)
public class YardSite {
    public static final String MODID = "yardsite";

    public YardSite(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, YardSiteConfig.SPEC);
        // 建新世界：把出生点放到选区里，跳过原版"随机找出生点"
        NeoForge.EVENT_BUS.addListener(SiteSpawnHandler::onCreateSpawnPosition);
        // 早进世界：加载期半径压小，服务器一起来就恢复正式半径并补回 START ticket
        NeoForge.EVENT_BUS.addListener(SiteFastLoad::onServerStarted);
        // 独立安装：出生点区块生成完之后用真实高度图对一次 y（树/雪层在规划阶段看不到）
        NeoForge.EVENT_BUS.addListener(SiteSpawnHandler::onServerStarted);
        // 这两个是进程内状态：换存档必须清空，否则旧世界的落点/半径会被带到新世界
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> {
            SiteRevealState.reset();
            SiteFastLoad.clear();
        });
    }
}
