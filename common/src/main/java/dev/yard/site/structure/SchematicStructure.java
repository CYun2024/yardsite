package dev.yard.site.structure;

import dev.yard.site.YardSiteConfig;
import dev.yard.site.YardSiteLog;
import dev.yard.site.schematic.Schematic;
import dev.yard.site.schematic.SchematicOutline;
import dev.yard.site.schematic.SchematicReader;
import dev.yard.site.schematic.SchematicTransform;
import dev.yard.site.platform.Platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 配置里那份投影文件：找到、读进内存、记住失败原因。
 *
 * <p>读一次就缓存，靠<b>文件修改时间</b>判过期：整合包作者调完结构直接回标题界面再建一次世界就能看到新版本，
 * 不用重启游戏。缓存只有一份，因为同一时刻只会用一份结构。
 *
 * <h2>没写路径时自动认一份</h2>
 * {@code structure.enabled = true} 但 {@code structure.file} 留空，是整合包作者最常见的手滑：
 * 结构丢进 {@code config/yardsite/structures/} 就以为会生效。所以这里退一步——去那个目录里
 * <b>按文件名排第一个</b>能认的格式（{@code .litematic} / {@code .nbt} / {@code .schem}）用；
 * 一个都没有才算"没设置"，界面提示「未设置结构文件」而不是报一堆读文件失败。
 *
 * <p>读失败（路径写错、超出体积上限、格式不认识）不会崩：{@link #get()} 返回 {@code null}，
 * 整个 mod 退回「没有结构」的样子，原因留在 {@link #lastError()} 里给日志和界面用。
 */
public final class SchematicStructure {

    private static String cachedKey = "";
    private static long cachedMtime;
    private static Schematic cached;
    private static String lastError = "";
    /** 打开了结构模式，但一份结构文件都找不到（配置是空的、目录里也是空的）。 */
    private static boolean missingFile;
    /** 自动挑中的那份：目录里加了新文件、或这份被删掉了就重新挑。 */
    private static Path autoPick;
    private static long autoPickCheckedAt;
    /**
     * 上次问文件修改时间的时刻。
     *
     * <p>{@link #get()} 会被<b>每帧</b>调到（选区图那块占地框是按 plot() 画的），
     * 每帧去 stat 一次文件虽然不贵，但没意义：文件不会在一帧之内变。这里一秒最多看一眼。
     */
    private static long checkedAt;
    /**
     * 本局临时的旋转/镜像覆盖（-1 = 用配置文件）。界面上的 T / M 改这两个值，
     * 不落盘：下次进游戏还是配置里那个朝向。客户端和服务端在单人世界里是同一个 JVM，
     * 所以规划、线框、铺方块看到的是同一个朝向。
     */
    private static int rotationOverride = -1;
    private static int mirrorOverride = -1;

    private SchematicStructure() {}

    /** 开关打开了吗（配置没加载完时按关闭算，别在建世界流程里抛异常）。 */
    public static boolean enabled() {
        try {
            return YardSiteConfig.STRUCTURE_ENABLED.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    /** 自动认文件的地方：{@code config/yardsite/structures/}（整合包作者只管往里丢）。 */
    public static Path structuresDir() {
        return Platform.configDir().resolve("yardsite").resolve("structures");
    }

    /**
     * 确保 {@link #structuresDir()} 存在（启动时调一次）。
     *
     * <p>目录不存在的话，「把文件放进 config/yardsite/structures/」这句提示就没法照做——
     * 玩家得先自己新建两层面包。建不出来也不报错，只是自动认文件那步会照旧找不到东西。
     */
    public static void ensureStructuresDir() {
        try {
            Files.createDirectories(structuresDir());
        } catch (Exception e) {
            YardSiteLog.debug(() -> "建不出结构目录 " + structuresDir() + "（" + e + "）");
        }
    }

    /** 配置里写的路径（绝对路径原样用，相对路径相对游戏目录）；没写就去目录里挑第一个。 */
    public static Path file() {
        String value;
        try {
            value = YardSiteConfig.STRUCTURE_FILE.get();
        } catch (IllegalStateException notLoaded) {
            return null;
        }
        if (value != null && !value.isBlank()) {
            Path path = Path.of(value.trim());
            return path.isAbsolute() ? path : Platform.gameDir().resolve(path);
        }
        return autoPick();
    }

    /**
     * 从 {@link #structuresDir()} 里按文件名挑第一个能读的。
     *
     * <p>一秒最多扫一次目录（{@link #get()} 本来就节流到一秒一次，这里只是兜住别的调用方）；
     * 挑中的文件还在就继续用它——选中一份结构、进世界、再回标题界面，用的还是同一份，
     * 不会因为半路丢进来一个名字更靠前的文件就把这次落座换了。
     */
    private static Path autoPick() {
        long now = System.currentTimeMillis();
        if (now - autoPickCheckedAt < 1000L && (autoPick == null || Files.isRegularFile(autoPick))) {
            return autoPick;
        }
        autoPickCheckedAt = now;
        Path best = null;
        try (Stream<Path> list = Files.list(structuresDir())) {
            best = list.filter(Files::isRegularFile)
                    .filter(SchematicReader::supported)
                    .min(Comparator.comparing(
                            p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .orElse(null);
        } catch (Exception ignored) {
            // 目录还不存在（从没建过 world/config）是常态，不用报错；
            // 连游戏目录都没查到（配置还没初始化）也按"没有文件"算，别在界面里抛异常
        }
        if (best != null && !best.equals(autoPick)) {
            Path picked = best;
            YardSiteLog.debug(() -> "structure.file 是空的，自动用目录里第一份结构：" + picked.getFileName());
        }
        autoPick = best;
        return best;
    }

    /** 当前这份结构；没开、没配、读不了都是 {@code null}。 */
    public static synchronized Schematic get() {
        if (!enabled()) return null;
        long now = System.currentTimeMillis();
        // 一秒内直接给上次的结果（成功或失败都算）：读坏文件时也别每帧重试一遍
        if (now - checkedAt < 1000L) return cached;
        checkedAt = now;
        Path path;
        try {
            path = file();
        } catch (RuntimeException badPath) {
            // 路径里带了 Windows 不认的字符（InvalidPathException 之类）：别让界面每帧抛一次
            cached = null;
            cachedKey = "";
            missingFile = false;
            lastError = "structure.file 的路径写法不对："
                    + (badPath.getMessage() == null ? badPath.toString() : badPath.getMessage());
            return null;
        }
        if (path == null) {
            cached = null;
            cachedKey = "";
            missingFile = true;
            lastError = "未设置结构文件";
            return null;
        }
        missingFile = false;
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(path).toMillis();
        } catch (Exception e) {
            cached = null;
            cachedKey = "";
            lastError = "找不到文件 " + path;
            return null;
        }
        int cap = YardSiteConfig.STRUCTURE_MAX_VOXELS.get();
        String key = path + "|" + cap;
        if (cached != null && key.equals(cachedKey) && mtime == cachedMtime) return cached;

        long startedAt = System.currentTimeMillis();
        try {
            cached = SchematicReader.read(path, cap);
            cachedKey = key;
            cachedMtime = mtime;
            lastError = "";
            YardSiteLog.debug("读到结构（" + SchematicReader.formatName(path) + "）："
                    + cached.describe() + "，折角棱 " + creaseEdgesOf(cached).rawCount + " 条合并成 "
                    + creaseEdgesOf(cached).count + " 条，耗时 "
                    + (System.currentTimeMillis() - startedAt) + " ms");
        } catch (Throwable t) {
            cached = null;
            cachedKey = "";
            lastError = t.getMessage() == null ? t.toString() : t.getMessage();
            YardSiteLog.warn("结构文件读不了（" + path + "）：" + lastError);
        }
        return cached;
    }

    /**
     * 这次落座用的旋转（0～3 个 90°）。默认读配置，界面上按 T 可以在<b>本局</b>临时改，
     * 不改配置文件——调朝向时不用反复改 toml、重启游戏、再建一次世界。
     */
    public static int rotationTurns() {
        if (rotationOverride >= 0) return rotationOverride;
        try {
            return YardSiteConfig.STRUCTURE_ROTATION.get().turns();
        } catch (IllegalStateException notLoaded) {
            return 0;
        }
    }

    /** 镜像：返回 {@code {mirrorX, mirrorZ}}。 */
    public static boolean[] mirrorFlags() {
        int index = mirrorOverride >= 0 ? mirrorOverride : configuredMirror();
        return new boolean[]{(index & 1) != 0, (index & 2) != 0};
    }

    /** 旋转/镜像（配置 + 本局临时改动）。 */
    public static SchematicTransform transform() {
        boolean[] mirror = mirrorFlags();
        return new SchematicTransform(rotationTurns(), mirror[0], mirror[1]);
    }

    /** 界面上按一下：顺时针转 90°。 */
    public static void cycleRotation() {
        rotationOverride = (rotationTurns() + 1) & 3;
        YardSiteLog.debug("结构朝向（本局）：" + transform().describe());
    }

    /** 界面上按一下：在「不镜像 → X → Z → XZ」里轮。 */
    public static void cycleMirror() {
        int index = (mirrorFlags()[0] ? 1 : 0) | (mirrorFlags()[1] ? 2 : 0);
        mirrorOverride = (index + 1) & 3;
        YardSiteLog.debug("结构朝向（本局）：" + transform().describe());
    }

    /** 配置文件里那一项，换成 0～3 的位标志（1 = X，2 = Z）。 */
    private static int configuredMirror() {
        try {
            StructureMirror mirror = YardSiteConfig.STRUCTURE_MIRROR.get();
            return (mirror.mirrorX() ? 1 : 0) | (mirror.mirrorZ() ? 2 : 0);
        } catch (IllegalStateException notLoaded) {
            return 0;
        }
    }

    public static int maxLines() {
        return YardSiteConfig.STRUCTURE_MAX_LINES.get();
    }

    /**
     * 这份结构的<b>全部折角棱</b>（合并成长直线，不判遮挡）。
     *
     * <p>落成动画、调试日志都走这里：参数只在一个地方取，缓存由 {@link Schematic} 自己管。
     * 谁挡谁不挡交给 {@link dev.yard.site.schematic.SchematicHiddenLines} 按机位算。
     */
    public static SchematicOutline creaseEdgesOf(Schematic schematic) {
        return schematic.creaseEdges(maxLines());
    }

    public static int offsetX() {
        return YardSiteConfig.STRUCTURE_OFFSET_X.get();
    }

    public static int offsetY() {
        return YardSiteConfig.STRUCTURE_OFFSET_Y.get();
    }

    public static int offsetZ() {
        return YardSiteConfig.STRUCTURE_OFFSET_Z.get();
    }

    public static boolean place() {
        return YardSiteConfig.STRUCTURE_PLACE.get();
    }

    public static boolean flatten() {
        return YardSiteConfig.STRUCTURE_FLATTEN.get();
    }

    /** 最近一次失败的原因（空串 = 没问题）。 */
    public static String lastError() {
        return lastError;
    }

    /**
     * 开了结构模式，但一份结构文件都没有（配置空着、{@code config/yardsite/structures/} 也空着）。
     *
     * <p>界面靠它换一句更实说的话：「未设置结构文件」+ 该把文件放哪，
     * 而不是把「读不了」这种听起来像文件坏了的字摆给玩家。
     */
    public static boolean missing() {
        return missingFile;
    }

    /** 丢掉缓存（改完文件重新读）。 */
    public static synchronized void invalidate() {
        cached = null;
        cachedKey = "";
        cachedMtime = 0L;
        checkedAt = 0L;
        autoPickCheckedAt = 0L;
    }
}
