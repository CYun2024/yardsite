package dev.yard.site.view;

import dev.yard.site.YardSiteLog;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.yard.site.api.BlueprintBox;
import dev.yard.site.api.SitePlan;
import dev.yard.site.worldgen.SiteRevealState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

/**
 * 落成动画用的世界坐标沙盘。
 *
 * <h2>开场：接着精读那张图往下推，不叠第二张图</h2>
 * 点「确认并创建」那一帧，屏幕上是精读的<b>正交</b>画面（沙盘相机：地图平面按 yaw/pitch/roll 转，
 * 整张图等比放大，没有近大远小）。这一段直接把精读那张粗场拿来画，屏幕位置按开场进度在两种投影之间
 * 混合：进度 0 时逐像素等于精读画面（同一批格子、同一份高度和颜色、同一个屏幕原点，连柱子宽度滑杆、
 * 滚转角、拖动的半格差额、还没采完的长高动画都照搬），进度 1 时是纯透视。中间每一帧是<b>同一张图</b>
 * 在被拉长的镜头下移动，不是两张图在对 dissolve。
 *
 * <p>格子从「一格 16 方块」换成「一格 4 方块」发生在中途某一帧：粗场和细场问的是同一份区块采样结果，
 * 同一块地里两种格子的顶面高度和颜色完全一样，所以只要换的那一帧<b>画面里已经看不到细场窗口外面那圈
 * 地</b>，换源前后一个像素都不差。看不看得到由投影自己判（把细场的实心范围投到屏幕上，整个画面都在
 * 里面才换）；开场走完或登录了也强制换，不会一直挂着粗场。
 *
 * <p>空中机位是<b>侧着看院子</b>（俯角在 30~60 度里挑最浅还能盖住的），并且离院子远一点。这里有个硬
 * 约束：柱子要盖住整个画面，才能挡住还没网格化的真实区块；而画面下沿能看多远，是俯角和视野算出来的。
 * 所以动画段用长焦（视野最多 {@code AERIAL_FOV}，玩家设得更宽就按上限走，落机时才展开回去）：窄视野
 * 下同样的俯角盖得住的距离远得多，镜头就不用为了盖住画面而俯到接近顶视。
 *
 * <h2>登录后：那块地皮的<b>真实网格真的进了绘制列表</b>，柱子才让位</h2>
 * <ul>
 *   <li>让位顺序<b>从庭院开始向外扩</b>：谁先开始淡由离院子中心的远近排。但顺序只是<b>最早</b>时刻，
 *       不是放行时刻——放行要等这一块的真实区块进了 Sodium 的绘制列表（{@code ChunkRenderListMixin}
 *       每帧迭代到的 section 就是「这一刻真的在提交绘制」的那一块），所以不会出现"柱子化了、底下还是空的"。</li>
 *   <li>等不到网格的那几块各有上限：<b>区块数据永远到不了</b>（超出客户端视距）和<b>数据到了但网格没进列表</b>
 *       （在相机背面/视距边缘）都在各自的上限到点时按自己的排时刻走。这两条是为了<b>收尾不再整片抹</b>：
 *       以前视距外那几百块一直吊着，等让位时间用满就被降落那一下整体淡掉，看着就是"唰地一起消失、间隔没了"。</li>
 *       没装 Sodium（这组 mixin 自动不生效）时只按"区块到货 + 一点余量"放行，退化成遮罩。</li>
 *   <li>每一块都是<b>原地</b>淡掉：整块一起、不逐格错峰，镜头这一段又是定住的，所以既不会"一片柱子飘着化"，
 *       也不会"从某条边推到另一边"。淡出用 1.6 秒的五次曲线，开始化和淡完两头都缓。</li>
 *   <li>柱顶<b>一动不动</b>：从头到尾都用预读到的那一份地面高度，淡出期间不再滑到真实地表高度。
 *       以前那一滑（含树和旅社的 {@code WORLD_SURFACE}）看着就是"柱子往上长一边散掉"，像往上飘。
 *       真实地形（树、屋顶、更高的地面）是在柱子下面直接显出来的。</li>
 *   <li>收尾判据：<b>只等视距内的等待圈</b>（中心 ±8~10 区块，见 {@link #waitRadius}）——圈内每一块都放行、
 *       且最后开始淡的那块也淡完了，就可以落机；圈外那几百块（视距外，数据永远不来）不再吊着镜头。</li>
 *   <li>落机前还有一步 <b>收尾消散</b>（{@link #beginCollapse()}）：剩下没轮到的柱子在同一时刻开始、
 *       用 {@link #COLLAPSE_MS} 一起散干净，散完镜头才动——否则镜头一挪就会从还立着的柱子中间穿过去。</li>
 *   <li>空中段镜头极缓地自转（{@link #DRIFT_YAW_DEG}）并带一点俯仰呼吸：等着区块的那几秒不是一张静止图。</li>
 * </ul>
 *
 * <p>不是原版世界空间渲染：柱子画在 GUI 批里，和真地形的深度不共享；柱子所在处就是还没露出的地方。
 */
final class SiteRevealWorldRenderer {

    /**
     * 单格柱子淡出的时长。交接给真世界那一段要慢要缓：0.65 秒太急，柱子像被按掉而不是化掉。
     * 柱顶全程不动，只有透明度按这段走一条五次曲线。
     */
    static final int FADE_MS = 1600;
    /** 一整块地形完全消散需要的时长（现在就地淡，不逐格错峰，所以就是一个淡出时长）。 */
    private static final int REVEAL_TAIL_MS = FADE_MS;
    /** 真实网格进了绘制列表后再多等这么久才开始淡（毫秒）：给这一帧的上传留出落地时间。 */
    private static final long MESH_SETTLE_MS = 120L;
    /**
     * 没挂上 Sodium 网格判据时，区块到货后给网格编译留的余量（毫秒）。
     * 这种模式下柱子本来就是唯一遮罩（真实 section 照常画在遮罩下面），只能按时间估。
     */
    private static final long NO_FILTER_MESH_MS = 1_200L;
    /** 区块数据到了，但这么久还没进绘制列表（相机背面、视距边缘）：按这块的排时刻放行。 */
    private static final long MESH_MISS_MS = 2_500L;
    /** 这么久区块数据都没到（超出客户端视距，永远到不了）：按这块的排时刻放行，别吊着。 */
    private static final long DATA_MISS_MS = 2_500L;
    /** 登录后探测这么久，一次都没收到绘制列表回报 ⇒ 认定 Sodium 过滤没生效。 */
    private static final long FILTER_PROBE_MS = 1_500L;
    /** 登录后先等不透明底淡掉，再开始让柱子让位（毫秒）。 */
    private static final long REVEAL_DELAY_MS = 900L;
    /**
     * 让位从庭院中心扩散到场地边缘要多久（毫秒）。<b>交接必须从院子开始往外走</b>：玩家关心的是
     * "我的庭院先变成真的"，按区块到货先后淡会从某条边推向另一边（到货登记是逐行扫的）。
     * 这是每一块<b>最早</b>能让位的时刻，真实网格到了才真的让。
     */
    private static final long REVEAL_SPREAD_MS = 8_000L;
    /**
     * 落机前<b>只等这一圈</b>真实区块露出来（区块半径，含中心）：默认就等于客户端视距。
     *
     * <p>细场整张是 31×31＝961 个区块列，而视距 12 的客户端最多只加载得到 25×25 —— 外面那一圈
     * 永远等不到，以前就是被它们拖在天下十几秒（进度条分母 900+ 也是这么来的）。
     * 现在只数这一小圈：视距 12 → 半径 12 → 25×25＝625 块，正好是视距内看得见的那一片。
     *
     * <p>为什么按视距而不是更小：半径比视距小一圈时，画面边缘那一圈还是沙盘的假地形，
     * 落机时会"漏出来一点"再被真地形替掉，看着像闪。等满视距就不会有这道边。
     * 上下限只防极端设置：视距太小（≤8）时按 8 等，视距再大也不超过 12（沙盘本身只有 ±15 区块）。
     */
    private static final int WAIT_RADIUS_MIN = 8;
    private static final int WAIT_RADIUS_MAX = 12;
    /**
     * 等待圈内那一波让位的时长（毫秒）：从中心往外一圈圈走完，比整场的
     * {@link #REVEAL_SPREAD_MS} 快得多，镜头不用在空中干等。
     */
    private static final long REVEAL_SPREAD_NEAR_MS = 2_200L;
    /**
     * 收尾「剩下的沙盘一次性消散」的时长（毫秒）。这段时间里镜头<b>不动</b>：
     * 没轮到的柱子一起淡掉，散完再开始落机，不然镜头动起来时远处还立着一片柱子会穿帮。
     */
    static final int COLLAPSE_MS = 300;
    /**
     * 空中段镜头<b>朝一个方向</b>极缓地绕：{@value #DRIFT_DEG_PER_SEC}°/秒（一分钟约 90 度），
     * 开场进度过半后才起转，最大累计 {@value #DRIFT_MAX_DEG} 度。
     *
     * <p>不用来回摆（正弦）：摆动的观感是"镜头在犹豫"，而且每经过中线都要反向一次，看着像抖。
     * 单向慢绕就是一次很轻的环绕运动，落机时由 {@code lookBlend} 收回到玩家视线。
     */
    private static final float DRIFT_DEG_PER_SEC = 1.5F;
    private static final float DRIFT_MAX_DEG = 18.0F;
    /** 透视那一头的名义视野：正交和透视在这一头交接，这个数只决定透视侧自己的成像大小。 */
    private static final float START_FOV = 15.0F;
    /** 空中机位距离：离院子远一些，院子整体在画面里，水平方向也拉开。 */
    private static final float END_DISTANCE = 110.0F;
    /**
     * 动画段用的视野上限。窄视野 = 长焦：屏幕下沿的视线不会快压到水平线，
     * 于是沙盘能盖住的距离就成了真正看得见的距离，镜头才能保持<b>平视一点地侧着看</b>
     * 而不用俯到接近顶视。落机时再展开回玩家自己的视野。
     */
    private static final float AERIAL_FOV = 40.0F;
    /** 空中机位最多压到这么陡（超过就成从高往低往下看，不是要的构图）。 */
    private static final float AERIAL_PITCH_MAX = 45.0F;
    /** 空中机位至少有这么俯：太平就只剩一条地平线，院子读不出立体。 */
    private static final float AERIAL_PITCH_MIN = 30.0F;
    /** 正交→透视的过渡时长。 */
    static final int INTRO_MS = 3000;
    /** 真实落点晚到时，镜头终点往新落点挪这么久（不重建、不重飞）。 */
    private static final int RETARGET_MS = 700;
    /** 屏幕外的剔除余量（像素）：近处一根柱子投影可以有一百多像素宽。 */
    private static final int CULL_MARGIN = 240;
    /** 判「细场盖住整屏」时屏幕四周让开的余量（像素）。 */
    private static final int COVER_MARGIN_PX = 24;
    /** 判覆盖时把细场实心圈外那一环切成多少个点。 */
    private static final int RING_SAMPLES = 64;
    /** 那一环再往外让出的余量（方块）：贴边的格子有边缘渐隐，别算成「盖得住」。 */
    private static final int RING_MARGIN_BLOCKS = 8;
    /**
     * 动画时间每帧最多累加这么多毫秒：建世界那几秒渲染线程会被堵住半秒到一秒多（日志里开场头两帧
     * 就是 296 / 1469 ms），一点不让追就等于镜头停住再启动；按 120 ms 追，堵住时只小跳一下
     * （约占整段开场的 4%），比冻住顺眼。
     */
    private static final long MAX_FRAME_MS = 120L;
    /** 精读那套格子长高 + 淡入的时长和最低透明度，开场原样照搬。 */
    private static final int GROWTH_MS = 520;
    private static final float GROWTH_ALPHA_FLOOR = 0.25F;
    /** 水面至少留这么厚一层薄皮（占竖向比例的比例，和精读同一个值）。 */
    private static final float WATER_SINK = 0.05F;

