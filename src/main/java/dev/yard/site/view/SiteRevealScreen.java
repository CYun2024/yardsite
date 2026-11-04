package dev.yard.site.view;

import dev.yard.site.YardSiteLog;
import dev.yard.site.client.ChunkLoadDiagnostics;
import dev.yard.site.client.RevealCinematics;
import dev.yard.site.api.SiteLink;
import dev.yard.site.api.SiteLinks;
import dev.yard.site.api.SitePlan;
import dev.yard.site.worldgen.SiteRevealState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

/**
 * 确认选址 → 进世界的落成动画。
 *
 * <p><b>现在的流程</b>：登录前只有<b>一张图</b>——开场那一帧就是精读画面本身（同一批格子、同一个屏幕
 * 原点），然后这个画面从正交一路 morph 到透视：镜头推到院子上空、FOV 拉开、地形展开成真实高度，
 * 中途格子悄悄从一格 16 方块换成一格 4 方块（两份场共用同一批区块采样，换的那帧画面不差一个像素）。
 * 玩家登录后画面交给真世界，柱子继续在 GUI 层用同一套相机画，并盖住<b>整个视野</b>。
 * 让位<b>从庭院开始向外扩</b>（排时刻按离院子中心的远近），但每一块要等<b>它身下那块真实区块真的进了
 * Sodium 的绘制列表</b>才开始淡，所以不会出现"柱子化了、底下还是空的"；每一块都是<b>原地</b>淡掉
 * （柱顶不高低变化、整块一起、不逐格错峰，镜头这一段又定住），既不会"往上飘着散"也不会"从某条边推到另一边"。
 * 视距外或相机背面那些永远等不到网格的，到自己的上限就按排时刻走 —— 不这样收尾就会把几百根柱子吊到最后
 * 一起抹掉。全部放行并且最后那块淡完，才进落机。
 * 落机前先把玩家放进<b>一层走廊中段</b>（大门往里几格，脸朝屋子内；判据是那一格真的站得住，每帧重试到
 * 盖完为止），镜头再走一条四段航线：<b>空中机位 → 大门正上方（屋顶以上）→ 门廊外·门轴线上·眼高 →
 * 顺着门洞等速进屋、落到玩家眼睛</b>。竖直下降那一列落在门廊顶棚外面，穿门那段走门洞中线，
 * 所以既不刮檐口也不从门楣/墙里穿出来；时长按行程算（3.2~5.2 秒），行程不再被塞进 1.9 秒里。
 *
 * <p>装了 Sodium 时，还没轮到的区块列连真实 section 都不提交绘制（{@code ChunkRenderListMixin}），
 * 所以沙盘淡出时露出来的确实是“这一刻才出现”的真实地形，而不是一直画在遮罩下面的世界；
 * 实体和方块实体也一起过滤。没装 Sodium 时这组 mixin 自动不生效，退化为纯遮罩。
 *
 * <p>动画期间玩家（客户端 + 服务端实体）设为无敌，免得受伤红屏和镜头抖打断镜头运动；
 * 收场时恢复原值。
 *
 * <p>柱子仍画在 GUI 批里，和真地形不共享深度；柱子只留在还没露出的地方，边界可能有少量覆盖。
 *
 * <p>原版 {@code Minecraft#doWorldLoad} 准备打开 {@code LevelLoadingScreen} 时换成这里
 * （见 {@code MinecraftSiteLoadMixin}）；登录时原版要插的那屏收区块过渡屏先拦住
 * （见 {@link SiteRevealGuard}）。生成、落盘、发包全是原版那一套，这里只换界面。
 */
public class SiteRevealScreen extends Screen {

    /** 一直读不到计划就认输（毫秒）：建世界前半段还没有服务端落点，别急着退出。 */
    private static final int NO_PLAN_MS = 30_000;
    /** 露真世界最多等这么久，之后直接落机（毫秒）。 */
    private static final int REVEAL_READY_CAP_MS = 25_000;
    /** 登录后不透明底淡出的时长（毫秒）。 */
    private static final int BACKDROP_FADE_MS = 900;
    /** 落机时长：按每段行程定速算，再夹在这个区间里（毫秒）。 */
    private static final int DESCEND_MIN_MS = 3400;
    private static final int DESCEND_MAX_MS = 5500;
    /** 三段各自的目标速度（格/秒）：空中掠过去可以快，门口必须慢。 */
    private static final double SPEED_AIR = 32.0D;
    private static final double SPEED_DROP = 12.0D;
    private static final double SPEED_DOOR = 9.0D;
    /**
     * 门外那一点往 -z 让开的格数。门廊顶棚通常铺到前墙外 3 格，所以下降列必须落在它外面，
     * 否则镜头是顺着檐口刮下来的（之前"一直穿墙"的一半原因就在这）。
     */
    private static final double APPROACH_BACK = 4.5D;
    /** 大门上方中转点比屋顶（{@code originY + 占地高度}）再高这么多格。 */
    private static final double APPROACH_ABOVE = 2.5D;
    /**
     * 大门上方那一点比门外那一点再往外让开的格数：让下降带一点内收，
     * 三段航线才接得顺（两点同 z 的话，Hermite 会在竖直段里往回悠一下，看着像卡了一下）。
     */
    private static final double APPROACH_LEAN = 2.0D;
    /** 没有建筑时的中转点：出生点正上方这么高，直接竖直落到眼睛里。 */
    private static final double SPAWN_APPROACH_ABOVE = 14.0D;
    /** 视角从「空中俯视」对齐到玩家视线，在这一段进度里做完（要早于穿门，进门前就得看正前方）。 */
    private static final float LOOK_FROM = 0.30F;
    private static final float LOOK_SPAN = 0.45F;
    /**
     * 落机进度按帧累加，单帧最多记这么多毫秒。
     * <p>建世界之后仍在发区块，偶尔会有 100～300 ms 的卡帧；如果进度直接用墙上时间算，
     * 一卡帧镜头就"瞬移"一大段（40 格/秒 × 0.2 s = 8 格），看起来就是收尾在抽搐。
     * 封顶之后卡帧只让镜头少走一点、动画整体略微变长，画面是连续的。
     */
    private static final long MAX_DESCEND_FRAME_MS = 80L;
    /**
     * 落机前看一下门口这片（方形半径，区块）的网格上没上屏。
     *
     * <p>实测（2026-11-03 21:06 的日志）：落机 5.5 秒里我们自己的开销是 0 ms，
     * 唯一一次 169 ms 卡帧正好压在"半径 6（13×13）客户端编译完"那一刻 —— 是网格上传，不是镜头。
     * 等它就位再开场，卡顿就不再叠在收尾上。
     *
     * <p><b>判据换成「真实网格进过绘制列表」</b>（和让位用的是同一份 Sodium 回报）：
     * 原来问原版 {@code LevelRenderer#isSectionCompiled}，而 Sodium 接管了区块渲染，那个接口
     * 在 Sodium 下永远报未编译 —— 判据永远不成立，只能干等到超时，这就是"已移交真实区块之后
     * 还在天上停十几秒"的原因。没装 Sodium（收不到回报）时直接放行，不拿这条卡人。
     */
    private static final int GATE_READY_RADIUS = 6;
    /** 查门口的画面上屏状态别每帧查（169 个区块，每帧查也够呛）。 */
    private static final long GATE_READY_POLL_MS = 250L;
    /**
     * 等待圈已经露完之后，最多再给门口这片这么久（毫秒）：门口那几列可能正好在相机背面、
     * 永远进不了绘制列表，不能让它把人吊死。
     */
    private static final long GATE_GRACE_MS = 1_200L;
    /** 总上限：动画再好看也不能把人关在外面。 */
    private static final int HARD_CAP_MS = 60_000;

