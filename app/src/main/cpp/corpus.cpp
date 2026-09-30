#include "corpus.h"
#include "fts_rank.h"

#include <algorithm>
#include <cctype>

#include "sqlite3.h"
#include "zstd.h"

namespace {

// RAII wrapper for prepared statements.
struct Stmt {
    sqlite3_stmt * s = nullptr;
    Stmt(sqlite3 * db, const char * sql) { sqlite3_prepare_v2(db, sql, -1, &s, nullptr); }
    ~Stmt() { sqlite3_finalize(s); }
    bool ok() const { return s != nullptr; }
    std::string text(int col) const {
        const auto * p = sqlite3_column_text(s, col);
        return p ? std::string(reinterpret_cast<const char *>(p), sqlite3_column_bytes(s, col)) : std::string();
    }
};

}  // namespace

// Must match norm_title() in tools/build_corpus.py for ASCII; Python's casefold
// also folds non-ASCII letters, which SQLite's lower() on the lookup side covers.
std::string normalize_title(const std::string & title) {
    std::string out;
    bool space = false;
    for (unsigned char c : title) {
        if (c == '_' || std::isspace(c)) {
            space = !out.empty();
            continue;
        }
        if (space) out += ' ';
        space = false;
        out += (char) (c < 0x80 ? std::tolower(c) : c);
    }
    return out;
}

Corpus::~Corpus() { close(); }

std::string Corpus::open(const std::string & path) {
    close();
    if (sqlite3_open_v2(path.c_str(), &db_, SQLITE_OPEN_READONLY | SQLITE_OPEN_NOMUTEX, nullptr) != SQLITE_OK) {
        error_ = db_ ? sqlite3_errmsg(db_) : "sqlite open failed";
        close();
        return error_;
    }
    sqlite3_exec(db_, "PRAGMA mmap_size=268435456; PRAGMA cache_size=-65536;", nullptr, nullptr, nullptr);
    // bm25() scores every row 0 on our contentless detail=column index; colrank() doesn't.
    if (bivouac_fts_rank_register(db_) != SQLITE_OK) {
        error_ = "could not register colrank()";
        close();
        return error_;
    }
    {
        Stmt a(db_, "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'alias'");
        has_alias_ = a.ok() && sqlite3_step(a.s) == SQLITE_ROW;
    }

    Stmt st(db_, "SELECT data FROM dict WHERE id = 1");
    if (!st.ok() || sqlite3_step(st.s) != SQLITE_ROW) {
        error_ = "not a Bivouac knowledge pack (missing dict table)";
        close();
        return error_;
    }
    ddict_ = ZSTD_createDDict(sqlite3_column_blob(st.s, 0), sqlite3_column_bytes(st.s, 0));
    dctx_ = ZSTD_createDCtx();
    if (!ddict_ || !dctx_) {
        error_ = "zstd init failed";
        close();
        return error_;
    }
    return "";
}

void Corpus::close() {
    if (db_) sqlite3_close_v2(db_);
    db_ = nullptr;
    if (ddict_) ZSTD_freeDDict(ddict_);
    ddict_ = nullptr;
    if (dctx_) ZSTD_freeDCtx(dctx_);
    dctx_ = nullptr;
    lru_.clear();
    cache_.clear();
}

std::string Corpus::meta(const std::string & key) {
    if (!db_) return "";
    Stmt st(db_, "SELECT value FROM meta WHERE key = ?");
    sqlite3_bind_text(st.s, 1, key.c_str(), -1, SQLITE_TRANSIENT);
    return sqlite3_step(st.s) == SQLITE_ROW ? st.text(0) : "";
}

