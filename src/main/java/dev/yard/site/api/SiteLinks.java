package dev.yard.site.api;

import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

/**
 * 联动实现的注册表：本 mod 只在有别的 mod 主动注册时才「认识庭院」，否则就是独立的一张群系图。
 *
 * <p>方向是<b>单向</b>的：本 mod 从不引用任何具体 mod 的类（除了编译期的接口实现方），
 * 由联动方在自己的入口里调 {@link #register}。这样单独发这个 mod 时不会因为找不到
 * 庭院核心而崩，装了庭院系列时又自动变成「给庭院选区」。
 *
 * <p>为了让联动方不必反过来依赖本 mod 的加载顺序，这里也提供一个反射兜底：
 * 若存在 {@code dev.yard.core.compat.CoreSiteLink}，第一次取用时让它自己装进来。
 */
public final class SiteLinks {

    private static volatile SiteLink link;
    private static volatile boolean resolved;

    private SiteLinks() {}

    public static void register(SiteLink implementation) {
        link = implementation;
        resolved = true;
        System.out.println("[yardsite] 已接入联动：" + implementation.id()
                + "（选址结果按「" + implementation.placeLabel() + "」处理）");
    }

    /** 当前联动实现；独立安装时为 {@code null}。 */
    @Nullable
    public static SiteLink get() {
        if (!resolved) resolve();
        return link;
    }

    /** 有联动方（= 选中一个落点会连带盖起建筑）。 */
    public static boolean linked() {
        return get() != null;
    }

    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;
        if (!ModList.get().isLoaded("yardcore")) return;
        try {
            Class<?> bridge = Class.forName("dev.yard.core.compat.CoreSiteLink");
            bridge.getMethod("install").invoke(null);
        } catch (Throwable t) {
            System.out.println("[yardsite] 检测到 yardcore 但联动没接上（" + t + "），按独立模式运行");
        }
    }
}