    private static final int TOP_UNSET = Integer.MIN_VALUE;
    private static final int GOLD = 0xFFFFD24A;

    /**
     * 开场要接上的那张精读画面。
     *
     * @param field   精读那张粗场（一格 = 一个区块），和细场共用同一份区块采样
     * @param view    精读最后一帧的相机快照（含屏幕缩放和像素平移）
     * @param mapX    沙盘图区左上角：正交画面压在这块区域的中心，不是屏幕正中
     * @param mapSize 图区边长，和 {@code mapX/mapY} 一起定出屏幕原点
     * @param siteX   落点方块坐标，开场那根金十字和占地框钉在它那一格
     */
    record Intro(SiteField field, SiteTerrainRenderer.View view,
                 int mapX, int mapY, int mapSize, int siteX, int siteZ) {}

    /** 细场（一格 4 方块）：换成它之后柱子要按区块抬高、按格子消散。 */
    private final SiteReliefField field;
    private final Intro intro;
    private SitePlan plan;

    // ── 正交侧：照搬精读那一帧 ───────────────────────────────────────────
    /** 精读沙盘转视角的三行（屏幕 x / 屏幕 y / 朝观察者），基在 {@link SiteTerrainRenderer#orthoRows}。 */
    private final float[] orthoRows = new float[9];
    private final float orthoOriginX;
    private final float orthoOriginY;
    /** 精读那帧的像素/方块（横向，柱子宽度已经乘进去了）。 */
    private final float orthoPxPerBlock;
    private final float orthoWidthScale;
    /** 精读窗口停在两个区块之间时那点差额，换算成方块。 */
    private final float orthoShiftX;
    private final float orthoShiftZ;
    /** 精读那帧一个模型单位等于多少方块（竖向比例也用它换算）。 */
    private final double orthoBlocksPerUnit;
    /** 竖向一个模型单位等于多少方块：占地框和十字那些余量按它折算，跟着粗场的竖向比例涨。 */
    private float heightUnitBlocks;

    // ── 透视侧 ─────────────────────────────────────────────────────────
    private final float siteYaw;
    private final float startPitch;
    private final float sitePitch;
    private final float endFov;
    /** 动画段真正用的视野：玩家设得比 {@link #AERIAL_FOV} 宽时按上限走，落机才展开回去。 */
    private final float aerialFov;
    /** D·tan(FOV/2)：视锥大小，按对数插值，镜头推进的观感均匀。 */
    private final float startK;
    private final float endK;
    private final float startFocusX;
    private final float startFocusZ;
    private final float baseY;
    /** 镜头终点：真实落点到了就从旧终点往新终点挪，不重建渲染器。 */
    private Vec3 focusFrom;
    private Vec3 focusTo;
    private Vec3 focus;
    private float retargetElapsed;

    /** 开场计时起点：第一次 tick 才记（界面可能先被建世界流程卡住几秒，别让镜头白飞）。0 = 还没开始。 */
    private long startedAt;
    /** 动画时间按帧累加、单帧封顶 50ms：卡一下不会让镜头瞬移。 */
    private long lastTickAt;
    private float introElapsed;
    private float introEase;
    /** 这一帧正交侧的比重（1 = 就是精读那张图）。 */
    private float orthoWeight;
    /** 这一帧的竖向压缩：开场按精读的比例，收尾展开成真实高度。 */
    private float heightScaleNow;
    /** 水面那层薄皮的厚度（方块），跟着竖向比例一起涨。 */
    private float waterFloor;

    // ── 这一帧画的是哪张场 ─────────────────────────────────────────────
    private SiteField drawField;
    private int drawFine;
    private int drawStep;
    private int drawBaseBlockX;
    private int drawBaseBlockZ;
    /** 最外圈侧壁的下沿：精读那帧是基准面，细场那帧是世界最低处。 */
    private float wallFloor;
    private boolean drawingIntro;

    private final int fineStep;
    private final int fineHalf;
    private final int fineBaseBlockX;
    private final int fineBaseBlockZ;

    /** 细场覆盖的区块网格：数据到货时刻（+1，0 = 还没到）。 */
    private final int chunkBaseX;
    private final int chunkBaseZ;
    private final int chunkCols;
    private final int chunkRows;
    private final long[] dataAt;
    /** 真实区块第一次在 Sodium 绘制列表里出现的时刻（+1，0 = 还没出现）——淡出的前置条件。 */
    private final long[] drawnAt;
    /** 每一块地形定下来的淡出开始时刻（-1 = 还没定，还在等网格/到货）。 */
    private final long[] fadeAt;
    /** 每一块地形按离庭院中心的远近排出来的<b>最早</b>让位时刻（毫秒，相对动画时钟）。 */
    private final long[] waveAt;
    private final int total;
    /** 落机前要等的区块半径（区块，含中心）。 */
    private final int waitRadius;
    /** 等待圈里有几个区块列（进度条的分母）。 */
    private final int waitTotal;
    /** 这个区块列在不在等待圈里：只有圈里的算进度、算"还没好"。 */
    private final boolean[] waitZone;
    /** 收尾整体消散的开始时刻（-1 = 还没开始）。 */
    private long collapseAt = -1L;
    /** 预读的平台高度（一格算一次存着）：淡出期间柱顶不再变，所以只用这一份。 */
    private final int[] preTop;
    private final float[] cellAlpha;
    private final float[] cellGrowth;
    /** 这一帧的颜色：收集时按行序取（对区块缓存友好），绘制按深度序时直接用。 */
    private final int[] cellRgb;
    private final int[] order;
    private final float[] depthKey;
    private final int[] orderCount = new int[1];

