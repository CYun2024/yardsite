package dev.yard.site.platform;

/**
 * 一张方块模型面的中性数据：四个角的位置 + 贴图平均色 + 是否带 tint。
 *
 * <p>刻意不出现 {@code BakedModel}/{@code BakedQuad}/{@code TextureAtlasSprite}：这些类型在
 * 1.21.11/26.x 已经换掉，放在核心里会把共享源码绑死在某个版本。法线、双面判定、地图色回退
 * 都由核心自己算。
 *
 * @param corners     12 个 float，四个角的 xyz 依次排列
 * @param spriteColor 0xRRGGBB；0 表示读不出来，调用方退回地图色
 * @param tinted      模型面是否声明了 tint（树叶/草/水）
 */
public record ModelQuad(float[] corners, int spriteColor, boolean tinted) {
}