package dev.yard.site.structure;

import dev.yard.site.YardSiteLog;
import dev.yard.site.api.BlueprintLine;
import dev.yard.site.api.SiteLink;
import dev.yard.site.api.SitePlan;
import dev.yard.site.api.SiteLinks;
import dev.yard.site.schematic.Schematic;
import dev.yard.site.schematic.SchematicHiddenLines;
import dev.yard.site.schematic.SchematicOutline;
import dev.yard.site.schematic.SchematicTransform;
import dev.yard.site.worldgen.SurfaceProbe;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 「结构模式」的联动实现：把配置里那份投影文件当成选址目标。
 *
 * <p>不只是线路由：占地尺寸来自结构的长宽、出生点取结构里站得住的那一格、落成镜头画的是它的外壳，
 * 进世界后由 {@link SchematicPlacer} 把方块铺下去。所以装一个 yardsite + 一份
 * {@code .litematic} 就是「开局在这栋建筑里」，不需要别的 mod，也不需要投影 mod 当代替品。
 *
 * <h2>为什么也走 {@link SiteLink} 这套接口</h2>
 * 选区图、落成动画、出生点规划本来都只认「联动方」，这套接口已经解决了"尺寸要在建世界之前拿到"
 * "计划要在服务端算""线框要在客户端画"这些时序问题。结构模式只是又一种联动方，
 * 于是它自动继承了所有既有行为（独立安装/和庭院一起装/别的联动方将来接进来都一样）。
 */
public final class SchematicSite implements SiteLink {

    /** 出生点候选的缓存（一次扫描是整份体素，不缓存会被选区图每帧重算）。 */
    private static Schematic spawnCachedFor;
    private static Schematic.Spawn spawnCached;

    /** 由 {@code SiteLinks} 在配置打开时装进注册表。 */
    public static void install() {
        SiteLinks.register(new SchematicSite());
    }

    @Override
    public String id() {
        return "yardsite-structure";
    }

    @Override
    public String placeLabel() {
        return "结构";
    }

    @Override
    public String placeLabelKey() {
        return "yardsite.place.structure";
    }

    @Override
    public @Nullable SitePlan.Plot plot() {
        Schematic schematic = SchematicStructure.get();
        if (schematic == null) return null;
        SchematicTransform transform = SchematicStructure.transform();
        return new SitePlan.Plot(
                transform.width(schematic.sizeX, schematic.sizeZ),
                transform.depth(schematic.sizeX, schematic.sizeZ),
                0,
                schematic.sizeY);
    }

    @Override
    public @Nullable SitePlan plan(ServerLevel level, SurfaceProbe probe,
                                   int siteX, int siteZ, boolean exact) {
        Schematic schematic = SchematicStructure.get();
        SitePlan.Plot plot = plot();
        if (schematic == null || plot == null) return null;
        SchematicTransform transform = SchematicStructure.transform();

        int width = plot.width();
        int depth = plot.depth();
        int originX = siteX - width / 2 + SchematicStructure.offsetX();
        int originZ = siteZ - depth / 2 + SchematicStructure.offsetZ();
        int baseY = groundLevel(probe, originX, originZ, width, depth) + SchematicStructure.offsetY();

        Schematic.Spawn spawn = spawnCandidate(schematic);
        int spawnX = originX + width / 2;
        int spawnZ = originZ + depth / 2;
        int spawnY = baseY + 1;
        if (spawn != null) {
            spawnX = originX + transform.blockX(spawn.x(), spawn.z(), schematic.sizeX, schematic.sizeZ);
            spawnZ = originZ + transform.blockZ(spawn.x(), spawn.z(), schematic.sizeX, schematic.sizeZ);
            spawnY = baseY + spawn.y();
        }

        YardSiteLog.debug("结构落点：占地 " + width + "×" + depth + "（高 " + plot.height()
                + "，" + transform.describe() + "），原点 " + originX + "," + baseY + "," + originZ
                + "，出生点 " + spawnX + "," + spawnY + "," + spawnZ
                + (spawn == null ? "（结构里没有站得住的格子，落在占地中心）"
                        : "（离门 " + spawn.front() + " 那一侧最近）"));
        return new SitePlan(siteX, siteZ, originX, baseY, originZ,
                spawnX, spawnY, spawnZ, probe.seaLevel(), 0, plot);
    }

    @Override
    public void onPlanned(ServerLevel level, SitePlan plan) {
        if (SchematicStructure.place()) {
            SchematicPlacer.arm(plan);
        }
    }

