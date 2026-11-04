package dev.yard.site.view;

import dev.yard.site.YardSiteConfig;
import dev.yard.site.worldgen.SiteSelection;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 建世界时的「群系选区」图：点「创建世界」先看到出生点附近 ±N 格的三维地形沙盘。
 *
 * <p><b>创建只在区块精读里点</b>：大沙盘只负责挑大致方位，主按钮是「进入区块精读」，没选点就落在
 * 图心（原版出生点猜测位置）。想完全不干涉就别点这张图，从「返回」退回原版建世界界面。
 *
 * <h2>两层视图</h2>
 * <ul>
 *   <li><b>大沙盘</b>：±{@code siteSelectRadius} 格，群系逐格 256×256、高度 128×128，用来挑大致方位；</li>
 *   <li><b>区块精读</b>：双击柱子或点「进入区块精读」进去，一格 = 一个区块，窗口是中心
 *       向外 32 区块；中心随拖动挪、新露出的边增量采样、采过的走缓存，选点就钉在区块上
 *       （见 {@link ChunkSitePlanner}）。「返回大沙盘」可以换地方重来。</li>
 * </ul>
 *
 * <p>两层共用 {@link SiteTerrainRenderer}：都是「一格一色的方柱子 + 四面墙 + 从中心向外长出来」，
 * 切换时播一段过渡（旧图淡出下沉、新图淡入长高、镜头轻推近），切换过程里随时能转视角。
 *
 * <h2>编辑模式（右上角开关，默认关）</h2>
 * 给作者自己用的调参入口，玩家看到的界面不受影响。打开后屏幕右边出现一块面板，分两页：
 * 「视图参数」是两根柱子宽度滑条（大沙盘、区块精读各一根，切层时自动顺过去）和取色工具，
 * 「群系调色」是一块 2D 取色方块加一条色相条 —— 吸管点一格（或按住 Alt 点）就选中那个群系，
 * 拖一下整张图里这种群系立刻变色，图上还会把它的范围提亮。存进
 * {@link SiteEditorPrefs}（{@code config/yardsite/site_biome_colors.json}），下次建世界还在。
 */
public class YardSiteScreen extends Screen {

    private static final int GOLD = 0xFFFFD24A;
    private static final int TEXT_SUB = 0xFFD4D9E4;
    private static final int TEXT_HINT = 0xFFA6AEBE;
    /** 左键按下到松开只动了这么点距离，算「单击选定」而不是「转视角」。 */
    private static final double CLICK_DRAG_LIMIT = 4.0;
    /** 大沙盘 ↔ 区块精读的过渡时长（毫秒）。 */
    private static final int TRANSITION_MS = 620;
    /** 两次单击隔这么久以内算双击。 */
    private static final int DOUBLE_CLICK_MS = 420;
    /** 虚拟按钮：正在拖面板里的某个控件（见 {@link #dragTarget}）。 */
    private static final int DRAG_NONE = 0;
    private static final int DRAG_SANDBOX_W = 1;
    private static final int DRAG_DETAIL_W = 2;
    private static final int DRAG_HUE = 3;
    private static final int DRAG_SV = 4;
    /** 面板两页。 */
    private static final int TAB_VIEW = 0;
    private static final int TAB_COLOR = 1;
    /** 面板里滑条/色相条/取色方块的公共宽度。 */
    private static final int SLIDER_W = 130;
    private static final int PANEL_W = 178;
    /** 柱子粗细（横向撑开倍数）的范围：1.0 = 正好拼合，越小越细露缝，越大越互相压住。 */
    private static final float WIDTH_MIN = 0.5F;
    private static final float WIDTH_MAX = 2.0F;

