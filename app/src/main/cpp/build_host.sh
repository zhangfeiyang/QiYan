#!/usr/bin/env bash
# 编译 x86-64 Linux 版 libxqnnue.so，供桌面 JVM 单元测试加载。
#
# Android 构建只出 arm64-v8a，桌面加载不到；但源码 (nnue_core.cpp + nnue_jni.cpp)
# 不含任何 Android 特有 API，用宿主 g++ 直接编即可。
#
# 用法:
#   cd app/src/main/cpp && bash build_host.sh
#
# 输出: host_lib/libxqnnue.so  →  Gradle 测试配了 java.library.path 指向这里

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
OUT_DIR="$SCRIPT_DIR/host_lib"
JDK_INCLUDE="/usr/lib/jvm/java-17-openjdk-amd64/include"

if [ ! -f "$JDK_INCLUDE/jni.h" ]; then
    echo "!! 找不到 JNI 头文件: $JDK_INCLUDE/jni.h" >&2
    echo "   确认 JDK 17 已安装: sudo apt install openjdk-17-jdk" >&2
    exit 1
fi

mkdir -p "$OUT_DIR"

# -mavx2 让 nnue_core.cpp 走 AVX2 求值路径 (比标量快, 数据生成一跑几小时,
# 值这个钱)。代价是这个 .so 只能在支持 AVX2 的 CPU 上加载 —— 只用于本机
# 开发/跑分, 不进 APK, 可以接受。CPU 太老就把这行去掉退回标量。
set -x
g++ -shared -fPIC -O3 -std=c++17 -mavx2 \
    -fno-exceptions -fno-rtti -fvisibility=hidden \
    -I"$JDK_INCLUDE" \
    -I"$JDK_INCLUDE/linux" \
    "$SCRIPT_DIR/nnue_core.cpp" \
    "$SCRIPT_DIR/nnue_jni.cpp" \
    "$SCRIPT_DIR/board.cpp" \
    "$SCRIPT_DIR/movegen.cpp" \
    "$SCRIPT_DIR/see.cpp" \
    "$SCRIPT_DIR/search.cpp" \
    "$SCRIPT_DIR/search_jni.cpp" \
    -o "$OUT_DIR/libxqnnue.so"

echo "→ $OUT_DIR/libxqnnue.so ($(du -h "$OUT_DIR/libxqnnue.so" | cut -f1))"
