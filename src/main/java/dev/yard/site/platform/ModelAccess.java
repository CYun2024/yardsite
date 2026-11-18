package dev.yard.site.platform;

import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 读方块烘焙模型的口子。{@code BakedModel#getQuads} 的额外参数在 NeoForge 是 {@code ModelData}、
 * 在 Fabric 没有；1.21.11/26.x 的模型类型又整体换成了 {@code BlockStateModel}/{@code BakedQuad} record。
 *
 * <p>所以这里只返回中性的 {@link ModelQuad}：核心不出现任何版本专属的模型类型，每个版本只需要
 * 实现“把这个版本的模型翻成角点 + 贴图色 + tint 标记”。
 */
public interface ModelAccess {

    /** 方块状态的全部模型面；拿不到模型时返回空表，调用方退回形状盒。 */
    List<ModelQuad> quads(BlockState state);

    /** 资源包/模型重载后清掉贴图色缓存。 */
    void invalidate();

    /** 当前缓存的贴图色数量（只用于日志）。 */
    int cachedSprites();
}