    /**
     * 落成动画的线框：结构折角棱的<b>全部候选</b>（已合并成长直线）。
     *
     * <p>直接给线段而不是盒子：投影文件里本来就有精确形状，画成盒子等于把一栋房子退化成几个方块，
     * 玩家看不出自己要住进去的是什么。
     *
     * <p>这份列表只用来定"扫到多高、大概多少条"（时间轴），真正画哪些线由渲染阶段按机位消隐
     * （{@link #hiddenLinesFor}）。
     */
    @Override
    public List<BlueprintLine> blueprintLines(SitePlan plan) {
        Schematic schematic = SchematicStructure.get();
        if (schematic == null || plan.plot() == null) return List.of();
        SchematicOutline outline = SchematicStructure.creaseEdgesOf(schematic);
        if (outline.count == 0) return List.of();
        SchematicTransform transform = SchematicStructure.transform();
        int sx = schematic.sizeX;
        int sz = schematic.sizeZ;
        int ox = plan.originX();
        int oy = plan.originY();
        int oz = plan.originZ();

        List<BlueprintLine> lines = new ArrayList<>(outline.count);
        for (int i = 0; i < outline.count; i++) {
            int base = i * 6;
            float x1 = outline.segs[base];
            float y1 = outline.segs[base + 1];
            float z1 = outline.segs[base + 2];
            float x2 = outline.segs[base + 3];
            float y2 = outline.segs[base + 4];
            float z2 = outline.segs[base + 5];
            lines.add(new BlueprintLine(
                    ox + transform.x(x1, z1, sx, sz), oy + y1, oz + transform.z(x1, z1, sx, sz),
                    ox + transform.x(x2, z2, sx, sz), oy + y2, oz + transform.z(x2, z2, sx, sz)));
        }
        return lines;
    }

    /**
     * 按机位消隐用的对象：这份结构的全部折角棱 + 反变换回去的相机。
     *
     * <p>返回 {@code null} = 没开结构模式 / 读不到文件，渲染阶段就照 {@link #blueprintLines} 给的线画。
     */
    /** 面表缓存：同一次落座只建一次（一栋房子两万个面，建一次几十毫秒，不能每帧建）。 */
    private static SurfaceMesh surfaceCached;
    private static SitePlan surfaceCachedFor;

    /**
     * 落成动画要画的"面"（见 {@link SurfaceMesh}）。
     *
     * <p>返回 {@code null} = 没开结构模式 / 读不到文件 / 结构是空的，渲染阶段就不画面，走原来的线框。
     */
    public static SurfaceMesh surfaceFor(SitePlan plan) {
        Schematic schematic = SchematicStructure.get();
        if (schematic == null || plan == null || plan.plot() == null) return null;
        if (surfaceCachedFor == plan && surfaceCached != null) return surfaceCached;
        SurfaceMesh mesh;
        try {
            mesh = SurfaceMesh.build(schematic, SchematicStructure.transform(),
                    plan.originX(), plan.originY(), plan.originZ());
        } catch (RuntimeException broken) {
            // 建面表失败就当作"没有面"：退回线框，别让落成动画整段挂掉
            YardSiteLog.debug("落成面表建不起来，退回线框：" + broken);
            surfaceCachedFor = plan;
            surfaceCached = null;
            return null;
        }
        surfaceCachedFor = plan;
        surfaceCached = mesh.count == 0 ? null : mesh;
        return surfaceCached;
    }

    public static SchematicHiddenLines hiddenLinesFor(SitePlan plan) {
        Schematic schematic = SchematicStructure.get();
        if (schematic == null || plan == null || plan.plot() == null) return null;
        return new SchematicHiddenLines(schematic, SchematicStructure.transform(),
                plan.originX(), plan.originY(), plan.originZ(),
                SchematicStructure.creaseEdgesOf(schematic));
    }

    @Override
    public boolean placesOwnStructure() {
        return SchematicStructure.place();
    }

    /**
     * 出口在世界哪一侧（0=+x，1=-x，2=+z，3=-z），非结构模式返回 -1。
     *
     * <p>落成镜头要靠它决定从哪边绕进去：旋转 90° 之后「门在 -z」这句话就不成立了，
     * 照着原来的前墙去推镜头会从侧墙里穿出来。
     */
    public static int frontSide() {
        Schematic schematic = SchematicStructure.get();
        if (schematic == null) return -1;
        Schematic.Spawn spawn = spawnCandidate(schematic);
        if (spawn == null) return -1;
        return SchematicStructure.transform().front(spawn.front());
    }

    private static Schematic.Spawn spawnCandidate(Schematic schematic) {
        Schematic.Spawn cachedSpawn = spawnCached;
        if (spawnCachedFor == schematic && cachedSpawn != null) return cachedSpawn;
        Schematic.Spawn found = schematic.spawnCandidate();
        spawnCachedFor = schematic;
        spawnCached = found == null ? new Schematic.Spawn(-1, -1, -1, 0) : found;
        return found == null ? null : found;
    }

    /**
     * 占地范围内的地面高度：按格抽样取<b>中位数</b>，不是最高点。
     *
     * <p>取最高点等于把整栋楼架到占地里最高的那块石头或那棵树上；取中位数只当"这一片的平均层次"，
     * 少数岩柱、凹坑、倒木都当离群值忽略，{@link SchematicPlacer} 整平时挖填也最少。
     *
     * <p>落在水里就抬到海平面之上：水下硬盖会变成一座泡在水里的房子。
     */
    private static int groundLevel(SurfaceProbe probe, int originX, int originZ, int width, int depth) {
        int step = Math.max(2, Math.min(width, depth) / 8);
        int[] samples = new int[((width / step) + 2) * ((depth / step) + 2)];
        int n = 0;
        for (int dx = 0; dx < width; dx += step) {
            for (int dz = 0; dz < depth; dz += step) {
                int h = probe.surfaceAt(originX + dx, originZ + dz);
                if (n < samples.length) samples[n++] = h;
            }
        }
        if (n == 0) return probe.seaLevel() + 1;
        java.util.Arrays.sort(samples, 0, n);
        int ground = samples[(n - 1) / 2];
        if (ground <= probe.seaLevel()) ground = probe.seaLevel() + 1;
        return ground;
    }
}
