package dev.yard.site.api;

/**
 * 落成动画里的一段线（世界坐标，两端点）。一段就是一整条直线，中间不打点。
 *
 * <p>给「按真实几何画线框」的联动方用：{@link SiteLink#blueprintLines} 返回一串这个，
 * 本 mod 按高度顺序把它们一条条点着，比一堆盒子更能看出建筑长什么样。
 * 线宽是屏幕像素常数（见 {@code site.revealLineWidth}），所以远处也不会细到看不见。
 *
 * @param x1/y1/z1 起点（方块坐标，可以带小数）
 * @param x2/y2/z2 终点
 */
public record BlueprintLine(float x1, float y1, float z1, float x2, float y2, float z2) {
}
