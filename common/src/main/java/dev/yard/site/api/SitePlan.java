package dev.yard.site.api;

import org.jetbrains.annotations.Nullable;

/**
 * 建世界前定好的落点与占地。整条记录一次性发布（不可变），客户端读到的永远是完整的。
 *
 * <p>本类<b>不依赖任何其它 mod</b>：占地的长宽高由 {@link SiteLink#plot()} 提供，
 * 没有联动方时 {@link #plot()} 为 {@code null}，表示「这里只有出生点，没有建筑」。
 *
 * @param siteX            玩家在选区图上点中的落点（方块；选了庭院时是庭院中心）
 * @param originX/originY/originZ 占地左下前角的方块坐标（没有建筑时等于落点）
 * @param spawnX/spawnY/spawnZ    出生点；出生点区块就是围着它生成的
 * @param seaLevel         世界海平面（图上用来判水）
 * @param spawnChunkRadius {@code RULE_SPAWN_CHUNK_RADIUS}：进世界后保活的方形区块区半径
 * @param plot             占地尺寸；{@code null} = 没有建筑，只是选一个出生点
 */
public record SitePlan(int siteX, int siteZ,
                       int originX, int originY, int originZ,
                       int spawnX, int spawnY, int spawnZ,
                       int seaLevel, int spawnChunkRadius,
                       @Nullable Plot plot) {

    /**
     * 占地尺寸（方块）：长、深、门廊往 -z 多让出的深度、建筑高度。
     *
     * <p>{@code depth} 是含门廊的总深，{@code porch} 是其中伸到前墙外面的部分，
     * 所以墙面的 z 就是 {@code originZ}，占地的外沿在 {@code originZ - porch}。
     */
    public record Plot(int width, int depth, int porch, int height) {

        /** 前墙以内（不含门廊）的进深。 */
        public int innerDepth() {
            return depth - porch;
        }
    }

    /** 落座后真正的占地中心（大沙盘落座时服务端会就近挪位，和 {@code siteX} 不同）。 */
    public int plotCenterX() {
        return plot == null ? siteX : originX + plot.width() / 2;
    }

    public int plotCenterZ() {
        return plot == null ? siteZ : originZ + plot.innerDepth() / 2;
    }

    /** 这次落座会不会有建筑盖起来（决定落机要不要等人搬进走廊、要不要画蓝图）。 */
    public boolean hasPlot() {
        return plot != null;
    }

    public int spawnChunkX() {
        return spawnX >> 4;
    }

    public int spawnChunkZ() {
        return spawnZ >> 4;
    }
}
