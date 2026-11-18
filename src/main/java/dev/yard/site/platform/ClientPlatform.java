package dev.yard.site.platform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.jetbrains.annotations.Nullable;

/**
 * 客户端区块状态的版本门面：{@code ChunkStatus} 的包名、{@code LevelRenderer} 的“方块列编译好”
 * 判断在 1.20.1/1.21.1 不一样，核心只问这两个问题。
 */
public interface ClientPlatform {

    @Nullable
    ChunkAccess chunkAt(ClientLevel level, int chunkX, int chunkZ);

    /** 区块数据到货（不加载新区块）。 */
    default boolean chunkArrived(ClientLevel level, int chunkX, int chunkZ) {
        return chunkAt(level, chunkX, chunkZ) != null;
    }

    /** 这个位置的区块网格是否已经编译并可见。 */
    boolean sectionCompiled(Minecraft mc, BlockPos pos);

    /** “准备创建世界”的兜底界面：1.20.1 叫 GenericDirtMessageScreen，1.21.1 叫 GenericMessageScreen。 */
    Screen messageScreen(Component title);
}