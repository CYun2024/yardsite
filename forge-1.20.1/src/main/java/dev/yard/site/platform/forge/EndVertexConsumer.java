package dev.yard.site.platform.forge;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * 1.20.1 专用：包一层 {@link VertexConsumer}，在写完顶点颜色后自动补 {@code endVertex()}。
 *
 * <p>为什么需要：{@code BufferBuilder#endVertex()} 里做的是 {@code vertices++}，1.20.1 不调它
 * 就等于一个顶点都没写进缓冲，整批几何被静默丢掉（沙盘柱子、落成动画的线框和表面全是这条路）。
 * 1.21 起 {@code endVertex()} 已被删除，所以核心代码里不能出现它 —— 由
 * {@code dev.yard.site.platform.Vertices#wrap} 在 Forge 端换成这个包装。
 *
 * <p>本项目所有顶点链都是"位置 + 颜色"（{@code POSITION_COLOR}），颜色是最后一个属性，
 * 所以收尾放在 {@link #color(int)} / {@link #color(int, int, int, int)} 里。
 * 别在业务代码里再显式调 {@code endVertex()}，会重复计数（缓冲区直接错位）。
 */
public final class EndVertexConsumer implements VertexConsumer {

    private final VertexConsumer delegate;

    public EndVertexConsumer(VertexConsumer delegate) {
        this.delegate = delegate;
    }

    @Override
    public VertexConsumer vertex(double x, double y, double z) {
        delegate.vertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer color(int red, int green, int blue, int alpha) {
        delegate.color(red, green, blue, alpha);
        delegate.endVertex();
        return this;
    }

    /** 顶点链的收尾：颜色写完就是一个完整顶点。 */
    @Override
    public VertexConsumer color(int argb) {
        delegate.color(argb);
        delegate.endVertex();
        return this;
    }

    @Override
    public VertexConsumer uv(float u, float v) {
        delegate.uv(u, v);
        return this;
    }

    @Override
    public VertexConsumer overlayCoords(int u, int v) {
        delegate.overlayCoords(u, v);
        return this;
    }

    @Override
    public VertexConsumer uv2(int u, int v) {
        delegate.uv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        delegate.normal(x, y, z);
        return this;
    }

    /** 已经由 color 自动收尾；这里只转发，业务代码不该再调。 */
    @Override
    public void endVertex() {
        delegate.endVertex();
    }

    @Override
    public void defaultColor(int red, int green, int blue, int alpha) {
        delegate.defaultColor(red, green, blue, alpha);
    }

    @Override
    public void unsetDefaultColor() {
        delegate.unsetDefaultColor();
    }
}
