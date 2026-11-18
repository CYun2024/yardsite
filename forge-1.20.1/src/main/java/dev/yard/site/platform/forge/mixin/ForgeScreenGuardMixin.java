package dev.yard.site.platform.forge.mixin;

import dev.yard.site.view.SiteRevealGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge 1.20.1 的 {@code ScreenEvent.Opening} 不可取消，这里直接在 {@code setScreen} 赋值前拦一次，
 * 判据和 NeoForge 端的 SiteRevealGuard 完全相同。
 */
@Mixin(Minecraft.class)
public abstract class ForgeScreenGuardMixin {

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void yardsite$guardReceivingLevel(Screen screen, CallbackInfo ci) {
        if (SiteRevealGuard.shouldCancel(screen)) {
            ci.cancel();
        }
    }
}