package dev.yard.site.mixin;

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
 * 进世界那段动画期间把原版相机接到空中机位。
 *
 * <p>为什么必须动相机：原版世界是 {@code GameRenderer#renderLevel} 用这个 {@link Camera} 画的，
 * 而我们的沙盘/世界柱子有自己的眼位。不接管的话，屏幕里的柱子在空中、背后的真地形却在玩家眼睛里，
 * 交棒之后两边对不上。接管之后区块剔除、天空、雾、真地形全跟着同一个机位走。
 *
 * <p>挂在 {@code setup} 末尾（原版已经把眼位、视角、其它 mod 的
 * {@code ViewportEvent.ComputeCameraAngles} 都算完），落机时和目标插值；落到位就返回 {@code null}
 * 不再接管 —— 最后一帧就是原版相机本身，和玩家长按下的视角逐位相同。
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    protected abstract void setPosition(Vec3 pos);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot, float roll);

    @Inject(method = "setup", at = @At("TAIL"))
    private void yardsite$siteRevealCamera(BlockGetter level, Entity entity, boolean detached,
                                           boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
        Camera self = (Camera) (Object) this;
        SiteRevealScreen.CameraTarget target = SiteRevealScreen.cameraTargetFor(
                self.getPosition(), self.getYRot(), self.getXRot());
        if (target == null) return;
        this.setPosition(target.eye());
        this.setRotation(target.yaw(), target.pitch(), 0.0F);
    }
}
