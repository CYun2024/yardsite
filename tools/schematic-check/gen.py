import gzip, struct, math, os
HERE = os.path.dirname(os.path.abspath(__file__))
D = os.path.join(HERE, 'out')
os.makedirs(D, exist_ok=True)

def TAG_Byte(v): return (1, struct.pack('>b', v))
def TAG_Short(v): return (2, struct.pack('>h', v))
def TAG_Int(v): return (3, struct.pack('>i', v))
def TAG_Long(v): return (4, struct.pack('>q', v))
def TAG_Float(v): return (5, struct.pack('>f', v))
def TAG_Double(v): return (6, struct.pack('>d', v))
def TAG_ByteArray(v): return (7, struct.pack('>i', len(v)) + bytes(v))
def TAG_String(v):
    b = v.encode('utf-8')
    return (8, struct.pack('>H', len(b)) + b)
def TAG_List(t, items):
    body = b''.join(p for _, p in items)
    return (9, struct.pack('>bi', t, len(items)) + body)
def TAG_Compound(d):
    body = b''
    for k, (t, p) in d.items():
        body += struct.pack('>b', t) + struct.pack('>H', len(k.encode())) + k.encode() + p
    body += b'\x00'
    return (10, body)
def TAG_IntArray(v): return (11, struct.pack('>i', len(v)) + b''.join(struct.pack('>i', x) for x in v))
def TAG_LongArray(v): return (12, struct.pack('>i', len(v)) + b''.join(struct.pack('>q', x) for x in v))

def write(path, root):
    body = struct.pack('>b', 10) + struct.pack('>H', 0) + root[1]
    with gzip.open(path, 'wb') as f:
        f.write(body)

def pack_states(cells, size, bits):
    per = 64 // bits
    words = []
    cur = 0; used = 0
    for v in cells:
        cur |= (v & ((1 << bits) - 1)) << (used * bits)
        used += 1
        if used == per:
            words.append(cur); cur = 0; used = 0
    if used: words.append(cur)
    return words

AIR, STONE, STAIRS = 0, 1, 2

# ── 1. litematic：主厅 8x4x6 + 侧翼 3x3x3（Position 偏到 +8）──────
sx, sy, sz = 8, 4, 6
main = [AIR] * (sx * sy * sz)
def put(arr, X, Y, Z, val, w, h, d):
    arr[(Y * d + Z) * w + X] = val
# 地板 y=0 整层石头
for x in range(sx):
    for z in range(sz):
        put(main, x, 0, z, STONE, sx, sy, sz)
# 四周墙（y=1,2），留一个门在 z=0 中间两格
for x in range(sx):
    for z in range(sz):
        if x in (0, sx-1) or z in (0, sz-1):
            for y in (1, 2):
                if z == 0 and x in (3, 4) and y in (1, 2):
                    continue      # 门
                put(main, x, y, z, STONE, sx, sy, sz)
# 屋顶 y=3
for x in range(sx):
    for z in range(sz):
        put(main, x, 3, z, STONE, sx, sy, sz)
# 一个楼梯在屋里，朝向 north
put(main, 5, 1, 3, STAIRS, sx, sy, sz)

main_words = pack_states(main, (sx, sy, sz), 2)

# 侧翼：负 Size，从 Position 往负 z 铺开
wx, wy, wz = 3, 3, 3
wing = [AIR] * (wx * wy * wz)
for x in range(wx):
    for z in range(wz):
        put(wing, x, 0, z, STONE, wx, wy, wz)
        put(wing, x, 1, z, STONE, wx, wy, wz)
wing_words = pack_states(wing, (wx, wy, wz), 2)

palette = TAG_List(10, [
    TAG_Compound({'Name': TAG_String('minecraft:air')}),
    TAG_Compound({'Name': TAG_String('minecraft:stone')}),
    TAG_Compound({'Name': TAG_String('minecraft:oak_stairs'),
                  'Properties': TAG_Compound({'facing': TAG_String('north')})}),
])

