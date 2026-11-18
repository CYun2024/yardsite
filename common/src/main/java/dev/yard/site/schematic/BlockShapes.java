package dev.yard.site.schematic;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 方块形状表：把一个方块状态（{@code id} + 属性）翻成<b>局部 0..1 坐标里的若干长方体</b>。
 *
 * <h2>为什么需要它</h2>
 * 只按「一格一个立方体」画线，楼梯、半砖、活板门、玻璃板全变成整格的方块轮廓：一段楼梯画成
 * 一摞完整的立方体，一面半砖墙画成一排方格，线框立刻从"房子"退化成"方块堆"。更要紧的是
 * <b>遮挡也不对</b>——半格高的台阶按整格挡视线，它后面那一大片线就全没了。
 *
 * <p>所以几何要细到盒子：楼梯 = 底下半格 + 上面半格的一块，活板门 = 3/16 格厚的板，
 * 玻璃板 = 中间的薄片。线框按盒子的棱出，遮挡按盒子求交，两边用的是同一份几何。
 *
 * <h2>两个来源</h2>
 * <ol>
 *   <li><b>游戏里：</b>平台层通过 {@link #setSource(ShapeSource)} 装一个 Minecraft 实现，
 *       直接问 {@code BlockState#getShape(...)}。它对<b>任何已注册方块</b>都成立（包括 mod 的
 *       楼梯/半砖/栅栏/墙/板），状态属性也由方块自己解释，不用逐类写规则。</li>
 *   <li><b>离线/兜底：</b>本类的手写表。{@code tools/schematic-check} 脱离游戏跑、
 *       或者 {@code getShape} 没给出可用形状（空形状的贴片、方块没注册）时用它。</li>
 * </ol>
 * 之所以保留手写表而不是去解析资源包：{@code blockstates}/{@code models} 是游戏渲染管线的
 * 输入（旋转、UV 锁定、multipart……），搬过来要写上千行；而 {@code getShape} 就是方块自己
 * 报给游戏的形状，已经包含这些结果。表只覆盖"形状不是满格"的常见几类，够离线自检和兜底。
 *
 * <p>坐标约定和 Minecraft 一致：x 向东、y 向上、z 向南，{@code facing=north} 指 -z。
 * 纯 Java，不 import 任何 Minecraft 类；{@code tools/schematic-check} 能直接喂投影文件验。
 */
public final class BlockShapes {

    /**
     * 外部几何来源。游戏里由平台层装成 {@code BlockState#getShape}；离线工具不装，走手写表。
     */
    public interface ShapeSource {
        /**
         * @return 每盒 6 个 float（x0,y0,z0,x1,y1,z1，局部 0..1 坐标）；
         *         {@code null} = 不认识/形状不可用，交回手写表
         */
        float[] boxes(String id, Map<String, String> props);
    }

    /** 满格立方体。 */
    public static final float[] FULL = {0F, 0F, 0F, 1F, 1F, 1F};

    /**
     * 整格跳过：十字模型的花草藤蔓、水、空气。
     *
     * <p>不是"零尺寸盒子"——那还会出现在射线求交里。这类方块按<b>不存在</b>处理：
     * 不产生棱，也不挡视线。一片草、几朵花线框化之后只是一堆噪点，挡在房子前面还会把轮廓切碎。
     */
    public static final float[] NONE = new float[0];

    private static final float T = 3F / 16F;          // 活板门/门的厚度
    private static final float P = 1F / 16F;          // 一块薄片

    /** 缓存：同一份结构里同一个状态会出现几千次，形状只算一次。 */
    private static final Map<String, float[]> CACHE = new HashMap<>(256);

    private static volatile ShapeSource source;

    private BlockShapes() {
    }

    /** 装/换几何来源。已经算过的形状作废（换来源前可能缓存了旧来源的结果）。 */
    public static void setSource(ShapeSource implementation) {
        synchronized (CACHE) {
            CACHE.clear();
            source = implementation;
        }
    }

    /**
     * 这个方块状态占哪些盒子。
     *
     * @return 每盒 6 个 float（x0,y0,z0,x1,y1,z1），局部 0..1 坐标；可能是 {@link #FULL}、
     *         {@link #NONE} 或任意盒数
     */
    public static float[] of(String id, Map<String, String> props) {
        String key = keyOf(id, props);
        synchronized (CACHE) {
            float[] cached = CACHE.get(key);
            if (cached != null) return cached;
        }
        float[] shape = compute(id, path(id), props == null ? Map.of() : props);
        synchronized (CACHE) {
            CACHE.put(key, shape);
        }
        return shape;
    }

    /** 缓存 key：id + 全部属性（属性会影响形状，宁可多存几份）。 */
    private static String keyOf(String id, Map<String, String> props) {
        if (props == null || props.isEmpty()) return id;
        StringBuilder sb = new StringBuilder(id).append('|');
        props.forEach((k, v) -> sb.append(k).append('=').append(v).append(';'));
        return sb.toString();
    }

    /** 去掉命名空间：{@code minecraft:cherry_stairs} → {@code cherry_stairs}。 */
    private static String path(String id) {
        int colon = id.indexOf(':');
        return (colon < 0 ? id : id.substring(colon + 1)).toLowerCase(Locale.ROOT);
    }

    // ── 分派 ────────────────────────────────────────────────────────────

    private static float[] compute(String id, String p, Map<String, String> props) {
        // 十字花草没有体积：不产生盒子（不挡视线），线由 SchematicBoxes 单独加进来（见 crossLines）
        if (isCrossPlant(p)) return NONE;
        if (isFlatModel(p)) return NONE;
        // 花盆：盆 + 盆里那棵，比 getShape（只到盆沿）看着更像一盆花
        if (p.equals("flower_pot") || p.startsWith("potted_")) return potted();

        ShapeSource live = source;
        if (live != null) {
            try {
                float[] boxes = live.boxes(id, props);
                if (boxes != null) return boxes;
            } catch (RuntimeException | LinkageError broken) {
                // 这个方块自己的形状抛了：退回手写表，别让整份结构读不出来
            }
        }

        if (p.endsWith("_slab")) return slab(props);
        if (p.endsWith("_stairs")) return stairs(props);
        if (p.endsWith("_trapdoor")) return trapdoor(props);
        if (p.endsWith("_fence_gate")) return fenceGate(props);
        if (p.endsWith("_fence")) return fence(props);
        if (p.endsWith("_wall") && !p.endsWith("_wall_sign") && !p.endsWith("_wall_banner")) {
            return wall(props);
        }
        if (p.endsWith("_door")) return door(props);
        if (p.endsWith("_pane") || p.equals("iron_bars") || p.endsWith("_bars")) return pane(props);
        if (p.endsWith("_carpet") || p.equals("moss_carpet")) return carpet();
        // 花瓣/野花铺在地上：原版是贴地的一薄层，给 2/16 就够，线框里也不会变成一团点
        if (p.endsWith("petals") || p.equals("wildflowers")) return box(0F, 0F, 0F, 1F, 0.125F, 1F);
        if (p.equals("snow")) return snow(props);
        if (p.endsWith("_button")) return button(props);
        if (p.endsWith("_pressure_plate")) return pressurePlate();
        if (p.endsWith("_wall_sign") || p.endsWith("_sign")) return sign(props);
        if (p.endsWith("_wall_banner") || p.endsWith("_banner")) return banner(props);
        if (p.endsWith("_torch")) return torch(props);
        if (p.endsWith("_lantern") || p.equals("lantern")) return lantern(props);
        if (p.equals("chain")) return box(0.40625F, 0F, 0.40625F, 0.59375F, 1F, 0.59375F);
        if (p.equals("end_rod")) return box(0.375F, 0F, 0.375F, 0.625F, 1F, 0.625F);
        if (p.equals("chest") || p.equals("trapped_chest") || p.equals("ender_chest")) {
            return box(0.0625F, 0F, 0.0625F, 0.9375F, 0.875F, 0.9375F);
        }
        if (p.equals("barrel") || p.equals("beehive")) return full();
        if (p.equals("hopper")) return concat(box(0.25F, 0F, 0.25F, 0.75F, 0.625F, 0.75F),
                box(0F, 0.625F, 0F, 1F, 1F, 1F));
        if (p.equals("campfire") || p.equals("soul_campfire")) return box(0F, 0F, 0F, 1F, 0.4375F, 1F);
        if (p.endsWith("_bed")) return box(0F, 0F, 0F, 1F, 0.5625F, 1F);
        if (p.endsWith("_anvil")) return box(0.125F, 0F, 0.125F, 0.875F, 1F, 0.875F);
        if (p.equals("cauldron")) return concat(box(0F, 0F, 0F, 1F, 0.3125F, 1F),
                box(0F, 0.3125F, 0F, 0.0625F, 1F, 1F), box(0.9375F, 0.3125F, 0F, 1F, 1F, 1F),
                box(0F, 0.3125F, 0F, 1F, 1F, 0.0625F), box(0F, 0.3125F, 0.9375F, 1F, 1F, 1F));
        if (p.equals("composter")) return concat(box(0F, 0F, 0F, 1F, 0.125F, 1F),
                box(0F, 0.125F, 0F, 0.125F, 1F, 0.125F), box(0.875F, 0.125F, 0F, 1F, 1F, 0.125F),
                box(0F, 0.125F, 0.875F, 0.125F, 1F, 1F), box(0.875F, 0.125F, 0.875F, 1F, 1F, 1F));
        if (p.equals("brewing_stand")) return concat(box(0.4375F, 0F, 0.4375F, 0.5625F, 0.875F, 0.5625F),
                box(0.125F, 0.875F, 0.125F, 0.875F, 0.9375F, 0.875F));
        if (p.equals("cake")) return box(0.0625F, 0F, 0.0625F, 0.9375F, 0.5F, 0.9375F);
        if (p.equals("lectern")) return concat(box(0F, 0F, 0F, 1F, 0.125F, 1F),
                box(0.25F, 0.125F, 0.25F, 0.75F, 0.875F, 0.75F));
        if (p.endsWith("_candle")) return box(0.4375F, 0F, 0.4375F, 0.5625F, 0.375F, 0.5625F);
        if (p.endsWith("_head") || p.endsWith("_skull")) return box(0.25F, 0F, 0.25F, 0.75F, 0.5F, 0.75F);
        if (p.equals("turtle_egg")) return box(0.375F, 0F, 0.375F, 0.625F, 0.4375F, 0.625F);
        if (p.equals("sea_pickle")) return box(0.375F, 0F, 0.375F, 0.625F, 0.375F, 0.625F);
        if (p.equals("daylight_detector")) return box(0F, 0F, 0F, 1F, 0.375F, 1F);
        return full();
    }

    /**
     * 十字模型（花草、藤蔓、作物）：整格跳过。
     *
     * <p>这些方块在实体渲染里是两张交叉的贴图，没有"体积"。给它们编个盒子，线框上就是
     * 一堆悬在半空的小点，遮挡上还会把身后的轮廓切开——不如当空气。
     */
    private static boolean isFlatModel(String p) {
        if (FLAT.contains(p)) return true;
        return p.endsWith("_sapling") || p.endsWith("_coral_fan")
                || p.endsWith("_bush") || p.endsWith("_roots")
                || p.endsWith("_sprouts") || p.endsWith("_vines") || p.equals("vine");
    }

    /**
     * 十字花草的线（局部 0..1）：两块对角平面各画成一个矩形，共 8 条斜棱。
     *
     * <p>它们是<b>线</b>不是盒子：花草没有可遮挡的体积，走盒子会把一朵花画成整格外框。
     * 两块平面沿对角线交叉，从上方看就是斜的 X（原版 cross 模型的角度）。
     */
    public static float[] crossLines() {
        return CROSS_LINES.clone();
    }

    private static final float[] CROSS_LINES = {
            // 面 A：从西北角到东南角的对角平面
            0.05F, 0F, 0.05F, 0.95F, 0F, 0.95F,
            0.95F, 0F, 0.95F, 0.95F, 1F, 0.95F,
            0.95F, 1F, 0.95F, 0.05F, 1F, 0.05F,
            0.05F, 1F, 0.05F, 0.05F, 0F, 0.05F,
            // 面 B：从东北角到西南角
            0.05F, 0F, 0.95F, 0.95F, 0F, 0.05F,
            0.95F, 0F, 0.05F, 0.95F, 1F, 0.05F,
            0.95F, 1F, 0.05F, 0.05F, 1F, 0.95F,
            0.05F, 1F, 0.95F, 0.05F, 0F, 0.95F
    };

    /** 十字模型的花草：花、草、蕨、枯木、树苗。名字要同时覆盖 1.20.1（grass）和 1.21（short_grass）。 */
    public static boolean isCrossPlant(String id, Map<String, String> props) {
        return isCrossPlant(path(id));
    }

    private static boolean isCrossPlant(String p) {
        if (p.startsWith("potted_")) return false;
        return FLOWER.contains(p) || p.endsWith("_tulip") || p.endsWith("_sapling") || GRASSY.contains(p);
    }

    private static final Set<String> GRASSY = Set.of(
            "grass", "short_grass", "fern", "large_fern", "dead_bush", "tall_grass");

    /** 花盆：盆底 + 口沿 + 盆里的一小簇。 */
    private static float[] potted() {
        return concat(box(0.3125F, 0F, 0.3125F, 0.6875F, 0.375F, 0.6875F),
                box(0.25F, 0.375F, 0.25F, 0.75F, 0.4375F, 0.75F),
                box(0.34375F, 0.4375F, 0.34375F, 0.65625F, 0.6875F, 0.65625F));
    }

    /** 单格/双格花：给交叉薄板，线框里能看出是棵花，而不是整格方块。 */
    private static final Set<String> FLOWER = Set.of(
            "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy",
            "cornflower", "lily_of_the_valley", "wither_rose", "torchflower",
            "sunflower", "lilac", "rose_bush", "peony", "closed_eyeblossom", "open_eyeblossom",
            "pitcher_plant");

    private static final Set<String> FLAT = Set.of(
            "short_grass", "tall_grass", "fern", "large_fern", "dead_bush", "wheat",
            "carrots", "potatoes", "beetroots", "nether_wart", "sweet_berry_bush", "glow_lichen",
            "hanging_roots", "cave_vines", "cave_vines_plant", "weeping_vines", "twisting_vines",
            "seagrass", "tall_seagrass", "kelp", "kelp_plant", "sugar_cane", "water", "lava",
            "air", "cave_air", "void_air", "structure_void", "cobweb", "tripwire");

    // ── 各类形状 ────────────────────────────────────────────────────────

    private static float[] slab(Map<String, String> props) {
        return switch (val(props, "type", "bottom")) {
            case "top" -> box(0F, 0.5F, 0F, 1F, 1F, 1F);
            case "double" -> full();
            default -> box(0F, 0F, 0F, 1F, 0.5F, 1F);
        };
    }

    /**
     * 楼梯：底下一层半格 + 上半级。上半级按 {@code shape} 分三种：
     * <ul>
     *   <li>{@code straight}：正对 facing 那一侧整半边；</li>
     *   <li>{@code inner_*}：直梯的整半边 + <b>低侧</b>一个 1/4 角（两级相接的内拐角）；</li>
     *   <li>{@code outer_*}：只有<b>高侧</b>一个 1/4 角（外拐角，比直梯还窄）。</li>
     * </ul>
     *
     * <p>象限照抄原版 {@code StairBlock} 的 shape 表（{@code SHAPE_BY_STATE}），不是从模型文件猜的：
     * facing 与 2D 数据值、shape 序数组合出来的下标，再对上八分体
     * {@code OCTET_NPN/NPP/PNN/PPP}。例如 facing=east 的 straight 是东半边、
     * inner_left 多出西北角、outer_right 只剩东南角。
     *
     * <p>朝向本身照客户端模型：{@code stairs.json} 的上半级在 x 8..16，
     * {@code facing=east} 不带旋转；{@code facing=north} 的上半级在 z 0..8。
     */
    private static float[] stairs(Map<String, String> props) {
        boolean top = "top".equals(val(props, "half", "bottom"));
        float baseLow = top ? 0.5F : 0F;
        float baseHigh = top ? 1F : 0.5F;
        float stepLow = top ? 0F : 0.5F;
        float stepHigh = top ? 0.5F : 1F;
        float[] base = box(0F, baseLow, 0F, 1F, baseHigh, 1F);

        String facing = val(props, "facing", "north");
        float[] straight = switch (facing) {
            case "north" -> box(0F, stepLow, 0F, 1F, stepHigh, 0.5F);
            case "south" -> box(0F, stepLow, 0.5F, 1F, stepHigh, 1F);
            case "west" -> box(0F, stepLow, 0F, 0.5F, stepHigh, 1F);
            default -> box(0.5F, stepLow, 0F, 1F, stepHigh, 1F);
        };

        String shape = val(props, "shape", "straight");
        boolean inner = shape.startsWith("inner");
        boolean outer = shape.startsWith("outer");
        if (!inner && !outer) return concat(base, straight);

        boolean left = shape.endsWith("_left");
        float[] corner = stairCorner(facing, inner, left, stepLow, stepHigh);
        return inner ? concat(base, straight, corner) : concat(base, corner);
    }

    /**
     * 楼梯内/外拐角那一个 1/4 块。{@code inner} 在低侧、{@code outer} 在高侧，具体象限见
     * {@link #stairs(Map)} 的说明。
     */
    private static float[] stairCorner(String facing, boolean inner, boolean left,
                                       float low, float high) {
        // 象限：0=西北 1=东北 2=西南 3=东南
        int quadrant = switch (facing) {
            case "east" -> inner ? (left ? 0 : 2) : (left ? 1 : 3);
            case "west" -> inner ? (left ? 3 : 1) : (left ? 2 : 0);
            case "south" -> inner ? (left ? 1 : 0) : (left ? 3 : 2);
            default -> inner ? (left ? 2 : 3) : (left ? 0 : 1);   // north
        };
        return switch (quadrant) {
            case 0 -> box(0F, low, 0F, 0.5F, high, 0.5F);
            case 1 -> box(0.5F, low, 0F, 1F, high, 0.5F);
            case 2 -> box(0F, low, 0.5F, 0.5F, high, 1F);
            default -> box(0.5F, low, 0.5F, 1F, high, 1F);
        };
    }

    /** 活板门：关着是 3/16 厚的平放板，开着是贴着 facing 那面竖起的一块。 */
    private static float[] trapdoor(Map<String, String> props) {
        if (!flag(props, "open", false)) {
            return "top".equals(val(props, "half", "bottom"))
                    ? box(0F, 1F - T, 0F, 1F, 1F, 1F)
                    : box(0F, 0F, 0F, 1F, T, 1F);
        }
        return switch (val(props, "facing", "north")) {
            case "north" -> box(0F, 0F, 0F, 1F, 1F, T);
            case "south" -> box(0F, 0F, 1F - T, 1F, 1F, 1F);
            case "west" -> box(0F, 0F, 0F, T, 1F, 1F);
            default -> box(1F - T, 0F, 0F, 1F, 1F, 1F);
        };
    }

    /** 门：3/16 厚的竖板。关着贴在 facing 那一侧，开着转 90° 到铰链那一侧。 */
    private static float[] door(Map<String, String> props) {
        String facing = val(props, "facing", "north");
        boolean open = flag(props, "open", false);
        if (!open) {
            return switch (facing) {
                case "north" -> box(0F, 0F, 0F, 1F, 1F, T);
                case "south" -> box(0F, 0F, 1F - T, 1F, 1F, 1F);
                case "west" -> box(0F, 0F, 0F, T, 1F, 1F);
                default -> box(1F - T, 0F, 0F, 1F, 1F, 1F);
            };
        }
        // 开着：板面转到了与 facing 垂直的方向，贴在铰链那一侧
        boolean left = "left".equals(val(props, "hinge", "left"));
        return switch (facing) {
            case "north", "south" -> left
                    ? box(0F, 0F, 0F, T, 1F, 1F)
                    : box(1F - T, 0F, 0F, 1F, 1F, 1F);
            case "west", "east" -> left
                    ? box(0F, 0F, 0F, 1F, 1F, T)
                    : box(0F, 0F, 1F - T, 1F, 1F, 1F);
            default -> box(0F, 0F, 0F, T, 1F, 1F);
        };
    }

    /** 栅栏：中间一根柱 + 每个连接方向两道横杆。 */
    private static float[] fence(Map<String, String> props) {
        float[] result = box(0.375F, 0F, 0.375F, 0.625F, 1F, 0.625F);
        if (linked(props, "north")) {
            result = concat(result, box(0.375F, 0.75F, 0F, 0.625F, 0.9375F, 0.375F),
                    box(0.375F, 0.375F, 0F, 0.625F, 0.5625F, 0.375F));
        }
        if (linked(props, "south")) {
            result = concat(result, box(0.375F, 0.75F, 0.625F, 0.625F, 0.9375F, 1F),
                    box(0.375F, 0.375F, 0.625F, 0.625F, 0.5625F, 1F));
        }
        if (linked(props, "west")) {
            result = concat(result, box(0F, 0.75F, 0.375F, 0.375F, 0.9375F, 0.625F),
                    box(0F, 0.375F, 0.375F, 0.375F, 0.5625F, 0.625F));
        }
        if (linked(props, "east")) {
            result = concat(result, box(0.625F, 0.75F, 0.375F, 1F, 0.9375F, 0.625F),
                    box(0.625F, 0.375F, 0.375F, 1F, 0.5625F, 0.625F));
        }
        return result;
    }

    /** 栅栏门：关着是中间一段矮板，开着就只剩两侧的立柱。 */
    private static float[] fenceGate(Map<String, String> props) {
        boolean alongX = switch (val(props, "facing", "north")) {
            case "north", "south" -> true;
            default -> false;
        };
        float[] post0 = alongX ? box(0F, 0.3125F, 0.375F, 0.125F, 1F, 0.625F)
                : box(0.375F, 0.3125F, 0F, 0.625F, 1F, 0.125F);
        float[] post1 = alongX ? box(0.875F, 0.3125F, 0.375F, 1F, 1F, 0.625F)
                : box(0.375F, 0.3125F, 0.875F, 0.625F, 1F, 1F);
        if (flag(props, "open", false)) return concat(post0, post1);
        float[] leaf = alongX ? box(0.125F, 0.3125F, 0.375F, 0.875F, 1F, 0.625F)
                : box(0.375F, 0.3125F, 0.125F, 0.625F, 1F, 0.875F);
        return concat(leaf, post0, post1);
    }

    /** 墙：中间一根粗柱 + 每个连接方向一块矮板（wall 的板顶在 14/16 高，不带横杆）。 */
    private static float[] wall(Map<String, String> props) {
        // 原版 template_wall_post 是 4..12、0..16，和 up 无关（up 只影响"顶上还有没有一块"）
        float top = 1F;
        float[] result = box(0.25F, 0F, 0.25F, 0.75F, top, 0.75F);
        if (linked(props, "north")) result = concat(result, box(0.3125F, 0F, 0F, 0.6875F, 0.875F, 0.25F));
        if (linked(props, "south")) result = concat(result, box(0.3125F, 0F, 0.75F, 0.6875F, 0.875F, 1F));
        if (linked(props, "west")) result = concat(result, box(0F, 0F, 0.3125F, 0.25F, 0.875F, 0.6875F));
        if (linked(props, "east")) result = concat(result, box(0.75F, 0F, 0.3125F, 1F, 0.875F, 0.6875F));
        return result;
    }

    /** 玻璃板 / 铁栏杆：中间 2/16 厚的薄片 + 每个连接方向延出去的一截。 */
    private static float[] pane(Map<String, String> props) {
        float[] result = box(0.4375F, 0F, 0.4375F, 0.5625F, 1F, 0.5625F);
        boolean any = linked(props, "north") || linked(props, "south")
                || linked(props, "west") || linked(props, "east");
        if (!any) return result;                       // 四面都不挨着：只剩中间那一小片
        if (linked(props, "north")) result = concat(result, box(0.4375F, 0F, 0F, 0.5625F, 1F, 0.4375F));
        if (linked(props, "south")) result = concat(result, box(0.4375F, 0F, 0.5625F, 0.5625F, 1F, 1F));
        if (linked(props, "west")) result = concat(result, box(0F, 0F, 0.4375F, 0.4375F, 1F, 0.5625F));
        if (linked(props, "east")) result = concat(result, box(0.5625F, 0F, 0.4375F, 1F, 1F, 0.5625F));
        return result;
    }

    /** 地毯：1/16 高的一层。 */
    private static float[] carpet() {
        return box(0F, 0F, 0F, 1F, P, 1F);
    }

    /** 雪：每层 1/8 格，最多 8 层。 */
    private static float[] snow(Map<String, String> props) {
        int layers = number(props, "layers", 1);
        return box(0F, 0F, 0F, 1F, Math.min(8, Math.max(1, layers)) * 0.125F, 1F);
    }

    /**
     * 按钮：贴在支撑面上的一小块。
     *
     * <p>照原版 {@code models/block/button.json}：一块 <b>x 5..11、y 0..2、z 6..10</b> 的板，
     * 板法线朝 -Y；然后按 {@code face}/{@code facing} 转：
     * <ul>
     *   <li>{@code face=floor}：只绕 y 转（east=90°、south=0°、north=180°、west=270°），板躺在 0..2/16；</li>
     *   <li>{@code face=ceiling}：再加 {@code x=180}，于是板在 14..16/16；</li>
     *   <li>{@code face=wall}：{@code x=90}，板竖起来贴在 facing 那一面的墙上，厚 2/16。</li>
     * </ul>
     * 上一版厚度写成 1/16、天花板写 15..16、地面写 0..1/16 且不管 facing —— 位置和朝向都不对。
     */
    private static float[] button(Map<String, String> props) {
        String face = val(props, "face", "wall");
        String facing = val(props, "facing", "north");
        float t = 2F / 16F;
        float lo = 6F / 16F;
        float hi = 10F / 16F;
        float w0 = 5F / 16F;
        float w1 = 11F / 16F;
        boolean alongX = "north".equals(facing) || "south".equals(facing);
        if ("floor".equals(face) || "ceiling".equals(face)) {
            float y0 = "ceiling".equals(face) ? 1F - t : 0F;
            float y1 = y0 + t;
            // 4/16 的那一边顺着 facing：南北朝向时它沿 z，东西朝向时沿 x
            return alongX ? box(w0, y0, lo, w1, y1, hi) : box(lo, y0, w0, hi, y1, w1);
        }
        return switch (facing) {
            case "north" -> box(w0, lo, 0F, w1, hi, t);
            case "south" -> box(w0, lo, 1F - t, w1, hi, 1F);
            case "west" -> box(0F, lo, w0, t, hi, w1);
            default -> box(1F - t, lo, w0, 1F, hi, w1);
        };
    }

    /** 踏板：1/16 厚的方片。 */
    private static float[] pressurePlate() {
        return box(0.0625F, 0F, 0.0625F, 0.9375F, 0.0625F, 0.9375F);
    }

    /** 牌子：一块 1/16 厚的板。挂墙的竖直贴墙，立地的落在方块中间。 */
    private static float[] sign(Map<String, String> props) {
        if (props.containsKey("rotation") || props.containsKey("ground")) {
            return box(0.4375F, 0F, 0.4375F, 0.5625F, 1F, 0.5625F);
        }
        return switch (val(props, "facing", "north")) {
            case "north" -> box(0F, 0.25F, 1F - P, 1F, 0.75F, 1F);
            case "south" -> box(0F, 0.25F, 0F, 1F, 0.75F, P);
            case "west" -> box(1F - P, 0.25F, 0F, 1F, 0.75F, 1F);
            default -> box(0F, 0.25F, 0F, P, 0.75F, 1F);
        };
    }

    /** 旗帜：一块很薄的竖面。 */
    private static float[] banner(Map<String, String> props) {
        return switch (val(props, "facing", "north")) {
            case "north" -> box(0F, 0F, 1F - P, 1F, 1F, 1F);
            case "south" -> box(0F, 0F, 0F, 1F, 1F, P);
            case "west" -> box(1F - P, 0F, 0F, 1F, 1F, 1F);
            default -> box(0F, 0F, 0F, P, 1F, 1F);
        };
    }

    /** 火把：中间一根细柱（墙上的换成贴墙的斜柱不划算，统一按立在地上）。 */
    private static float[] torch(Map<String, String> props) {
        return box(0.4375F, 0F, 0.4375F, 0.5625F, 0.625F, 0.5625F);
    }

    /**
     * 灯笼：一个 6/16 见方的小灯笼（原版 template_lantern：x/z 5..11、y 1..8），
     * 挂着的从顶上再往上接一截链子。
     */
    private static float[] lantern(Map<String, String> props) {
        float[] body = box(0.3125F, 0.0625F, 0.3125F, 0.6875F, 0.5F, 0.6875F);
        if (!flag(props, "hanging", false)) return body;
        return concat(body, box(0.4375F, 0.5F, 0.4375F, 0.5625F, 1F, 0.5625F));
    }

    // ── 盒子工具 ────────────────────────────────────────────────────────

    private static float[] full() {
        return FULL;
    }

    private static float[] box(float x0, float y0, float z0, float x1, float y1, float z1) {
        return new float[]{x0, y0, z0, x1, y1, z1};
    }

    private static float[] concat(float[]... parts) {
        int total = 0;
        for (float[] part : parts) total += part.length;
        float[] out = new float[total];
        int at = 0;
        for (float[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    private static String val(Map<String, String> props, String key, String fallback) {
        String v = props.get(key);
        return v == null ? fallback : v;
    }

    private static boolean flag(Map<String, String> props, String key, boolean fallback) {
        String v = props.get(key);
        return v == null ? fallback : "true".equals(v);
    }

    private static int number(Map<String, String> props, String key, int fallback) {
        try {
            return Integer.parseInt(val(props, key, Integer.toString(fallback)));
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    /**
     * 这个方向有没有连接。
     *
     * <p>原版对栅栏/墙/板写的是 {@code north=false} 这种三态：{@code true} = 连上，{@code false} = 明确不连，
     * 缺省 = 由游戏在放置时现算（"low"/"tall" 之类也算连）。投影文件里通常只记 true/false，
     * 缺省时按<b>连</b>算——宁可多画一截，也别把栏杆画成断头。
     */
    private static boolean linked(Map<String, String> props, String key) {
        String v = props.get(key);
        return v == null || !"false".equals(v);
    }
}
