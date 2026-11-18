package dev.yard.site.view;

import dev.yard.site.structure.SurfaceLooks;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * 资源包 / 模型重载后把落成外观缓存清掉：贴图平均色和模型面都可能已经换了。
 * 平台层只负责把这个监听器注册进去。
 */
public final class SurfaceReload {

    private SurfaceReload() {}

    public static SimplePreparableReloadListener<Void> listener() {
        return new SimplePreparableReloadListener<>() {
            @Override
            protected Void prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
                return null;
            }

            @Override
            protected void apply(Void unused, ResourceManager resourceManager, ProfilerFiller profiler) {
                SurfaceLooks.invalidate();
            }
        };
    }
}