    /** 露真世界的动画时钟：登录后开始，按帧累加、单帧封顶 {@code MAX_FRAME_MS}（外面卡住不会跳过一段）。 */
    private long revealClockMs;
    private long lastRevealTickAt;
    private boolean revealClockStarted;
    /** 淡完了的区块列数（进度条）。 */
    private int ready;
    /** 还没定下淡出时刻的区块列数：非 0 就不能落机。 */
    private int waiting;
    /** 已经定下来的淡出时刻里最晚的那一个（收尾要等它淡完）。 */
    private long maxFadeStart;
    /** 绘制列表回报次数（>0 = Sodium 那组 mixin 真的挂上了）。 */
    private int sightings;
    /** 淡出放行方式各用了多少块（诊断用）。 */
    private int provenByMesh;
    private int provenByData;
    private int provenByMiss;
    /** 两种模式的判定结果各只打一次日志。 */
    private boolean meshGateAnnounced;
    private boolean noFilterAnnounced;

    /** 基准机位（相机接管 / 落机插值用），每帧由 tick 重算。 */
    private Vec3 eye = Vec3.ZERO;
    private float yaw;
    private float pitch;
    /** 空中段累计绕了多少度（单向累加，收尾消散时停）。 */
    private float orbitDeg;
    private float fov = START_FOV;
    private Vec3 look = new Vec3(0.0, 0.0, 1.0);
    private Vec3 right = new Vec3(-1.0, 0.0, 0.0);
    private Vec3 up = new Vec3(0.0, 1.0, 0.0);

    /** 这一帧画柱子用的机位：平时等于基准机位，落机时跟着真世界一起插值。 */
    private Vec3 drawEye = Vec3.ZERO;
    private Vec3 drawLook = new Vec3(0.0, 0.0, 1.0);
    private Vec3 drawRight = new Vec3(-1.0, 0.0, 0.0);
    private Vec3 drawUp = new Vec3(0.0, 1.0, 0.0);
    private double drawProjF = 1.0 / Math.tan(Math.toRadians(START_FOV) * 0.5);
    private double aspect = 1.0;
    private int width = 1;
    private int height = 1;
    /** 这一帧的时间，格子的长高比例要用（收集和平顶面两次算同一个值）。 */
    private long frameNow;
    /** 这一格朝相机的方向（正交与透视按进度混合），四面判面共用。 */
    private float camX;
    private float camZ;

    /** 上一帧沙盘各段耗时（诊断用）。 */
    private long lastCollectNs;
    private long lastSortNs;
    private long lastDrawNs;
    private long lastFlushNs;

    SiteRevealWorldRenderer(SiteReliefField field, SitePlan plan, Intro intro) {
        this.field = field;
        this.intro = intro;
        this.plan = plan;
        SiteTerrainRenderer.View view = intro.view();
        SiteField coarse = intro.field();

        this.focus = new Vec3(plan.plotCenterX(), plan.originY() + 5.0, plan.plotCenterZ());
        this.focusFrom = this.focus;
        this.focusTo = this.focus;
        this.siteYaw = 180.0F - (float) Math.toDegrees(view.yaw());
        // 精读的俯仰和透视相机的俯仰是同一个含义（往下多少度），开场先原样接上
        this.startPitch = Mth.clamp(90.0F - (float) Math.toDegrees(view.pitch()), 5.0F, 89.0F);
        this.endFov = Minecraft.getInstance().options.fov().get();
        this.aerialFov = Math.min(endFov, AERIAL_FOV);
        this.yaw = this.siteYaw;
        this.pitch = this.startPitch;

        // 正交侧：屏幕原点 = 交接过来那块图区的中心 + 精读那帧的像素平移
        this.orthoOriginX = intro.mapX() + intro.mapSize() / 2.0F + view.panX();
        this.orthoOriginY = intro.mapY() + intro.mapSize() / 2.0F + view.panY();
        this.orthoBlocksPerUnit = Math.max(1.0, (coarse.fine() - 1) * coarse.step() * 0.5);
        this.orthoWidthScale = view.widthScale();
        // 精读那帧：一个模型单位 = orthoBlocksPerUnit 方块，横向撑开在投影里各轴自己乘
        this.orthoPxPerBlock = (float) (view.scale() / orthoBlocksPerUnit);
        this.orthoShiftX = coarse.originShiftX() * (float) orthoBlocksPerUnit;
        this.orthoShiftZ = coarse.originShiftY() * (float) orthoBlocksPerUnit;
        SiteTerrainRenderer.orthoRows(view.yaw(), view.pitch(), view.roll(), orthoRows);

        this.fineStep = (int) Math.ceil(field.step());
        this.fineHalf = (field.fine() - 1) / 2;
        this.fineBaseBlockX = (int) Math.floor(field.centerX() - fineHalf * (double) field.step());
        this.fineBaseBlockZ = (int) Math.floor(field.centerZ() - fineHalf * (double) field.step());
        this.startFocusX = coarse.centerX();
        this.startFocusZ = coarse.centerZ();
        this.baseY = coarse.seaLevel() - 4.0F;
        // 开场正交画面里「一个模型单位 = orthoBlocksPerUnit 方块」，所以像素/方块就是上面那个比值
        this.startK = Minecraft.getInstance().getWindow().getGuiScaledHeight() * 0.5F / orthoPxPerBlock;
        this.endK = END_DISTANCE * (float) Math.tan(Math.toRadians(aerialFov) * 0.5);
        this.sitePitch = solveAerialPitch();

        int maxSide = Math.max(field.fine(), coarse.fine());
        int maxCells = maxSide * maxSide;
        this.preTop = new int[maxCells];
        this.cellAlpha = new float[maxCells];
        this.cellGrowth = new float[maxCells];
        this.cellRgb = new int[maxCells];
        this.order = new int[maxCells];
        this.depthKey = new float[maxCells];
        java.util.Arrays.fill(preTop, TOP_UNSET);
        java.util.Arrays.fill(cellRgb, -1);
        useIntroSource();

        int maxBlockX = fineBaseBlockX + (field.fine() - 1) * fineStep + fineStep - 1;
        int maxBlockZ = fineBaseBlockZ + (field.fine() - 1) * fineStep + fineStep - 1;
        this.chunkBaseX = Math.floorDiv(fineBaseBlockX, 16);
        this.chunkBaseZ = Math.floorDiv(fineBaseBlockZ, 16);
        this.chunkCols = Math.floorDiv(maxBlockX, 16) - chunkBaseX + 1;
        this.chunkRows = Math.floorDiv(maxBlockZ, 16) - chunkBaseZ + 1;
        this.total = chunkCols * chunkRows;
        this.dataAt = new long[total];
        this.drawnAt = new long[total];
        this.waveAt = new long[total];
        this.fadeAt = new long[total];
        java.util.Arrays.fill(fadeAt, -1L);

        // 等待圈：以细场中心区块为中心的方形（切比雪夫距离），默认按客户端视距来
        int renderDistance = Minecraft.getInstance().options.renderDistance().get();
        this.waitRadius = Mth.clamp(renderDistance, WAIT_RADIUS_MIN, WAIT_RADIUS_MAX);
        this.waitZone = new boolean[total];
        int centerChunkX = field.centerChunkX();
        int centerChunkZ = field.centerChunkZ();
        int zone = 0;
        for (int row = 0; row < chunkRows; row++) {
            for (int col = 0; col < chunkCols; col++) {
                boolean in = Math.abs(chunkBaseX + col - centerChunkX) <= waitRadius
                        && Math.abs(chunkBaseZ + row - centerChunkZ) <= waitRadius;
                waitZone[row * chunkCols + col] = in;
                if (in) zone++;
            }
        }
        this.waitTotal = zone;
        recomputeWave();
        YardSiteLog.debug("落机等待范围：中心 ±" + waitRadius + " 区块（" + waitTotal
                + " 块；整张细场 " + chunkCols + "×" + chunkRows + "＝" + total
                + " 块，视距 " + renderDistance + "）");
    }

    /** 开场画精读那张粗场：粗场的格子中心落在「图心 + (格序 − 半宽)·一步」上，所以再退半格。 */
    private void useIntroSource() {
        SiteField coarse = intro.field();
        int step = (int) Math.ceil(coarse.step());
        int half = (coarse.fine() - 1) / 2;
        drawingIntro = true;
        drawField = coarse;
        drawFine = coarse.fine();
        drawStep = step;
        drawBaseBlockX = (int) Math.floor(coarse.centerX() - half * (double) coarse.step()) - step / 2;
        drawBaseBlockZ = (int) Math.floor(coarse.centerZ() - half * (double) coarse.step()) - step / 2;
        wallFloor = baseY;
    }

