package dev.yard.site.mixin;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 让选址屏在玩家确认后能回到 {@code CreateWorldScreen#onCreate()} 继续原版建世界流程。
 *
 * <p>必须写成「accessor mixin 接口」（自己带 {@code @Mixin}，并写进 mixin 配置的 client 列表），
 * 而不是普通接口：普通接口不会生成 {@code @Invoker} 实现，运行时会 AbstractMethodError。
 */
@Mixin(CreateWorldScreen.class)
public interface CreateWorldScreenAccessor {

    @Invoker("onCreate")
    void yardsite$createWorld();
}