    private static final int GOLD = 0xFFFFD24A;
    private static final int TEXT_SUB = 0xFFD4D9E4;
    private static final int TEXT_HINT = 0xFFA6AEBE;
    private static final int BACKDROP = 0xFF0A0C12;

    private final ChunkSitePlanner planner;
    private final SiteReliefField relief;
    private final SiteField field;
    private final int siteX;
    private final int siteZ;
    /** 登录前被跳过时交回的原版加载界面（此时 {@code minecraft.level} 还是 null）。 */
    private final Screen fallback;
    private final long openedAt = System.currentTimeMillis();

    private SiteRevealWorldRenderer world;
    private SiteBlueprint blueprint;
    private long blueprintAt;
    /** 是否已经读到服务端发布的真实落点（之前用点击处拼的临时计划先播）。 */
    private boolean planKnown;
    /** 客户端第一次有世界可渲染。 */
    private long loginAt;
    /** 相机交给真世界、天象视觉让路的时刻。 */
    private long aerialAt;
    /** 镜头开始落回玩家的时刻。 */
    private long descendAt;
    /**
     * 收尾消散的开始时刻：等待圈露完之后，先把剩下没轮到的柱子一次性散掉，
     * 散完（{@link SiteRevealWorldRenderer#COLLAPSE_MS}）才 {@code descendAt}，否则镜头会从柱子中间穿过去。
     */
    private long collapseAt;
    /**
     * 落机航点（世界坐标，第一个是空中机位、最后一个是玩家眼睛）与各自的时间节点。
     * 到 {@code descendAt} 那一刻才定；{@code null} = 还没定，退回直线插值。
     */
    private Vec3[] pathPoints;
    private float[] pathKnots;
    /** 这一段是怎么走的（日志用）。 */
    private String pathNote = "";
    /** 这次落机用多久（毫秒，按行程算）。 */
    private float descendMs = DESCEND_MIN_MS;
    /** 落机已走过的时长（毫秒）：按帧累加、单帧封顶，卡一下镜头不会瞬移。 */
    private float descendClockMs;
    /** 上一帧推进落机的时刻。 */
    private long descendLastAt;
    /** 落机这一段自己的统计（帧数 / 最大帧间隔 / 我们的最大开销），收尾时打一行。 */
    private long descendFrames;
    private long descendMaxGapMs;
    private long descendMaxFrameNs;
    private long descendMaxWorldNs;
    /** 柱子全淡完的时刻（用来报"等门口网格多等了多久"）。 */
    private long allReadyAt;
    /** 上一次查门口区块编译状态的时刻。 */
    private long gateCheckAt;
    /** 门口那片网格编译完了。 */
    private boolean gateAreaReady;
    /** 已经把人搬进建筑里了（成功后不再重复发传送包）。 */
    private boolean placedInCorridor;

    private long frameNow;

    private boolean hudWasHidden;
    private boolean hudTouched;
    /** 动画前的主音量；-1 = 还没静音。 */
    private float masterVolume = -1.0F;

    /** 动画期间替玩家扛伤害（服务端实体也设），别让受伤红屏/镜头抖打进动画。 */
    private boolean invulnerableTouched;
    private boolean invulnerableBefore;
    private boolean serverInvulnerableBefore;
    private boolean serverInvulnerableApplied;
    private UUID invulnerablePlayer;

    /** 正在播的那段动画：相机 mixin / FOV mixin 只认这一个入口。 */
    private static SiteRevealScreen active;

    SiteRevealScreen(SiteRevealHandoff.Payload payload) {
        super(SiteText.revealTitle(SiteText.Stage.ENTER));
        this.planner = payload.planner;
        this.relief = new SiteReliefField(planner, payload.siteX, payload.siteZ);
        this.field = relief;
        this.siteX = payload.siteX;
        this.siteZ = payload.siteZ;
        this.fallback = payload.fallback;
        SiteRevealWorldRenderer.Intro intro = new SiteRevealWorldRenderer.Intro(
                planner.field(), payload.view, payload.mapX, payload.mapY, payload.mapSize,
                payload.siteX, payload.siteZ);
        SitePlan plan = SiteRevealState.plan();
        this.planKnown = plan != null;
        // 服务端计划要等建世界流程走到出生点规划才有；先用点击处拼一份临时计划，
        // 让沙盘从点「确认并创建」那一刻就接着精读那张图往下推，而不是先看原版的「准备创建世界」屏。
        SitePlan initial = plan != null ? plan : provisionalPlan();
        this.world = new SiteRevealWorldRenderer(relief, initial, intro);
        if (plan != null) {
            this.blueprint = blueprintOf(plan);
            this.blueprintAt = openedAt + SiteRevealWorldRenderer.INTRO_MS;
        }
        YardSiteLog.debug("落成动画开始：落点 x=" + siteX + " z=" + siteZ
                + "，开场接着精读那张图往透视推"
                + (plan == null ? "（等真实落点）" : ""));
    }

