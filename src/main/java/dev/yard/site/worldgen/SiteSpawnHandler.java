package dev.yard.site.worldgen;

import dev.yard.site.YardSiteLog;
import dev.yard.site.YardSiteConfig;
import dev.yard.site.api.SiteLink;
import dev.yard.site.api.SiteLinks;
import dev.yard.site.api.SitePlan;
import dev.yard.site.compat.EpicTerrainCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 新世界初始化时接管出生点：玩家在选区图上点了哪里，原版就把出生点放在哪里。
 *
 * <p>NeoForge 在 {@code MinecraftServer.setInitialSpawn} 里给了一个可取消事件
 * （<b>只在 {@code !levelData.isInitialized()} 时触发</b>，也就是建新世界/重建世界）。
 * 我们取消它、自己设 spawn，原版就不会先在随机位置找出生点，随后
 * {@code prepareLevels} 会直接围着选区生成「出生点区块」——这正是「确定后生成选区附近的区块」。
 *
 * <h2>两种模式</h2>
 * <ul>
 *   <li><b>有联动方</b>（庭院系列）：规划由联动方做（按院子尺寸找一块平地、定建筑原点），
 *       出生点通常落在建筑内部，这里只负责把结果落进世界。</li>
 *   <li><b>独立安装</b>：落点就是出生点，y 取这一列的地表高度。</li>
 * </ul>
 *
 * <h2>为什么要在建世界阶段就把 y 定下来</h2>
 * 以前是「出生点先放在落点，进世界后盖房再把出生点挪过去」：第二次 {@code setDefaultSpawnPos}
 * 会把出生点区块的 ticket 移到新位置，于是两片 7×7 区块区域都被生成，边界上多出窄条。
 * 现在在<b>生成区块之前</b>就用高度噪声把 y 算好，之后不再移动出生点区块。
 */
public final class SiteSpawnHandler {

    private SiteSpawnHandler() {}

    public static void onCreateSpawnPosition(LevelEvent.CreateSpawnPosition event) {
        if (!YardSiteConfig.SPAWN_SITE_SELECT.get()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;

        SiteLink link = SiteLinks.get();
        if (link != null && !link.siteSelectEnabled()) return;

        SiteSelection.Pending pending = SiteSelection.take(level.getSeed());
        if (pending == null) return;

        long startedAt = System.currentTimeMillis();
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = randomState(level, generator);
        // 只有落点这几列，没有邻居网格可参考 → 起点走默认快扫，上限按世界认（史诗地形那种抬到 500）
        SurfaceProbe probe = new SurfaceProbe(generator, randomState, level.getMinBuildHeight(),
                level.getHeight(), generator.getSeaLevel(),
                EpicTerrainCompat.peakCeiling(level.registryAccess()));
        LevelHeightAccessor accessor = probe.fullAccessor();

        SitePlan plan = link == null ? null : link.plan(level, probe, pending.x(), pending.z(), pending.exact());
        if (plan == null) plan = spawnOnly(probe, pending.x(), pending.z());
        BlockPos spawn = clampSpawnY(new BlockPos(plan.spawnX(), plan.spawnY(), plan.spawnZ()), accessor);
        plan = withSpawnY(plan, spawn.getY());

        // 出生点区块半径要在「加 ticket 之前」改：prepareLevels 先按这个值给出生点加 START ticket，
        // 再按它算要等多少个区块 ticking。这里改晚了就只影响等待数量、不影响生成范围。
        int chunkRadius = YardSiteConfig.SITE_SPAWN_CHUNK_RADIUS.get();
        if (chunkRadius <= 0) {
            chunkRadius = level.getGameRules().getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS);
        }
        // 早进世界：加载期把半径压到 SiteFastLoad.LOAD_RADIUS（1 = 等 9 个区块，约 2～3 秒），
        // 正式半径由 SiteFastLoad 在 ServerStartedEvent（prepareLevels 之后、开始 tick 之前）补回去。
        // 世界渲染阶段只有登录后才存在，所以"真区块一块块顶掉沙盘柱子"只能发生在登录后 —— 生成挪过去。
        boolean early = YardSiteConfig.SITE_EARLY_ENTRY.get() && YardSiteConfig.SITE_REVEAL_ANIM.get();
        if (early) {
            level.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS)
                    .set(SiteFastLoad.LOAD_RADIUS, null);
            SiteFastLoad.arm(level, chunkRadius);
        } else if (chunkRadius > 0) {
            level.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(chunkRadius, null);
        }