root = TAG_Compound({
    'MinecraftDataVersion': TAG_Int(3955),
    'Version': TAG_Int(6),
    'Metadata': TAG_Compound({'Name': TAG_String('Test Hall'), 'Author': TAG_String('dev')}),
    'Regions': TAG_Compound({
        'main': TAG_Compound({
            'Position': TAG_IntArray([0, 0, 0]),
            'Size': TAG_IntArray([sx, sy, sz]),
            'BlockStatePalette': palette,
            'BlockStates': TAG_LongArray(main_words),
        }),
        'wing': TAG_Compound({
            'Position': TAG_IntArray([10, 0, -1]),
            'Size': TAG_IntArray([wx, wy, -wz]),   # 负的 z：往 -z 铺
            'BlockStatePalette': palette,
            'BlockStates': TAG_LongArray(wing_words),
        }),
    }),
})
write(D + '/test.litematic', root)

# ── 2. 原版结构 .nbt（同样的 8x4x6 主厅，未压缩）──────────────────
blocks = []
for y in range(sy):
    for z in range(sz):
        for x in range(sx):
            v = main[(y * sz + z) * sx + x]
            if v != AIR:
                blocks.append(TAG_Compound({
                    'pos': TAG_List(3, [TAG_Int(x), TAG_Int(y), TAG_Int(z)]),
                    'state': TAG_Int(v),
                }))
vanilla = TAG_Compound({
    'DataVersion': TAG_Int(3955),
    'size': TAG_List(3, [TAG_Int(sx), TAG_Int(sy), TAG_Int(sz)]),
    'palette': TAG_List(10, [
        TAG_Compound({'Name': TAG_String('minecraft:air')}),
        TAG_Compound({'Name': TAG_String('minecraft:stone')}),
        TAG_Compound({'Name': TAG_String('minecraft:oak_stairs'),
                      'Properties': TAG_Compound({'facing': TAG_String('north')})}),
    ]),
    'blocks': TAG_List(10, blocks),
    'entities': TAG_List(10, []),
})
body = struct.pack('>b', 10) + struct.pack('>H', 0) + vanilla[1]
open(D + '/test.nbt', 'wb').write(body)

# ── 2b. 封死的空心盒 + 两个变体：里面加隔墙 / 外面贴一块凸起 ─────────────
# 用来钉住"内部看不到的线不画"和"装饰碎面不画"这两条判据
def write_box(path, sx, sy, sz, solid):
    pal = TAG_List(10, [
        TAG_Compound({'Name': TAG_String('minecraft:air')}),
        TAG_Compound({'Name': TAG_String('minecraft:stone')}),
    ])
    blocks = []
    for y in range(sy):
        for z in range(sz):
            for x in range(sx):
                if solid(x, y, z):
                    blocks.append(TAG_Compound({
                        'pos': TAG_List(3, [TAG_Int(x), TAG_Int(y), TAG_Int(z)]),
                        'state': TAG_Int(1),
                    }))
    root = TAG_Compound({
        'DataVersion': TAG_Int(3955),
        'size': TAG_List(3, [TAG_Int(sx), TAG_Int(sy), TAG_Int(sz)]),
        'palette': pal,
        'blocks': TAG_List(10, blocks),
        'entities': TAG_List(10, []),
    })
    body = struct.pack('>b', 10) + struct.pack('>H', 0) + root[1]
    open(path, 'wb').write(body)

def write_blocks(path, sx, sy, sz, cell):
    """按格子给方块状态：cell(x,y,z) 返回 (名字, 属性dict) 或 None。

    write_box 只会放一种方块，验"楼梯/半砖/活板门这些形状不是满格"就不够了。
    """
    states = []
    index = {}
    blocks = []
    for y in range(sy):
        for z in range(sz):
            for x in range(sx):
                st = cell(x, y, z)
                if st is None:
                    continue
                name, props = st
                key = (name, tuple(sorted(props.items())))
                if key not in index:
                    index[key] = len(states)
                    states.append((name, props))
                blocks.append((x, y, z, index[key] + 1))     # +1：调色板第 0 项是空气
    entries = [TAG_Compound({'Name': TAG_String('minecraft:air')})]
    for name, props in states:
        entry = {'Name': TAG_String(name)}
        if props:
            entry['Properties'] = TAG_Compound({k: TAG_String(v) for k, v in props.items()})
        entries.append(TAG_Compound(entry))
    root = TAG_Compound({
        'DataVersion': TAG_Int(3955),
        'size': TAG_List(3, [TAG_Int(sx), TAG_Int(sy), TAG_Int(sz)]),
        'palette': TAG_List(10, entries),
        'blocks': TAG_List(10, [
            TAG_Compound({'pos': TAG_List(3, [TAG_Int(x), TAG_Int(y), TAG_Int(z)]),
                          'state': TAG_Int(st)}) for x, y, z, st in blocks]),
        'entities': TAG_List(10, []),
    })
    body = struct.pack('>b', 10) + struct.pack('>H', 0) + root[1]
    open(path, 'wb').write(body)

