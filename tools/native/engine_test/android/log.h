// Desktop stand-in for <android/log.h>, so app/src/main/cpp/engine.cpp builds for tests.
#pragma once
#include <cstdarg>
#include <cstdio>
enum { ANDROID_LOG_DEBUG = 3, ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5, ANDROID_LOG_ERROR = 6 };
inline int __android_log_print(int, const char * tag, const char * fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    std::fprintf(stderr, "[%s] ", tag);
    int n = std::vfprintf(stderr, fmt, ap);
    std::fputc(10, stderr);
    va_end(ap);
    return n;
}
inline int __android_log_write(int, const char *, const char *) { return 0; }  // llama.cpp chatter: quiet