std::vector<ChunkHit> Corpus::search(const std::string & fts_query, int limit, double prior_weight) {
    std::vector<ChunkHit> hits;
    if (!db_ || fts_query.empty()) return hits;

    // BM25 over a wide candidate set, then blend in popularity: for ambiguous
    // words ("Mercury", "Java") the famous article is usually the one meant.
    const int n_candidates = std::max(limit * 8, 200);
    Stmt st(db_,
            "SELECT f.id, f.s, c.article, c.section, a.title, a.prior "
            "FROM (SELECT rowid AS id, colrank(fts, 6.0, 2.0, 1.0) AS s FROM fts WHERE fts MATCH ?1 "
            "      ORDER BY s LIMIT ?2) AS f "
            "JOIN chunk c ON c.id = f.id JOIN article a ON a.id = c.article");
    if (!st.ok()) {
        error_ = sqlite3_errmsg(db_);
        return hits;
    }
    sqlite3_bind_text(st.s, 1, fts_query.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_int(st.s, 2, n_candidates);
    int rc;
    while ((rc = sqlite3_step(st.s)) == SQLITE_ROW) {
        ChunkHit h;
        h.chunk = sqlite3_column_int64(st.s, 0);
        h.bm25 = sqlite3_column_double(st.s, 1);
        h.article = sqlite3_column_int64(st.s, 2);
        h.section = st.text(3);
        h.title = st.text(4);
        h.score = -h.bm25 + prior_weight * sqlite3_column_double(st.s, 5);
        hits.push_back(std::move(h));
    }
    if (rc != SQLITE_DONE) error_ = sqlite3_errmsg(db_);
    std::sort(hits.begin(), hits.end(), [](const ChunkHit & a, const ChunkHit & b) { return a.score > b.score; });
    if ((int) hits.size() > limit) hits.resize(limit);
    return hits;
}

bool Corpus::find_article(const std::string & title, ArticleRef & out) {
    if (!db_) return false;
    const std::string norm = normalize_title(title);
    auto lookup = [&](const char * sql) {
        Stmt st(db_, sql);
        if (!st.ok()) return false;
        sqlite3_bind_text(st.s, 1, norm.c_str(), -1, SQLITE_TRANSIENT);
        if (sqlite3_step(st.s) != SQLITE_ROW) return false;
        out.id = sqlite3_column_int64(st.s, 0);
        out.title = st.text(1);
        out.prior = sqlite3_column_double(st.s, 2);
        out.first_chunk = sqlite3_column_int64(st.s, 3);
        out.n_chunks = sqlite3_column_int(st.s, 4);
        return true;
    };
    if (lookup("SELECT id, title, prior, first_chunk, n_chunks FROM article WHERE norm_title = ?1 "
               "ORDER BY prior DESC LIMIT 1")) {
        return true;
    }
    // Other names for articles ("GPS", "TCP"), from Wikipedia redirects; see tools/build_aliases.py.
    return has_alias_ &&
           lookup("SELECT a.id, a.title, a.prior, a.first_chunk, a.n_chunks FROM alias l "
                  "JOIN article a ON a.id = l.article WHERE l.norm_title = ?1 ORDER BY l.views DESC LIMIT 1");
}

std::vector<std::string> Corpus::other_names(int64_t article, int limit) {
    std::vector<std::string> out;
    if (!db_ || !has_alias_) return out;
    Stmt st(db_, "SELECT norm_title FROM alias WHERE article = ?1 ORDER BY views DESC LIMIT ?2");
    if (!st.ok()) return out;
    sqlite3_bind_int64(st.s, 1, article);
    sqlite3_bind_int(st.s, 2, limit);
    while (sqlite3_step(st.s) == SQLITE_ROW) out.push_back(st.text(0));
    return out;
}

int Corpus::doc_freq(const std::string & fts_query, int cap) {
    if (!db_ || fts_query.empty()) return 0;
    Stmt st(db_, "SELECT count(*) FROM (SELECT 1 FROM fts WHERE fts MATCH ?1 LIMIT ?2)");
    if (!st.ok()) return 0;
    sqlite3_bind_text(st.s, 1, fts_query.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_int(st.s, 2, cap);
    return sqlite3_step(st.s) == SQLITE_ROW ? sqlite3_column_int(st.s, 0) : 0;
}

bool Corpus::read_block(int64_t block, std::string & out) {
    auto it = cache_.find(block);
    if (it != cache_.end()) {
        lru_.splice(lru_.begin(), lru_, it->second.second);
        out = it->second.first;
        return true;
    }
    Stmt st(db_, "SELECT data FROM block WHERE id = ?1");
    sqlite3_bind_int64(st.s, 1, block);
    if (sqlite3_step(st.s) != SQLITE_ROW) return false;
    const void * src = sqlite3_column_blob(st.s, 0);
    const size_t n = sqlite3_column_bytes(st.s, 0);
    const unsigned long long cap = ZSTD_getFrameContentSize(src, n);
    if (cap == ZSTD_CONTENTSIZE_ERROR || cap == ZSTD_CONTENTSIZE_UNKNOWN) return false;
    std::string buf(cap, '\0');
    const size_t got = ZSTD_decompress_usingDDict(dctx_, buf.data(), buf.size(), src, n, ddict_);
    if (ZSTD_isError(got)) {
        error_ = ZSTD_getErrorName(got);
        return false;
    }
    buf.resize(got);
    lru_.push_front(block);
    cache_[block] = {buf, lru_.begin()};
    if (cache_.size() > kCacheBlocks) {
        cache_.erase(lru_.back());
        lru_.pop_back();
    }
    out = std::move(buf);
    return true;
}

bool Corpus::chunk_text(int64_t chunk, std::string & text, std::string & section, int64_t & article) {
    if (!db_) return false;
    Stmt st(db_, "SELECT block, off, len, section, article FROM chunk WHERE id = ?1");
    sqlite3_bind_int64(st.s, 1, chunk);
    if (sqlite3_step(st.s) != SQLITE_ROW) return false;
    const int64_t block = sqlite3_column_int64(st.s, 0);
    const int64_t off = sqlite3_column_int64(st.s, 1);
    const int64_t len = sqlite3_column_int64(st.s, 2);
    section = st.text(3);
    article = sqlite3_column_int64(st.s, 4);
    std::string data;
    if (!read_block(block, data) || off + len > (int64_t) data.size()) return false;
    text.assign(data, off, len);
    return true;
}