    /** 换成细场（一格 4 方块）：两份场问的是同一批区块，切换那一帧画面不动。 */
    private void useFineSource() {
        drawingIntro = false;
        drawField = field;
        drawFine = field.fine();
        drawStep = fineStep;
        drawBaseBlockX = fineBaseBlockX;
        drawBaseBlockZ = fineBaseBlockZ;
        wallFloor = field.minY();
        // 缓存是按格子下标存的，下标换了一套含义，重头存
        java.util.Arrays.fill(preTop, TOP_UNSET);
        java.util.Arrays.fill(cellRgb, -1);
        YardSiteLog.debug("落成开场接细场：开场进度 "
                + String.format(java.util.Locale.ROOT, "%.2f", introEase)
                + (revealClockStarted ? "（登录了）" : "（细场已经盖住整屏）"));
    }

    /**
     * 空中机位的俯角：在 30~60 度里取「细场实心范围还盖得住整屏」最浅的那一个，越侧越好。
     * 段首那段「慢速前蹭」的废话已经删了：镜头从开场结束到落机就是定住的。
     */
    private float solveAerialPitch() {
        float lo = Math.max(startPitch, AERIAL_PITCH_MIN);
        if (lo >= AERIAL_PITCH_MAX) return AERIAL_PITCH_MAX;
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        double projF = 1.0 / Math.tan(Math.toRadians(aerialFov) * 0.5);
        for (float candidate = lo; candidate <= AERIAL_PITCH_MAX; candidate += 1.0F) {
            if (fineRingIsOffScreenPose(poseAt(candidate, w, h), projF, w, h)) return candidate;
        }
        return AERIAL_PITCH_MAX;
    }