    /** 施工线框：有联动方才有盒子；独立安装返回 null（不画）。 */
    private static SiteBlueprint blueprintOf(SitePlan plan) {
        SiteLink link = SiteLinks.get();
        if (link == null || !plan.hasPlot()) return null;
        return SiteBlueprint.of(plan, link.blueprint(plan));
    }

    /** 真实落点还没到时用的临时计划：以点击处为落点，高度取预读地表。 */
    private SitePlan provisionalPlan() {
        int center = (field.fine() - 1) / 2;
        int surface = field.surfaceAtCell(center, center);
        SitePlan.Plot plot = SiteText.plot();
        int width = plot == null ? 0 : plot.width();
        int depth = plot == null ? 0 : plot.innerDepth();
        return new SitePlan(siteX, siteZ,
                siteX - width / 2, surface, siteZ - depth / 2,
                siteX, surface, siteZ,
                field.seaLevel(), 3, plot);
    }

    /** 还没落机就拦住原版的收区块过渡屏。 */
    public boolean holdsHandoff() {
        return System.currentTimeMillis() - openedAt < HARD_CAP_MS;
    }

    @Override
    public boolean isPauseScreen() {
        // 一暂停服务端就不 tick，区块发不出来、旅社也盖不完
        return false;
    }

    @Override
    protected void init() {
        active = this;
        if (this.minecraft != null) {
            // 第一人称的手是挂在相机上的，镜头飞到空中后它会一直挡在画面底部
            this.minecraft.gameRenderer.setRenderHand(false);
            if (!this.hudTouched) {
                this.hudTouched = true;
                this.hudWasHidden = this.minecraft.options.hideGui;
                this.minecraft.options.hideGui = true;
            }
            if (this.masterVolume < 0.0F) {
                this.masterVolume = this.minecraft.options.getSoundSourceVolume(SoundSource.MASTER);
                this.minecraft.getSoundManager().updateSourceVolume(SoundSource.MASTER, 0.0F);
            }
        }
    }

    @Override
    public void removed() {
        if (active == this) active = null;
        RevealCinematics.setAerial(false);
        restoreInvulnerability(this.minecraft);
        if (this.minecraft != null) {
            this.minecraft.gameRenderer.setRenderHand(true);
            if (this.hudTouched) {
                this.hudTouched = false;
                this.minecraft.options.hideGui = this.hudWasHidden;
            }
            if (this.masterVolume >= 0.0F) {
                this.minecraft.getSoundManager().updateSourceVolume(SoundSource.MASTER, this.masterVolume);
                this.masterVolume = -1.0F;
            }
        }
        planner.close();
        SiteRevealState.reset();
    }

    // ── 进度推进 ────────────────────────────────────────────────────────

    private void advance(long now) {
        if (now - openedAt >= HARD_CAP_MS) {
            YardSiteLog.debug("落成动画撞到总上限，直接收场");
            finish();
            return;
        }
        SitePlan plan = SiteRevealState.plan();
        if (plan == null) {
            if (now - openedAt > NO_PLAN_MS) {
                YardSiteLog.debug("一直没读到选址计划，直接收场");
                finish();
                return;
            }
        } else if (!planKnown) {
            planKnown = true;
            // 真实落点到了：只把镜头终点偏过去，开场进度和换源状态都不动，镜头不会重新飞一遍
            world.retargetPlan(plan);
            blueprint = blueprintOf(plan);
            blueprintAt = now;
            YardSiteLog.debug("读到服务端落点：" + (plan.hasPlot() ? "占地中心" : "出生点")
                    + " x=" + plan.plotCenterX()
                    + " z=" + plan.plotCenterZ() + (blueprint == null ? "" : "，线框开始生长"));
        }
        if (loginAt == 0L && worldRendering()) {
            loginAt = now;
            ChunkLoadDiagnostics.markLoginAt(now);
            applyInvulnerability(this.minecraft);
            YardSiteLog.debug("客户端有世界了（点创建后 " + (now - openedAt) + " ms），交给真世界");
        }
        world.tick(this.minecraft, now);
        if (descendAt != 0L) {
            // 落机进度自己按帧累加（单帧封顶）：卡帧只让镜头少走一点，不会"瞬移一段"
            long gap = descendLastAt == 0L ? 0L : Math.max(0L, now - descendLastAt);
            descendLastAt = now;
            descendFrames++;
            descendMaxGapMs = Math.max(descendMaxGapMs, gap);
            descendClockMs += (float) Math.min(gap, MAX_DESCEND_FRAME_MS);
        }
        if (loginAt != 0L && aerialAt == 0L) {
            aerialAt = now;
            RevealCinematics.setAerial(true);
            YardSiteLog.debug("真世界接管相机（点创建后 " + (now - openedAt) + " ms）");
        }
        if (aerialAt != 0L && descendAt == 0L) {
            // 每帧都试着把人放到位：建筑是进世界之后才盖的，盖完那一刻之前走廊还站不住，一帧定生死会直接跳过
            placePlayerInCorridor();
            if (collapseAt == 0L) {
                // 只等等待圈（视距内 ±8~10 区块）露出来，不再等整张沙盘
                boolean all = world.zoneRevealed();
                if (all && allReadyAt == 0L) allReadyAt = now;
                if (all && !gateAreaReady && now - gateCheckAt >= GATE_READY_POLL_MS) {
                    gateCheckAt = now;
                    gateAreaReady = gateAreaCompiled(plan);
                }
                // 门口那片只是"让卡顿别叠在收尾上"的余量：圈已经露完就先等一小会儿，
                // 它要是永远不就位（相机背面 / 没装 Sodium），到点照样走，不能把人吊在天上
                boolean gateSettled = gateAreaReady
                        || (allReadyAt != 0L && now - allReadyAt >= GATE_GRACE_MS);
                boolean timeout = now - aerialAt >= REVEAL_READY_CAP_MS;
                if ((all && gateSettled) || timeout) {
                    // 先让剩下的沙盘一次性散干净，散完才动镜头（不然远处还立着柱子，镜头一挪就穿帮）
                    collapseAt = now;
                    world.beginCollapse();
                    long settle = allReadyAt == 0L ? 0L : now - allReadyAt;
                    YardSiteLog.debug("等待圈已露真地形（" + world.ready() + "/" + world.total()
                            + "，半径 " + world.waitRadius() + " 区块，" + world.revealReport()
                            + (timeout ? "，等到上限" : "")
                            + "，门口那片" + (gateAreaReady ? "已上屏"
                                    : "未就位、按宽限 " + settle + " ms 放行")
                            + "，点创建后 " + (now - openedAt) + " ms），开始收尾消散");
                }
            } else if (world.allReady()) {
                // allReady 这里的意思是「收尾那批也散完了」（用动画自己的时钟算，卡帧时不会提前动）
                descendAt = now;
                descendLastAt = now;
                prepareDescent();
                YardSiteLog.debug("沙盘散完，开始落机（点创建后 " + (now - openedAt) + " ms）");
            }
        }
        if (descendAt != 0L && descendClockMs >= descendMs) {
            reportDescend();
            YardSiteLog.debug("落成动画结束：镜头已交回玩家（点创建后 "
                    + (now - openedAt) + " ms）");
            finish();
        }
    }

