package dev.yard.site.api;

import dev.yard.site.worldgen.SurfaceProbe;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 与其它 mod 的联动点。本 mod <b>不依赖任何 mod</b>：没有联动方时就是一张纯群系图，
 * 选出来的只是一个出生点；装了庭院系列，这里会多出一个联动实现，把同一个落点变成庭院占地。
 *
 * <p>实现方通过 {@link SiteLinks#register} 注册；本 mod 自己只按 {@link SiteLinks#get()} 取用，
 * 取不到就按「只有出生点」走。
 */
public interface SiteLink {

    /** 联动方 modid（日志用）。 */
    String id();

    /** 地点叫什么（只用于日志；界面上的叫法见 {@link #placeLabelKey()}）。 */
    String placeLabel();

    /**
     * 界面上的地点叫法：一个译键。
     *
     * <p>单独给一个键、而不是把 {@link #placeLabel()} 改成 {@code Component}，是为了<b>不破坏已经发出去的
     * 联动实现</b>：老版本（yardcore 0.1.0）不实现这个方法也能跑，默认就是「庭院」。
     *
     * <p>键可以放在 <b>yardsite</b> 的 {@code lang/*.json} 里 —— 客户端会把所有命名空间的 lang 合起来读；
     * 联动方想用别的词（旅社、营地…）就覆盖这个方法，并把条目加进自己的 lang。
     */
    default String placeLabelKey() {
        return "yardsite.place.plot";
    }

    /**
     * 是否要在创建世界时弹这张图（对应联动方自己的开关，例如「开局自动生成旅社」关掉时不弹）。
     * 默认 true。
     */
    default boolean siteSelectEnabled() {
        return true;
    }

    /**
     * 选址图上要画出的占地框（长/深/门廊/高度）。{@code null} = 不画框，也没有建筑。
     * 这个值必须在建世界<b>之前</b>就能拿到，因为选区图要用它画框。
     */
    @Nullable
    SitePlan.Plot plot();

    /**
     * 服务端：按玩家点中的落点算出建筑原点与出生点。
     *
     * @param exact true = 玩家在区块精读里一格一格挑的，不要为了找平地而挪位
     * @return 完整计划；{@code null} = 联动方这次不给建筑（本 mod 会退回「只定出生点」）
     */
    @Nullable
    SitePlan plan(ServerLevel level, SurfaceProbe probe, int siteX, int siteZ, boolean exact);

    /** 计划已经落到世界（出生点、区块半径都设好了）：联动方在这里保存自己的记录。 */
    void onPlanned(ServerLevel level, SitePlan plan);

    /** 落成动画里的金色线框；空列表 = 不画（独立安装就是这样）。 */
    default List<BlueprintBox> blueprint(SitePlan plan) {
        return List.of();
    }
}
