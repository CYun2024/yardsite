package dev.yard.site.api;

/**
 * 落成动画里一个要画成金色线框的盒子（世界坐标，max 为开区间）。
 *
 * <p>由联动方（例如庭院系列的旅社布局）给出，本 mod 只负责按 {@code delayMs/riseMs} 让它一层层长起来。
 * 独立安装时一个盒子都没有，动画只放地面和落点标记。
 *
 * @param delayMs 动画开始后多久这根线开始升（毫秒）
 * @param riseMs  升到顶要多久（毫秒）
 * @param color   0xRRGGBB
 */
public record BlueprintBox(float minX, float minY, float minZ,
                           float maxX, float maxY, float maxZ,
                           int delayMs, int riseMs, int color) {
}
