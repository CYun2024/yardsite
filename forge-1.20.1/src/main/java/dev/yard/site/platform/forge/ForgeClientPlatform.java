package dev.yard.site.platform.forge;

import dev.yard.site.platform.ClientPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;

/** Forge 1.20.1 的区块状态判断：ChunkStatus 还在老包，编译判据叫 isChunkCompiled。 */
public final class ForgeClientPlatform implements ClientPlatform {

    public static final ForgeClientPlatform INSTANCE = new ForgeClientPlatform();

    private ForgeClientPlatform() {}

    @Override
    public ChunkAccess chunkAt(ClientLevel level, int chunkX, int chunkZ) {
        return level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
    }

    @Override
    public boolean sectionCompiled(Minecraft mc, BlockPos pos) {
        return mc.levelRenderer != null && mc.levelRenderer.isChunkCompiled(pos);
    }

    @Override
    public Screen messageScreen(Component title) {
        return new GenericDirtMessageScreen(title);
    }
}