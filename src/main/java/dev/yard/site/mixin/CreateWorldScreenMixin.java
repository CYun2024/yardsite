package dev.yard.site.mixin;

import dev.yard.site.YardSiteConfig;
import dev.yard.site.api.SiteLink;
import dev.yard.site.api.SiteLinks;
import dev.yard.site.view.SiteRevealHandoff;
import dev.yard.site.view.YardSiteScreen;
import dev.yard.site.worldgen.SiteSelection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拦截「创建世界」：先弹群系选区图，玩家在图上确认后才继续原版流程。
 *
 * <p>只在单人/局域网建世界时生效（专用服务器没有这个界面）。玩家在图上点过「确认并创建」或
 * 「创建世界」后，{@link SiteSelection#setPass} 会记住这个界面 + 种子，重试创建时不再重复弹图。
 */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {

    @Inject(method = "onCreate", at = @At("HEAD"), cancellable = true)
    private void yardsite$openSiteSelect(CallbackInfo ci) {
        CreateWorldScreen self = (CreateWorldScreen) (Object) this;
        if (!YardSiteConfig.SPAWN_SITE_SELECT.get()) return;
        SiteLink link = SiteLinks.get();
        // 和庭院系列一起装时，由它自己决定要不要弹（例如「开局自动生成旅社」关掉就不弹）
        if (link != null && !link.siteSelectEnabled()) return;

        WorldCreationContext context = self.getUiState().getSettings();
        long seed = context.options().seed();
        if (SiteSelection.hasPass(self, seed)) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof YardSiteScreen) return;

        // 实例已经过 Mixin 处理，直接当成 accessor 接口用
        CreateWorldScreenAccessor accessor = (CreateWorldScreenAccessor) self;
        minecraft.setScreen(new YardSiteScreen(self, context, accessor::yardsite$createWorld));
        ci.cancel();
    }

    /**
     * 确认创建后原版先换一张 {@code GenericMessageScreen("createWorld.preparing")}（“准备创建世界”），
     * 建世界目录、装数据包，之后才进 {@code doWorldLoad}。这一段会把选址精读和落成动画切开；
     * 有交接时直接换成落成界面，沙盘从点击那一刻接着播。
     */
    @Inject(method = "queueLoadScreen", at = @At("HEAD"), cancellable = true)
    private static void yardsite$revealWhilePreparing(Minecraft minecraft, Component title, CallbackInfo ci) {
        Screen vanilla = new GenericMessageScreen(title);
        Screen next = SiteRevealHandoff.orReveal(vanilla);
        if (next != vanilla) {
            minecraft.forceSetScreen(next);
            ci.cancel();
        }
    }
}
