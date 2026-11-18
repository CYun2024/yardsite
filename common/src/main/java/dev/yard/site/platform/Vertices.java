package dev.yard.site.platform;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * 顶点消费者的跨版本适配。
 *
 * <p>1.20.1 的 {@code BufferBuilder#endVertex()} 里做的是 {@code vertices++}：<b>不调它，一个顶点都
 * 不计进缓冲</b>，整批几何被静默丢掉（不报错、不崩，画面上什么都没有）。1.21 起这个方法已经删除，
 * 写完 color 就是一个顶点。核心代码不写版本分支，取消费者统一走这里：共享版原样返回，
 * Forge 版包一层 {@code EndVertexConsumer} 自动补 {@code endVertex()}（见
 * {@code platform/forge/EndVertexConsumer}，由 {@code build.gradle} 的替换表接上）。
 *
 * <p>所以：<b>任何新写顶点的地方都要从这里拿 {@link VertexConsumer}</b>，不要直接
 * {@code bufferSource().getBuffer(...)}。
 */
public final class Vertices {

    private Vertices() {}

    public static VertexConsumer wrap(VertexConsumer consumer) {
        return consumer;   // FORGE_WRAP
    }
}