    /**
     * 落机这一段到底卡在哪：帧间隔大 = 不是我们的动画，是建世界/发区块/GC 把渲染线程堵住了；
     * 本屏开销大 = 是我们自己的柱子批处理。这条日志就是为了分清这两件事。
     */
    private void reportDescend() {
        if (descendFrames <= 0L) return;
        float fps = descendFrames * 1000.0F / Math.max(1.0F, descendClockMs);
        YardSiteLog.debug("落机统计：" + descendFrames + " 帧 / " + Math.round(descendClockMs)
                + " ms（平均 " + String.format(java.util.Locale.ROOT, "%.1f", fps) + " 帧/秒），"
                + "最大帧间隔 " + descendMaxGapMs + " ms，本屏最大 "
                + (descendMaxFrameNs / 1_000_000L) + " ms（其中沙盘 "
                + (descendMaxWorldNs / 1_000_000L) + " ms）"
                + (descendMaxGapMs > 100L ? " → 卡顿主要来自帧间隔（区块/GC），不是镜头曲线" : ""));
    }

    /** 客户端真的在画这个世界了（有 level、有网格器、镜头也有人）。 */
    private boolean worldRendering() {
        Minecraft mc = this.minecraft;
        return mc != null && mc.level != null && mc.levelRenderer != null && mc.getCameraEntity() != null;
    }

    // ── 落机：镜头从空中落到玩家眼睛 ──────────────────────────────────────

