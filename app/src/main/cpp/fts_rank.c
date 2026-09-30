/*
** colrank(fts, w0, w1, ...): BM25 for contentless detail=column FTS5 tables.
**
** SQLite's built-in bm25() needs per-row term counts, which a contentless
** table with detail=column can't provide, so it scores every row 0. This
** index does know which columns of a row hold each term, and each column's
** length, so colrank computes BM25 with the term frequency taken as 1.
** Chunks are a few hundred words, so repeated terms matter little next to IDF.
**
** Same convention as bm25(): more negative = better, per-column weights optional.
** Used by the app (corpus.cpp) and, built as a loadable extension, by the desktop
** tools (tools/native/fts_rank.so via tools/retrieve_proto.py).
*/
#ifdef BIVOUAC_LOADABLE
#include "sqlite3ext.h"
SQLITE_EXTENSION_INIT1
#else
#include "sqlite3.h"
#endif

#include <math.h>
#include <stdlib.h>
#include <string.h>

#define K1 1.2
#define B 0.75

typedef struct {
    int n_phrase;
    int n_col;
    double *idf;      /* per phrase */
    double *avg_len;  /* per column */
} RankData;

static int count_row(const Fts5ExtensionApi *api, Fts5Context *ctx, void *user) {
    (void) api;
    (void) ctx;
    (*(sqlite3_int64 *) user)++;
    return SQLITE_OK;
}

static int rank_data(const Fts5ExtensionApi *api, Fts5Context *ctx, RankData **out) {
    RankData *d = (RankData *) api->xGetAuxdata(ctx, 0);
    if (d) {
        *out = d;
        return SQLITE_OK;
    }
    const int n_phrase = api->xPhraseCount(ctx);
    const int n_col = api->xColumnCount(ctx);
    d = (RankData *) sqlite3_malloc64(sizeof(RankData) + sizeof(double) * (n_phrase + n_col));
    if (!d) return SQLITE_NOMEM;
    d->n_phrase = n_phrase;
    d->n_col = n_col;
    d->idf = (double *) &d[1];
    d->avg_len = d->idf + n_phrase;

    sqlite3_int64 n_rows = 0;
    int rc = api->xRowCount(ctx, &n_rows);
    for (int c = 0; rc == SQLITE_OK && c < n_col; c++) {
        sqlite3_int64 total = 0;
        rc = api->xColumnTotalSize(ctx, c, &total);
        d->avg_len[c] = n_rows > 0 ? (double) total / (double) n_rows : 1.0;
        if (d->avg_len[c] <= 0) d->avg_len[c] = 1.0;
    }
    for (int i = 0; rc == SQLITE_OK && i < n_phrase; i++) {
        sqlite3_int64 hits = 0;
        rc = api->xQueryPhrase(ctx, i, &hits, count_row);
        double idf = log((n_rows - hits + 0.5) / (hits + 0.5));
        d->idf[i] = idf > 1e-6 ? idf : 1e-6;
    }
    if (rc == SQLITE_OK) rc = api->xSetAuxdata(ctx, d, sqlite3_free);
    if (rc != SQLITE_OK) {
        sqlite3_free(d);
        return rc;
    }
    *out = d;
    return SQLITE_OK;
}

static void colrank(const Fts5ExtensionApi *api, Fts5Context *ctx, sqlite3_context *res, int n_arg,
                    sqlite3_value **args) {
    RankData *d = NULL;
    int rc = rank_data(api, ctx, &d);
    if (rc != SQLITE_OK) {
        sqlite3_result_error_code(res, rc);
        return;
    }
    double score = 0.0;
    for (int i = 0; i < d->n_phrase; i++) {
        Fts5PhraseIter it;
        int col = -1;
        for (api->xPhraseFirstColumn(ctx, i, &it, &col); col >= 0; api->xPhraseNextColumn(ctx, &it, &col)) {
            const double w = col < n_arg ? sqlite3_value_double(args[col]) : 1.0;
            int len = 0;
            if (api->xColumnSize(ctx, col, &len) != SQLITE_OK) len = (int) d->avg_len[col];
            const double tf = (K1 + 1.0) / (1.0 + K1 * (1.0 - B + B * len / d->avg_len[col]));
            score += w * d->idf[i] * tf;
        }
    }
    sqlite3_result_double(res, -score);
}

/* Registers colrank() on this connection. */
int bivouac_fts_rank_register(sqlite3 *db) {
    fts5_api *api = NULL;
    sqlite3_stmt *st = NULL;
    int rc = sqlite3_prepare_v2(db, "SELECT fts5(?1)", -1, &st, NULL);
    if (rc != SQLITE_OK) return rc;
    sqlite3_bind_pointer(st, 1, (void *) &api, "fts5_api_ptr", NULL);
    sqlite3_step(st);
    rc = sqlite3_finalize(st);
    if (rc != SQLITE_OK) return rc;
    if (!api) return SQLITE_ERROR;
    return api->xCreateFunction(api, "colrank", NULL, colrank, NULL);
}

#ifdef BIVOUAC_LOADABLE
#ifdef _WIN32
__declspec(dllexport)
#endif
int sqlite3_ftsrank_init(sqlite3 *db, char **err, const sqlite3_api_routines *routines) {
    (void) err;
    SQLITE_EXTENSION_INIT2(routines);
    return bivouac_fts_rank_register(db);
}
#endif
