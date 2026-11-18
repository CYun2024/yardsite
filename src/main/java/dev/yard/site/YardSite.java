package dev.yard.site;

/**
 * 群系选区：创建单人世界前先看一张出生点附近的地形/群系图，把落点定准，再走一段落成镜头进世界。
 *
 * <h2>三种装法</h2>
 * <ul>
 *   <li><b>独立安装</b>：选出来的就是一个出生点。没有建筑、没有蓝图，落机直接落到出生点。</li>
 *   <li><b>和庭院系列一起装</b>：庭院核心会注册一个 {@link dev.yard.site.api.SiteLink}，
 *       同一个落点就变成庭院占地——服务端按院子尺寸规划原点、出生点落在院内，
 *       落成动画会画出旅社的金色线框。</li>
 *   <li><b>结构模式</b>（{@code structure.enabled = true}）：落点变成「把这份投影文件放进世界」。
 *       占地、出生点、落成线框都来自文件，进世界后由 {@link dev.yard.site.structure.SchematicPlacer}
 *       铺方块。不需要装投影 mod，见 {@code dev.yard.site.schematic}。</li>
 * </ul>
 *
 * <p>本 mod 不依赖任何其它 mod 的类：联动方实现 {@code SiteLink} 接口并注册，反向用反射兜底
 * （见 {@link dev.yard.site.api.SiteLinks}）。
 */
public final class YardSite {
    public static final String MODID = "yardsite";

    private YardSite() {}
}