    /** 按给定俯角摆一个空中机位（只为判覆盖用，不进动画）。 */
    private Pose poseAt(float aerialPitch, int w, int h) {
        double distance = endK / Math.tan(Math.toRadians(aerialFov) * 0.5);
        double yr = Math.toRadians(siteYaw);
        double pr = Math.toRadians(aerialPitch);
        Vec3 axis = new Vec3(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
        Vec3 side = new Vec3(-Math.cos(yr), 0.0, -Math.sin(yr));
        Vec3 sky = side.cross(axis).normalize();
        Vec3 eyeAt = focusTo.subtract(axis.scale(distance));
        return new Pose(eyeAt, axis, side, sky, (double) w / h);
    }

    /** 一套机位：判覆盖要用两次（解俯仰时是还没开演的假设机位，跑起来用的是这一帧的）。 */
    private record Pose(Vec3 eye, Vec3 look, Vec3 right, Vec3 up, double aspect) {}

    // ── 相机 / 进度 ─────────────────────────────────────────────────────

    /** 每帧更新相机和区块状态；登录前 mc.level 为 null，不会开始露真世界。 */
    void tick(Minecraft mc, long now) {
        updateCamera(now);
        updateChunks(mc, now);
    }

    private void updateCamera(long now) {
        if (startedAt == 0L) {
            startedAt = now;
            lastTickAt = now;
        }
        // 动画时间按帧累加、单帧封顶 50ms：建世界那几秒偶尔卡一下，镜头也不会瞬移
        long delta = Math.min(MAX_FRAME_MS, Math.max(0L, now - lastTickAt));
        lastTickAt = now;
        introElapsed = Math.min((float) INTRO_MS, introElapsed + delta);
        retargetElapsed = Math.min((float) RETARGET_MS, retargetElapsed + delta);
        float e = smooth(introElapsed / INTRO_MS);
        this.introEase = e;
        this.orthoWeight = 1.0F - e;
        this.focus = focusFrom.lerp(focusTo, smooth(retargetElapsed / (float) RETARGET_MS));
        // 动画段用长焦（不超过 AERIAL_FOV），落机时才展开回玩家自己的视野
        fov = Mth.lerp(e, START_FOV, aerialFov);
        float k = (float) Math.exp(Mth.lerp(e, (float) Math.log(startK), (float) Math.log(endK)));
        float distance = k / (float) Math.tan(Math.toRadians(fov) * 0.5);
        // 空中段朝同一方向极缓地绕（不是来回摆），等着区块的那几秒就不像一张静止图。
        // ramp 从开场过半之后才起转，接图那一帧和精读画面逐像素一致；收尾消散那 300 ms 停转，
        // 落机时再由 lookBlend 收回玩家视线。
        float ramp = smooth(Mth.clamp((introElapsed - INTRO_MS * 0.5F) / (INTRO_MS * 0.5F), 0.0F, 1.0F));
        if (collapseAt < 0L) {
            this.orbitDeg = Math.min(DRIFT_MAX_DEG,
                    orbitDeg + DRIFT_DEG_PER_SEC * ramp * (delta / 1000.0F));
        }
        yaw = siteYaw + orbitDeg;
        pitch = Mth.lerp(e, startPitch, sitePitch);
        double yr = Math.toRadians(yaw);
        double pr = Math.toRadians(pitch);
        look = new Vec3(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
        right = new Vec3(-Math.cos(yr), 0.0, -Math.sin(yr));
        up = right.cross(look).normalize();
        float fx = Mth.lerp(e, startFocusX, (float) focus.x);
        // 正交画面以「基准面」(seaLevel−4) 的图心为屏幕中心，开头也按这个高度对，
        // 推近的同时抬到真实机位的聚焦高度，免得一开始整体上下错几像素
        float fy = Mth.lerp(e, baseY, (float) focus.y);
        float fz = Mth.lerp(e, startFocusZ, (float) focus.z);
        eye = new Vec3(fx, fy, fz).subtract(look.scale(distance));
        // 竖向比例跟着粗场现值走（精读那边可能还在慢慢涨），两侧用同一个数才对得上
        float span = Math.max(20.0F, intro.field().reliefSpan());
        heightScaleNow = Mth.lerp(e, (float) (SiteTerrainRenderer.RELIEF * orthoBlocksPerUnit / span), 1.0F);
        waterFloor = WATER_SINK * span;
        heightUnitBlocks = span / SiteTerrainRenderer.RELIEF;
        setDrawCamera(eye, yaw, pitch, fov);
    }

    /**
     * 落机阶段让柱子跟着真世界的相机一起插值。
     * 只改绘制机位，不动基准机位，否则 {@code cameraTargetFor} 会在上一帧的结果上再插一次。
     */
    void setDrawCamera(Vec3 eye, float yaw, float pitch, float fov) {
        this.drawEye = eye;
        this.drawProjF = 1.0 / Math.tan(Math.toRadians(fov) * 0.5);
        double yr = Math.toRadians(yaw);
        double pr = Math.toRadians(pitch);
        this.drawLook = new Vec3(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
        this.drawRight = new Vec3(-Math.cos(yr), 0.0, -Math.sin(yr));
        this.drawUp = drawRight.cross(drawLook).normalize();
    }

    private void updateChunks(Minecraft mc, long now) {
        if (mc.level == null || mc.levelRenderer == null) return;
        if (!revealClockStarted) {
            revealClockStarted = true;
            lastRevealTickAt = now;
        } else {
            revealClockMs += Math.min(MAX_FRAME_MS, Math.max(0L, now - lastRevealTickAt));
            lastRevealTickAt = now;
        }
        if (!meshGateAnnounced && sightings > 0) {
            meshGateAnnounced = true;
            YardSiteLog.debug("落成让位判据：真实区块进了 Sodium 绘制列表才淡柱子");
        }
        if (!noFilterAnnounced && sightings == 0 && revealClockMs > FILTER_PROBE_MS) {
            noFilterAnnounced = true;
            YardSiteLog.debug("落成让位判据：没收到绘制列表回报（Sodium 过滤没挂上？），"
                    + "退回「区块到货 + " + NO_FILTER_MESH_MS + " ms」放行");
        }

        int done = 0;
        int pending = 0;
        long latest = 0L;
        for (int row = 0; row < chunkRows; row++) {
            for (int col = 0; col < chunkCols; col++) {
                int index = row * chunkCols + col;
                if (dataAt[index] == 0L
                        && mc.level.getChunkSource().getChunk(chunkBaseX + col, chunkBaseZ + row,
                        ChunkStatus.FULL, false) != null) {
                    // +1：0 继续表示“还没到货”
                    dataAt[index] = revealClockMs + 1L;
                }
                long start = fadeAt[index];
                if (start < 0L) {
                    start = fadeStartNow(index);
                    if (start < 0L) {
                        // 只有等待圈里的才算"还没好"：圈外那些视距外的区块永远等不到，
                        // 让它们自己去散，最后一起收尾
                        if (waitZone[index]) pending++;
                        continue;
                    }
                    fadeAt[index] = start;   // 一定住：定了就不再改，不然淡到一半会跳
                }
                if (!waitZone[index]) continue;
                if (start > latest) latest = start;
                if (revealClockMs >= start + fadeLen(start)) done++;
            }
        }
        ready = done;
        waiting = pending;
        if (latest > maxFadeStart) maxFadeStart = latest;
    }

    /**
     * 这一块现在能不能定下淡出时刻（-1 = 还不能，继续盖着）。
     *
     * <p>放行要同时满足两件事：轮到它了（{@code waveAt}，从庭院向外排）和它身下那块真实地形<b>真的在画</b>。
     * 后者靠 Sodium 绘制列表的回报；数据或网格迟迟不来的（视距外、相机背面）到自己的上限就按排时刻走——
     * 不然它们会一直吊着，最后被降落那一下整体淡掉，看着就是"柱子唰地一起消失"。
     */
    private long fadeStartNow(int index) {
        long wave = REVEAL_DELAY_MS + waveAt[index];
        long data = dataAt[index];
        if (data == 0L) {
            if (revealClockMs < wave + DATA_MISS_MS) return -1L;
            provenByMiss++;
            return revealClockMs;   // 视距外：区块永远不来，到点就让它跟着自己的排时刻淡
        }
        long drawn = drawnAt[index];
        if (drawn != 0L) {
            provenByMesh++;
            return Math.max(wave, drawn - 1L + MESH_SETTLE_MS);
        }
        if (sightings > 0 || revealClockMs <= FILTER_PROBE_MS) {
            // 判据是活的：这一块还没进绘制列表，可能是相机背面，也可能是网格还没上传
            if (revealClockMs < data - 1L + MESH_MISS_MS) return -1L;
            provenByMiss++;
            return revealClockMs;
        }
        provenByData++;
        return Math.max(wave, data - 1L + NO_FILTER_MESH_MS);
    }

    /**
     * Sodium 绘制列表回报：这个区块列的真实网格这一帧确实被迭代到（= 已上传、正在提交绘制）。
     * 渲染线程调用，和 {@link #tick} 同一个线程，不需要加锁。
     */
    void noteSectionDrawn(int chunkX, int chunkZ) {
        int index = chunkIndex(chunkX, chunkZ);
        if (index < 0) return;
        sightings++;
        if (drawnAt[index] == 0L) drawnAt[index] = revealClockMs + 1L;
    }

    /**
     * 某个区块为心的方形范围内，真实网格上没上屏：数的是 {@link #drawnAt}（Sodium 绘制列表的回报）。
     *
     * <p>只要求<b>绝大多数</b>进过列表（85%）：镜头朝向那一侧会一直在画，相机背后那几列
     * 可能永远进不了列表，不能因为它们把人卡在天上。一次回报都没收到（没装 Sodium）时直接放行。
     */
    boolean gateAreaDrawn(int centerChunkX, int centerChunkZ, int radius) {
        if (sightings <= 0) return true;
        int known = 0;
        int drawn = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int index = chunkIndex(centerChunkX + dx, centerChunkZ + dz);
                if (index < 0) continue;
                known++;
                if (drawnAt[index] != 0L) drawn++;
            }
        }
        return known == 0 || drawn * 100 >= known * 85;
    }

    private int chunkIndex(int chunkX, int chunkZ) {
        int col = chunkX - chunkBaseX;
        int row = chunkZ - chunkBaseZ;
        if (col < 0 || row < 0 || col >= chunkCols || row >= chunkRows) return -1;
        return row * chunkCols + col;
    }

    /**
     * 柱顶（未压缩的方块高度）：这一格预读到的那一份地面高度，<b>从头到尾不变</b>。
     *
     * <p>以前这一块开始淡出时会把柱顶从预读平台滑到真实地表高度（WORLD_SURFACE 含树和旅社），
     * 结果就是"柱子往上长一边散掉"，看着像往上飘。现在柱子位置完全钉住，只有透明度在降，
     * 真实地形（树、屋顶、更高的地面）是在它下面直接显出来的。
     */
    private float rawTop(int ci, int cj) {
        int index = cj * drawFine + ci;
        int pre = preTop[index];
        if (pre == TOP_UNSET) {
            // 一格的预读高度采到就不会再变（窗口锚点交接后不再挪），存一次，别每帧问五遍
            pre = drawField.surfaceAtCell(ci, cj);
            preTop[index] = pre;
        }
        return pre;
    }

    /** 这一格这一帧画到多高（世界 y）：先抬水面薄皮，再按开场竖向压缩，最后乘这格自己的长高比例。 */
    private float drawY(float rawTop, float growth) {
        return baseY + Math.max(rawTop - baseY, waterFloor) * heightScaleNow * growth;
    }

    /** 这一格的长高比例（0 = 刚从底面冒出来，1 = 已经长完）。粗场那一段才用得上。 */
    private float growthOf(int ci, int cj) {
        if (!drawingIntro) return 1.0F;
        return smooth(Mth.clamp((frameNow - drawField.drawnAt(ci, cj, frameNow))
                / (float) GROWTH_MS, 0.0F, 1.0F));
    }

    /** 邻格画到多高；出窗口、没采到都按底面算（和精读那张图一致）。 */
    private float neighborDrawY(int ci, int cj) {
        if (ci < 0 || cj < 0 || ci >= drawFine || cj >= drawFine) return wallFloor;
        if (!drawField.ready(ci, cj)) return wallFloor;
        return drawY(rawTop(ci, cj), growthOf(ci, cj));
    }

    /** 这一块地形开始<b>就地</b>淡出的时刻（-1 = 还没放行，柱子继续盖住）。 */
    private long chunkStart(int chunkX, int chunkZ) {
        int index = chunkIndex(chunkX, chunkZ);
        return index < 0 ? -1L : fadeAt[index];
    }

    /**
     * Sodium 渲染钩子用：这个区块列的真实地形现在要不要藏起来。
     * 只有场地圆内、且还没轮到淡出的区块列才藏；场地外沿用原本的渲染，免得多出空洞。
     */
    boolean isHidden(int chunkX, int chunkZ) {
        if (chunkIndex(chunkX, chunkZ) < 0) return false;
        double dx = chunkX * 16 + 8 - plan.plotCenterX();
        double dz = chunkZ * 16 + 8 - plan.plotCenterZ();
        double radius = fineHalf * (double) fineStep - 12.0;
        if (dx * dx + dz * dz > radius * radius) return false;
        long start = chunkStart(chunkX, chunkZ);
        return start < 0L || revealClockMs < start;
    }

    int ready() {
        return ready;
    }

    /** 进度条分母：等待圈里的区块列数（不是整张细场，见 {@link #waitRadius}）。 */
    int total() {
        return waitTotal;
    }

    /** 落机前等的半径（区块）：界面上写清楚"在等多大一圈"。 */
    int waitRadius() {
        return waitRadius;
    }

    /** 淡出放行方式各用了多少块（日志用：能一眼看出这次交接走的是网格判据还是到货判据）。 */
    String revealReport() {
        return "网格 " + provenByMesh + "/到货 " + provenByData + "/到点放行 " + provenByMiss
                + "，绘制列表回报 " + sightings + " 次";
    }

    /**
     * 等待圈里该露的都露完了：圈内每一块都已经放行、且最后开始淡的那块也淡完了。
     *
     * <p>圈外（视距之外那几百块）不参与判定：它们的数据永远不来，等它们只会把人吊在天上。
     * 圈内好了就开始收尾消散，圈外剩下的由 {@link #beginCollapse()} 一次性抹掉。
     */
    boolean zoneRevealed() {
        return revealClockStarted && collapseAt < 0L
                && waiting == 0 && revealClockMs >= maxFadeStart + REVEAL_TAIL_MS;
    }

    /** 收尾消散已经开始。 */
    boolean collapsing() {
        return collapseAt >= 0L;
    }

    /**
     * 该露的都露完了：正在收尾时就是"收尾散完了"，否则和 {@link #zoneRevealed()} 一致。
     * 绘制那边用它判断「这一帧还有没有东西要画」。
     */
    boolean allReady() {
        if (!revealClockStarted) return false;
        if (collapseAt >= 0L) return revealClockMs >= collapseAt + COLLAPSE_MS;
        return waiting == 0 && revealClockMs >= maxFadeStart + REVEAL_TAIL_MS;
    }

    /**
     * 收尾：把所有还没轮到（或排到很久以后）的柱子<b>一次性</b>拉到同一时刻开始消散，
     * 用 {@link #COLLAPSE_MS} 这么短的时长一起淡掉。
     *
     * <p>为什么必须一起散：落机缘由是"等待圈已经露出来了"，镜头接着要往门口/出生点动，
     * 这时远处还立着柱子的话，镜头一平移就会从柱子中间穿过去（穿帮）。先散再动，
     * 屏幕边缘那点地形换真地形也就一瞬间的事。
     */
    void beginCollapse() {
        if (collapseAt >= 0L) return;
        collapseAt = revealClockMs;
        int removed = 0;
        for (int i = 0; i < total; i++) {
            long start = fadeAt[i];
            if (start < 0L || start > collapseAt) {
                if (start < 0L) removed++;
                fadeAt[i] = collapseAt;
            }
        }
        waiting = 0;
        YardSiteLog.debug("沙盘收尾：还没放行的 " + removed + " 个区块列一起消散（"
                + COLLAPSE_MS + " ms，之后镜头再动）");
    }

    /** 这一块的淡出时长：收尾那批用短时长，正常让位用 {@link #FADE_MS}。 */
    private long fadeLen(long start) {
        return collapseAt >= 0L && start >= collapseAt ? COLLAPSE_MS : REVEAL_TAIL_MS;
    }

    /**
     * 按离中心（庭院）的远近排一遍让位时刻：中心那格最先开始，向外扩散。
     *
     * <p>分两档：<b>等待圈内</b>用 {@link #REVEAL_SPREAD_NEAR_MS}（约 2.2 秒走完整圈，镜头不用等太久），
     * <b>圈外</b>仍按整场的 {@link #REVEAL_SPREAD_MS} 慢慢铺开（那一片本来就在视距外/边缘，
     * 太早淡掉会露出空洞）。服务端真实落点到得晚，圆心会变，所以单独一个方法，{@link #retargetPlan} 里要重排。
     */
    private void recomputeWave() {
        double radius = Math.max(1.0, fineHalf * (double) fineStep);
        double nearBlocks = Math.max(32.0, (waitRadius + 0.5) * 16.0);
        for (int row = 0; row < chunkRows; row++) {
            for (int col = 0; col < chunkCols; col++) {
                int index = row * chunkCols + col;
                double dx = (chunkBaseX + col) * 16 + 8 - plan.plotCenterX();
                double dz = (chunkBaseZ + row) * 16 + 8 - plan.plotCenterZ();
                double dist = Math.sqrt(dx * dx + dz * dz);
                double spread = waitZone[index] ? REVEAL_SPREAD_NEAR_MS : REVEAL_SPREAD_MS;
                double norm = Math.min(1.0, dist / (waitZone[index] ? nearBlocks : radius));
                waveAt[index] = (long) (spread * Math.pow(norm, 0.7));
            }
        }
    }

    /**
     * 服务端真实落点到了：只挪镜头终点（{@code isHidden} 的圆心、让位顺序也跟着走）。开场进度、
     * 换源状态、区块到货记录都不动，所以镜头是接着往新落点偏过去，不会重新飞一遍。
     */
    void retargetPlan(SitePlan plan) {
        this.plan = plan;
        this.focusFrom = this.focus;
        this.focusTo = new Vec3(plan.plotCenterX(), plan.originY() + 5.0, plan.plotCenterZ());
        this.retargetElapsed = 0.0F;
        recomputeWave();
    }

    Vec3 eye() {
        return eye;
    }

    float yaw() {
        return yaw;
    }

    float pitch() {
        return pitch;
    }

    float fov() {
        return fov;
    }

    float endFov() {
        return endFov;
    }

    // ── 换源判定：什么时候可以不画粗场 ─────────────────────────────────

    /**
     * 细场实心范围<b>外面那一圈</b>（再往外就是粗场多出来的地）现在是不是已经完全在画面之外。
     * 在画面之外才许把画源从粗场换成细场：这样粗场多出来的那圈本来就没在屏幕上，换源前后一个像素都不差。
     *
     * <p>判的是「画面里还有没有沙盘盖不住的地面」，所以要看这一圈投在屏幕上的位置：相机其实常常站在
     * 圈里面，圈有一半在相机背后 —— 那些点本来就看不见，不能当成「没盖住」。
     */
    private boolean fineRingIsOffScreen(int w, int h) {
        double radius = field.opaqueRadiusBlocks() + RING_MARGIN_BLOCKS;
        for (int i = 0; i < RING_SAMPLES; i++) {
            double a = i * Math.PI * 2.0 / RING_SAMPLES;
            float x = (float) (field.centerX() + Math.cos(a) * radius);
            float z = (float) (field.centerZ() + Math.sin(a) * radius);
            if (!project(x, baseY, z, scratch)) continue;     // 相机背后：看不见
            if (onScreen(scratch[0], scratch[1], w, h)) return false;
        }
        return true;
    }

    /** 同一个判定，用在还没开演的假设机位上（解俯角用，纯透视，不掺正交）。 */
    private boolean fineRingIsOffScreenPose(Pose pose, double projF, int w, int h) {
        double radius = field.opaqueRadiusBlocks() + RING_MARGIN_BLOCKS;
        for (int i = 0; i < RING_SAMPLES; i++) {
            double a = i * Math.PI * 2.0 / RING_SAMPLES;
            double dx = field.centerX() + Math.cos(a) * radius - pose.eye().x;
            double dy = baseY - pose.eye().y;
            double dz = field.centerZ() + Math.sin(a) * radius - pose.eye().z;
            double d = dx * pose.look().x + dy * pose.look().y + dz * pose.look().z;
            if (d <= 0.05) continue;
            double sx = dx * pose.right().x + dy * pose.right().y + dz * pose.right().z;
            double sy = dx * pose.up().x + dy * pose.up().y + dz * pose.up().z;
            if (onScreen((float) ((sx / d * projF / pose.aspect() * 0.5 + 0.5) * w),
                    (float) ((1.0 - (sy / d * projF * 0.5 + 0.5)) * h), w, h)) {
                return false;
            }
        }
        return true;
    }

    /** 这个屏幕点算不算落在画面里（四周各让出一个余量，免得贴着边的那圈算看见）。 */
    private static boolean onScreen(float px, float py, int w, int h) {
        return px > COVER_MARGIN_PX && px < w - COVER_MARGIN_PX
                && py > COVER_MARGIN_PX && py < h - COVER_MARGIN_PX;
    }

    /** 开场走完、登录、或细场已经盖住整屏，三者任一成立就换细场（换了不换回来）。 */
    private void updateDrawSource() {
        if (!drawingIntro) return;
        if (introEase < 1.0F && !revealClockStarted && !fineRingIsOffScreen(width, height)) return;
        useFineSource();
    }

    // ── 绘制 ────────────────────────────────────────────────────────────

    void render(GuiGraphics g, int width, int height, long now, SiteBlueprint blueprint,
                long blueprintElapsed, float globalAlpha) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.aspect = this.width / (double) this.height;
        this.frameNow = now;
        updateDrawSource();

        // 柱子全淡完、蓝图/落点十字也都收干净了：一个像素都不会变，别每帧再扫一遍格子
        if (globalAlpha <= 0.002F && allReady()
                && (blueprint == null || blueprintElapsed < 0L)) {
            orderCount[0] = 0;
            lastCollectNs = 0L;
            lastSortNs = 0L;
            lastDrawNs = 0L;
            lastFlushNs = 0L;
            return;
        }

        VertexConsumer consumer = g.bufferSource().getBuffer(RenderType.gui());
        // 整张场地都画：柱子盖住整个视野，按距离一圈圈淡，真世界才不会被提前看见
        long tCollect0 = System.nanoTime();
        orderCount[0] = 0;
        for (int cj = 0; cj < drawFine; cj++) {
            for (int ci = 0; ci < drawFine; ci++) {
                if (!drawField.ready(ci, cj)) continue;
                int index = cj * drawFine + ci;
                int blockX = drawBaseBlockX + ci * drawStep;
                int blockZ = drawBaseBlockZ + cj * drawStep;
                long start = chunkStart(Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16));
                float fade;
                if (start < 0L) {
                    // 区块还没到，整根柱子先盖着
                    fade = drawField.edgeFade(ci, cj) * globalAlpha;
                } else {
                    long elapsed = revealClockMs - start;
                    long len = fadeLen(start);
                    if (elapsed >= len) continue;
                    // 整块就地一起淡：不再逐格错峰，错峰看起来像一道波扫过去；
                    // 收尾那批（beginCollapse）用短时长，一起散干净
                    float t = Mth.clamp(elapsed / (float) len, 0.0F, 1.0F);
                    if (t >= 1.0F) continue;
                    fade = (1.0F - soften(t)) * drawField.edgeFade(ci, cj) * globalAlpha;
                }
                float growth = growthOf(ci, cj);
                if (drawingIntro) {
                    // 精读那边还没采完的格子是一边淡入一边从底面长高，开场照搬
                    fade *= GROWTH_ALPHA_FLOOR + (1.0F - GROWTH_ALPHA_FLOOR) * growth;
                }
                if (fade <= 0.002F) continue;
                cellAlpha[index] = fade;
                cellGrowth[index] = growth;
                int rgb = cellRgb[index];
                if (rgb < 0) {
                    // 配色按当前调色板现算，但一格算一次存着就行：不必每帧给上万格各算一遍
                    rgb = drawField.colorAtCell(ci, cj) & 0xFFFFFF;
                    cellRgb[index] = rgb;
                }
                float cx = blockX + drawStep * 0.5F;
                float cz = blockZ + drawStep * 0.5F;
                float top = drawY(rawTop(ci, cj), growth);
                // 视锥外的不提交：整张场地上万根柱子，先把屏幕外的筛掉
                if (!project(cx, top, cz, scratch)) continue;
                if (scratch[0] < -CULL_MARGIN || scratch[0] > this.width + CULL_MARGIN
                        || scratch[1] < -CULL_MARGIN || scratch[1] > this.height + CULL_MARGIN) {
                    continue;
                }
                depthKey[index] = depth(cx, top, cz);
                order[orderCount[0]++] = index;
            }
        }
        long tSort0 = System.nanoTime();
        sort(order, depthKey, orderCount[0]);
        long tDraw0 = System.nanoTime();

