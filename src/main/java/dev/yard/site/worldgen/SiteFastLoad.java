package dev.yard.site.worldgen;

import dev.yard.site.YardSiteLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * 早进世界：加载期把出生点区块半径压到 0，登录几乎立刻发生，剩下的区块在进世界后继续生成。
 *
 * <p>为什么要这么绕：原版 {@code MinecraftServer#prepareLevels} 会等
 * {@code (2r+1)²} 个区块到 ticking 才放行登录，而世界渲染阶段只有登录之后才存在。
 * 不缩短这段等待，生成过程中的"真地形替换"就只能由 GUI 沙盘假装，玩家永远看不到真区块。
 *
 * <p>半径 0 时 {@code prepareLevels} 的等待数是 0，而且 {@code ServerLevel#setDefaultSpawnPos}
 * 也不会加 START ticket；{@link ServerStartedEvent} 在 {@code prepareLevels} 之后、第一次 tick
 * 之前触发，这里恢复正式半径再调一次 {@code setDefaultSpawnPos}，原版会自己补回 START ticket。
 * 结果：出生点区块该保活的照旧，只是生成挪到了登录之后（客户端 <b>isReady 要等第一次 tick</b>，
 * 所以补 ticket 一定发生在玩家连接之前）。
 */
public final class SiteFastLoad {

    /**
     * 加载期使用的出生点区块半径。原版等待量是 {@code (2r+1)²}：这里 1 = 等 9 个区块（约 2～3 秒），
     * 进服时出生点周围已有一小片真地形；正式半径在 {@link ServerStartedEvent} 补回。
     */
    public static final int LOAD_RADIUS = 1;

    private static volatile ServerLevel level;
    /** 正式半径；-1 = 没登记。 */
    private static volatile int spawnChunkRadius = -1;

    private SiteFastLoad() {}

    /** 建世界时（gamerule 已被压到 {@link #LOAD_RADIUS} 之后）登记：服务器一起来就把正式半径补回去。 */
    public static void arm(ServerLevel serverLevel, int intendedRadius) {
        level = serverLevel;
        spawnChunkRadius = intendedRadius;
        YardSiteLog.debug("早进世界：加载期出生点区块半径按 " + LOAD_RADIUS
                + " 放行登录（等 " + ((LOAD_RADIUS * 2 + 1) * (LOAD_RADIUS * 2 + 1)) + " 个区块），"
                + "ServerStartedEvent 恢复为 " + intendedRadius);
    }

    public static void onServerStarted(ServerStartedEvent event) {
        ServerLevel armed = level;
        // 上一次世界没来得及恢复就换了服务器：旧 level 不算数
        if (armed != null && armed.getServer() != event.getServer()) {
            clear();
            return;
        }
        restore();
    }

    /** 恢复正式半径并补回 START ticket；没登记过就什么都不做。 */
    public static void restore() {
        ServerLevel serverLevel = level;
        int radius = spawnChunkRadius;
        clear();
        if (serverLevel == null || radius < 0) return;

        GameRules rules = serverLevel.getGameRules();
        rules.getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(radius, null);
        BlockPos spawn = serverLevel.getSharedSpawnPos();
        // prepareLevels 当时按 LOAD_RADIUS 加的 START ticket 半径不对，这里按正式半径再调一次：
        // remove 旧 ticket / add 新 ticket 都由原版 setDefaultSpawnPos 自己做。
        serverLevel.setDefaultSpawnPos(spawn, serverLevel.getSharedSpawnAngle());
        YardSiteLog.debug("早进世界：出生点区块半径恢复为 " + radius
                + "（START ticket 半径 " + (radius + 1) + "），剩下的区块从现在开始补生成");
    }

    /** 换存档/收场时清掉登记（没恢复的也别在下一个世界头上动）。 */
    public static void clear() {
        level = null;
        spawnChunkRadius = -1;
    }
}
