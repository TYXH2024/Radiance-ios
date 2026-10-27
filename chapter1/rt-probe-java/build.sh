#!/usr/bin/env bash
# rt-probe 从源码重建脚本
# 依赖: JDK 8+ (javac 或任意 JDK), glslangValidator (Khronos glslang >= 12), zip
# 用法: ./build.sh
set -euo pipefail
cd "$(dirname "$0")"

DEPS_CP=""
for jar in \
    "$HOME/.m2/repository/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar" \
    "$HOME/.m2/repository/org/lwjgl/lwjgl-vulkan/3.3.3/lwjgl-vulkan-3.3.3.jar" \
    ./deps/fabric-loader.jar; do
    [ -f "$jar" ] && DEPS_CP="$DEPS_CP:$jar"
done
# 若本机没有 m2 依赖，自动下载
mkdir -p deps
if [ ! -f deps/lwjgl-3.3.3.jar ]; then
    echo ">> 下载 LWJGL 3.3.3 ..."
    curl -sL -o deps/lwjgl-3.3.3.jar \
        "https://repo1.maven.org/maven2/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar"
fi
if [ ! -f deps/lwjgl-vulkan-3.3.3.jar ]; then
    echo ">> 下载 lwjgl-vulkan 3.3.3 ..."
    curl -sL -o deps/lwjgl-vulkan-3.3.3.jar \
        "https://repo1.maven.org/maven2/org/lwjgl/lwjgl-vulkan/3.3.3/lwjgl-vulkan-3.3.3.jar"
fi
if [ ! -f deps/fabric-loader.jar ]; then
    echo ">> 下载 fabric-loader 0.16.10 ..."
    curl -sL -o deps/fabric-loader.jar \
        "https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.16.10/fabric-loader-0.16.10.jar"
fi

echo ">> 编译着色器 (SPIR-V 双变体) ..."
GLSLANG="${GLSLANG:-glslangValidator}"
$GLSLANG -V --target-env vulkan1.2 shaders/probe.comp -o assets/rtprobe/probe12.spv
$GLSLANG -V --target-env spirv1.4  shaders/probe.comp -o assets/rtprobe/probe14.spv

echo ">> 编译 Java (target 8) ..."
rm -rf classes jarbuild rt-probe-1.1.jar
mkdir -p classes
if command -v javac >/dev/null 2>&1; then
    javac -source 8 -target 8 -encoding UTF-8 -nowarn \
        -cp "deps/lwjgl-3.3.3.jar:deps/lwjgl-vulkan-3.3.3.jar:deps/fabric-loader.jar" \
        -d classes src/net/prisma/rtprobe/*.java
else
    # 无 javac 时用 ecj
    if [ ! -f deps/ecj.jar ]; then
        curl -sL -o deps/ecj.jar \
            "https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.36.0/ecj-3.36.0.jar"
    fi
    java -jar deps/ecj.jar -nowarn -source 8 -target 8 -encoding UTF-8 \
        -cp "deps/lwjgl-3.3.3.jar:deps/lwjgl-vulkan-3.3.3.jar:deps/fabric-loader.jar" \
        -d classes src/net/prisma/rtprobe/*.java
fi

echo ">> 打包 ..."
mkdir -p jarbuild/META-INF jarbuild/rtprobe
cp -r classes/* jarbuild/
cp fabric.mod.json jarbuild/
cp assets/rtprobe/*.spv jarbuild/rtprobe/
printf 'Manifest-Version: 1.0\nMain-Class: net.prisma.rtprobe.RTProbeMod\nImplementation-Title: rt-probe\nImplementation-Version: 1.1.0\n\n' \
    > jarbuild/META-INF/MANIFEST.MF
(cd jarbuild && zip -q -X -r ../rt-probe-1.1.jar META-INF fabric.mod.json net rtprobe)

echo ">> 完成: rt-probe-1.1.jar"
