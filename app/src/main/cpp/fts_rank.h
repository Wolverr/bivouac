#pragma once

#include "sqlite3.h"

#ifdef __cplusplus
extern "C" {
#endif

// Registers colrank(), BM25 for contentless detail=column FTS5 tables (see fts_rank.c).
int bivouac_fts_rank_register(sqlite3 * db);

#ifdef __cplusplus
}
#endif
