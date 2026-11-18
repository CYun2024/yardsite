package dev.yard.site.client;

/**
 * 落成动画「空中镜头」期间的进程内状态：从相机切到院子上空，到镜头落回玩家为止为 true。
 *
 * <p>内容 mod 可以选择在这段时间把屏幕/天空上的视觉效果让开（例如天象的雨雾、云色、极光），
 * 让真世界的载入过程不被压暗或遮挡。状态只影响客户端表现，不影响服务端天气/事件本身。
 *
 * <p>用静态字段而不是事件，是为了让没有编译依赖的内容 mod（如 skyweaver）用反射读取；
 * 见 {@code dev.yard.skyweaver.compat.YardCompat#aerialReveal()}。
 */
public final class RevealCinematics {

    private static volatile boolean aerial;

    private RevealCinematics() {}

    public static void setAerial(boolean value) {
        aerial = value;
    }

    public static boolean isAerial() {
        return aerial;
    }
}
