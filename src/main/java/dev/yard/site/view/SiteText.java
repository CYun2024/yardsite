package dev.yard.site.view;

import dev.yard.site.api.SiteLink;
import dev.yard.site.api.SiteLinks;
import dev.yard.site.api.SitePlan;
import org.jetbrains.annotations.Nullable;

/**
 * 界面上所有会随「装没装庭院系列」变化的字都在这里。
 *
 * <p>独立安装时这张图只决定出生点，界面里不出现"庭院""旅社""落座"这类只有联动方才说得通的词；
 * 和庭院系列一起装时再换回那套词。集中放一处是为了避免同一句话在几个界面里写法不一致。
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
    static String place() {
        SiteLink link = SiteLinks.get();
        return link == null ? "出生点" : link.placeLabel();
    }

    /** 图的标题。 */
    static String screenTitle() {
        return "群系选区";
    }

    /** 落成动画的标题。 */
    static String revealTitle(String stage) {
        return linked() ? "庭院落成 · " + stage : "世界落成 · " + stage;
    }
}
