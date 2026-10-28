#!/usr/bin/env bash
# pathtrace 完整构建（macOS + Xcode toolchain 上运行；CI 于 macos-26 runner 执行）
# 产物: pathtrace-0.4.0.jar（内含 iphoneos arm64 dylib + metallib + MSL 源
#       + mixin 类与 pathtrace.mixins.json）
# 依赖: Xcode (xcrun metal/metallib/iphoneos SDK), clang, JDK 8+(javac 或 ecj), zip
# 用法: JAVA_HOME=<jdk21> ./build.sh
set -euo pipefail
cd "$(dirname "$0")"

MOD_ID="pathtrace"
VERSION="0.4.0"
JAR_NAME="pathtrace-${VERSION}.jar"

# ------------------------------------------------------------------
# [1/4] Java 编译依赖（fabric-loader 为主；mixin 编译期需要 sponge-mixin——
#       运行环境由 Fabric 安装自带；lifecycle/api-base 仅编译期需要）
# ------------------------------------------------------------------
mkdir -p deps
dl() { # dl <url> <file>
    [ -f "deps/$2" ] || curl -sL --retry 3 -o "deps/$2" "$1"
}
dl "https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.16.10/fabric-loader-0.16.10.jar" fabric-loader.jar
dl "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api-base/2.0.8%2B24b2efa99f/fabric-api-base-2.0.8%2B24b2efa99f.jar" fabric-api-base.jar
dl "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-lifecycle-events-v1/4.1.11%2B24b2efa99f/fabric-lifecycle-events-v1-4.1.11%2B24b2efa99f.jar" fabric-lifecycle-events-v1.jar
dl "https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.36.0/ecj-3.36.0.jar" ecj.jar
# sponge-mixin（@Mixin/@Inject 注解——仅编译期；版本回退链同 metal -std 级联模式）
MIXIN_OK=""
for MV in 0.16.5+mixin.0.8.7 0.16.4+mixin.0.8.7 0.15.5+mixin.0.8.7 0.14.6+mixin.0.8.7; do
    if dl "https://maven.fabricmc.net/net/fabricmc/sponge-mixin/${MV}/sponge-mixin-${MV}.jar" sponge-mixin.jar \
         && [ -s "deps/sponge-mixin.jar" ] && unzip -l deps/sponge-mixin.jar 2>/dev/null | grep -q "org/spongepowered/asm/mixin/Mixin.class"; then
        MIXIN_OK="$MV"; break
    fi
    rm -f deps/sponge-mixin.jar
done
if [ -z "$MIXIN_OK" ]; then
    echo "FATAL: no sponge-mixin version downloadable (mixin classes cannot compile)"
    exit 1
fi
echo "   sponge-mixin: $MIXIN_OK"

# ------------------------------------------------------------------
# [2/4] 原生 dylib —— iphoneos arm64 交叉编译（jni.h 来自 JDK）
# ------------------------------------------------------------------
SDK="$(xcrun --sdk iphoneos --show-sdk-path)"
JH="${JAVA_HOME:-$(/usr/libexec/java_home)}"
mkdir -p build/native
echo ">> [2/4] 交叉编译 libradiance_pathtrace.dylib (arm64 iphoneos) ..."
clang -fobjc-arc -target arm64-apple-ios16.0.0 -isysroot "$SDK" \
    -I "$JH/include" -I "$JH/include/darwin" \
    -O2 -shared \
    -framework Metal -framework Foundation \
    -o build/native/libradiance_pathtrace.dylib \
    native/metal_pathtrace.m
file build/native/libradiance_pathtrace.dylib

echo ">> 签名（ad-hoc；Xcode15+ linker 已默认 ad-hoc，此处兜底重签）..."
if command -v ldid >/dev/null 2>&1; then
    ldid -S build/native/libradiance_pathtrace.dylib && echo "ldid -S OK"
elif command -v codesign >/dev/null 2>&1; then
    codesign -s - --force build/native/libradiance_pathtrace.dylib && echo "codesign ad-hoc OK"
else
    echo "(无 ldid/codesign —— 依赖 linker 默认 ad-hoc 签名)"
fi

# ------------------------------------------------------------------
# [3/4] metallib —— MSL ray_query + pathtrace + present kernels（-std 级联直到编译通过）
# ------------------------------------------------------------------
echo ">> [3/4] 编译 pathtrace.metallib (scene_trace + pathtrace + present kernels) ..."
OK_STD=""
for STD in metal3.2 metal3.1 metal3.0 metal2.4; do
    if xcrun -sdk iphoneos metal -std="$STD" -c native/pathtrace.metal \
         -target air64-apple-ios17.0.0 -o build/native/pathtrace.air; then
        OK_STD="$STD"; break
    fi
    rm -f build/native/pathtrace.air
done
if [ -z "$OK_STD" ]; then
    echo "FATAL: no -std value compiled the pathtrace kernels for iOS"
    exit 1
fi
echo "   MSL std: $OK_STD"
xcrun -sdk iphoneos metallib build/native/pathtrace.air \
    -o build/native/pathtrace.metallib
file build/native/pathtrace.metallib

# ------------------------------------------------------------------
# [4/4] Java 编译 + JAR 组装
# ------------------------------------------------------------------
echo ">> [4/4] 编译 Java + 打包 ${JAR_NAME} ..."
rm -rf classes jarbuild "${JAR_NAME}"
mkdir -p classes
CP="deps/fabric-loader.jar:deps/fabric-lifecycle-events-v1.jar:deps/fabric-api-base.jar:deps/sponge-mixin.jar"
if command -v javac >/dev/null 2>&1; then
    javac -source 8 -target 8 -encoding UTF-8 -nowarn -proc:none \
        -cp "$CP" -d classes src/net/prisma/pathtrace/*.java src/net/prisma/pathtrace/mixin/*.java
else
    java -jar deps/ecj.jar -nowarn -proc:none -source 8 -target 8 -encoding UTF-8 \
        -cp "$CP" -d classes src/net/prisma/pathtrace/*.java src/net/prisma/pathtrace/mixin/*.java
fi

mkdir -p jarbuild/META-INF "jarbuild/${MOD_ID}"
cp -r classes/* jarbuild/
cp fabric.mod.json pathtrace.mixins.json jarbuild/
cp build/native/libradiance_pathtrace.dylib \
   build/native/pathtrace.metallib \
   native/pathtrace.metal \
   "jarbuild/${MOD_ID}/"
printf 'Manifest-Version: 1.0\nMain-Class: net.prisma.pathtrace.PathtraceMod\nImplementation-Title: pathtrace\nImplementation-Version: %s\n\n' "$VERSION" \
    > jarbuild/META-INF/MANIFEST.MF
(cd jarbuild && zip -q -X -r "../${JAR_NAME}" META-INF fabric.mod.json pathtrace.mixins.json net "${MOD_ID}")

echo ">> 完成: ${JAR_NAME}"
unzip -l "${JAR_NAME}" | grep -E "mixin|mixins.json" || echo "FATAL: mixin classes missing from jar"
test -s "${JAR_NAME}"
