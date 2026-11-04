package dev.yard.site.view;

import dev.yard.site.YardSite;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * 登录完原版要换上「收区块」的过渡屏（{@link ReceivingLevelScreen}）。落成动画还没落机
 * （蓝图画完、白光切过去之前）就先把我们这个界面留着：拦掉这一次开屏。
 *
 * <p>为什么走事件不用 Mixin：原版有两条路径会设这个界面（{@code Minecraft#setLevel} 和
 * {@code ClientPacketListener#startWaitingForNewLevel}），而 {@code Minecraft#setScreen} 在赋值
 * <b>之前</b>发这个可取消事件，一处就盖得全；取消也不会碰到 {@code removed()}，界面不被拆掉。
 *
 * <p>拦掉它不欠原版账：判断世界就绪的 {@code LevelLoadStatusManager} 由
 * {@code ClientPacketListener} 自己 tick，跟这个界面没关系。玩家只是暂时动不了，交回界面就正常。
 */
@EventBusSubscriber(modid = YardSite.MODID, value = Dist.CLIENT)
public final class SiteRevealGuard {

    private SiteRevealGuard() {}

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getScreen() instanceof ReceivingLevelScreen)) return;
        Screen current = Minecraft.getInstance().screen;
        if (current instanceof SiteRevealScreen reveal && reveal.holdsHandoff()) {
            event.setCanceled(true);
        }
    }
}
