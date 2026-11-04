package dev.yard.site.view;

import dev.yard.site.api.SiteLink;
import dev.yard.site.api.SiteLinks;
import dev.yard.site.api.SitePlan;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * 界面上所有会随「装没装庭院系列」变化的字都在这里。
 *
 * <p>独立安装时这张图只决定出生点，界面里不出现"庭院""旅社""落座"这类只有联动方才说得通的词；
 * 和庭院系列一起装时再换回那套词。集中放一处是为了避免同一句话在几个界面里写法不一致。
 *
 * <p>文案本体在 {@code assets/yardsite/lang/*.json}，这里只给出译键 —— 切语言时跟着变，
 * 不用在两处各改一遍。
 */
final class SiteText {

    private SiteText() {}

    /** 有没有联动方。 */
    static boolean linked() {
        return SiteLinks.get() != null;
    }

    /** 这次落点会不会连带盖起建筑。 */
    static boolean hasPlot() {
        return plot() != null;
    }

    /** 占地尺寸；独立安装为 {@code null}。 */
    @Nullable
    static SitePlan.Plot plot() {
        SiteLink link = SiteLinks.get();
        return link == null ? null : link.plot();
    }

    /** 选的是什么：出生点 / 庭院。 */
    static Component place() {
        SiteLink link = SiteLinks.get();
        return link == null ? Component.translatable("yardsite.place.spawn")
                : Component.translatable(link.placeLabelKey());
    }

    /** 图的标题（窗口标题用；界面里那行带层级的标题见 {@code yardsite.screen.title.*}）。 */
    static Component screenTitle() {
        return Component.translatable("yardsite.screen.title");
    }

    /** 落成动画的几个阶段。 */
    enum Stage {
        /** 刚确认落点、还在生成。 */
        ENTER("entering"),
        /** 按蓝图搭建筑。 */
        BLUEPRINT("blueprint"),
        /** 真实地形接上来。 */
        REVEAL("reveal");

        private final String key;

        Stage(String key) {
            this.key = key;
        }
    }

    /**
     * 落成动画的标题。两种装法各有整套标题（{@code .plot} / {@code .spawn}），
     * 不在代码里拼「庭院落成 · 」这种前缀。
     */
    static Component revealTitle(Stage stage) {
        return Component.translatable("yardsite.reveal.title." + stage.key + (linked() ? ".plot" : ".spawn"));
    }
}
