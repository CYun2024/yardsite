package dev.yard.site.mixin;

import dev.yard.site.view.SiteRevealHandoff;
import dev.yard.site.view.SiteRevealScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 建世界等出生点区块那段时间的界面归本 mod 的生成动画管：原版在 {@code doWorldLoad} 里准备打开
 * {@code LevelLoadingScreen}，这里换成 {@link SiteRevealScreen}。没交接（没选址、开关关掉、
 * 建世界过程本身不对）就原样放行。
 *
 * <p>只换这一句，不动生成流程：服务端该等多少个区块、怎么落盘、怎么发区块，全都还是原版那套。
 * 登录时原版要换上收区块的过渡屏那一处不归这里管，走可取消的 {@code ScreenEvent.Opening}
 * （见 {@code SiteRevealGuard}），因为原版有两条设置该界面的路径，事件那条才盖得全。
 */
@Mixin(Minecraft.class)
public class MinecraftSiteLoadMixin {

    @Redirect(method = "doWorldLoad", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"))
    private void yardsite$showSiteReveal(Minecraft self, Screen vanilla) {
        if (self.screen instanceof SiteRevealScreen) {
            // 创建阶段（WorldOpenFlows）已经提前接手，别换回原版加载屏
            return;
        }
        self.setScreen(SiteRevealHandoff.orReveal(vanilla));
    }

    /**
     * 建世界前会先 {@code disconnect()} 旧世界，那一下要换 {@link ProgressScreen}；
     * 动画在创建阶段就已经接管了界面，这里别让它被拆掉（拆掉会触发 removed，规划器就关了）。
     */
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void yardsite$keepRevealDuringDisconnect(Screen screen, CallbackInfo ci) {
        Minecraft self = (Minecraft) (Object) this;
        if (screen instanceof ProgressScreen && self.screen instanceof SiteRevealScreen) {
            ci.cancel();
        }
    }
}
