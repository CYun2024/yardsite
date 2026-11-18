package dev.yard.site.mixin.sodium;

import dev.yard.site.client.SiteRevealCulling;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 方块实体在 {@code ChunkRenderList} 里已经过滤；普通实体走这条裁剪判据。
 * 没轮到的区块列里的 NPC/生物/掉落物先不画，免得它们在沙盘上面提前出现。
 *
 * <p>目标类用字符串写，理由见 {@link ChunkRenderListMixin}：配置解析期 Sodium 的类还加载不到。
 * 这里<b>不</b>回报网格——实体可见只代表区块数据在客户端有了，不代表地形网格已经画出来。
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer")
public abstract class SodiumWorldRendererMixin {

    @Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
    private void yardsite$hideEntityInUnrevealedChunk(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity == null) return;
        ChunkPos pos = entity.chunkPosition();
        if (SiteRevealCulling.hides(pos.x, pos.z)) {
            cir.setReturnValue(false);
        }
    }
}
