#!/bin/sh
# 投影解析自检：生成几份构造好的 .litematic/.nbt/.schem，再逐项断言解析结果。
# 不启动游戏、不需要 Minecraft 依赖 —— schematic 包是纯 Java 的。
set -e
here=$(cd "$(dirname "$0")" && pwd)
out="$here/out"
mkdir -p "$out/classes"

python "$here/gen.py"
javac -encoding UTF-8 -d "$out/classes" \
    $(find "$here/../../src/main/java/dev/yard/site/schematic" -name '*.java') \
    "$here/Check.java"
java -Dstdout.encoding=UTF-8 -cp "$out/classes" check.Check "$out"
