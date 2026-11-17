package dev.yard.site.structure;

/** 结构落地的旋转（配置文件里写 {@code structure.rotation = CW90} 这样的值）。 */
public enum StructureRotation {
    NONE(0),
    CW90(1),
    CW180(2),
    CW270(3);

    private final int turns;

    StructureRotation(int turns) {
        this.turns = turns;
    }

    /** 90° 的倍数（0～3），给 {@code SchematicTransform} 用。 */
    public int turns() {
        return turns;
    }
}