def shell5(x, y, z):
    return x in (0, 4) or y in (0, 4) or z in (0, 4)

# 5×5×5 外壳（内腔 3×3×3 封死）→ 线框应该正好是外面那个立方体的 12 条棱
write_box(D + '/test-hollow.nbt', 5, 5, 5, shell5)
# 同一只盒子，里面再立一道隔墙（x=2）→ 隔墙全是内表面，线框必须还是 12 条
write_box(D + '/test-hollow-partition.nbt', 5, 5, 5,
          lambda x, y, z: shell5(x, y, z) or (x == 2 and 1 <= y <= 3 and 1 <= z <= 3))
# 同一只盒子，外墙面上挂五块装饰（1×1 的凸起）→ 装饰自己的侧面是碎面，判定要能把它们滤掉，
# 只剩"墙上挖了五个洞"的框（与不给 minFace 过滤时相比线要少）
BUMP = {(1, 1), (1, 3), (2, 2), (3, 1), (3, 3)}
write_box(D + '/test-hollow-decor.nbt', 6, 5, 5,
          lambda x, y, z: (x <= 4 and shell5(x, y, z)) or (x == 5 and (y, z) in BUMP))

# ── 2e. 方块形状：半砖只挡住后面柱子下半格（验"遮挡精度细到盒子"）──────────
# 半砖 (0,0,0) 高 0.5；石柱 (0,0,1) 满格。从 -z 平视，柱子那条左前竖棱
# 只在 y=0.5..1 露出来，下半格被半砖挡着 —— 按整格算的话会整根不画。
def slab_occlusion(x, y, z):
    if x == 0 and y == 0 and z == 0:
        return ('minecraft:oak_slab', {'type': 'bottom'})
    if x == 0 and y == 0 and z == 1:
        return ('minecraft:stone', {})
    return None
write_blocks(D + '/test-slab-occlusion.nbt', 1, 1, 2, slab_occlusion)

# ── 2d. 两个盒子错开叠（验"一个盒子把另一个的轮廓挡住一段"）─────────────
# 后墙 x 0..5、y 0..5、z 0..1（+z 面在 z=2）；前面的矮墙 x 0..2、y 0..5、z 2..3，
# 从 +z 正面看，后墙左边那根竖棱整根被前面的墙挡住，右边那根露着。
def stack(x, y, z):
    back = 0 <= x <= 5 and 0 <= y <= 5 and 0 <= z <= 1
    frontWall = 0 <= x <= 2 and 0 <= y <= 5 and 2 <= z <= 3
    return back or frontWall
write_box(D + '/test-stack.nbt', 7, 6, 4, stack)

# ── 2c. 遮挡剔除用：一根长条 + 半截挡板（验"挡一半就画一半"）─────────
# 长条 x=3..4, y=0..1, z=0..10；挡板 x=0..1, y=0..1, z=0..5。
# 从 -x 侧平视，长条正面（x=3）上沿本该从 z=0 画到 z=10，被挡板挡住前半截 → 只该剩 z=6..10 四格。
def bar_blocker(x, y, z):
    bar = 3 <= x <= 4 and 0 <= y <= 1 and 0 <= z <= 10
    # 挡板比长条高一格（尖端到 y=3），免得"挡板顶面和长条上沿正好同高"这种擦边情形
    blocker = 0 <= x <= 1 and 0 <= y <= 2 and 0 <= z <= 5
    return bar or blocker
write_box(D + '/test-half.nbt', 5, 2, 11, bar_blocker)

# ── 3. Sponge .schem（3x2x3 实心，全部石头）────────────────────────
def varint(v):
    out = b''
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out += bytes([b | 0x80])
        else:
            out += bytes([b]); return out
