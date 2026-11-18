package dev.yard.site.view;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;

/**
 * 登录完原版要换上「收区块」的过渡屏（{@link ReceivingLevelScreen}）。落成动画还没落机
 * （蓝图画完、白光切过去之前）就先把我们这个界面留着：拦掉这一次开屏。
 *
 * <p>为什么不用 {@code Minecraft#setScreen} 的 Mixin：原版有两条路径会设这个界面
 * （{@code Minecraft#setLevel} 和 {@code ClientPacketListener#startWaitingForNewLevel}），
 * 平台层在赋值<b>之前</b>拦一次就盖得全；取消也不会碰到 {@code removed()}，界面不被拆掉。
 * NeoForge 用可取消的 {@code ScreenEvent.Opening}，Fabric 将来用同一个判断的 Mixin。
 *
 * <p>拦掉它不欠原版账：判断世界就绪的 {@code LevelLoadStatusManager} 由
 * {@code ClientPacketListener} 自己 tick，跟这个界面没关系。玩家只是暂时动不了，交回界面就正常。
 */
public final class SiteRevealGuard {

    private SiteRevealGuard() {}

    /** 这次要打开的界面该不该被落成动画拦下。 */
    public static boolean shouldCancel(Screen next) {
        if (!(next instanceof ReceivingLevelScreen)) return false;
        Screen current = Minecraft.getInstance().screen;
        return current instanceof SiteRevealScreen reveal && reveal.holdsHandoff();
    }
}
