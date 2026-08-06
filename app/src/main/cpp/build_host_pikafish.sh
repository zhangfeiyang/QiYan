#!/usr/bin/env bash
# 宿主机 x86-64 Linux 版 libpikafish.so，供 JVM 单测/JNI 冒烟加载。
#
# 与 Android 构建同源（同一个 pikafish_jni.cpp），只是去掉 android/log.h
# 依赖 —— pikafish_jni.cpp 里用 __android_log_print，宿主机没有，需要
# -DANDROID_LOG_TAG 或替换日志宏。PikaFish 源码自带 __android_log_print
# 条件编译（检查 __ANDROID__），宿主编译时不定义 __ANDROID__ 即可走
# fprintf 分支。
set -euo pipefail

script_dir=$(cd "$(dirname "$0")" && pwd)
repo_root=$(cd "$script_dir/../../../.." && pwd)
out_dir="$script_dir/host_lib"
jdks=("/usr/lib/jvm/java-17-openjdk-amd64" "/usr/lib/jvm/java-21-openjdk-amd64")
for jdk in "${jdks[@]}"; do
    if [ -f "$jdk/include/jni.h" ]; then JDK_INCLUDE="$jdk"; break; fi
done
if [ -z "${JDK_INCLUDE:-}" ]; then
    echo "!! 找不到 JNI 头文件" >&2
    exit 1
fi

mkdir -p "$out_dir"
pika_src="$repo_root/third_party/Pikafish/src"

mapfile -t sources < <(
    find "$pika_src" -name "*.cpp" \
        ! -name "main.cpp" ! -name "tune.cpp" \
        ! -path "*/universal/*" ! -path "*/temp_builds/*" \
        | sort
)
mapfile -t asm_sources < <(
    find "$pika_src" -name "*.S" \
        ! -path "*/universal/*" ! -path "*/temp_builds/*" \
        | sort
)

g++ -shared -fPIC -O3 -std=c++17 \
    -fno-exceptions -fno-rtti -fvisibility=hidden \
    -DIS_64BIT \
    -DUSE_POPCNT \
    -I"$JDK_INCLUDE/include" -I"$JDK_INCLUDE/include/linux" \
     \
    -I"$pika_src" \
    -I"$script_dir/host_include" \
    "${sources[@]}" \
    "${asm_sources[@]}" \
    -o "$out_dir/libpikafish.so"

echo "→ $out_dir/libpikafish.so ($(du -h "$out_dir/libpikafish.so" | cut -f1))"
