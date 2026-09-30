# SQLite (with FTS5) and the zstd decompressor, shared by the app and desktop tools.
set(BIVOUAC_TP ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party)

add_library(bivouac_sqlite STATIC ${BIVOUAC_TP}/sqlite/sqlite3.c)
target_include_directories(bivouac_sqlite PUBLIC ${BIVOUAC_TP}/sqlite)
target_compile_definitions(bivouac_sqlite PRIVATE
        SQLITE_ENABLE_FTS5
        SQLITE_THREADSAFE=2
        SQLITE_DEFAULT_MEMSTATUS=0
        SQLITE_OMIT_LOAD_EXTENSION
        SQLITE_DQS=0)
set_target_properties(bivouac_sqlite PROPERTIES POSITION_INDEPENDENT_CODE ON)

file(GLOB BIVOUAC_ZSTD_SRC ${BIVOUAC_TP}/zstd/common/*.c ${BIVOUAC_TP}/zstd/decompress/*.c)
add_library(bivouac_zstd STATIC ${BIVOUAC_ZSTD_SRC})
target_include_directories(bivouac_zstd PUBLIC ${BIVOUAC_TP}/zstd)
target_compile_definitions(bivouac_zstd PRIVATE ZSTD_DISABLE_ASM ZSTD_MULTITHREAD=0)
set_target_properties(bivouac_zstd PROPERTIES POSITION_INDEPENDENT_CODE ON)
