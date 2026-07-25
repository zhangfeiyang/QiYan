# 中国象棋 App - Native 部分
#
# 当前版本使用 ProcessBuilder 调用 Pikafish 可执行文件,无需 NDK 编译.
# 此 CMakeLists.txt 是占位文件,防止 Gradle 报告错误.
#
# 如未来需要 JNI 集成(提升性能),请参考:
# - docs/NDK_BUILD.md

cmake_minimum_required(VERSION 3.22.1)
project(zhongguoxiangqi CXX)

# 占位: 不构建任何 native 代码
# 引擎通过 stdio UCI 协议与 Pikafish 进程通信

# 完整 Pikafish JNI 集成时,取消注释以下内容:
# add_subdirectory(pikafish pikafish_build)
# target_link_libraries(zhongguoxiangqi pikafish_engine)