    /**
     * 建筑是进世界之后才盖的，而出生点就在建筑内部：盖楼前人站在天然地面上（实测有人落在
     * y=56 被地基埋住、有人落在 y=77 站在屋顶上），盖楼后不改坐标就是"卡墙里/跑天花板上"。
     * 镜头终点是玩家眼睛，人不搬回去就会正好落进墙里或坑里。
     *
     * <p>只有「这次落座会盖建筑」（{@link SitePlan#hasPlot()}）时才搬：独立安装只选出生点，
     * 玩家本来就在正确的那一格上。
     *
     * <p>每帧调一次、放成功就不再动：判据用<b>真实的方块</b>（脚和头是空气、脚下不是空气），
     * 而不是"盖完了"那个标记——标记只在服务端那条生成路径翻，进服补生成那条以前不翻，
     * 于是人从来没被搬过。
     */
    private void placePlayerInCorridor() {
        Minecraft mc = this.minecraft;
        SitePlan plan = SiteRevealState.plan();
        if (mc == null || mc.level == null || mc.player == null || plan == null) return;
        if (!plan.hasPlot()) return;
        BlockPos door = new BlockPos(plan.spawnX(), plan.spawnY(), plan.spawnZ());
        if (placedInCorridor || mc.player.blockPosition().equals(door)) return;
        if (!standable(mc.level, door)) return;
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return;
        UUID id = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer self = server.getPlayerList().getPlayer(id);
            if (self == null) return;
            // yaw 0 = 朝 +z，就是从大门往里看屋子；别让玩家一落地面朝一堵墙
            self.connection.teleport(door.getX() + 0.5, door.getY(), door.getZ() + 0.5, 0.0F, 0.0F);
        });
        placedInCorridor = true;
        YardSiteLog.debug("落机前把玩家放回出生点 " + door.toShortString()
                + "（原来在 " + mc.player.blockPosition().toShortString() + "）");
    }

    /** 这一格站得住吗：脚和头都是空气、脚下不是空气（读的是客户端自己的区块，渲染线程安全）。 */
    private static boolean standable(Level level, BlockPos feet) {
        return level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir()
                && !level.getBlockState(feet.below()).isAir();
    }

    /**
     * 落机路径。两种走法：
     * <ul>
     *   <li><b>有建筑</b>：{@code 空中机位 → 大门正上方（屋顶以上）→ 门廊外·门轴线上·眼高 → 玩家眼睛}。
     *       只留「门外一点 + 直线插到眼睛」是不行的：空中机位常在屋子另一侧几十格外，直线过去必然
     *       从屋顶/门楣穿过。四段拆开之后：第一段从院子外面绕过来（屋顶以上），第二段<b>竖直</b>下降
     *       （列落在门廊顶棚外面，不刮檐口），第三段<b>沿门洞轴线等速平移</b>，正好从两格高的门洞
     *       穿进走廊。</li>
     *   <li><b>没有建筑</b>：{@code 空中机位 → 出生点正上方 → 玩家眼睛}。地面上什么都没有，
     *       竖直落下来就行。</li>
     * </ul>
     * 时长<b>按每段行程定速算</b>（见 {@code SPEED_*}），再夹在 {@value #DESCEND_MIN_MS}~{@value #DESCEND_MAX_MS}
     * ms 之间，时间节点由各段反推 —— 不会把上百格行程塞进一两秒。
     */
    private void prepareDescent() {
        Minecraft mc = this.minecraft;
        SitePlan plan = SiteRevealState.plan();
        if (mc == null || plan == null) return;
        BlockPos spawn = new BlockPos(plan.spawnX(), plan.spawnY(), plan.spawnZ());
        double eyeHeight = mc.player == null ? 1.62D : mc.player.getEyeHeight();
        double eyeY = spawn.getY() + eyeHeight;
        Vec3 eyeAtSpawn = new Vec3(spawn.getX() + 0.5D, eyeY, spawn.getZ() + 0.5D);
        Vec3 aerial = world.eye();

        java.util.List<Vec3> points = new java.util.ArrayList<>(4);
        java.util.List<Double> speeds = new java.util.ArrayList<>(3);
        points.add(aerial);
        if (plan.hasPlot() && plan.plot() != null) {
            double axisX = gateAxisX(mc.level, spawn, plan.originZ());
            double frontZ = plan.originZ() - APPROACH_BACK;
            Vec3 front = new Vec3(axisX, eyeY, frontZ);
            Vec3 high = new Vec3(axisX,
                    plan.originY() + plan.plot().height() + APPROACH_ABOVE, frontZ - APPROACH_LEAN);
            points.add(high);
            speeds.add(SPEED_AIR);
            points.add(front);
            speeds.add(SPEED_DROP);
            points.add(eyeAtSpawn);
            speeds.add(SPEED_DOOR);
            this.pathNote = "门轴 x=" + fmt(axisX) + "，门外 z=" + fmt(frontZ);
        } else {
            Vec3 high = new Vec3(eyeAtSpawn.x, eyeY + SPAWN_APPROACH_ABOVE, eyeAtSpawn.z);
            points.add(high);
            speeds.add(SPEED_AIR);
            points.add(eyeAtSpawn);
            speeds.add(SPEED_DROP);
            this.pathNote = "出生点 " + spawn.getX() + "," + spawn.getZ() + " 正上方";
        }
        this.pathPoints = points.toArray(new Vec3[0]);

        int segments = pathPoints.length - 1;
        double[] lengths = new double[segments];
        double totalSeconds = 0.0D;
        for (int i = 0; i < segments; i++) {
            lengths[i] = pathPoints[i].distanceTo(pathPoints[i + 1]);
            totalSeconds += lengths[i] / speeds.get(i);
        }
        this.descendMs = Mth.clamp((float) (totalSeconds * 1000.0D),
                (float) DESCEND_MIN_MS, (float) DESCEND_MAX_MS);
        // 各段按自己的速度分时间；总时长被夹过时整体等比缩放，各段的节奏比例不变
        this.pathKnots = new float[segments + 1];
        double acc = 0.0D;
        for (int i = 0; i < segments; i++) {
            acc += lengths[i] / speeds.get(i);
            pathKnots[i + 1] = (float) (acc / Math.max(1.0E-6D, totalSeconds));
        }
        pathKnots[0] = 0.0F;
        pathKnots[segments] = 1.0F;

        StringBuilder note = new StringBuilder();
        for (int i = 0; i < segments; i++) {
            if (i > 0) note.append(" / ");
            note.append(Math.round(lengths[i]));
        }
        YardSiteLog.debug("落机路径：" + pathNote + "，眼高 y=" + fmt(eyeY)
                + "，行程 " + Math.round(aerial.distanceTo(eyeAtSpawn)) + " 格"
                + "（各段 " + note + "）→ 用时 " + fmt(descendMs / 1000.0F) + " 秒，节点 "
                + fmt(pathKnots[1]) + (segments > 2 ? "/" + fmt(pathKnots[2]) : ""));
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /**
     * 出生点周围这一片（方形半径 {@link #GATE_READY_RADIUS}）的真实网格上没上屏。
     *
     * <p>判据是<b>Sodium 绘制列表的回报</b>（{@code ChunkRenderListMixin} 每帧迭代到就记一次），
     * 和柱子的让位判据同源。以前问的是原版 {@code LevelRenderer#isSectionCompiled}，
     * 而 Sodium 接管了区块渲染，那个接口在它下面永远报"未编译" —— 判据永远不成立，
     * 只能等到 33 秒的超时兜底，表现就是"显示已移交真实区块之后，还在天上停十几秒"。
     * 收不到回报（没装 Sodium）时由 {@link SiteRevealWorldRenderer#gateAreaDrawn} 直接放行。
     */
    private boolean gateAreaCompiled(SitePlan plan) {
        Minecraft mc = this.minecraft;
        if (mc == null || mc.level == null || mc.levelRenderer == null || plan == null) return false;
        BlockPos spawn = new BlockPos(plan.spawnX(), plan.spawnY(), plan.spawnZ());
        int radius = Math.min(GATE_READY_RADIUS,
                Math.max(1, mc.options.renderDistance().get() - 1));
        return world != null && world.gateAreaDrawn(spawn.getX() >> 4, spawn.getZ() >> 4, radius);
    }

    /**
     * 门洞中线 x：大门是前墙（{@code originZ}）上那两格高的空列。
     * 从出生点那一列向两侧各扫 4 格找最近的空列，再向两侧扩成整段门洞取中点；
     * 找不到（建筑还没盖好）就退回出生点所在列。
     */
    private static double gateAxisX(Level level, BlockPos door, int wallZ) {
        if (level == null) return door.getX() + 0.5D;
        int feetY = door.getY();
        int lon = door.getX();
        if (!gateOpen(level, lon, feetY, wallZ)) {
            int best = Integer.MIN_VALUE;
            for (int d = 1; d <= 4 && best == Integer.MIN_VALUE; d++) {
                if (gateOpen(level, door.getX() - d, feetY, wallZ)) best = door.getX() - d;
                else if (gateOpen(level, door.getX() + d, feetY, wallZ)) best = door.getX() + d;
            }
            if (best == Integer.MIN_VALUE) return door.getX() + 0.5D;
            lon = best;
        }
        int lo = lon;
        int hi = lon;
        while (hi - lo < 8 && gateOpen(level, lo - 1, feetY, wallZ)) lo--;
        while (hi - lo < 8 && gateOpen(level, hi + 1, feetY, wallZ)) hi++;
        return (lo + hi + 1) / 2.0D;
    }

    /** 这一列是不是门洞：脚下那两格都是空气（大门从不放木门，房间门才放）。 */
    private static boolean gateOpen(Level level, int x, int feetY, int z) {
        return level.getBlockState(new BlockPos(x, feetY, z)).isAir()
                && level.getBlockState(new BlockPos(x, feetY + 1, z)).isAir();
    }

    /**
     * 落机这一帧相机该在哪：逐段 Hermite，端点切向量自己给 ——
     * 起点静止、终点停住，中间各点的切向量取「下一段的平均速度」，
     * 所以穿过门洞那一段退化成一条<b>沿轴线的直线</b>（等速进屋、落地前慢下来），
     * 而竖直下降那段和它接得是连续的，不会在门口拐个折角。
     */
    private Vec3 descendEye(Vec3 playerEye, float descend) {
        Vec3 aerial = world.eye();
        Vec3[] p = pathPoints;
        float[] knot = pathKnots;
        if (p == null || knot == null || p.length < 2) {
            return aerial.lerp(playerEye, smooth(descend));
        }
        int last = p.length - 1;
        Vec3[] pts = p.clone();
        pts[last] = playerEye;   // 终点跟着玩家当前眼睛走
        int i = 0;
        while (i < last - 1 && descend >= knot[i + 1]) i++;
        float h = knot[i + 1] - knot[i];
        float u = Mth.clamp((descend - knot[i]) / h, 0.0F, 1.0F);
        Vec3 m0 = i == 0 ? Vec3.ZERO : pts[i + 1].subtract(pts[i]).scale(1.0D / h);
        Vec3 m1 = i + 2 > last ? Vec3.ZERO
                : pts[i + 2].subtract(pts[i + 1]).scale(1.0D / (knot[i + 2] - knot[i + 1]));
        return hermite(pts[i], pts[i + 1], m0, m1, h, u);
    }

    /** 三次 Hermite：{@code u∈[0,1]}，切向量按「每单位进度多少格」给。 */
    private static Vec3 hermite(Vec3 a, Vec3 b, Vec3 ma, Vec3 mb, float h, float u) {
        float u2 = u * u;
        float u3 = u2 * u;
        float h00 = 2.0F * u3 - 3.0F * u2 + 1.0F;
        float h10 = u3 - 2.0F * u2 + u;
        float h01 = -2.0F * u3 + 3.0F * u2;
        float h11 = u3 - u2;
        return a.scale(h00).add(ma.scale(h10 * h)).add(b.scale(h01)).add(mb.scale(h11 * h));
    }

    /** 视角什么时候已经对齐玩家：进门前（第二个航点之前）就得做完，否则穿门时还在扭头。 */
    private static float lookBlend(float descend) {
        return smooth(Mth.clamp((descend - LOOK_FROM) / LOOK_SPAN, 0.0F, 1.0F));
    }

    private float descendYaw(float descend, float vanillaYaw) {
        return Mth.rotLerp(lookBlend(descend), world.yaw(), vanillaYaw);
    }

    private float descendPitch(float descend, float vanillaPitch) {
        return Mth.rotLerp(lookBlend(descend), world.pitch(), vanillaPitch);
    }

    // ── 真实区块渲染过滤（Sodium mixin 调进来）──────────────────────────

    /** 当前是否在按区块列隐藏真实地形。 */
    public static boolean cullingActive() {
        SiteRevealScreen self = active;
        return self != null && self.world != null && self.loginAt != 0L && self.descendAt == 0L;
    }

    /** 这个区块列的真实地形要不要藏起来；落机一开始就全部放行。 */
    public static boolean hidesChunk(int chunkX, int chunkZ) {
        SiteRevealScreen self = active;
        if (self == null || self.world == null || self.loginAt == 0L || self.descendAt != 0L) {
            return false;
        }
        return self.world.isHidden(chunkX, chunkZ);
    }

    /** Sodium 的绘制列表回报：这个区块列的真实网格这一帧确实被迭代到（淡出的前置条件）。 */
    public static void noteSectionDrawn(int chunkX, int chunkZ) {
        SiteRevealScreen self = active;
        if (self == null || self.world == null || self.loginAt == 0L) return;
        self.world.noteSectionDrawn(chunkX, chunkZ);
    }

    /** 收场：把画面交回给世界（落到位、玩家按 ESC、或撞到上限）。 */
    private void finish() {
        RevealCinematics.setAerial(false);
        Minecraft mc = this.minecraft;
        if (mc == null) return;
        if (mc.level == null) {
            // 还没登录：交回原版那个加载界面。这里 setScreen(null) 会被顶成标题界面
            if (fallback != null) mc.setScreen(fallback);
            return;
        }
        mc.setScreen(null);
    }

    // ── 动画期间无敌 ────────────────────────────────────────────────────

    /** 只挡伤害，不动玩家原有状态；服务端实体也要设，否则伤害包照样把红屏/镜头抖动送过来。 */
    private void applyInvulnerability(Minecraft mc) {
        if (mc == null || mc.player == null || invulnerableTouched) return;
        invulnerableTouched = true;
        invulnerableBefore = mc.player.isInvulnerable();
        mc.player.setInvulnerable(true);
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return;
        UUID id = mc.player.getUUID();
        invulnerablePlayer = id;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) return;
            serverInvulnerableBefore = player.isInvulnerable();
            serverInvulnerableApplied = true;
            player.setInvulnerable(true);
        });
    }

    private void restoreInvulnerability(Minecraft mc) {
        if (!invulnerableTouched) return;
        invulnerableTouched = false;
        if (mc != null && mc.player != null) {
            mc.player.setInvulnerable(invulnerableBefore);
        }
        MinecraftServer server = mc == null ? null : mc.getSingleplayerServer();
        UUID id = invulnerablePlayer;
        invulnerablePlayer = null;
        if (server == null || id == null) return;
        // 和 apply 排在同一条服务端队列上，先 apply 后 restore，顺序有保证
        server.execute(() -> {
            if (!serverInvulnerableApplied) return;
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) player.setInvulnerable(serverInvulnerableBefore);
        });
    }

    // ── 相机接管（CameraMixin / GameRendererMixin 调进来）──────────────

    /** 相机接管的目标：{@code null} = 这一帧不接管。 */
    public record CameraTarget(Vec3 eye, float yaw, float pitch) {}

    public static CameraTarget cameraTargetFor(Vec3 vanillaEye, float vanillaYaw, float vanillaPitch) {
        SiteRevealScreen self = active;
        if (self == null || self.world == null) return null;
        if (self.aerialAt == 0L) return null;      // 登录前真世界还不存在
        float descend = self.descendProgress();
        if (descend >= 1.0F) return null;          // 落到位：让原版相机完全接管
        Vec3 eye = self.descendEye(vanillaEye, descend);
        return new CameraTarget(eye,
                self.descendYaw(descend, vanillaYaw),
                self.descendPitch(descend, vanillaPitch));
    }

    /** GameRenderer#getFov 的 override：登录前没有真世界，返回值只用于 GUI 柱子的透视投影。 */
    public static double fovForGameRenderer() {
        SiteRevealScreen self = active;
        if (self == null || self.world == null) return 0.0;
        float fov = self.world.fov();
        if (self.descendAt != 0L) {
            // 视野跟视角一起展回去：进门前就恢复成玩家自己的视野，穿门那段不会因为广角显得更快
            float d = lookBlend(self.descendProgress());
            fov = Mth.lerp(d, fov, self.world.endFov());
        }
        return fov;
    }

    private float descendProgress() {
        if (descendAt == 0L) return 0.0F;
        // 这里<b>不做</b>缓动：缓动在路径本身（两端切向量为 0）和视角混合曲线里，
        // 再叠一层 smooth() 会把四段的时间节点打乱，穿门那段又被挤快。
        return Mth.clamp(descendClockMs / descendMs, 0.0F, 1.0F);
    }

    private static float smooth(float t) {
        return t * t * (3.0F - 2.0F * t);
    }

    // ── 画面 ────────────────────────────────────────────────────────────

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long frameStart = System.nanoTime();
        this.frameNow = System.currentTimeMillis();
        long advanceStart = System.nanoTime();
        advance(frameNow);
        long advanceNs = System.nanoTime() - advanceStart;

        float backdrop = backdropAlpha(frameNow);
        if (backdrop > 0.001F) {
            int a = (int) (255.0F * backdrop);
            g.fill(0, 0, this.width, this.height, (a << 24) | (BACKDROP & 0xFFFFFF));
        }
        long worldNs = 0L;
        if (world != null) {
            float descend = descendProgress();
            float alpha = 1.0F - descend;
            // 柱子已经全部淡完（落机前就成立）时整段沙盘都不用再提交：这里是收尾每帧白画的活，
            // 一万六千格遍历 + 排序 + GUI 批量 flush，落机期间一个像素都不会变
            boolean sandboxGone = descendAt != 0L && world.allReady();
            if (!sandboxGone && (descendAt == 0L || descend < 1.0F) && alpha > 0.001F) {
                if (descendAt != 0L && this.minecraft != null && this.minecraft.player != null) {
                    // 落机时柱子必须跟着真世界相机一起插值，最后整体淡掉；最后几块没撤掉也不会“啪”地消失
                    Vec3 vanillaEye = this.minecraft.player.getEyePosition(partialTick);
                    world.setDrawCamera(
                            descendEye(vanillaEye, descend),
                            descendYaw(descend, this.minecraft.player.getViewYRot(partialTick)),
                            descendPitch(descend, this.minecraft.player.getViewXRot(partialTick)),
                            Mth.lerp(lookBlend(descend), world.fov(), world.endFov()));
                } else {
                    world.setDrawCamera(world.eye(), world.yaw(), world.pitch(), world.fov());
                }
                long bpElapsed = blueprint == null || blueprintAt == 0L ? -1L : frameNow - blueprintAt;
                long w0 = System.nanoTime();
                world.render(g, this.width, this.height, frameNow, blueprint, bpElapsed, alpha);
                worldNs = System.nanoTime() - w0;
            }
        }
        diagFrame(frameStart, advanceNs, worldNs);
        if (descendAt != 0L) {
            descendMaxFrameNs = Math.max(descendMaxFrameNs, System.nanoTime() - frameStart);
            descendMaxWorldNs = Math.max(descendMaxWorldNs, worldNs);
        }
    }

    // ── 临时诊断：落成动画这十几秒到底卡在哪（帧间隔 vs 我们自己的渲染）──────

    private long diagLastFrameStart;
    private long diagLastReportMs;
    private long diagFrames;
    private long diagFrameNsSum;
    private long diagMaxFrameNs;
    private long diagAdvanceNsSum;
    private long diagMaxAdvanceNs;
    private long diagWorldNsSum;
    private long diagMaxWorldNs;
    private long diagGcCount;
    private long diagGcMillis;

    /** 每帧的诊断：只在 {@code -Dyardsite.debug=true} 时跑，否则连 GC 计数都不查（这是每帧路径）。 */
    private void diagFrame(long frameStart, long advanceNs, long worldNs) {
        if (!YardSiteLog.debugEnabled()) return;
        long frameNs = System.nanoTime() - frameStart;   // 本屏这一帧自己的渲染耗时
        long deltaNs = diagLastFrameStart == 0L ? 0L : frameStart - diagLastFrameStart;
        diagLastFrameStart = frameStart;
        diagFrames++;
        diagFrameNsSum += frameNs;
        diagMaxFrameNs = Math.max(diagMaxFrameNs, frameNs);
        diagAdvanceNsSum += advanceNs;
        diagMaxAdvanceNs = Math.max(diagMaxAdvanceNs, advanceNs);
        diagWorldNsSum += worldNs;
        diagMaxWorldNs = Math.max(diagMaxWorldNs, worldNs);

        if (deltaNs > 100_000_000L) {
            YardSiteLog.debug("落成卡帧：" + (deltaNs / 1_000_000L) + " ms"
                    + "（advance " + (advanceNs / 1_000_000L) + "，沙盘 " + (worldNs / 1_000_000L)
                    + "，柱子 " + (world != null ? world.lastCells() : 0)
                    + "，阶段 " + diagPhase() + "）");
        }

        long nowMs = System.currentTimeMillis();
        if (diagLastReportMs == 0L) diagLastReportMs = nowMs;
        if (nowMs - diagLastReportMs < 2000L) return;
        long frames = Math.max(1L, diagFrames);
        long gcCount = diagGcTotal();
        long gcMillis = diagGcMillis();
        YardSiteLog.debug("落成帧：" + diagFrames + " 帧，本屏平均 "
                + (diagFrameNsSum / frames / 1_000_000L) + " ms / 最大 "
                + (diagMaxFrameNs / 1_000_000L) + " ms；advance 平均 "
                + (diagAdvanceNsSum / frames / 1_000_000L) + "/最大 " + (diagMaxAdvanceNs / 1_000_000L)
                + " ms；沙盘 平均 " + (diagWorldNsSum / frames / 1_000_000L) + "/最大 "
                + (diagMaxWorldNs / 1_000_000L) + " ms（柱子 " + (world != null ? world.lastCells() : 0)
                + "）；最后帧 [收集 "
                + (world != null ? world.lastCollectNs() / 1_000_000L : 0L) + " / 排序 "
                + (world != null ? world.lastSortNs() / 1_000_000L : 0L) + " / 绘制 "
                + (world != null ? world.lastDrawNs() / 1_000_000L : 0L) + " / flush "
                + (world != null ? world.lastFlushNs() / 1_000_000L : 0L)
                + "]；GC +" + (gcCount - diagGcCount) + " 次/+" + (gcMillis - diagGcMillis)
                + " ms；阶段 " + diagPhase());
        diagLastReportMs = nowMs;
        diagFrames = 0;
        diagFrameNsSum = 0;
        diagMaxFrameNs = 0;
        diagAdvanceNsSum = 0;
        diagMaxAdvanceNs = 0;
        diagWorldNsSum = 0;
        diagMaxWorldNs = 0;
        diagGcCount = gcCount;
        diagGcMillis = gcMillis;
    }

    private String diagPhase() {
        if (!planKnown) return "等落点";
        if (loginAt == 0L) return "等登录";
        if (descendAt == 0L) return "露真世界";
        return "落机";
    }

    private static long diagGcTotal() {
        long sum = 0L;
        for (java.lang.management.GarbageCollectorMXBean bean
                : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = bean.getCollectionCount();
            if (count > 0L) sum += count;
        }
        return sum;
    }

    private static long diagGcMillis() {
        long sum = 0L;
        for (java.lang.management.GarbageCollectorMXBean bean
                : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            long time = bean.getCollectionTime();
            if (time > 0L) sum += time;
        }
        return sum;
    }

    /** 登录后不透明底淡掉，真世界从柱子后面露出来。 */
    private float backdropAlpha(long now) {
        if (loginAt == 0L) return 1.0F;
        return 1.0F - smooth(Mth.clamp((now - loginAt) / (float) BACKDROP_FADE_MS, 0.0F, 1.0F));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (aerialAt == 0L) {
            drawInfo(g);
        } else if (descendAt == 0L) {
            drawProgress(g);
        }
    }

    /** 界面上的一行字：文案本体在 {@code assets/yardsite/lang/*.json}（同 {@code YardSiteScreen#tr}）。 */
    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private void drawInfo(GuiGraphics g) {
        SitePlan plan = SiteRevealState.plan();
        boolean linked = SiteText.linked();
        g.drawString(this.font, SiteText.revealTitle(SiteText.Stage.BLUEPRINT).getString(), 8, 6, GOLD, true);
        String sub;
        if (plan == null) {
            sub = tr("yardsite.reveal.info.prepare");
        } else if (linked && plan.hasPlot()) {
            sub = tr("yardsite.reveal.info.plot", plan.plotCenterX(), plan.plotCenterZ(),
                    tr(SiteRevealState.built() ? "yardsite.reveal.info.built" : "yardsite.reveal.info.building"));
        } else {
            sub = tr("yardsite.reveal.info.spawn", plan.spawnX(), plan.spawnY(), plan.spawnZ());
        }
        g.drawString(this.font, sub, 8, 18, TEXT_SUB, true);
        g.drawString(this.font, tr("yardsite.reveal.info.escape"), 8, 30, TEXT_HINT, true);
        if (loginAt == 0L) {
            g.drawString(this.font, tr("yardsite.reveal.info.generating"), 8, this.height - 21, TEXT_HINT, true);
        }
    }

    private void drawProgress(GuiGraphics g) {
        int ready = world == null ? 0 : world.ready();
        int total = world == null ? 0 : world.total();
        int radius = world == null ? 0 : world.waitRadius();
        g.drawString(this.font, SiteText.revealTitle(SiteText.Stage.REVEAL).getString(), 8, 6, GOLD, true);
        String text;
        if (collapseAt != 0L) {
            text = tr("yardsite.reveal.progress.collapse");
        } else if (ready >= total && total > 0) {
            text = tr("yardsite.reveal.progress.ready");
        } else {
            // 分母是等待圈里的区块数（视距内 ±N 区块），不是整张 31×31 的沙盘
            text = tr("yardsite.reveal.progress.loading", ready, total, radius);
        }
        int barWidth = Math.min(280, this.width - 40);
        int x = (this.width - barWidth) / 2;
        int y = this.height - 28;
        g.fill(x, y, x + barWidth, y + 4, 0x66000000);
        int filled = (int) (barWidth * (total == 0 ? 0.0F : Mth.clamp(ready / (float) total, 0.0F, 1.0F)));
        if (filled > 0) {
            g.fill(x, y, x + filled, y + 4, GOLD);
        }
        g.drawString(this.font, text, (this.width - this.font.width(text)) / 2, y - 12, TEXT_SUB, true);
    }

    // ── 交互 ────────────────────────────────────────────────────────────

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            finish();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
