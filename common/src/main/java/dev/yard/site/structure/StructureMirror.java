package dev.yard.site.structure;

/**
 * 结构落地的镜像（先镜像、再按 {@link StructureRotation} 旋转）。
 *
 * <p>{@code X} = 沿 x 轴翻（左右对调），{@code Z} = 沿 z 轴翻（前后对调）。
 */
public enum StructureMirror {
    NONE(false, false),
    X(true, false),
    Z(false, true),
    XZ(true, true);

    private final boolean x;
    private final boolean z;

    StructureMirror(boolean x, boolean z) {
        this.x = x;
        this.z = z;
    }

    public boolean mirrorX() {
        return x;
    }

    public boolean mirrorZ() {
        return z;
    }
}
