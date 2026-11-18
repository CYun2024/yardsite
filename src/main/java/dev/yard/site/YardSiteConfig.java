package dev.yard.site;

import dev.yard.site.structure.StructureMirror;
import dev.yard.site.structure.StructureRotation;

/**
 * 群系选区的配置键：开关、范围、采样线程、出生点区块半径、落成动画、投影结构。
 *
 * <p>这里只声明键和取值类型，具体的 TOML 规格由平台层提供（NeoForge 见
 * {@code dev.yard.site.platform.neoforge.NeoForgeConfig}，Fabric 将来给同一组键）。默认值、
 * 范围和注释都跟着平台规格走，所以不同加载器生成的配置文件内容一致。
 */
public final class YardSiteConfig {

    /** 平台层实现的取值后端；配置未加载时保持 {@link NotLoaded}，读取会抛 {@link IllegalStateException}。 */
    public interface Backend {
        Object get(String key);

        boolean isLoaded();
    }

    private static volatile Backend backend = new NotLoaded();

    public static void install(Backend implementation) {
        backend = implementation;
    }

    public static boolean isLoaded() {
        return backend.isLoaded();
    }

    public static final class BoolValue {
        private final String key;

        BoolValue(String key) {
            this.key = key;
        }

        public boolean get() {
            return (Boolean) backend.get(key);
        }
    }

    public static final class IntValue {
        private final String key;

        IntValue(String key) {
            this.key = key;
        }

        public int get() {
            return ((Number) backend.get(key)).intValue();
        }
    }

    public static final class DoubleValue {
        private final String key;

        DoubleValue(String key) {
            this.key = key;
        }

        public double get() {
            return ((Number) backend.get(key)).doubleValue();
        }
    }

    public static final class StringValue {
        private final String key;

        StringValue(String key) {
            this.key = key;
        }

        public String get() {
            return (String) backend.get(key);
        }
    }

    public static final class EnumValue<E extends Enum<E>> {
        private final String key;
        private final Class<E> type;

        EnumValue(String key, Class<E> type) {
            this.key = key;
            this.type = type;
        }

        public E get() {
            return type.cast(backend.get(key));
        }
    }

    public static final BoolValue SPAWN_SITE_SELECT = new BoolValue("site.spawnSiteSelect");
    public static final IntValue SITE_SELECT_RADIUS = new IntValue("site.selectRadius");
    public static final IntValue SITE_SELECT_RADIUS_EPIC = new IntValue("site.selectRadiusEpic");
    public static final IntValue SITE_SANDBOX_COLUMNS = new IntValue("site.sandboxColumns");
    public static final IntValue SITE_SAMPLE_THREADS = new IntValue("site.sampleThreads");
    public static final IntValue SITE_SPAWN_CHUNK_RADIUS = new IntValue("site.spawnChunkRadius");
    public static final BoolValue SITE_EARLY_ENTRY = new BoolValue("site.earlyEntry");
    public static final BoolValue SITE_INSTANT_REVEAL = new BoolValue("site.instantReveal");
    public static final BoolValue SITE_REVEAL_ANIM = new BoolValue("site.revealAnim");
    public static final DoubleValue REVEAL_LINE_WIDTH = new DoubleValue("site.revealLineWidth");
    public static final BoolValue REVEAL_LINE_GLOW = new BoolValue("site.revealLineGlow");
    public static final BoolValue REVEAL_SURFACE = new BoolValue("site.revealSurface");
    public static final BoolValue REVEAL_SURFACE_SCAN = new BoolValue("site.revealSurfaceScan");
    public static final IntValue REVEAL_FADE_DELAY_MS = new IntValue("site.revealFadeDelayMs");

    public static final BoolValue STRUCTURE_ENABLED = new BoolValue("structure.enabled");
    public static final StringValue STRUCTURE_FILE = new StringValue("structure.file");
    public static final BoolValue STRUCTURE_SHOW_INFO = new BoolValue("structure.showInfo");
    public static final EnumValue<StructureRotation> STRUCTURE_ROTATION =
            new EnumValue<>("structure.rotation", StructureRotation.class);
    public static final EnumValue<StructureMirror> STRUCTURE_MIRROR =
            new EnumValue<>("structure.mirror", StructureMirror.class);
    public static final IntValue STRUCTURE_OFFSET_X = new IntValue("structure.offsetX");
    public static final IntValue STRUCTURE_OFFSET_Y = new IntValue("structure.offsetY");
    public static final IntValue STRUCTURE_OFFSET_Z = new IntValue("structure.offsetZ");
    public static final BoolValue STRUCTURE_PLACE = new BoolValue("structure.place");
    public static final BoolValue STRUCTURE_FLATTEN = new BoolValue("structure.flatten");
    public static final IntValue STRUCTURE_MAX_VOXELS = new IntValue("structure.maxVoxels");
    public static final IntValue STRUCTURE_MAX_LINES = new IntValue("structure.maxLines");

    private YardSiteConfig() {}

    private static final class NotLoaded implements Backend {
        @Override
        public Object get(String key) {
            throw new IllegalStateException("yardsite config not loaded");
        }

        @Override
        public boolean isLoaded() {
            return false;
        }
    }
}