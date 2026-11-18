package dev.yard.site.platform.neoforge;

import dev.yard.site.platform.ClientPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** NeoForge 1.21.1 的区块状态判断。 */
public final class NeoForgeClientPlatform implements ClientPlatform {

    public static final NeoForgeClientPlatform INSTANCE = new NeoForgeClientPlatform();

    private NeoForgeClientPlatform() {}

    @Override
    public ChunkAccess chunkAt(ClientLevel level, int chunkX, int chunkZ) {
        return level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
    }

    @Override
    public boolean sectionCompiled(Minecraft mc, BlockPos pos) {
        return mc.levelRenderer != null && mc.levelRenderer.isSectionCompiled(pos);
    }

    @Override
    public Screen messageScreen(Component title) {
        return new GenericMessageScreen(title);
    }
}