sw, sh, sl = 3, 2, 3
data = b''.join(varint(1) for _ in range(sw * sh * sl))
schem = TAG_Compound({
    'Version': TAG_Int(2),
    'DataVersion': TAG_Int(3955),
    'Width': TAG_Short(sw), 'Height': TAG_Short(sh), 'Length': TAG_Short(sl),
    'Palette': TAG_Compound({'minecraft:air': TAG_Int(0), 'minecraft:stone': TAG_Int(1)}),
    'PaletteMax': TAG_Int(2),
    'BlockData': TAG_ByteArray(data),
    'Offset': TAG_IntArray([0, 0, 0]),
})
write(D + '/test.schem', schem)
print("generated", os.listdir(D))

# ── 4. 只有主厅的 litematic（用来和 .nbt 逐格对比）──────────────────
root_main = TAG_Compound({
    'MinecraftDataVersion': TAG_Int(3955),
    'Version': TAG_Int(6),
    'Metadata': TAG_Compound({'Name': TAG_String('Test Hall Only')}),
    'Regions': TAG_Compound({
        'main': TAG_Compound({
            'Position': TAG_IntArray([0, 0, 0]),
            'Size': TAG_IntArray([sx, sy, sz]),
            'BlockStatePalette': palette,
            'BlockStates': TAG_LongArray(main_words),
        }),
    }),
})
write(D + '/test-main.litematic', root_main)

# ── 4b. 同主厅，但 Position/Size 写成 {x,y,z} 复合、BlockStates 写成 long 列表
#         （实测有导出工具这么写；以前这种文件每个 region 都被判"没有 Region"整块跳过）
def TAG_Pos(x, y, z):
    return TAG_Compound({'x': TAG_Int(x), 'y': TAG_Int(y), 'z': TAG_Int(z)})

root_compound = TAG_Compound({
    'MinecraftDataVersion': TAG_Int(3955),
    'Version': TAG_Int(7),
    'Metadata': TAG_Compound({'Name': TAG_String('Test Hall (compound pos)'),
                              'EnclosingSize': TAG_Pos(sx, sy, sz)}),
    'Regions': TAG_Compound({
        'Unnamed': TAG_Compound({
            'Position': TAG_Pos(0, 0, 0),
            'Size': TAG_Pos(sx, sy, sz),
            'BlockStatePalette': palette,
            'BlockStates': TAG_List(4, [TAG_Long(w) for w in main_words]),
            'Entities': TAG_List(10, []),
        }),
    }),
})
write(D + '/test-compound.litematic', root_compound)

# ── 4c. 同上但 Size 带负号（x 方向 -sx、Position 挪到 x=sx-1）──────────
root_neg = TAG_Compound({
    'MinecraftDataVersion': TAG_Int(3955),
    'Version': TAG_Int(7),
    'Metadata': TAG_Compound({'Name': TAG_String('Test Hall (negative compound size)')}),
    'Regions': TAG_Compound({
        'Unnamed': TAG_Compound({
            'Position': TAG_Pos(sx - 1, 0, 0),
            'Size': TAG_Pos(-sx, sy, sz),
            'BlockStatePalette': palette,
            'BlockStates': TAG_LongArray(main_words),
        }),
    }),
})
write(D + '/test-compound-neg.litematic', root_neg)

# ── 5. 大结构：120x60x120 的空心塔楼（性能与线段上限用）──────────────
BX, BY, BZ = 120, 60, 120
big = [AIR] * (BX * BY * BZ)
for y in range(BY):
    for z in range(BZ):
        for x in range(BX):
            edge = (x in (0, BX - 1) or z in (0, BZ - 1))
            if y in (0, BY - 1) or edge:
                big[(y * BZ + z) * BX + x] = STONE
big_pal = TAG_List(10, [
    TAG_Compound({'Name': TAG_String('minecraft:air')}),
    TAG_Compound({'Name': TAG_String('minecraft:stone')}),
])
write(D + '/big.litematic', TAG_Compound({
    'MinecraftDataVersion': TAG_Int(3955),
    'Version': TAG_Int(6),
    'Metadata': TAG_Compound({'Name': TAG_String('Big Tower')}),
    'Regions': TAG_Compound({
        'tower': TAG_Compound({
            'Position': TAG_IntArray([0, 0, 0]),
            'Size': TAG_IntArray([BX, BY, BZ]),
            'BlockStatePalette': big_pal,
            'BlockStates': TAG_LongArray(pack_states(big, (BX, BY, BZ), 2)),
        }),
    }),
}))
print("big structure written")
