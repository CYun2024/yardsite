package dev.yard.site.view;

import dev.yard.site.YardSiteConfig;

/**
 * 选址图后台采样的线程预算：默认「CPU 逻辑核数的一半」，剩下一半留给渲染线程和系统。
 *
 * <p>建世界前的群系/高度大沙盘（{@link SiteMapSampler}）和选完点后的区块精读
 * （{@link ChunkSitePlanner}）共用这套预算：两处都是纯客户端算力活，跟渲染线程抢 CPU，
 * 以前各自写死 {@code min(16, 核数)}，核多的机器上会把渲染线程饿到掉帧。
 *
 * <p>配置文件 {@code config/yardsite-common.toml} 的 {@code site.sampleThreads}
 * 可以覆盖：0 = 自动（核数 ÷ 2），1–64 = 手动。
 */
final class SiteThreadBudget {

    /** 自动模式：只用一半核，另一半留给渲染线程和系统。 */
    static final int AUTO_DIVISOR = 2;

    private SiteThreadBudget() {}

    /** 本机该开几个采样线程（永远 ≥ 1）。 */
    static int resolve() {
        int configured = configured();
        return configured > 0 ? configured : auto();
    }

    /** 自动值：核数 ÷ 2；2 核以下的机器压到 1，别一个线程都不开。 */
    static int auto() {
        return Math.max(1, cores() / AUTO_DIVISOR);
    }

    private static int cores() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    private static int configured() {
        try {
            return YardSiteConfig.SITE_SAMPLE_THREADS.get();
        } catch (RuntimeException notLoadedYet) {
            // 配置还没读上来（正常流程里界面出来时已经读了）：退回自动
            return 0;
        }
    }

    /** 日志后缀：说清这个线程数是自动算的还是配置文件指定的。 */
    static String describe(int threads) {
        String cores = "CPU " + cores() + " 核";
        if (configured() <= 0) {
            return threads == auto()
                    ? "（自动：" + cores + " ÷ " + AUTO_DIVISOR + "，配置文件可改）"
                    : "（自动：" + cores + "）";
        }
        return "（配置 structure.siteSampleThreads，" + cores + "）";
    }
}
