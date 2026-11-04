package dev.yard.site.client;

import dev.yard.site.YardSite;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 临时诊断：测「点创建 → 进世界」「进世界 → 周围半径 6 / 12 区块客户端编译完」各要多久。
 *
 * <p>只读客户端状态，不改生成/登录/动画流程。用 {@code LevelRenderer#isSectionCompiled}
 * （和原版 LevelLoadStatusManager 同判据）按玩家登录时的区块为原点、每秒扫一次方形半径，
 * 记录每次完整编译完的里程碑；日志关键词是 {@code [yardsite] 加载计时}。
 */
@EventBusSubscriber(modid = YardSite.MODID, value = Dist.CLIENT)
public final class ChunkLoadDiagnostics {

    private static final int[] MILESTONES = {3, 6, 12};
    private static final int MAX_RADIUS = 12;
    private static final long CHECK_INTERVAL_MS = 1000L;
    private static final long CAP_MS = 180_000L;

    /** 选址界面点「确认并创建」的时刻；登录时消费一次，避免下一局误用。 */
    private static long createAtMs;
    /** 落成界面看到真实登录的时刻（客户端 tick 可能比它晚几秒）。 */
    private static volatile long markedLoginAtMs;
    private static long runCreateAtMs;
    private static long loginAtMs;
    private static int originChunkX;
    private static int originChunkZ;
    private static long lastCheckAt;
    private static final boolean[] done = new boolean[MILESTONES.length];
    private static boolean capped;

    private static final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

    private ChunkLoadDiagnostics() {}

    /** 由选址界面确认时调用；只用于把「点创建」和「登录」对上。 */
    public static void markCreateAt(long ms) {
        createAtMs = ms;
    }

    /** 由落成界面在第一次看到真实世界时调用；比客户端 tick 事件更接近真实登录。 */
    public static void markLoginAt(long ms) {
        markedLoginAtMs = ms;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            if (loginAtMs != 0L) resetRun();
            return;
        }
        long now = System.currentTimeMillis();
        if (loginAtMs == 0L) {
            loginAtMs = markedLoginAtMs > 0L ? markedLoginAtMs : now;
            markedLoginAtMs = 0L;
            runCreateAtMs = createAtMs;
            createAtMs = 0L;
            originChunkX = mc.player.chunkPosition().x;
            originChunkZ = mc.player.chunkPosition().z;
            lastCheckAt = now;
            System.out.println("[yardsite] 加载计时：进入世界"
                    + (runCreateAtMs > 0L ? "（点创建后 " + (loginAtMs - runCreateAtMs) + " ms）" : "")
                    + "，客户端视距 " + mc.options.renderDistance().get() + " 区块，原点 chunk "
                    + originChunkX + "," + originChunkZ);
            return;
        }
        if (capped || now - lastCheckAt < CHECK_INTERVAL_MS) return;
        lastCheckAt = now;
        check(mc, now);
    }

    private static void check(Minecraft mc, long now) {
        ClientLevel level = mc.level;
        if (level == null || mc.levelRenderer == null) return;

        int[] readyByDistance = new int[MAX_RADIUS + 1];
        for (int dx = -MAX_RADIUS; dx <= MAX_RADIUS; dx++) {
            for (int dz = -MAX_RADIUS; dz <= MAX_RADIUS; dz++) {
                if (chunkReady(mc, level, originChunkX + dx, originChunkZ + dz)) {
                    readyByDistance[Math.max(Math.abs(dx), Math.abs(dz))]++;
                }
            }
        }

        int sum = 0;
        int largest = 0;
        for (int r = 0; r <= MAX_RADIUS; r++) {
            sum += readyByDistance[r];
            int total = (2 * r + 1) * (2 * r + 1);
            if (sum >= total) largest = r;
            for (int i = 0; i < MILESTONES.length; i++) {
                if (MILESTONES[i] == r && !done[i] && sum >= total) {
                    done[i] = true;
                    System.out.println("[yardsite] 加载计时：半径 " + r + "（" + (2 * r + 1) + "×"
                            + (2 * r + 1) + "）客户端编译完，登录后 " + (now - loginAtMs) + " ms"
                            + (runCreateAtMs > 0L ? "，点创建后 " + (now - runCreateAtMs) + " ms" : ""));
                }
            }
        }

        if (now - loginAtMs >= CAP_MS) {
            capped = true;
            System.out.println("[yardsite] 加载计时：已等 " + (now - loginAtMs)
                    + " ms，最大完整半径 " + largest + "，半径 3/6/12 已编译 "
                    + countWithin(readyByDistance, 3) + "/" + (7 * 7) + "、"
                    + countWithin(readyByDistance, 6) + "/" + (13 * 13) + "、"
                    + countWithin(readyByDistance, 12) + "/" + (25 * 25));
        }
    }

    /**
     * 以某个区块为中心、半径 {@code radius} 的方形范围，客户端网格是不是都编译好了。
     *
     * <p><b>别拿它当落机判据</b>：判据是 {@code LevelRenderer#isSectionCompiled}，
     * 而 Sodium 接管了区块渲染，装 Sodium 时它<b>永远返回 false</b>（2026-11-04 实测：
     * 用它的门口判据永远不成立，只能等超时，表现是"显示已移交真实区块后还在天上停十几秒"）。
     * 落机现在改用 {@code SiteRevealWorldRenderer#gateAreaDrawn}（Sodium 绘制列表回报）。
     * 这里留着只给诊断/无 Sodium 的排查用。
     */
    public static boolean areaCompiled(Minecraft mc, int centerChunkX, int centerChunkZ, int radius) {
        ClientLevel level = mc.level;
        if (level == null || mc.levelRenderer == null) return false;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!chunkReady(mc, level, centerChunkX + dx, centerChunkZ + dz)) return false;
            }
        }
        return true;
    }

    /** 区块中心那一列的地表 section 编译好了没有。 */
    private static boolean chunkReady(Minecraft mc, ClientLevel level, int chunkX, int chunkZ) {
        ChunkAccess chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null) return false;
        int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, 8, 8);
        int y = Math.max(level.getMinBuildHeight(), surface - 1);
        probe.set(chunkX * 16 + 8, y, chunkZ * 16 + 8);
        return mc.levelRenderer.isSectionCompiled(probe);
    }

    private static int countWithin(int[] readyByDistance, int radius) {
        int sum = 0;
        for (int r = 0; r <= radius && r < readyByDistance.length; r++) {
            sum += readyByDistance[r];
        }
        return sum;
    }

    private static void resetRun() {
        loginAtMs = 0L;
        markedLoginAtMs = 0L;
        runCreateAtMs = 0L;
        originChunkX = 0;
        originChunkZ = 0;
        lastCheckAt = 0L;
        capped = false;
        java.util.Arrays.fill(done, false);
    }
}
