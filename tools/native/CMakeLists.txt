# Desktop build of the app's native retrieval code, for testing against a real
# wiki.db without a phone:  cmake -B build -G Ninja && ninja -C build
cmake_minimum_required(VERSION 3.22)
project(bivouac_native_tools C CXX)
set(CMAKE_CXX_STANDARD 17)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

set(ROOT ${CMAKE_CURRENT_LIST_DIR}/../..)
include(${ROOT}/app/src/main/cpp/third_party.cmake)

add_executable(corpus_cli corpus_cli.cpp ${ROOT}/app/src/main/cpp/corpus.cpp ${ROOT}/app/src/main/cpp/fts_rank.c)
target_include_directories(corpus_cli PRIVATE ${ROOT}/app/src/main/cpp)
target_link_libraries(corpus_cli bivouac_sqlite bivouac_zstd)

# colrank() as a loadable SQLite extension for the Python tools (retrieve_proto.py).
add_library(fts_rank MODULE ${ROOT}/app/src/main/cpp/fts_rank.c)
target_compile_definitions(fts_rank PRIVATE BIVOUAC_LOADABLE)
target_include_directories(fts_rank PRIVATE ${ROOT}/third_party/sqlite)
target_link_libraries(fts_rank m)
set_target_properties(fts_rank PROPERTIES PREFIX "")
