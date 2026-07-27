cmake_minimum_required(VERSION 3.22.1)
project(xqnnue LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# host_test.cpp 是宿主机验证用的, 有 main(), 不能进 Android 库
add_library(xqnnue SHARED
    nnue_core.cpp
    nnue_jni.cpp)

# -O3 而不是默认的 -O2: 这个库存在的唯一意义就是求值要快。
# 不开 -ffast-math —— 全是整数运算, 用不上, 而它会放宽浮点语义, 白担风险。
target_compile_options(xqnnue PRIVATE
    -O3
    -fno-exceptions
    -fno-rtti
    -fvisibility=hidden)

# 只出 arm64-v8a (见 build.gradle.kts 的 abiFilters), 而 AArch64 一定带
# NEON/ASIMD, 所以不需要运行时 CPU 特性探测。
target_link_libraries(xqnnue log)
