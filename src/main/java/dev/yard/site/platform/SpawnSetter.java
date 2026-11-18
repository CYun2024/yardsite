package dev.yard.site.platform;

import net.minecraft.core.BlockPos;

/** 把规划好的出生点写回世界数据；NeoForge/Forge 事件和 Fabric mixin 都走这里。 */
@FunctionalInterface
public interface SpawnSetter {

    void setSpawn(BlockPos pos, float angle);
}