package dev.yard.site.platform.forge.mixin;

import dev.yard.site.view.SiteRevealScreen;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.20.1 的 {@code Camera#setRotation} 只有 yaw/pitch 两参数，roll 是 1.21.1 才加的。
 * 逻辑和共享的 CameraMixin 一样，只是这一处签名不同。
 */
@Mixin(Camera.class)
public abstract class ForgeCameraMixin {

    @Shadow
    protected abstract void setPosition(Vec3 pos);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "setup", at = @At("TAIL"))
    private void yardsite$siteRevealCamera(BlockGetter level, Entity entity, boolean detached,
                                           boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
        Camera self = (Camera) (Object) this;
        SiteRevealScreen.CameraTarget target = SiteRevealScreen.cameraTargetFor(
                self.getPosition(), self.getYRot(), self.getXRot());
        if (target == null) return;
        this.setPosition(target.eye());
        this.setRotation(target.yaw(), target.pitch());
    }
}