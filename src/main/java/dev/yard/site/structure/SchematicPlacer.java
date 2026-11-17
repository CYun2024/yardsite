package dev.yard.site.structure;

import dev.yard.site.YardSiteLog;
import dev.yard.site.api.SitePlan;
import dev.yard.site.schematic.Schematic;
import dev.yard.site.schematic.SchematicTransform;
import dev.yard.site.worldgen.SiteRevealState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Map;
import java.util.Optional;

/**
 * 把结构铺进世界：出生点区块生成完之后，按 {@link SitePlan} 的位置把投影里的方块一格一格放下去。
 *
 * <h2>为什么分 tick 做</h2>
 * 一栋 40×40×20 的楼要写几万个方块，全在同一 tick 里做完会把服务器堵住一大截（玩家进服前那几秒
 * 本来就紧张）。这里每 tick 写 {@value #BLOCKS_PER_TICK} 格，剩下的排队，铺完才翻
 * {@code SiteRevealState.markBuilt()}。
 *
 * <h2>顺序</h2>
 * <ol>
 *   <li><b>整平</b>（{@code structure.flatten}）：地基往下补 {@value #FOUNDATION_DEPTH} 格、占地范围里
 *       从地基到屋顶高度清空。坡地、树下、水边也能盖得方正，不然一半方块会埋在土里、树会从客厅穿出去。</li>
 *   <li><b>铺方块</b>：按 y 从下往上（和落成动画的扫描线同一个方向），空气不写。</li>
 * </ol>
 *
 * <p>方块状态会跟着 {@link SchematicTransform} 一起镜像/旋转（楼梯、门、栅栏的朝向不会错位）。
 *
 * <h2>什么时候不做</h2>
 * 专用服务器建图时没有选区流程，不会 {@link #arm}；{@code structure.place = false} 时不铺，
 * 只留线框（让别人去盖）。
 */
public final class SchematicPlacer {

    /**
     * 每 tick 最多写多少格（整平 + 铺方块一共算）。
     *
     * <p>每格都会给客户端发一次方块更新，写太快会把这十几秒的落成镜头顶出卡顿；
     * 4 千格/tick 在 20 tps 下约 8 万格/秒，一栋 40×40×20 的楼两秒内铺完，机器还有余量。
     */
    private static final int BLOCKS_PER_TICK = 4_096;
    /** 世界起来之后先等这么多 tick 再动手：出生点区块和出生点位置还在收尾。 */
    private static final int START_DELAY_TICKS = 20;
    /** 地基往下补几格（不够就补到碰着实地为止）。 */
    private static final int FOUNDATION_DEPTH = 4;
    private static final BlockState FILL = Blocks.DIRT.defaultBlockState();

    /** 已经接下的活（还没到点）。 */
    private static SitePlan pending;
    private static int startAtTick = -1;
    /** 正在铺的这份。 */
    private static Job job;

    private SchematicPlacer() {}

    /** 规划阶段调用：记下这次要盖在哪，等世界起来再动手。 */
    public static void arm(SitePlan plan) {
        pending = plan;
        startAtTick = -1;
        job = null;
    }

    public static void onServerStarted(ServerStartedEvent event) {
        if (pending == null) return;
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        Schematic schematic = SchematicStructure.get();
        if (schematic == null) {
            YardSiteLog.warn("结构模式：要盖建筑，但结构文件读不到（"
                    + SchematicStructure.lastError() + "），这次只画线框");
            pending = null;
            return;
        }
        job = new Job(level, pending, schematic, SchematicStructure.transform(),
                SchematicStructure.flatten());
        startAtTick = server.getTickCount() + START_DELAY_TICKS;
        pending = null;
        YardSiteLog.debug("结构待铺：原点 " + job.plan.originX() + "," + job.plan.originY() + ","
                + job.plan.originZ() + "，占地 " + job.width + "×" + job.depth + "×" + job.height
                + (job.flatten ? "，先整平" : "，不整平"));
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (job == null) return;
        MinecraftServer server = event.getServer();
        if (startAtTick >= 0) {
            if (server.getTickCount() < startAtTick) return;
            startAtTick = -1;
        }
        Job current = job;
        current.step();
        if (current.done) {
            job = null;
            SiteRevealState.markBuilt();
            YardSiteLog.debug("结构已铺完：" + current.placed + " 格方块"
                    + (current.flatten ? "，整平 " + current.cleared + " 格" : "")
                    + "，用了 " + current.ticks + " tick（" + (System.currentTimeMillis() - current.startedAt)
                    + " ms）");
        }
    }

    /** 换存档时清空。 */
    public static void clear() {
        pending = null;
        job = null;
        startAtTick = -1;
    }

    /** 一次铺房任务。整个流程分成两段，靠 {@link #phase} 推进。 */
    private static final class Job {
        final ServerLevel level;
        final SitePlan plan;
        final Schematic schematic;
        final SchematicTransform transform;
        final boolean flatten;
        final int ox;
        final int oy;
        final int oz;
        final int width;
        final int depth;
        final int height;
        final BlockState[] states;
        final long startedAt = System.currentTimeMillis();

        int phase;
        int colX;
        int colZ;
        int index;
        int placed;
        int cleared;
        int ticks;
        boolean done;