    private final CreateWorldScreen parent;
    private final WorldCreationContext context;
    private final Runnable onProceed;
    private final int radius;
    private final ExecutorService samplerExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "yard-site-map");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<ResourceLocation, Component> nameCache = new HashMap<>();
    private final ConcurrentLinkedQueue<SiteMapSampler.View> results = new ConcurrentLinkedQueue<>();
    private final SiteTerrainRenderer terrain = new SiteTerrainRenderer();

    private final CompletableFuture<Void> worker;
    private final long startedAt = System.currentTimeMillis();
    private volatile boolean cancelled;
    private volatile String sampleError;

    /** 大沙盘（采完也留着，从精读回来不用重采）。 */
    private SiteMapSampler.View sandbox;
    /** 区块精读：懒创建，缓存跟着界面活到关掉为止。 */
    private ChunkSitePlanner planner;
    private boolean detailMode;
    private long transitionEndAt;
    private boolean transitionToDetail;
    /** 过渡期间顺带着把缩放从旧图的默认值挪到新图的默认值（0 = 已就位）。 */
    private float transitionZoomFrom;
    private float transitionZoomTo;

    private int mapX;
    private int mapY;
    private int mapSize;

    // ── 编辑模式（作者用：柱子宽度 + 群系调色）────────────────────────────

    /** 右上角开关：打开才看得到柱子宽度、取色工具这些。关着就是给玩家看的那张图。 */
    private boolean editMode;
    /** 面板当前页：参数（宽度/工具）或调色。 */
    private int panelTab = TAB_VIEW;
    /** 大沙盘柱子宽度：两根分开存，切层时顺过去（语义同旧版，横向撑开、高度不撑）。 */
    private float sandboxWidth = 1.0F;
    /** 区块精读柱子宽度。 */
    private float detailWidth = 1.0F;
    /** 吸管工具：开着时左键点柱子 = 选那种群系去调色，不改选点。 */
    private boolean eyedropper;
    /** 正在调色的群系（null = 没选，调色页显示提示）。 */
    private ResourceLocation colorTarget;
    /** 调色页当前 HSV（0..1）。 */
    private float targetHue;
    private float targetSat;
    private float targetVal;
    /** 正在拖面板里的哪个控件。 */
    private int dragTarget = DRAG_NONE;
    /** 这一帧登记的可点区域（工具切换、色块、恢复原色、编辑模式开关都用它）。 */
    private final List<Hotspot> hotspots = new ArrayList<>();
    /** 面板几何：卡片本身 + 每根滑条 + 取色方块 + 色相条（画的时候算，点击时复用）。 */
    private int panelX;
    private int panelY = 42;
    private int panelH;
    private final int[] sandboxSliderRect = new int[4];
    private final int[] detailSliderRect = new int[4];
    private final int[] hueRect = new int[4];
    private final int[] svRect = new int[4];
    /** 右上角「编辑模式」开关的矩形（一直能点，所以要单独记）。 */
    private final int[] toggleRect = new int[4];
    /** 切层过渡期间顺过去的柱子宽度（-1 = 已经就位）。 */
    private float transitionWidthTo = -1.0F;
    private float transitionWidthFrom = 1.0F;
    private final float[] hsvScratch = new float[3];

    private int dragButton = -1;
    private double dragDistance;
    /** 屏幕拖动 → 窗口中心该挪的格数（精读用），复用同一份数组避免每帧分配。 */
    private final float[] dragOut = new float[2];
    private long lastClickAt;
    private int lastClickColumn = -1;

    /** 这一帧鼠标指到的地方（命中才有值）。 */
    private boolean hoverHit;
    private int hoverCellX;
    private int hoverCellY;

    private boolean hasSelection;
    private int selectedCellX;
    private int selectedCellY;
    private int selectedX;
    private int selectedZ;

    public YardSiteScreen(CreateWorldScreen parent, WorldCreationContext context, Runnable onProceed) {
        super(Component.literal(SiteText.screenTitle()));
        this.parent = parent;
        this.context = context;
        this.onProceed = onProceed;
        this.radius = YardSiteConfig.SITE_SELECT_RADIUS.get();
        // 编辑模式的参数是存盘的（config/yardsite/site_biome_colors.json）：上次调好的宽度/配色这次还在
        SiteEditorPrefs.load();
        this.sandboxWidth = SiteEditorPrefs.sandboxWidth();
        this.detailWidth = SiteEditorPrefs.detailWidth();
        terrain.setWidthScale(detailMode ? detailWidth : sandboxWidth);
        this.worker = CompletableFuture.runAsync(this::sampleInBackground, samplerExecutor);
        System.out.println("[yardsite] 打开群系选区图：种子 " + context.options().seed()
                + "，范围 ±" + radius + " 格");
    }

    /** 后台：先把活数据交给界面，再从中心向外一边采一边让界面往外画。 */
    private void sampleInBackground() {
        long started = System.currentTimeMillis();
        try (SiteMapSampler sampler = new SiteMapSampler(context, radius)) {
            SiteMapSampler.View view = sampler.view();
            results.add(view);
            System.out.println("[yardsite] 选址图初始化（生成器/噪声）："
                    + (System.currentTimeMillis() - started) + " ms，从中心 "
                    + view.centerX + "," + view.centerZ + " 向外采样");
            long buildStart = System.currentTimeMillis();
            sampler.build(() -> cancelled);
            if (cancelled) return;
            System.out.println("[yardsite] 选址图 256×256 采完：+"
                    + (System.currentTimeMillis() - buildStart) + " ms（累计 "
                    + (System.currentTimeMillis() - started) + " ms）");
        } catch (Throwable t) {
            if (!cancelled) {
                Throwable cause = t.getCause() == null ? t : t.getCause();
                sampleError = cause.getMessage() == null ? cause.toString() : cause.getMessage();
                System.out.println("[yardsite] 选址图采样失败：" + cause);
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        // 沙盘占满中间：只留一圈边给四个角的字和按钮
        this.mapSize = Math.min(this.width - 20, this.height - 56);
        this.mapX = (this.width - mapSize) / 2;
        this.mapY = Math.max(6, (this.height - mapSize) / 2 - 6);
        this.panelX = this.width - 12 - PANEL_W;
        this.panelH = Math.max(110, this.height - 34 - panelY);
        rebuildButtons();
    }

    /** 按钮从屏幕右下角往左排。 */
    private void rebuildButtons() {
        this.clearWidgets();
        int y = this.height - 26;
        int right = this.width - 10;
        // 大沙盘不给直接创建：先落到区块精读（一格 = 一个区块）把落点钉准，创建只在精读里点
        if (detailMode) {
            right = addButtonRight(right, y, 120, "确认并创建", b -> proceed());
            right = addButtonRight(right, y, 76, "返回大沙盘", b -> backToSandbox());
        } else if (sandbox != null) {
            right = addButtonRight(right, y, 120, "进入区块精读", b -> enterDetailForCreate());
        }
        right = addButtonRight(right, y, 68, "复位视角", b -> {
            SiteField field = active();
            if (field != null) terrain.resetView(field);
        });
        if (hasSelection) {
            right = addButtonRight(right, y, 72, "清除选点", b -> clearSelection());
        }
        addButtonRight(right, y, 56, "返回", b -> goBack());
    }

    private int addButtonRight(int right, int y, int width, String text, Button.OnPress action) {
        int x = right - width;
        this.addRenderableWidget(Button.builder(Component.literal(text), action).bounds(x, y, width, 20).build());
        return x - 6;
    }

    @Override
    public void tick() {
        SiteMapSampler.View next = results.poll();
        if (next != null) {
            sandbox = next;
            terrain.setZoom(next.defaultZoom());
            rebuildButtons();
        }
    }

    // ── 两层视图 ────────────────────────────────────────────────────────

    /** 现在正在操作的那张图。 */
    private SiteField active() {
        if (detailMode && planner != null) return planner.field();
        return sandbox;
    }

    /** 过渡期间要淡出的那张图（进精读=大沙盘淡出，回大沙盘=精读淡出）。 */
    private SiteField outgoing() {
        if (System.currentTimeMillis() >= transitionEndAt) return null;
        return transitionToDetail ? sandbox : (planner == null ? null : planner.field());
    }

    /** 进区块精读：一格 = 一个区块，中心就在选中的那一格。 */
    private void enterDetail(int blockX, int blockZ) {
        if (planner == null) {
            planner = new ChunkSitePlanner(context, blockX, blockZ);
            planner.start();
        } else {
            planner.setCenter(blockX, blockZ);
        }
        detailMode = true;
        transitionToDetail = true;
        transitionEndAt = System.currentTimeMillis() + TRANSITION_MS;
        SiteField field = planner.field();
        // 精读一屏要装下 97 个区块，默认缩放比大沙盘小；过渡期间把缩放也顺过去，别一下跳大
        transitionZoomFrom = terrain.zoom();
        transitionZoomTo = field.defaultZoom();
        transitionWidthFrom = terrain.widthScale();
        transitionWidthTo = detailWidth;
        terrain.shiftPan(-terrain.panX(), -terrain.panY());
        int center = field.fine() / 2;
        selectAt(center, center);
        System.out.println("[yardsite] 进入区块精读：中心 " + blockX + "," + blockZ
                + "（区块 " + Math.floorDiv(blockX, 16) + "," + Math.floorDiv(blockZ, 16) + "）"
                + "，窗口半径 " + ChunkSitePlanner.RADIUS + " 区块");
        rebuildButtons();
    }

    /**
     * 大沙盘那颗主按钮：带当前落点进小沙盘；一个点都没选就用图心（原版出生点猜测位置）。
     * 进层后中心落在哪、落点就在哪，所以这条路径一定有一个钉在区块上的落点。
     */
    private void enterDetailForCreate() {
        SiteField field = active();
        if (field == null) return;
        enterDetail(hasSelection ? selectedX : field.centerX(),
                hasSelection ? selectedZ : field.centerZ());
    }

    private void backToSandbox() {
        detailMode = false;
        transitionToDetail = false;
        transitionEndAt = System.currentTimeMillis() + TRANSITION_MS;
        transitionZoomFrom = terrain.zoom();
        transitionZoomTo = sandbox == null ? terrain.zoom() : sandbox.defaultZoom();
        transitionWidthFrom = terrain.widthScale();
        transitionWidthTo = sandboxWidth;
        terrain.shiftPan(-terrain.panX(), -terrain.panY());
        clearSelection();
        rebuildButtons();
    }

    /** 拖动沙盘：精读里挪的是「窗口中心」（世界坐标），大沙盘里挪的是画面（像素平移）。 */
    private void dragView(double dragX, double dragY) {
        if (detailMode && planner != null) {
            terrain.dragToCells(planner.field(), mapSize, dragX, dragY, dragOut);
            planner.moveCenterByCells(dragOut[0], dragOut[1]);
        } else {
            terrain.dragPan(dragX, dragY);
        }
    }

    // ── 渲染 ────────────────────────────────────────────────────────────

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        // 不再画金框面板，只压暗一层，让沙盘读得清楚
        g.fill(0, 0, this.width, this.height, 0x990A0C12);
        // 沙盘画在背景这一层：按钮和字都在它上面，不会被挡
        drawMap(g, mouseX, mouseY);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        // 面板画在沙盘之后：它盖住右边一小块，但那一小块以外的转视角、选点、吸管都照常
        drawEditPanel(g, mouseX, mouseY);
        drawTopLeft(g);
        drawProgress(g);
        drawBottomLeft(g);
        if (hoverHit && !pointerInPanel(mouseX, mouseY)) drawHover(g, mouseX, mouseY);
    }

    private void drawMap(GuiGraphics g, int mouseX, int mouseY) {
        SiteField field = active();
        if (field == null) {
            drawNotice(g);
            return;
        }
        if (detailMode && hasSelection) {
            // 精读里中心随拖动连续挪，落点要按存下的方块坐标重新折算成格子，标记才不会飘
            selectedCellX = cellOfBlock(field, selectedX, field.centerX());
            selectedCellY = cellOfBlock(field, selectedZ, field.centerZ());
        }

        long now = System.currentTimeMillis();
        SiteField fading = outgoing();
        float ease = 1.0F;
        if (fading != null) {
            // p：0 = 刚开始切，1 = 切完；先慢后快再慢，推近感才不像跳切
            float p = 1.0F - Mth.clamp((transitionEndAt - now) / (float) TRANSITION_MS, 0.0F, 1.0F);
            ease = p * p * (3.0F - 2.0F * p);
            terrain.setZoom(transitionZoomFrom + (transitionZoomTo - transitionZoomFrom) * ease);
            // 两层柱子宽度可以不一样，切层时和缩放一起顺过去，别一下跳粗/跳细
            if (transitionWidthTo >= 0.0F) {
                terrain.setWidthScale(transitionWidthFrom
                        + (transitionWidthTo - transitionWidthFrom) * ease);
            }
        } else {
            if (transitionZoomTo > 0.0F) {
                terrain.setZoom(transitionZoomTo);
                transitionZoomTo = 0.0F;
            }
            if (transitionWidthTo >= 0.0F) {
                terrain.setWidthScale(transitionWidthTo);
                transitionWidthTo = -1.0F;
            }
        }
        terrain.setZoomBoost(fading == null ? 1.0F : 0.84F + 0.16F * ease);

        hoverHit = pickAt(field, mouseX, mouseY);
        if (hoverHit) {
            hoverCellX = terrain.pickCellX;
            hoverCellY = terrain.pickCellY;
        }
        int hoverColumn = hoverHit ? terrain.columnOf(field, hoverCellX, hoverCellY) : -1;
        int selCellX = hasSelection ? selectedCellX : -1;
        int selCellY = hasSelection ? selectedCellY : -1;

        if (fading != null) {
            // 旧图：淡出 + 压下去
            terrain.render(g, fading, mapX, mapY, mapSize, now, -1, -1, -1, 1.0F - ease,
                    1.0F - 0.55F * ease);
        }
        terrain.render(g, field, mapX, mapY, mapSize, now, hoverColumn, selCellX, selCellY,
                fading == null ? 1.0F : ease, fading == null ? 1.0F : 0.45F + 0.55F * ease);
        terrain.setZoomBoost(1.0F);
    }

    /** 图还没开始往外长（正在定位出生点、建噪声）时中间放一行字，进度在右边。 */
    private void drawNotice(GuiGraphics g) {
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        if (sampleError != null) {
            drawCentered(g, "采样失败：" + sampleError, centerX, centerY - 20, 0xFFFF8A80);
            drawCentered(g, "可以点「返回」换个设置，或回原版建世界界面",
                    centerX, centerY - 6, TEXT_SUB);
            return;
        }
        drawCentered(g, "正在定位出生点…", centerX, centerY - 6, 0xFFE8E8F0);
    }

    /** 左上角：标题 + 当前视图 + 操作说明。 */
    private void drawTopLeft(GuiGraphics g) {
        SiteField field = active();
        String title = SiteText.screenTitle()
                + (detailMode ? " · 区块精读（一格 = 1 区块）" : " · 大沙盘");
        g.drawString(this.font, title, 8, 6, GOLD, true);
        String sub = "种子 " + context.options().seed()
                + (detailMode ? "　·　窗口半径 " + ChunkSitePlanner.RADIUS + " 区块"
                : "　·　覆盖 ±" + radius + " 格");
        if (field != null) {
            sub += "　·　中心 x=" + field.centerX() + ", z=" + field.centerZ();
        }
        g.drawString(this.font, sub, 8, 18, TEXT_SUB, true);
        String hint = detailMode
                ? "左键拖动=转视角　右键拖动=挪窗口（越拖越补采，采过的不重算）　中键拖动=翻面　滚轮=缩放　左键单击=选定（钉在区块上）　R=复位"
                : "左键拖动=转视角　右键拖动=平移　中键拖动=翻面　滚轮=缩放　左键单击=选定　双击=进入区块精读　R=复位";
        g.drawString(this.font, hint, 8, 30, TEXT_HINT, true);
        if (editMode) {
            String editHint = eyedropper
                    ? "编辑模式：左键点一格=选它那个群系去调色（不改选点）　ESC=放下吸管"
                    : "编辑模式：按住 Alt 点一格也能取色　ESC=退出";
            g.drawString(this.font, editHint, 8, 41, GOLD, true);
        }
    }

    /** 右上角一行状态 + 屏幕右边缘一根细竖条。 */
    private void drawProgress(GuiGraphics g) {
        SiteField field = active();
        int total = field == null ? 1 : Math.max(1, field.progressTotal());
        int done = field == null ? 0 : Math.min(field.progress(), total);
        int barW = 4;
        // 编辑模式的面板占了右边，竖条挪到面板左边，别压在面板底下
        int barX = editMode ? panelX - barW - 4 : this.width - barW - 4;
        int barTop = 44;
        int barBottom = this.height - 40;
        g.fill(barX, barTop, barX + barW, barBottom, 0x50FFFFFF);
        g.fill(barX, barTop, barX + barW, barTop + (barBottom - barTop) * done / total, GOLD);

        String line;
        if (sampleError != null) {
            line = "采样失败";
        } else if (field == null) {
            line = "正在准备数据…";
        } else if (field.full()) {
            line = (detailMode ? "区块采样完成" : "采样完成") + "　·　群系 " + field.biomes().size() + " 种";
        } else {
            line = (detailMode ? "补采 " : "采样 ") + done * 100 / total + "%　·　已用 "
                    + Math.max(0L, (System.currentTimeMillis() - startedAt) / 1000L) + " 秒　·　群系 "
                    + field.biomes().size() + " 种";
        }
        int right = editMode ? barX - 4 : this.width - 14;
        drawTrimmedRight(g, line, right, 6, this.width / 2,
                sampleError != null ? 0xFFFF8A80 : TEXT_SUB);
    }

    // ── 编辑模式面板 ────────────────────────────────────────────────────

    /**
     * 面板上一个可点区域。自定义控件（开关、页签、工具、色块）都在画的这一帧登记进来，
     * 点击时按登记的矩形命中 —— 只有面板会盖住的那一小块沙盘点不到，其余照常。
     */
    private record Hotspot(int x, int y, int w, int h, Runnable action) {}

    /** 这一帧鼠标位置（chip 画的时候要知道有没有压着）。 */
    private double uiMouseX;
    private double uiMouseY;

    /** 当前这一层用的柱子宽度。 */
    private float currentWidth() {
        return detailMode ? detailWidth : sandboxWidth;
    }

    /** 右上角「编辑模式」开关：打开才看得见柱子宽度和取色工具。 */
    private void toggleEditMode() {
        editMode = !editMode;
        if (!editMode) {
            eyedropper = false;
            colorTarget = null;
            dragTarget = DRAG_NONE;
            terrain.setHighlightBiome(null);
            SiteEditorPrefs.save();
        }
    }

    /**
     * 画编辑 UI：开关一直在，打开后再画右边那块面板。
     *
     * <p>面板分两页 —— 「视图参数」放两根柱子宽度 + 工具 + 已改过的群系，「调色」放取色方块。
     * 分两页是因为界面高度按 GUI 缩放算下来常常只有一百多格，一屏放不下全部还压住太多沙盘。
     */
    private void drawEditPanel(GuiGraphics g, int mouseX, int mouseY) {
        this.uiMouseX = mouseX;
        this.uiMouseY = mouseY;
        this.hotspots.clear();
        this.sandboxSliderRect[2] = 0;
        this.detailSliderRect[2] = 0;
        this.hueRect[2] = 0;
        this.svRect[2] = 0;

        int toggleW = 74;
        int toggleX = this.width - 12 - toggleW;
        this.toggleRect[0] = toggleX;
        this.toggleRect[1] = 15;
        this.toggleRect[2] = toggleW;
        this.toggleRect[3] = 14;
        chip(g, toggleX, 15, toggleW, 14, editMode ? "编辑模式：开" : "编辑模式",
                this::toggleEditMode, editMode);
        if (!editMode) return;

        // 卡片按这一页需要的内容高度来（撑到底会把右边一大片沙盘白白盖住）；窗口太矮就压缩
        int want = panelTab == TAB_VIEW ? (SiteEditorPrefs.customCount() > 0 ? 192 : 164) : 238;
        this.panelH = Math.min(want, this.height - 34 - panelY);
        g.fill(panelX, panelY, panelX + PANEL_W, panelY + panelH, 0xE612141B);
        g.fill(panelX, panelY, panelX + PANEL_W, panelY + 1, 0x4DFFFFFF);
        int tabW = (PANEL_W - 14) / 2;
        chip(g, panelX + 6, panelY + 4, tabW, 13, "视图参数", () -> panelTab = TAB_VIEW,
                panelTab == TAB_VIEW);
        chip(g, panelX + 8 + tabW, panelY + 4, tabW, 13, "群系调色", () -> panelTab = TAB_COLOR,
                panelTab == TAB_COLOR);

        if (panelTab == TAB_VIEW) {
            drawViewTab(g, panelY + 23);
        } else {
            drawColorTab(g, panelY + 23);
        }
    }

    /** 「视图参数」页：两根柱子宽度滑条（大沙盘/精读各一根）+ 工具切换 + 已改过的群系。 */
    private void drawViewTab(GuiGraphics g, int y) {
        int x = panelX + 8;
        int innerW = PANEL_W - 16;
        int bottom = panelY + panelH - 4;
        g.drawString(this.font, "柱子宽度（只撑横向，高度不变）", x, y, GOLD, true);
        y += 12;
        drawSlider(g, sandboxSliderRect, x, y, "大沙盘", sandboxWidth, detailMode);
        y += 24;
        drawSlider(g, detailSliderRect, x, y, "区块精读", detailWidth, !detailMode);
        y += 24;
        if (y + 10 <= bottom) {
            g.drawString(this.font, detailMode ? "上面那根：回大沙盘才看得到"
                    : "下面那根：进区块精读才看得到", x, y, TEXT_HINT, true);
            y += 14;
        }
        if (y + 26 > bottom) return;
        g.drawString(this.font, "工具", x, y, GOLD, true);
        y += 12;
        int half = (innerW - 4) / 2;
        chip(g, x, y, half, 14, "选择落点", () -> eyedropper = false, !eyedropper);
        chip(g, x + half + 4, y, half, 14, "取群系调色", () -> eyedropper = true, eyedropper);
        y += 18;
        if (y + 10 > bottom) return;
        g.drawString(this.font, "按住 Alt 点地图 = 直接取色", x, y, TEXT_HINT, true);
        y += 13;
        int count = SiteEditorPrefs.customCount();
        if (y + 11 > bottom) return;
        String title = "已改 " + count + " 种群系";
        g.drawString(this.font, title, x, y, TEXT_SUB, true);
        List<ResourceLocation> ids = SiteEditorPrefs.orderedIds();
        int chipX = Math.min(x + this.font.width(title) + 6, panelX + PANEL_W - 8 - 12 * 9);
        int shown = 0;
        for (ResourceLocation id : ids) {
            Integer rgb = SiteEditorPrefs.colorOf(id);
            if (rgb == null) continue;
            if (chipX + 11 > panelX + PANEL_W - 8) break;
            int px = chipX;
            int py = y - 1;
            g.fill(px, py, px + 11, py + 10, 0xFF000000 | rgb);
            if (id.equals(colorTarget)) outline(g, px - 1, py - 1, 13, 12, GOLD);
            hotspots.add(new Hotspot(px - 2, py - 2, 15, 14, () -> focusBiome(id, naturalRgb(id))));
            chipX += 13;
            shown++;
        }
        if (ids.size() > shown && chipX + 14 <= panelX + PANEL_W - 6) {
            g.drawString(this.font, "+" + (ids.size() - shown), chipX + 2, y, TEXT_HINT, true);
        }
        y += 15;
        if (count > 0 && y + 14 <= bottom) {
            chip(g, x, y, 60, 14, "全部还原", () -> {
                SiteEditorPrefs.clearAll();
                colorTarget = null;
                terrain.setHighlightBiome(null);
            }, false);
            g.drawString(this.font, "改完存进配置文件", x + 66, y + 3, TEXT_HINT, true);
        }
    }

    /** 「群系调色」页：原色/现色对比 + 取色方块 + 色相条 + 还原/关闭。 */
    private void drawColorTab(GuiGraphics g, int y) {
        int x = panelX + 8;
        int innerW = PANEL_W - 16;
        if (colorTarget == null) {
            drawTrimmed(g, "还没选群系", x, y, innerW, TEXT_SUB);
            drawTrimmed(g, "切到「视图参数」选吸管，", x, y + 12, innerW, TEXT_HINT);
            drawTrimmed(g, "或直接按住 Alt 点地图上一格", x, y + 22, innerW, TEXT_HINT);
            return;
        }
        int natural = naturalRgb(colorTarget);
        Integer custom = SiteEditorPrefs.colorOf(colorTarget);
        int shownRgb = custom == null ? Mth.hsvToRgb(targetHue, targetSat, targetVal) & 0xFFFFFF : custom;
        drawTrimmed(g, biomeName(colorTarget).getString(), x, y, innerW, GOLD);
        y += 11;
        drawTrimmed(g, colorTarget.toString(), x, y, innerW, TEXT_HINT);
        y += 12;
        // 左边原版色、右边现在图上用的色，改完当场能对比
        g.fill(x, y, x + 22, y + 11, 0xFF000000 | natural);
        g.fill(x + 26, y, x + 48, y + 11, 0xFF000000 | shownRgb);
        drawTrimmed(g, "原版 / 现在 #" + SiteEditorPrefs.hex(shownRgb), x + 52, y + 2,
                innerW - 52, TEXT_SUB);
        y += 16;
        if (BiomePalette.isWaterBiome(colorTarget)) {
            drawTrimmed(g, "水面：这个色当浅水，深水自动压暗", x, y, innerW, TEXT_HINT);
            y += 11;
        }
        // 取色方块能拿多少高度就拿多少（小 GUI 缩放时也别把按钮挤出去）
        int boxH = Mth.clamp(panelY + panelH - (y + 8 + 16 + 20), 34, 92);
        drawColorBox(g, x, y, innerW, boxH);
        y += boxH + 5;
        drawHueBar(g, x, y, innerW, 8);
        y += 13;
        int half = (innerW - 4) / 2;
        chip(g, x, y, half, 14, "恢复原色", () -> {
            SiteEditorPrefs.clear(colorTarget);
            rgbToHsv(natural, hsvScratch);
            targetHue = hsvScratch[0];
            targetSat = hsvScratch[1];
            targetVal = hsvScratch[2];
        }, false);
        chip(g, x + half + 4, y, half, 14, "关闭", () -> {
            colorTarget = null;
            terrain.setHighlightBiome(null);
            SiteEditorPrefs.save();
        }, false);
    }

    /** 一块 2D 取色方块：横着是饱和、竖着是明暗（上亮下暗），左边白、右边最纯。 */
    private void drawColorBox(GuiGraphics g, int x, int y, int w, int h) {
        svRect[0] = x;
        svRect[1] = y;
        svRect[2] = w;
        svRect[3] = h;
        int full = Mth.hsvToRgb(targetHue, 1.0F, 1.0F) & 0xFFFFFF;
        g.fill(x, y, x + w, y + h, 0xFF000000 | full);
        for (int i = 0; i < w; i++) {
            int alpha = (int) (255.0F * (1.0F - i / (float) w));
            g.fill(x + i, y, x + i + 1, y + h, (alpha << 24) | 0xFFFFFF);
        }
        for (int j = 0; j < h; j++) {
            int alpha = (int) (255.0F * j / (float) h);
            g.fill(x, y + j, x + w, y + j + 1, alpha << 24);
        }
        outline(g, x - 1, y - 1, w + 2, h + 2, 0x66FFFFFF);
        int cx = x + (int) (targetSat * (w - 1));
        int cy = y + (int) ((1.0F - targetVal) * (h - 1));
        outline(g, cx - 3, cy - 3, 7, 7, 0xFF0A0C12);
        outline(g, cx - 2, cy - 2, 5, 5, GOLD);
    }

    /** 色相条：整条铺开 360°，金色一竖是当前色相。 */
    private void drawHueBar(GuiGraphics g, int x, int y, int w, int h) {
        hueRect[0] = x;
        hueRect[1] = y;
        hueRect[2] = w;
        hueRect[3] = h;
        for (int i = 0; i < w; i++) {
            int rgb = Mth.hsvToRgb(i / (float) w, 1.0F, 1.0F) & 0xFFFFFF;
            g.fill(x + i, y, x + i + 1, y + h, 0xFF000000 | rgb);
        }
        int mark = x + (int) (targetHue * (w - 1));
        g.fill(mark - 1, y - 2, mark + 2, y + h + 2, 0xFF0A0C12);
        g.fill(mark, y - 2, mark + 1, y + h + 2, GOLD);
    }

    /** 一根滑条：上面一行是名字和当前倍数，下面一行是轨道；几何写进 rect 供点击命中。 */
    private void drawSlider(GuiGraphics g, int[] rect, int x, int y, String label, float value,
                            boolean dim) {
        String valueText = "×" + String.format(java.util.Locale.ROOT, "%.2f", value);
        int textColor = dim ? TEXT_HINT : TEXT_SUB;
        g.drawString(this.font, label, x, y, textColor, true);
        g.drawString(this.font, valueText, x + SLIDER_W - this.font.width(valueText), y,
                dim ? TEXT_HINT : GOLD, true);
        int trackY = y + 11;
        g.fill(x, trackY, x + SLIDER_W, trackY + 3, 0x50FFFFFF);
        float t = (value - WIDTH_MIN) / (WIDTH_MAX - WIDTH_MIN);
        int knob = x + (int) (SLIDER_W * Mth.clamp(t, 0.0F, 1.0F));
        g.fill(knob - 2, trackY - 3, knob + 3, trackY + 9, dim ? 0xB08C94A6 : GOLD);
        rect[0] = x;
        rect[1] = trackY - 5;
        rect[2] = SLIDER_W;
        rect[3] = 15;
    }

    /** 画一个自定义小块按钮并登记点击；选中态描金、压着提亮。 */
    private void chip(GuiGraphics g, int x, int y, int w, int h, String text,
                      Runnable action, boolean active) {
        boolean hot = x <= uiMouseX && uiMouseX <= x + w && y <= uiMouseY && uiMouseY <= y + h;
        g.fill(x, y, x + w, y + h, active ? 0xF033363F : hot ? 0xE0262A34 : 0xC01A1D26);
        if (active) outline(g, x, y, w, h, GOLD);
        drawCentered(g, text, x + w / 2, y + (h - 9) / 2, active ? GOLD : TEXT_SUB);
        if (action != null) hotspots.add(new Hotspot(x, y, w, h, action));
    }

    /** 吸管/色块选定一个群系：跳到调色页，并把图上这种群系的顶面提亮。 */
    private void focusBiome(ResourceLocation id, int naturalRgb) {
        colorTarget = id;
        panelTab = TAB_COLOR;
        editMode = true;
        terrain.setHighlightBiome(id);
        Integer custom = SiteEditorPrefs.colorOf(id);
        rgbToHsv(custom == null ? naturalRgb : custom, hsvScratch);
        targetHue = hsvScratch[0];
        targetSat = hsvScratch[1];
        targetVal = hsvScratch[2];
    }

    /** 这个群系原版是什么色（在当前这张图的调色板里查；查不到退回自定义色）。 */
    private int naturalRgb(ResourceLocation id) {
        SiteField field = active();
        if (field != null) {
            for (BiomeInfo info : field.biomes()) {
                if (info.id().equals(id)) return info.rgb();
            }
        }
        Integer custom = SiteEditorPrefs.colorOf(id);
        return custom == null ? 0x808080 : custom;
    }

    /** 把 HSV 写成这个群系的底色：渲染器按版本只重算这种群系的柱子，所以拖动是顺的。 */
    private void pushColor() {
        if (colorTarget == null) return;
        SiteEditorPrefs.setColor(colorTarget, Mth.hsvToRgb(targetHue, targetSat, targetVal) & 0xFFFFFF);
    }

    /** 0xRRGGBB → [色相, 饱和, 明度]（和 {@link Mth#hsvToRgb} 同一套定义）。 */
    private static void rgbToHsv(int rgb, float[] out) {
        int r = (rgb >> 16) & 0xFF;
        int gg = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int max = Math.max(r, Math.max(gg, b));
        int min = Math.min(r, Math.min(gg, b));
        float delta = max - min;
        out[2] = max / 255.0F;
        out[1] = max == 0 ? 0.0F : delta / max;
        if (delta <= 0.0F) {
            out[0] = 0.0F;
            return;
        }
        float hue;
        if (max == r) {
            hue = (gg - b) / delta + (gg < b ? 6.0F : 0.0F);
        } else if (max == gg) {
            hue = (b - r) / delta + 2.0F;
        } else {
            hue = (r - gg) / delta + 4.0F;
        }
        out[0] = hue / 6.0F;
    }

    /** 点在面板控件上 → 返回拖动编号，没点中返回 {@link #DRAG_NONE}。 */
    private int uiHit(double x, double y) {
        if (!editMode) return DRAG_NONE;
        if (panelTab == TAB_VIEW) {
            if (inRect(sandboxSliderRect, x, y)) return DRAG_SANDBOX_W;
            if (inRect(detailSliderRect, x, y)) return DRAG_DETAIL_W;
        } else {
            if (inRect(svRect, x, y)) return DRAG_SV;
            if (inRect(hueRect, x, y)) return DRAG_HUE;
        }
        return DRAG_NONE;
    }

    private static boolean inRect(int[] rect, double x, double y) {
        return rect[2] > 0 && x >= rect[0] - 2 && x <= rect[0] + rect[2] + 2
                && y >= rect[1] - 2 && y <= rect[1] + rect[3] + 2;
    }

    private Hotspot hotspotAt(double x, double y) {
        for (Hotspot spot : hotspots) {
            if (x >= spot.x() && x <= spot.x() + spot.w() && y >= spot.y() && y <= spot.y() + spot.h()) {
                return spot;
            }
        }
        return null;
    }

    /** 把当前拖动目标按鼠标位置换算成参数值。 */
    private void applyDrag(double x, double y) {
        switch (dragTarget) {
            case DRAG_SANDBOX_W -> {
                sandboxWidth = sliderValue(sandboxSliderRect, x);
                SiteEditorPrefs.setSandboxWidth(sandboxWidth);
                terrain.setWidthScale(currentWidth());
            }
            case DRAG_DETAIL_W -> {
                detailWidth = sliderValue(detailSliderRect, x);
                SiteEditorPrefs.setDetailWidth(detailWidth);
                terrain.setWidthScale(currentWidth());
            }
            case DRAG_HUE -> {
                targetHue = Mth.clamp((float) ((x - hueRect[0]) / hueRect[2]), 0.0F, 1.0F);
                pushColor();
            }
            case DRAG_SV -> {
                targetSat = Mth.clamp((float) ((x - svRect[0]) / svRect[2]), 0.0F, 1.0F);
                targetVal = 1.0F - Mth.clamp((float) ((y - svRect[1]) / svRect[3]), 0.0F, 1.0F);
                pushColor();
            }
            default -> {
            }
        }
    }

    private static float sliderValue(int[] rect, double x) {
        float t = Mth.clamp((float) ((x - rect[0]) / rect[2]), 0.0F, 1.0F);
        return WIDTH_MIN + t * (WIDTH_MAX - WIDTH_MIN);
    }

    /** 鼠标压在这块面板（含右上角开关）上 —— 这时候别再画格子的悬浮提示。 */
    private boolean pointerInPanel(double x, double y) {
        if (inRect(toggleRect, x, y)) return true;
        return editMode && x >= panelX - 2 && x <= panelX + PANEL_W + 2
                && y >= panelY && y <= panelY + panelH + 2;
    }

    /** 左下角：选点结果，没选点时说要怎么选；字放不下就折行，不截掉。 */
    private void drawBottomLeft(GuiGraphics g) {
        int maxWidth = this.width - 420;
        List<String> lines = new ArrayList<>();
        int hintFrom = Integer.MAX_VALUE;
        if (sampleError != null) {
            wrapLines("采样失败，可以点「返回」换个设置，或回原版建世界界面", maxWidth, lines);
        } else {
            wrapLines(selectionLine(), maxWidth, lines);
            SiteField field = active();
            if (field != null && !field.full()) {
                hintFrom = lines.size();
                wrapLines(detailMode ? "新区块还在补采，可以先把窗口拖过去"
                        : "地形正在从中间往外长，现在就能转视角、能选点", maxWidth, lines);
            }
        }
        // 整块贴着屏幕底部往上排：多出来的行往上长，不会压到右下角那排按钮
        int y = this.height - 10 - lines.size() * 11;
        for (int i = 0; i < lines.size(); i++) {
            int color = sampleError != null ? 0xFFFF8A80 : (i < hintFrom ? 0xFFE4E8F0 : TEXT_HINT);
            g.drawString(this.font, lines.get(i), 8, y, color);
            y += 11;
        }
    }

    /** 按字体宽度把一行字折成多行：能在全角空格/逗号处断就从那里断，否则按宽度硬断。 */
    private void wrapLines(String text, int maxWidth, List<String> out) {
        int start = 0;
        while (start < text.length()) {
            int end = start;
            int width = 0;
            int lastBreak = -1;
            while (end < text.length()) {
                int w = this.font.width(String.valueOf(text.charAt(end)));
                if (width + w > maxWidth) break;
                width += w;
                char c = text.charAt(end);
                if (c == '　' || c == '，' || c == ' ') lastBreak = end + 1;
                end++;
            }
            if (end >= text.length()) {
                out.add(text.substring(start));
                return;
            }
            int cut = lastBreak > start + 4 ? lastBreak : end;
            out.add(text.substring(start, cut));
            start = cut;
        }
    }

    private String selectionLine() {
        SiteField field = active();
        if (!hasSelection || field == null) {
            return detailMode
                    ? "点一格 = 把" + SiteText.place() + "定在这个区块上；「返回大沙盘」可换地方"
                    : "左键点一下 = 把" + SiteText.place() + "定在这一格；双击 = 进入区块精读；"
                            + "「进入区块精读」= 落在标记处，没有标记就落在默认出生点";
        }
        int b = field.biomeIndexAtCell(selectedCellX, selectedCellY);
        List<BiomeInfo> biomes = field.biomes();
        if (b < 0 || b >= biomes.size()) {
            // 刚进精读、或窗口挪到还没采到的地方：那一格连群系都还没有，别去查调色板
            return "落点 x=" + selectedX + " z=" + selectedZ + "　这一格还在采样…";
        }
        BiomeInfo biome = biomes.get(b);
        int height = field.surfaceAtCell(selectedCellX, selectedCellY);
        boolean water = height <= field.seaLevel();
        String prefix = detailMode
                ? "区块 " + Math.floorDiv(selectedX, 16) + "," + Math.floorDiv(selectedZ, 16)
                : "方块 x=" + selectedX + " z=" + selectedZ;
        return prefix + "　落点 x=" + selectedX + " z=" + selectedZ
                + "　群系 " + biomeName(biome.id()).getString()
                + "　y≈" + height
                + (water ? (SiteText.hasPlot() ? "（水域，建筑会把地板以下补实）" : "（水域，低于海平面）") : "")
                + (detailMode ? "　·　创建时按这个区块定位，不再自动挪位" : "");
    }

    private void drawHover(GuiGraphics g, int mouseX, int mouseY) {
        SiteField field = active();
        if (field == null) return;
        int b = field.biomeIndexAtCell(hoverCellX, hoverCellY);
        if (b >= field.biomes().size()) return;
        BiomeInfo biome = field.biomes().get(b);
        int height = field.surfaceAtCell(hoverCellX, hoverCellY);
        int blockX = blockForCell(field, hoverCellX, true);
        int blockZ = blockForCell(field, hoverCellY, false);

        List<String> lines = new ArrayList<>(3);
        lines.add("群系：" + biomeName(biome.id()).getString());
        if (detailMode) {
            lines.add("区块 " + Math.floorDiv(blockX, 16) + "," + Math.floorDiv(blockZ, 16)
                    + "　方块 " + blockX + "," + blockZ);
        } else {
            lines.add("坐标 x=" + blockX + " z=" + blockZ);
        }
        lines.add(height <= field.seaLevel() ? "高度约 y=" + height + "（水面）" : "高度约 y=" + height);
        if (editMode && eyedropper) {
            lines.add("点一下 = 调这个群系的颜色");
        }

        int w = 0;
        for (String line : lines) w = Math.max(w, this.font.width(line));
        w += 10;
        int h = lines.size() * 10 + 5;
        int tx = Mth.clamp(mouseX + 12, 4, this.width - w - 4);
        int ty = Mth.clamp(mouseY + 12, 4, this.height - h - 4);
        g.fill(tx, ty, tx + w, ty + h, 0xF01A1D26);
        outline(g, tx, ty, w, h, 0xFFC9A227);
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(this.font, lines.get(i), tx + 5, ty + 4 + i * 10, 0xFFE8E8F0, false);
        }
    }

    private Component biomeName(ResourceLocation id) {
        return nameCache.computeIfAbsent(id, key -> {
            String translationKey = "biome." + key.getNamespace() + "." + key.getPath();
            if (Language.getInstance().has(translationKey)) return Component.translatable(translationKey);
            // 模组群系没带中文翻译时只能退回名字本身（下划线换成空格，至少不是裸 ID）
            return Component.literal(key.getPath().replace('_', ' '));
        });
    }

    private void drawTrimmed(GuiGraphics g, String text, int x, int y, int maxWidth, int color) {
        String shown = text;
        if (this.font.width(shown) > maxWidth) {
            while (shown.length() > 1 && this.font.width(shown + "…") > maxWidth) {
                shown = shown.substring(0, shown.length() - 1);
            }
            shown = shown + "…";
        }
        g.drawString(this.font, shown, x, y, color, true);
    }

    private void drawTrimmedRight(GuiGraphics g, String text, int right, int y, int maxWidth, int color) {
        String shown = text;
        if (this.font.width(shown) > maxWidth) {
            while (shown.length() > 1 && this.font.width(shown + "…") > maxWidth) {
                shown = shown.substring(0, shown.length() - 1);
            }
            shown = shown + "…";
        }
        g.drawString(this.font, shown, right - this.font.width(shown), y, color, true);
    }

    private void drawCentered(GuiGraphics g, String text, int centerX, int y, int color) {
        g.drawString(this.font, text, centerX - this.font.width(text) / 2, y, color, true);
    }

    private static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    // ── 坐标换算 ────────────────────────────────────────────────────────

    /** 细格 → 方块坐标（两种视图同一套算法，只是每格多少方块不一样）。 */
    private static int blockForCell(SiteField field, int cell, boolean xAxis) {
        int center = xAxis ? field.centerX() : field.centerZ();
        return center + (int) Math.round((cell - (field.fine() - 1) / 2.0) * field.step());
    }

    /** 方块坐标 → 细格（窗口中心挪动后要靠它把落点钉回原来的地块）。 */
    private static int cellOfBlock(SiteField field, int block, int center) {
        return Mth.clamp(Math.round((block - center) / (float) field.step() + (field.fine() - 1) / 2.0F),
                0, field.fine() - 1);
    }

    private boolean pickAt(SiteField field, double mouseX, double mouseY) {
        return field != null && terrain.pick(field, mouseX, mouseY, mapX, mapY, mapSize);
    }

    // ── 交互 ────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Hotspot spot = hotspotAt(mouseX, mouseY);
        if (spot != null) {
            spot.action().run();
            return true;
        }
        int hit = uiHit(mouseX, mouseY);
        if (hit != DRAG_NONE) {
            dragTarget = hit;
            applyDrag(mouseX, mouseY);
            return true;
        }
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        // 面板卡片上其他位置（标题、说明字）也吃掉，别穿过去点到后面的沙盘
        if (editMode && pointerInPanel(mouseX, mouseY)) return true;
        if (button == 0 && altDown()) {
            // 按住 Alt 点一格 = 直接取那个群系的颜色（不用先进编辑模式选吸管）
            SiteField field = active();
            if (pickAt(field, mouseX, mouseY)) {
                pickBiomeAt(terrain.pickCellX, terrain.pickCellY);
                return true;
            }
        }
        if (button == 0 || button == 1 || button == 2) {
            dragButton = button;
            dragDistance = 0.0;
            return true;
        }
        return false;
    }

    /** Alt 有没有按着（左右都算）。 */
    private boolean altDown() {
        long window = this.minecraft.getWindow().getWindow();
        return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragTarget != DRAG_NONE) {
            applyDrag(mouseX, mouseY);
            return true;
        }
        if (dragButton == 0) {
            terrain.dragRotate(dragX, dragY, false);
            dragDistance += Math.abs(dragX) + Math.abs(dragY);
            return true;
        }
        if (dragButton == 1) {
            // 右键 = 平移沙盘（精读里挪的是窗口中心，大沙盘是像素平移）
            dragView(dragX, dragY);
            return true;
        }
        if (dragButton == 2) {
            terrain.dragRotate(dragX, dragY, true);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragTarget != DRAG_NONE) {
            dragTarget = DRAG_NONE;
            // 拖完才写盘：拖动中一秒能改几十次，别每次都落文件
            SiteEditorPrefs.save();
            return true;
        }
        if (dragButton != button) return super.mouseReleased(mouseX, mouseY, button);
        dragButton = -1;
        if (button != 0 || dragDistance > CLICK_DRAG_LIMIT) return true;

        SiteField field = active();
        if (!pickAt(field, mouseX, mouseY)) return true;
        if (eyedropper) {
            // 吸管模式下这一格只用来选群系，不改选点、也不双击进精读
            pickBiomeAt(terrain.pickCellX, terrain.pickCellY);
            return true;
        }
        int column = terrain.columnOf(field, terrain.pickCellX, terrain.pickCellY);
        long now = System.currentTimeMillis();
        // 同一格上连着点两下 = 进区块精读
        if (now - lastClickAt <= DOUBLE_CLICK_MS && column == lastClickColumn && !detailMode) {
            selectAt(terrain.pickCellX, terrain.pickCellY);
            enterDetail(selectedX, selectedZ);
            lastClickAt = 0L;
            return true;
        }
        lastClickAt = now;
        lastClickColumn = column;
        selectAt(terrain.pickCellX, terrain.pickCellY);
        return true;
    }

    /** 吸管：把这一格的群系设为调色目标（格子没采到就什么都不做）。 */
    private void pickBiomeAt(int cellX, int cellY) {
        SiteField field = active();
        if (field == null) return;
        int b = field.biomeIndexAtCell(cellX, cellY);
        List<BiomeInfo> biomes = field.biomes();
        if (b < 0 || b >= biomes.size()) return;
        BiomeInfo info = biomes.get(b);
        focusBiome(info.id(), info.rgb());
        System.out.println("[yardsite] 选址取色：" + info.id() + " 现色 #"
                + SiteEditorPrefs.hex(naturalRgb(info.id())));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (editMode && pointerInPanel(mouseX, mouseY)) return false;
        if (active() != null && scrollY != 0.0) {
            terrain.zoomBy(scrollY > 0.0 ? 1 : -1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_R && active() != null) {
            terrain.resetView(active());
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && editMode) {
            // ESC 分层退：先收调色面板 → 再放下吸管 → 最后才关整个界面
            if (colorTarget != null || panelTab == TAB_COLOR) {
                colorTarget = null;
                panelTab = TAB_VIEW;
                terrain.setHighlightBiome(null);
                return true;
            }
            if (eyedropper) {
                eyedropper = false;
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void selectAt(int cellX, int cellY) {
        SiteField field = active();
        if (field == null) return;
        selectedCellX = cellX;
        selectedCellY = cellY;
        selectedX = blockForCell(field, cellX, true);
        selectedZ = blockForCell(field, cellY, false);
        hasSelection = true;
    }

    private void clearSelection() {
        hasSelection = false;
        if (detailMode && planner != null) {
            // 小沙盘里创建就钉在选中的那一格，不能没有落点：清掉等于退回窗口正中
            int center = planner.field().fine() / 2;
            selectAt(center, center);
        }
        rebuildButtons();
    }

    private void proceed() {
        long seed = context.options().seed();
        // 记下「这个建世界界面已经处理过」，再次触发 onCreate（比如数据包校验失败后重试）不再弹图
        SiteSelection.setPass(parent, seed);
        // 只有精读里能走到这里，落点必定钉在区块上，服务端不再就近挪位
        SiteSelection.offer(selectedX, selectedZ, seed, true);
        System.out.println("[yardsite] 选址确认：x=" + selectedX + ", z=" + selectedZ
                + "，按区块精确定位，种子 " + seed);
        handoffToReveal();
        cancelled = true;
        onProceed.run();
    }

    /**
     * 把精读场、当前视角和沙盘在屏幕上的位置交给进世界前那段落成动画。
     *
     * <p>动画开场要<b>接着同一张图</b>往下推镜头，所以除了相机快照，还得给它精读那帧的图区
     * （{@code mapX/mapY/mapSize}）：正交画面不是压在屏幕正中，而是压在这块区域的中心。
     * 交给出去之后要把 {@code planner} 摘干净，不然 {@link #removed()} 会替动画界面把线程关掉。
     */
    private void handoffToReveal() {
        if (!YardSiteConfig.SITE_REVEAL_ANIM.get()) return;
        ChunkSitePlanner reveal = planner;
        if (reveal == null) return;
        planner = null;
        SiteRevealHandoff.offer(reveal, terrain.snapshot(), mapX, mapY, mapSize,
                selectedX, selectedZ);
    }

    private void goBack() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        goBack();
    }

    @Override
    public void removed() {
        cancelled = true;
        if (editMode) SiteEditorPrefs.save();
        if (worker != null) worker.cancel(true);
        samplerExecutor.shutdownNow();
        if (planner != null) planner.close();
    }
}
