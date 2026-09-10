cmake_minimum_required(VERSION 3.22.1)
project(zhongguoxiangqi LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# PikaFish 引擎（GPL-3，见 third_party/Pikafish/Copying.txt）。
# 整体采用后 App 以 GPL-3 开源。pikafish_jni.cpp 提供
# com.zhongguoxiangqi.engine.PikafishNative 的 JNI 入口。
set(PIKA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/Pikafish/src)
file(GLOB PIKA_SOURCES CONFIGURE_DEPENDS
    "${PIKA_SRC}/*.cpp"
    "${PIKA_SRC}/nnue/*.cpp"
    "${PIKA_SRC}/nnue/features/*.cpp"
    "${PIKA_SRC}/external/common/*.cpp"
    "${PIKA_SRC}/external/decompress/*.cpp"
    "${PIKA_SRC}/external/decompress/*.S")
list(FILTER PIKA_SOURCES EXCLUDE REGEX ".*/(main|tune)\.cpp$")

add_library(pikafish SHARED ${PIKA_SOURCES})

target_compile_options(pikafish PRIVATE
    -O3
    -fno-exceptions
    -fno-rtti
    -fvisibility=hidden
    -DIS_64BIT)
# 16 KB 内存页面对齐: Google Play 自 2025-11 起对 targetSdk 35+ 的原生库
# 强制要求, 否则新版本上传被 Console 拒收("不支持 16 KB 页面大小").
target_link_options(pikafish PRIVATE
    -Wl,-z,max-page-size=16384)
target_include_directories(pikafish PRIVATE ${PIKA_SRC})
target_link_libraries(pikafish log)
