cmake_minimum_required(VERSION 3.22)
project(bivouac LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
endif()

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

include(${CMAKE_CURRENT_LIST_DIR}/third_party.cmake)

add_library(bivouac SHARED
        engine.cpp
        corpus.cpp
        sql_pack.cpp
        jni_bridge.cpp)

target_include_directories(bivouac PRIVATE
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/ggml/include)

target_link_libraries(bivouac
        bivouac_sqlite
        bivouac_zstd
        llama
        llama-common
        android
        log)
