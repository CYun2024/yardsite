package dev.yard.site.mixin.sodium;

import dev.yard.site.client.SiteRevealCulling;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.util.iterator.ByteIterator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium 每个 region 的绘制列表是按 section 索引迭代的。落成动画期间把「还没轮到的区块列」
 * 从迭代里过滤掉，等效于这些区块的网格根本不提交 draw call——真地形在露出来之前确实不存在于画面。
 *
 * <p>反过来这里也是<b>「这块真实网格这一刻真的在被绘制」的唯一可靠证据</b>：能被迭代到就说明
 * section 已经上传、进了绘制列表，所以顺手回报给落成动画当淡出的前置条件（原版
 * {@code LevelRenderer#isSectionCompiled} 在 Sodium 下永远报未编译，不能用来判）。
 *
 * <p>只过滤 terrain 几何和 section 里的方块实体；静态区块仍走 Sodium 原来的合批路径。
 * 直接改迭代器比拆 draw call 便宜，也不会碰到 Sodium 的着色器/uniform。
 *
 * <p>目标类用<b>字符串</b>写：mixin 在解析配置时就会问 {@code shouldApplyMixin}，那时 Sodium 的客户端类
 * 还没被加载，用类常量的写法会直接 ClassNotFoundException ⇒ 整组 mixin 被永久丢掉（2026-11-03 实测
 * 就是这样，日志里「Sodium 区块渲染过滤已接管」一次都没出现）。配置本身
 * {@code "required": false}，没装 Sodium 时只是启动多两行警告。
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList")
public abstract class ChunkRenderListMixin {

    @Unique
    private RevealSectionIterator yardsite$geometryIterator;

    @Unique
    private RevealSectionIterator yardsite$entityIterator;

    @Inject(method = "sectionsWithGeometryIterator", at = @At("RETURN"), cancellable = true)
    private void yardsite$filterGeometry(boolean reverse, CallbackInfoReturnable<ByteIterator> cir) {
        ByteIterator base = cir.getReturnValue();
        if (base == null || !SiteRevealCulling.active()) return;
        if (this.yardsite$geometryIterator == null) {
            this.yardsite$geometryIterator = new RevealSectionIterator();
        }
        // 几何迭代：先回报「这一列的真实网格进了绘制列表」，再决定让不让画
        this.yardsite$geometryIterator.reset(base, ((ChunkRenderList) (Object) this).getRegion(), true);
        cir.setReturnValue(this.yardsite$geometryIterator);
    }

    @Inject(method = "sectionsWithEntitiesIterator", at = @At("RETURN"), cancellable = true)
    private void yardsite$filterEntities(CallbackInfoReturnable<ByteIterator> cir) {
        ByteIterator base = cir.getReturnValue();
        if (base == null || !SiteRevealCulling.active()) return;
        if (this.yardsite$entityIterator == null) {
            this.yardsite$entityIterator = new RevealSectionIterator();
        }
        this.yardsite$entityIterator.reset(base, ((ChunkRenderList) (Object) this).getRegion(), false);
        cir.setReturnValue(this.yardsite$entityIterator);
    }

    /** 按 section 索引跳过被藏起来的区块列；索引和 fillCommandBuffer 用的是同一套。 */
    static final class RevealSectionIterator implements ByteIterator {

        private ByteIterator base;
        private RenderRegion region;
        private boolean markDrawn;
        private int next;
        private boolean ready;

        void reset(ByteIterator base, RenderRegion region, boolean markDrawn) {
            this.base = base;
            this.region = region;
            this.markDrawn = markDrawn;
            this.ready = false;
        }

        @Override
        public boolean hasNext() {
            while (!ready && base.hasNext()) {
                int index = base.nextByteAsInt();
                RenderSection section = region.getSection(index);
                if (section == null) continue;
                if (markDrawn) {
                    SiteRevealCulling.sectionInRenderList(section.getChunkX(), section.getChunkZ());
                }
                if (SiteRevealCulling.hides(section.getChunkX(), section.getChunkZ())) continue;
                next = index;
                ready = true;
            }
            return ready;
        }

        @Override
        public int nextByteAsInt() {
            if (!hasNext()) {
                throw new java.util.NoSuchElementException();
            }
            ready = false;
            return next;
        }
    }
}