        event.getSettings().setSpawn(spawn, 0.0F);
        event.setCanceled(true);

        plan = new SitePlan(plan.siteX(), plan.siteZ(),
                plan.originX(), plan.originY(), plan.originZ(),
                spawn.getX(), spawn.getY(), spawn.getZ(),
                plan.seaLevel(), chunkRadius, plan.plot());
        SiteRevealState.publish(plan);
        if (link != null) link.onPlanned(level, plan);

        YardSiteLog.debug("选区接管出生点：" + describe(plan)
                + "，出生点区块半径 " + chunkRadius + "（" + (chunkRadius * 2 + 1) + "×"
                + (chunkRadius * 2 + 1) + " 个区块）"
                + (early ? "，加载期先按 " + SiteFastLoad.LOAD_RADIUS + " 放行登录、进世界后再补生成"
                        : "，原版先等这些区块生成完")
                + "（规划耗时 " + (System.currentTimeMillis() - startedAt) + " ms）");
    }

    /**
     * 独立安装时的 y 修正：规划阶段只有「雕刻与地物之前」的地形，树、雪层这些要在区块生成之后才知道。
     * 出生点区块生成完（{@link ServerStartedEvent}，早于玩家连接）就用真实高度图再对一次，
     * 只改 y、不改 x/z，所以出生点区块的 ticket 不会跨区块，也不会多生成一片地。
     *
     * <p>有联动方时不做：那时出生点在建筑内部，y 是按占地统一规划好的，改了就跑到屋顶上去了。
     */
    public static void onServerStarted(ServerStartedEvent event) {
        SitePlan plan = SiteRevealState.plan();
        if (plan == null || plan.hasPlot()) return;
        ServerLevel level = event.getServer().overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ());
        if (surface <= level.getMinBuildHeight() || surface == spawn.getY()) return;
        level.setDefaultSpawnPos(new BlockPos(spawn.getX(), surface, spawn.getZ()),
                level.getSharedSpawnAngle());
        YardSiteLog.debug("出生点高度按真实地形修正：y " + spawn.getY() + " → " + surface);
    }

    private static String describe(SitePlan plan) {
        return plan.hasPlot()
                ? "占地原点 " + shortPos(plan.originX(), plan.originY(), plan.originZ())
                        + "，出生点 " + shortPos(plan.spawnX(), plan.spawnY(), plan.spawnZ())
                : "出生点 " + shortPos(plan.spawnX(), plan.spawnY(), plan.spawnZ());
    }

    private static String shortPos(int x, int y, int z) {
        return x + ", " + y + ", " + z;
    }

    /** 独立安装：落点即出生点，y 取那一列的地表高度（水域取海平面之上，别把人放进水里）。 */
    private static SitePlan spawnOnly(SurfaceProbe probe, int x, int z) {
        int surface = probe.surfaceAt(x, z);
        int y = Math.max(surface, probe.seaLevel() + 1);
        return new SitePlan(x, z, x, y, z, x, y, z, probe.seaLevel(), 0, null);
    }

    private static SitePlan withSpawnY(SitePlan plan, int y) {
        return new SitePlan(plan.siteX(), plan.siteZ(), plan.originX(), plan.originY(), plan.originZ(),
                plan.spawnX(), y, plan.spawnZ(), plan.seaLevel(), plan.spawnChunkRadius(), plan.plot());
    }

    /** 出生点的 y 夹进可建造范围，避免贴着世界上下边界。 */
    private static BlockPos clampSpawnY(BlockPos spawn, LevelHeightAccessor accessor) {
        int y = Mth.clamp(spawn.getY(), accessor.getMinBuildHeight() + 1, accessor.getMaxBuildHeight() - 2);
        return new BlockPos(spawn.getX(), y, spawn.getZ());
    }

    @Nullable
    static RandomState randomState(ServerLevel level, ChunkGenerator generator) {
        RandomState existing = level.getChunkSource().randomState();
        if (existing != null) return existing;
        NoiseGeneratorSettings settings = generator instanceof NoiseBasedChunkGenerator noise
                ? noise.generatorSettings().value()
                : NoiseGeneratorSettings.dummy();
        return RandomState.create(settings, level.registryAccess().lookupOrThrow(Registries.NOISE), level.getSeed());
    }
}
