package dev.yard.site;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * 本 mod 的日志出口。
 *
 * <p>这个 mod 以前一路 {@code System.out.println}：选区采样、区块到货、落机每一帧的耗时都直接打屏，
 * 正常玩一次会刷出十几屏，玩家反馈里的日志基本读不了。现在分两档：
 *
 * <ul>
 *   <li>{@link #debug}：开发排查用（采样耗时、落点、让位判据、落机路径、帧统计…）。
 *       <b>默认不打</b>，加 JVM 参数 {@code -Dyardsite.debug=true} 才输出；</li>
 *   <li>{@link #warn}：真正的异常（采样失败、读配置失败、联动接不上）。任何时候都打。</li>
 * </ul>
 *
 * <p>传 {@link Supplier} 的重载在开关关着时不会去拼字符串 —— 有几个调用点每帧都在拼。
 */
public final class YardSiteLog {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PREFIX = "[yardsite] ";
    /** 诊断日志开关：{@code -Dyardsite.debug=true}。 */
    private static final boolean DEBUG = Boolean.getBoolean("yardsite.debug");

    private YardSiteLog() {}

    public static boolean debugEnabled() {
        return DEBUG;
    }

    /** 诊断日志（默认静默）。 */
    public static void debug(String message) {
        if (DEBUG) LOGGER.info(PREFIX + message);
    }

    /** 诊断日志（默认静默）；关着时不会调用 {@code message}。 */
    public static void debug(Supplier<String> message) {
        if (DEBUG) LOGGER.info(PREFIX + message.get());
    }

    /** 需要让玩家/作者看见的问题。 */
    public static void warn(String message) {
        LOGGER.warn(PREFIX + message);
    }

    public static void warn(String message, Throwable cause) {
        LOGGER.warn(PREFIX + message, cause);
    }
}
