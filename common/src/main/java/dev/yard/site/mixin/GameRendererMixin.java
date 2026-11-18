package dev.yard.site.mixin;

import dev.yard.site.view.SiteRevealScreen;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 落成动画期间把渲染 FOV 接到动画相机上：登录前给 GUI 柱子用，登录后给真世界用，
 * 让“正交沙盘 → 透视真世界”的过渡两边是同一个 FOV。落机时插值回玩家自己的 FOV。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void yardsite$revealFov(Camera camera, float partialTick, boolean useFovSetting,
                                    CallbackInfoReturnable<Double> cir) {
        double fov = SiteRevealScreen.fovForGameRenderer();
        if (fov > 0.0) {
            cir.setReturnValue(fov);
        }
    }
}
