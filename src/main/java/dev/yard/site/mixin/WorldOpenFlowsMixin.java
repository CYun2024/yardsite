package dev.yard.site.mixin;

import dev.yard.site.view.SiteRevealHandoff;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 点「创建世界」之后，原版先换一张 {@code GenericMessageScreen}（“准备创建世界/读取存档数据”）
 * 去做数据包/世界数据加载，之后才轮到 {@code doWorldLoad} 里的加载屏。这一段原版界面会把
 * 选址精读和落成动画切开——这里把它直接换成本 mod 的落成界面，沙盘从点击那一刻接着播。
 *
 * <p>没交接（没选址/开关关掉）就原样放行。
 */
@Mixin(WorldOpenFlows.class)
public class WorldOpenFlowsMixin {

    @Redirect(method = "createFreshLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;forceSetScreen(Lnet/minecraft/client/gui/screens/Screen;)V"))
    private void yardsite$revealWhileCreating(Minecraft self, Screen vanilla) {
        self.forceSetScreen(SiteRevealHandoff.orReveal(vanilla));
    }
}
