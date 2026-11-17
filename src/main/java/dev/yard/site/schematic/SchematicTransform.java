package dev.yard.site.schematic;

/**
 * 结构在落地时的朝向：先镜像、再按 90° 的整数倍转。
 *
 * <p>镜像和旋转都作用在<b>局部坐标</b>上，而且角点坐标是连续的（{@code [0, size]}），
 * 所以体素、线框、出生点走的是同一套换算，不会出现"线框转了、方块没转"。
 *
 * <p>顺时针是从<b>上往下看</b>（+y 朝自己）说的，和玩家把图纸原地转 90° 的直觉一致。
 */
public record SchematicTransform(int turns, boolean mirrorX, boolean mirrorZ) {

    public static final SchematicTransform NONE = new SchematicTransform(0, false, false);

    /** 转完之后的横向占地（x 方向）。 */
    public int width(int sizeX, int sizeZ) {
        return (turns & 1) == 0 ? sizeX : sizeZ;
    }

    /** 转完之后的进深（z 方向）。 */
    public int depth(int sizeX, int sizeZ) {
        return (turns & 1) == 0 ? sizeZ : sizeX;
    }

    /** 局部 x → 转完之后的 x（角点坐标，可以带小数）。 */
    public float x(float x, float z, int sizeX, int sizeZ) {
        float mx = mirrorX ? sizeX - x : x;
        float mz = mirrorZ ? sizeZ - z : z;
        return switch (turns & 3) {
            case 1 -> sizeZ - mz;
            case 2 -> sizeX - mx;
            case 3 -> mz;
            default -> mx;
        };
    }

    /** 局部 z → 转完之后的 z（角点坐标，可以带小数）。 */
    public float z(float x, float z, int sizeX, int sizeZ) {
        float mx = mirrorX ? sizeX - x : x;
        float mz = mirrorZ ? sizeZ - z : z;
        return switch (turns & 3) {
            case 1 -> mx;
            case 2 -> sizeZ - mz;
            case 3 -> sizeX - mx;
            default -> mz;
        };
    }

    /**
     * 转完之后的 x → 局部 x（{@link #x} 的反函数，角点坐标，可以带小数）。
     *
     * <p>遮挡剔除要把相机从世界坐标换算回结构自己的坐标里打光线，走的就是这一对反函数。
     */
    public float localX(float x, float z, int sizeX, int sizeZ) {
        float mx = switch (turns & 3) {
            case 1 -> z;
            case 2 -> sizeX - x;
            case 3 -> sizeX - z;
            default -> x;
        };
        return mirrorX ? sizeX - mx : mx;
    }

    /** 转完之后的 z → 局部 z（{@link #z} 的反函数）。 */
    public float localZ(float x, float z, int sizeX, int sizeZ) {
        float mz = switch (turns & 3) {
            case 1 -> sizeZ - x;
            case 2 -> sizeZ - z;
            case 3 -> x;
            default -> z;
        };
        return mirrorZ ? sizeZ - mz : mz;
    }

    /** 体素中心（整数格）→ 转完之后的整数格：换算完直接取整，别让 0.5 漂到隔壁格。 */
    public int blockX(int x, int z, int sizeX, int sizeZ) {
        int mx = mirrorX ? sizeX - 1 - x : x;
        int mz = mirrorZ ? sizeZ - 1 - z : z;
        return switch (turns & 3) {
            case 1 -> sizeZ - 1 - mz;
            case 2 -> sizeX - 1 - mx;
            case 3 -> mz;
            default -> mx;
        };
    }

    public int blockZ(int x, int z, int sizeX, int sizeZ) {
        int mx = mirrorX ? sizeX - 1 - x : x;
        int mz = mirrorZ ? sizeZ - 1 - z : z;
        return switch (turns & 3) {
            case 1 -> mx;
            case 2 -> sizeZ - 1 - mz;
            case 3 -> sizeX - 1 - mx;
            default -> mz;
        };
    }

    /** 出口方向（0=+x，1=-x，2=+z，3=-z）跟着一起转。 */
    public int front(int front) {
        int f = front;
        if (mirrorX) f = f == 0 ? 1 : f == 1 ? 0 : f;
        if (mirrorZ) f = f == 2 ? 3 : f == 3 ? 2 : f;
        return switch (turns & 3) {
            case 1 -> switch (f) { case 0 -> 2; case 2 -> 1; case 1 -> 3; default -> 0; };
            case 2 -> f == 0 ? 1 : f == 1 ? 0 : f == 2 ? 3 : 2;
            case 3 -> switch (f) { case 0 -> 3; case 3 -> 1; case 1 -> 2; default -> 0; };
            default -> f;
        };
    }

    /** 日志/界面用：「不转 / 顺时针 90° + X 镜像」这种。 */
    public String describe() {
        StringBuilder text = new StringBuilder(switch (turns & 3) {
            case 1 -> "顺时针 90°";
            case 2 -> "旋转 180°";
            case 3 -> "顺时针 270°";
            default -> "不旋转";
        });
        if (mirrorX) text.append(" + X 镜像");
        if (mirrorZ) text.append(" + Z 镜像");
        return text.toString();
    }
}