        for (int k = 0; k < orderCount[0]; k++) {
            int index = order[k];
            int ci = index % drawFine;
            int cj = index / drawFine;
            int blockX = drawBaseBlockX + ci * drawStep;
            int blockZ = drawBaseBlockZ + cj * drawStep;
            float top = drawY(rawTop(ci, cj), cellGrowth[index]);
            int rgb = cellRgb[index];
            int alpha = (int) (255.0F * cellAlpha[index]);
            if (alpha <= 0) continue;
            int argb = (alpha << 24) | rgb;
            float x0 = blockX;
            float x1 = blockX + drawStep;
            float z0 = blockZ;
            float z1 = blockZ + drawStep;
            quad(consumer, x0, top, z0, x1, top, z0, x1, top, z1, x0, top, z1, argb);
            // 开场正交占大头时按正交那套方向判面，收尾完全按相机在哪一侧判，
            // 中间混着走：镜头推进途中柱子侧面不会忽然空一半，也不会整块翻面闪一下。
            towardCamera((x0 + x1) * 0.5F, top, (z0 + z1) * 0.5F);
            if (x1 < drawEye.x || camX > 0.0F) {
                float low = neighborDrawY(ci + 1, cj);
                if (top > low) wall(consumer, x1, z0, x1, z1, low, top, rgb, alpha, 0.82F, 0.60F);
            }
            if (x0 > drawEye.x || camX < 0.0F) {
                float low = neighborDrawY(ci - 1, cj);
                if (top > low) wall(consumer, x0, z1, x0, z0, low, top, rgb, alpha, 0.56F, 0.40F);
            }
            if (z1 < drawEye.z || camZ > 0.0F) {
                float low = neighborDrawY(ci, cj + 1);
                if (top > low) wall(consumer, x0, z1, x1, z1, low, top, rgb, alpha, 0.70F, 0.52F);
            }
            if (z0 > drawEye.z || camZ < 0.0F) {
                float low = neighborDrawY(ci, cj - 1);
                if (top > low) wall(consumer, x1, z0, x0, z0, low, top, rgb, alpha, 0.46F, 0.34F);
            }
        }

