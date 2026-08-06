// 宿主机（桌面 JVM 单测）用的 android/log.h 兼容桩。
// Android 构建用 NDK 自带的真头文件；这里只让 pikafish_jni.cpp 在
// 宿主编译时能通过，日志落到 stderr。
#ifndef HOST_ANDROID_LOG_H
#define HOST_ANDROID_LOG_H

#include <cstdio>

#define ANDROID_LOG_VERBOSE 2
#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6

inline void __android_log_print_stub(int /*prio*/, const char* tag, const char* fmt, ...) {
    (void)tag;
    (void)fmt;
}

#define __android_log_print(prio, tag, ...) \
    do { (void)(prio); (void)(tag); } while (0)

#endif
