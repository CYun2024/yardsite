package dev.yard.site.view;

import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.world.level.dimension.LevelStem;

/**
 * 建世界时真正会被用到的主世界维度。
 *
 * <p>存档就是这么 bake 出来的（{@code WorldDimensions#bake}）：<b>数据包给了同名维度就优先用数据包那份</b>，
 * 世界预设里的只兜底。Terralith 这类「纯数据包 mod」在 1.20.1 上只提供
 * {@code data/minecraft/dimension/overworld.json}（群系表、噪声设置都在里面），预设那份仍是原版 ——
 * 采样只读预设，图上就只剩原版群系，和进世界看到的地形对不上。
 */
final class SiteDimensions {

    private SiteDimensions() {}

    static LevelStem overworld(WorldCreationContext context) {
        return context.datapackDimensions().getOptional(LevelStem.OVERWORLD)
                .or(() -> context.selectedDimensions().get(LevelStem.OVERWORLD))
                .orElseThrow(() -> new IllegalStateException("缺少主世界生成设置"));
    }
}