        if (blueprint != null && blueprintElapsed >= 0L && globalAlpha > 0.0F) {
            drawBlueprint(consumer, blueprint, blueprintElapsed, globalAlpha);
        }
        drawMarker(consumer);
        long tDraw1 = System.nanoTime();

        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        g.flush();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        long tFlush1 = System.nanoTime();

        lastCollectNs = tSort0 - tCollect0;
        lastSortNs = tDraw0 - tSort0;
        lastDrawNs = tDraw1 - tDraw0;
        lastFlushNs = tFlush1 - tDraw1;
    }

    /** 这一格朝相机的方向，混合正交视线和「相机在这一格的哪一侧」，四面共用一次。 */
    private void towardCamera(float cx, float top, float cz) {
        double dx = drawEye.x - cx;
        double dy = drawEye.y - top;
        double dz = drawEye.z - cz;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-4) {
            camX = orthoRows[6];
            camZ = orthoRows[7];
            return;
        }
        camX = Mth.lerp(introEase, orthoRows[6], (float) (dx / len));
        camZ = Mth.lerp(introEase, orthoRows[7], (float) (dz / len));
    }

    long lastCollectNs() {
        return lastCollectNs;
    }

    long lastSortNs() {
        return lastSortNs;
    }

    long lastDrawNs() {
        return lastDrawNs;
    }

    long lastFlushNs() {
        return lastFlushNs;
    }

    /** 上一帧实际提交的柱子数（诊断用）。 */
    int lastCells() {
        return orderCount[0];
    }

    private final float[] scratch = new float[3];
    private final float[] qa = new float[3];
    private final float[] qb = new float[3];
    private final float[] qc = new float[3];
    private final float[] qd = new float[3];

    private static void sort(int[] order, float[] depth, int n) {
        // 柱子全淡完时 n=0，不能进快排：(0 + -1) >>> 1 会算出 Integer.MAX_VALUE
        if (n <= 1) return;
        quickSort(order, depth, 0, n - 1);
    }

    private static void quickSort(int[] a, float[] key, int lo, int hi) {
        int i = lo;
        int j = hi;
        float pivot = key[a[(lo + hi) >>> 1]];
        while (i <= j) {
            while (key[a[i]] > pivot) i++;
            while (key[a[j]] < pivot) j--;
            if (i <= j) {
                int t = a[i];
                a[i] = a[j];
                a[j] = t;
                i++;
                j--;
            }
        }
        if (lo < j) quickSort(a, key, lo, j);
        if (i < hi) quickSort(a, key, i, hi);
    }

    // ── 蓝图 ────────────────────────────────────────────────────────────

    private void drawBlueprint(VertexConsumer c, SiteBlueprint blueprint, long elapsedMs, float alpha) {
        float px0 = blueprint.plotMinX;
        float px1 = blueprint.plotMaxX;
        float pz0 = blueprint.plotMinZ;
        float pz1 = blueprint.plotMaxZ;
        float py = drawY(blueprint.baseY, 1.0F);
        int fill = ((int) (0x30 * alpha) << 24) | (SiteBlueprint.GOLD & 0xFFFFFF);
        int lip = ((int) (0xB0 * alpha) << 24) | (SiteBlueprint.GOLD & 0xFFFFFF);
        flat(c, px0, px1, pz0, pz1, py, fill);
        float w = 0.04F;
        flat(c, px0, px1, pz0 - w, pz0 + w, py, lip);
        flat(c, px0, px1, pz1 - w, pz1 + w, py, lip);
        flat(c, px0 - w, px0 + w, pz0, pz1, py, lip);
        flat(c, px1 - w, px1 + w, pz0, pz1, py, lip);

        for (BlueprintBox b : blueprint.boxes) {
            if (elapsedMs < b.delayMs()) continue;
            float t = Mth.clamp((elapsedMs - b.delayMs()) / (float) Math.max(1, b.riseMs()), 0.0F, 1.0F);
            float topBlock = b.minY() + (b.maxY() - b.minY()) * smooth(t);
            float x0 = b.minX();
            float x1 = b.maxX();
            float z0 = b.minZ();
            float z1 = b.maxZ();
            float yb = drawY(b.minY(), 1.0F);
            float yt = drawY(topBlock, 1.0F);
            float h = Math.max(0.03F, yt - yb);
            int a = (int) (255.0F * alpha * (0.55F + 0.45F * t));
            int argb = (a << 24) | (b.color() & 0xFFFFFF);
            float e = 0.05F;
            for (int k = 0; k < 4; k++) {
                float cx = (k & 1) == 0 ? x0 : x1;
                float cz = (k & 2) == 0 ? z0 : z1;
                edge(c, cx, cz, cx + e, cz, yb, h, argb);
                edge(c, cx, cz, cx, cz + e, yb, h, argb);
            }
            if (t > 0.02F) {
                flat(c, x0, x1, z0 - e, z0 + e, yt, argb);
                flat(c, x0, x1, z1 - e, z1 + e, yt, argb);
                flat(c, x0 - e, x0 + e, z0, z1, yt, argb);
                flat(c, x1 - e, x1 + e, z0, z1, yt, argb);
            }
        }

        if (elapsedMs > blueprint.durationMs) {
            float sweep = ((elapsedMs - blueprint.durationMs) % 2600L) / 2600.0F;
            float y = drawY(blueprint.baseY + sweep * blueprint.heightBlocks, 1.0F);
            int plane = ((int) (0x12 * alpha) << 24) | (SiteBlueprint.GOLD & 0xFFFFFF);
            int line = ((int) (0x66 * alpha) << 24) | (SiteBlueprint.GOLD_BRIGHT & 0xFFFFFF);
            flat(c, px0, px1, pz0, pz1, y, plane);
            float e = 0.03F;
            flat(c, px0, px1, pz0 - e, pz0 + e, y, line);
            flat(c, px0, px1, pz1 - e, pz1 + e, y, line);
            flat(c, px0 - e, px0 + e, pz0, pz1, y, line);
            flat(c, px1 - e, px1 + e, pz0, pz1, y, line);
        }
    }

    // ── 落点标：精读那帧上的金十字和占地框，开场按 1−进度收掉 ────────────

    private void drawMarker(VertexConsumer c) {
        float a = 1.0F - introEase;
        if (a <= 0.002F) return;
        int ci = Mth.clamp(Math.round((intro.siteX() - drawBaseBlockX - drawStep * 0.5F)
                / (float) drawStep), 0, drawFine - 1);
        int cj = Mth.clamp(Math.round((intro.siteZ() - drawBaseBlockZ - drawStep * 0.5F)
                / (float) drawStep), 0, drawFine - 1);
        float growth = growthOf(ci, cj);
        float raw = rawTop(ci, cj);
        // 精读里那些竖向余量是按模型单位给的，换算回方块再走同一套压缩才和画出来的一致
        float plate = drawY(raw + 0.012F * heightUnitBlocks, growth);
        float lipTop = drawY(raw + 0.032F * heightUnitBlocks, growth);
        // 占地框和选区图上那块金框同一个矩形（门廊往 -z 侧多让几格，中心不在盒子正中）；
        // 独立安装没有占地，只画落点十字
        SitePlan.Plot plot = SiteText.plot();
        if (plot != null) {
            int px0 = intro.siteX() - plot.width() / 2;
            int pz0 = intro.siteZ() - plot.innerDepth() / 2;
            float x0 = px0;
            float x1 = px0 + plot.width();
            float z0 = pz0 - plot.porch();
            float z1 = pz0 + plot.depth() - plot.porch();
            int fill = ((int) (0x4C * a) << 24) | (GOLD & 0xFFFFFF);
            int lip = ((int) (0xB0 * a) << 24) | (GOLD & 0xFFFFFF);
            flat(c, x0, x1, z0, z1, plate, fill);
            float lipH = Math.max(0.5F, lipTop - plate);
            edge(c, x0, z0, x1, z0, plate, lipH, lip);
            edge(c, x1, z0, x1, z1, plate, lipH, lip);
            edge(c, x1, z1, x0, z1, plate, lipH, lip);
            edge(c, x0, z1, x0, z0, plate, lipH, lip);
        } else {
            flat(c, intro.siteX() - 2.5F, intro.siteX() + 2.5F, intro.siteZ() - 2.5F,
                    intro.siteZ() + 2.5F, plate, ((int) (0x4C * a) << 24) | (GOLD & 0xFFFFFF));
        }
        // 落点十字：精读里它是从底面立到柱顶之上的两根薄片，任意角度都看得见
        float bar = 0.012F * (float) orthoBlocksPerUnit;
        int gold = ((int) (0xFF * a) << 24) | (GOLD & 0xFFFFFF);
        float pinTop = drawY(raw + 0.16F * heightUnitBlocks, growth);
        edge(c, intro.siteX() - bar, intro.siteZ(), intro.siteX() + bar, intro.siteZ(),
                baseY, pinTop - baseY, gold);
        edge(c, intro.siteX(), intro.siteZ() - bar, intro.siteX(), intro.siteZ() + bar,
                baseY, pinTop - baseY, gold);
    }

    // ── 投影 / 顶点 ─────────────────────────────────────────────────────

    private float depth(float x, float y, float z) {
        return (float) ((x - drawEye.x) * drawLook.x + (y - drawEye.y) * drawLook.y
                + (z - drawEye.z) * drawLook.z);
    }

    /**
     * 世界点 → 屏幕点。开场那一头是正交（精读那帧的映射），镜头推进时两头的结果按进度混合，
     * 所以第一帧就是精读画面，最后一帧是真透视。
     *
     * <p>传进来的 {@code y} 已经是压好的高度（{@link #drawY}），正交侧不再压第二遍。
     *
     * @return false = 这个点在相机背后
     */
    private boolean project(float x, float y, float z, float[] out) {
        double dx = x - drawEye.x;
        double dy = y - drawEye.y;
        double dz = z - drawEye.z;
        double d = dx * drawLook.x + dy * drawLook.y + dz * drawLook.z;
        if (d <= 0.05) return false;
        double sx = dx * drawRight.x + dy * drawRight.y + dz * drawRight.z;
        double sy = dx * drawUp.x + dy * drawUp.y + dz * drawUp.z;
        // 精读的柱子宽度滑杆只撑横向；透视那一头按同一个倍数撑横向视野，收尾时放回 1，
        // 这样两头在开场那一帧是同一张图，最后又是一张能给真世界用的等比透视。
        float wide = 1.0F + (orthoWidthScale - 1.0F) * orthoWeight;
        float px = (float) ((sx / d * drawProjF / aspect * wide * 0.5 + 0.5) * width);
        float py = (float) ((1.0 - (sy / d * drawProjF * 0.5 + 0.5)) * height);
        if (orthoWeight > 0.0005F) {
            float wx = (x + orthoShiftX - startFocusX) * orthoWidthScale;
            float wz = (z + orthoShiftZ - startFocusZ) * orthoWidthScale;
            float wy = y - baseY;
            float ox = orthoOriginX + orthoPxPerBlock
                    * (orthoRows[0] * wx + orthoRows[1] * wz + orthoRows[2] * wy);
            float oy = orthoOriginY + orthoPxPerBlock
                    * (orthoRows[3] * wx + orthoRows[4] * wz + orthoRows[5] * wy);
            px = Mth.lerp(orthoWeight, px, ox);
            py = Mth.lerp(orthoWeight, py, oy);
        }
        out[0] = px;
        out[1] = py;
        out[2] = (float) d;
        return true;
    }

    private void quad(VertexConsumer c, float x0, float y0, float z0,
                      float x1, float y1, float z1, float x2, float y2, float z2,
                      float x3, float y3, float z3, int argb) {
        if (!project(x0, y0, z0, qa) || !project(x1, y1, z1, qb)
                || !project(x2, y2, z2, qc) || !project(x3, y3, z3, qd)) return;
        c.addVertex(qa[0], qa[1], 0.0F).setColor(argb);
        c.addVertex(qb[0], qb[1], 0.0F).setColor(argb);
        c.addVertex(qc[0], qc[1], 0.0F).setColor(argb);
        c.addVertex(qd[0], qd[1], 0.0F).setColor(argb);
    }

    private void flat(VertexConsumer c, float x0, float x1, float z0, float z1, float y, int argb) {
        quad(c, x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1, argb);
    }

    private void wall(VertexConsumer c, float ax, float az, float bx, float bz,
                      float low, float high, int rgb, int alpha, float litTop, float litLow) {
        if (!project(ax, high, az, qa) || !project(bx, high, bz, qb)
                || !project(bx, low, bz, qc) || !project(ax, low, az, qd)) return;
        int top = shade(rgb, litTop, alpha);
        int bottom = shade(rgb, litLow, alpha);
        c.addVertex(qa[0], qa[1], 0.0F).setColor(top);
        c.addVertex(qb[0], qb[1], 0.0F).setColor(top);
        c.addVertex(qc[0], qc[1], 0.0F).setColor(bottom);
        c.addVertex(qd[0], qd[1], 0.0F).setColor(bottom);
    }

    private void edge(VertexConsumer c, float ax, float az, float bx, float bz,
                      float y, float height, int argb) {
        quad(c, ax, y, az, bx, y, bz, bx, y + height, bz, ax, y + height, az, argb);
    }

    private static int shade(int rgb, float factor, int alpha) {
        int rr = Mth.clamp((int) (((rgb >> 16) & 255) * factor), 0, 255);
        int gg = Mth.clamp((int) (((rgb >> 8) & 255) * factor), 0, 255);
        int bb = Mth.clamp((int) ((rgb & 255) * factor), 0, 255);
        return (rr << 16) | (gg << 8) | bb | (Mth.clamp(alpha, 0, 255) << 24);
    }

    private static float smooth(float t) {
        return t * t * (3.0F - 2.0F * t);
    }

    /** 比 smoothstep 两头都更缓（五次）：柱子开始化和淡完那两头都不突然。 */
    private static float soften(float t) {
        return t * t * t * (t * (t * 6.0F - 15.0F) + 10.0F);
    }
}