        Job(ServerLevel level, SitePlan plan, Schematic schematic,
            SchematicTransform transform, boolean flatten) {
            this.level = level;
            this.plan = plan;
            this.schematic = schematic;
            this.transform = transform;
            this.flatten = flatten;
            this.ox = plan.originX();
            this.oy = plan.originY();
            this.oz = plan.originZ();
            this.width = plan.plot() == null ? schematic.sizeX : plan.plot().width();
            this.depth = plan.plot() == null ? schematic.sizeZ : plan.plot().depth();
            this.height = schematic.sizeY;
            this.states = blockStates(schematic, transform);
            if (!flatten) phase = 1;
        }

        /** 这一 tick 写一批，写完推进游标；没活干就收工。 */
        void step() {
            ticks++;
            int budget = BLOCKS_PER_TICK;
            if (phase == 0) {
                budget = flattenStep(budget);
                if (phase == 0) return;   // 还没整平完，这一 tick 就到这
            }
            placeStep(budget);
            if (index >= volume()) done = true;
        }

        /** 整平：按列做，一列里把地基补实、把占地范围清空。 */
        private int flattenStep(int budget) {
            // 一列最多 (地基 + 高度) 格；夹到一 tick 的预算上限，免得巨塔永远做不完一列
            int perColumn = Math.min(FOUNDATION_DEPTH + height, BLOCKS_PER_TICK);
            while (budget > 0) {
                if (colZ >= depth) {
                    phase = 1;
                    return budget;
                }
                if (budget < perColumn) break;      // 剩下的预算不够做完整列：留到下一 tick，别做半列
                int x = ox + colX;
                int z = oz + colZ;
                level.getChunk(x >> 4, z >> 4);
                // 地基以下：只补空的和水，别把石头/矿石换成泥土
                for (int y = oy - 1; y >= oy - FOUNDATION_DEPTH; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState existing = level.getBlockState(pos);
                    if (existing.isAir() || !existing.getFluidState().isEmpty()) {
                        level.setBlock(pos, FILL, Block.UPDATE_CLIENTS);
                        cleared++;
                    }
                    budget--;
                }
                // 占地范围：从地基到屋顶高度清空（树、地形、水都清掉）
                for (int y = oy; y < oy + height; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                        cleared++;
                    }
                    budget--;
                }
                colX++;
                if (colX >= width) {
                    colX = 0;
                    colZ++;
                }
            }
            return budget;
        }

        /**
         * 铺方块：按<b>结构局部坐标</b>从下往上走，每格换算成世界坐标再写。
         *
         * <p>不反过来「遍历世界占地、猜这一格是结构的哪一格」—— 那要按旋转求逆，每格都得扫一遍，
         * 是 O(格数²)。局部遍历天然是 O(格数)，而且 y 从下往上正好和落成动画的扫描线一致。
         */
        private void placeStep(int budget) {
            int sx = schematic.sizeX;
            int sz = schematic.sizeZ;
            int volume = volume();
            while (budget > 0 && index < volume) {
                int ly = index / (sx * sz);
                int rest = index % (sx * sz);
                int lz = rest / sx;
                int lx = rest % sx;
                index++;
                budget--;
                int cell = schematic.cell(lx, ly, lz);
                if (cell < 0) continue;
                BlockState state = cell < states.length ? states[cell] : null;
                if (state == null) continue;
                BlockPos pos = new BlockPos(
                        ox + transform.blockX(lx, lz, sx, sz), oy + ly,
                        oz + transform.blockZ(lx, lz, sx, sz));
                if (level.getBlockState(pos) == state) continue;
                level.setBlock(pos, state, Block.UPDATE_CLIENTS);
                placed++;
            }
        }

        private int volume() {
            return schematic.sizeX * schematic.sizeY * schematic.sizeZ;
        }

    }

    /**
     * 调色板 → 方块状态，一次性算好并带上镜像/旋转。
     *
     * <p>先镜像再旋转，和 {@link SchematicTransform} 的坐标换算同一个顺序，
     * 否则楼梯会朝错方向（转了 90° 但朝向没转，或者反过来）。
     */
    @SuppressWarnings("deprecation")   // 1.21.1 里 BlockState#rotate 被标了过时，但行为就是我们要的（连朝向属性一起转）
    private static BlockState[] blockStates(Schematic schematic, SchematicTransform transform) {
        BlockState[] out = new BlockState[schematic.ids.size()];
        Rotation rotation = switch (transform.turns() & 3) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
        for (int i = 0; i < out.length; i++) {
            BlockState state = resolve(schematic.ids.get(i), schematic.props.get(i));
            if (state == null) continue;
            if (transform.mirrorX()) state = state.mirror(Mirror.LEFT_RIGHT);
            if (transform.mirrorZ()) state = state.mirror(Mirror.FRONT_BACK);
            if (rotation != Rotation.NONE) state = state.rotate(rotation);
            out[i] = state;
        }
        return out;
    }

    /** 方块 id + 属性 → 方块状态；不认识的方块返回 {@code null}（跳过这一格，不崩）。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState resolve(String id, Map<String, String> properties) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) return null;
        Block block = BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
        if (block == null || block == Blocks.AIR) return null;
        BlockState state = block.defaultBlockState();
        if (properties.isEmpty()) return state;
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            Property property = state.getBlock().getStateDefinition().getProperty(entry.getKey());
            if (property == null) continue;
            Optional<? extends Comparable<?>> value = property.getValue(entry.getValue());
            if (value.isPresent()) {
                state = state.setValue(property, (Comparable) value.get());
            }
        }
        return state;
    }
}
