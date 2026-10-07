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

// Every word capitalized except small connecting words ("Peace of Westphalia"):
// how names are written. Non-ASCII first letters count as capitalized.
bool looks_like_name(const std::string & title) {
    static const char * kSmall[] = {"of", "the", "and", "de", "du", "la", "le", "in", "on", "upon", "von", "van", "da"};
    bool any = false;
    size_t i = 0;
    while (i < title.size()) {
        while (i < title.size() && std::isspace((unsigned char) title[i])) i++;
        const size_t start = i;
        while (i < title.size() && !std::isspace((unsigned char) title[i])) i++;
        if (start == i) break;
        const std::string w = title.substr(start, i - start);
        const unsigned char c = w[0];
        if (c >= 0x80 || std::isupper(c) || std::isdigit(c)) {
            any = true;
            continue;
        }
        if (std::none_of(std::begin(kSmall), std::end(kSmall), [&](const char * s) { return w == s; })) return false;
    }
    return any;
}

// "TCP", "PoS", "AC/DC" no, "L2" no: a capital first, at least two capitals, and only
// ASCII letters, digits, '&' and '-'. Must match ACRONYM_RE in tools/retrieve_proto.py.
bool looks_like_acronym(const std::string & s) {
    if (s.size() < 2 || s.size() > 10 || !std::isupper((unsigned char) s[0])) return false;
    int caps = 0;
    for (unsigned char c : s) {
        if (std::isupper(c)) caps++;
        else if (!std::isalnum(c) && c != '&' && c != '-') return false;
    }
    return caps >= 2;
}

// Does a word of `title` (4+ letters, compared by its first five) start a word of `context`?
// "Completely Fair Scheduler" and "how does Linux schedule processes": yes. Must match
// shares_word() in tools/retrieve_proto.py.
bool shares_word(const std::string & title, const std::string & context) {
    auto words = [](const std::string & s) {
        std::vector<std::string> out;
        std::string w;
        for (size_t i = 0; i <= s.size(); i++) {
            const unsigned char c = i < s.size() ? s[i] : ' ';
            if (std::isalnum(c) || c >= 0x80) {
                w += (char) (c < 0x80 ? std::tolower(c) : c);
            } else if (!w.empty()) {
                out.push_back(w);
                w.clear();
            }
        }
        return out;
    };
    const auto ctx = words(context);
    for (const auto & t : words(title)) {
        if (t.size() < 4) continue;
        const std::string p = t.substr(0, 5);
        for (const auto & c : ctx) {
            if (c.compare(0, p.size(), p) == 0) return true;
        }
    }
    return false;
}

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
    {
        Stmt a(db_, "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'acronym'");
        has_acronym_ = a.ok() && sqlite3_step(a.s) == SQLITE_ROW;
    }
    {
        Stmt a(db_, "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'coord'");
        has_coord_ = a.ok() && sqlite3_step(a.s) == SQLITE_ROW;
    }
    {
        Stmt a(db_, "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'coord_rtree'");
        has_rtree_ = a.ok() && sqlite3_step(a.s) == SQLITE_ROW;
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

std::vector<ChunkHit> Corpus::search(const std::string & fts_query, int limit, double prior_weight,
                                     const std::vector<int64_t> & boost_articles, double boost) {
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
        if (std::find(boost_articles.begin(), boost_articles.end(), h.article) != boost_articles.end()) h.score += boost;
        hits.push_back(std::move(h));
    }
    if (rc != SQLITE_DONE) error_ = sqlite3_errmsg(db_);
    std::sort(hits.begin(), hits.end(), [](const ChunkHit & a, const ChunkHit & b) { return a.score > b.score; });
    if ((int) hits.size() > limit) hits.resize(limit);
    return hits;
}

bool Corpus::find_article(const std::string & title, ArticleRef & out, const std::string & context) {
    if (!db_) return false;
    const std::string norm = normalize_title(title);
    // ?1 = normalized title, ?2 = title as written.
    auto lookup_into = [&](const char * sql, ArticleRef & r) {
        Stmt st(db_, sql);
        if (!st.ok()) return false;
        sqlite3_bind_text(st.s, 1, norm.c_str(), -1, SQLITE_TRANSIENT);
        if (sqlite3_bind_parameter_count(st.s) >= 2) sqlite3_bind_text(st.s, 2, title.c_str(), -1, SQLITE_TRANSIENT);
        if (sqlite3_step(st.s) != SQLITE_ROW) return false;
        r.id = sqlite3_column_int64(st.s, 0);
        r.title = st.text(1);
        r.prior = sqlite3_column_double(st.s, 2);
        r.first_chunk = sqlite3_column_int64(st.s, 3);
        r.n_chunks = sqlite3_column_int(st.s, 4);
        return true;
    };
    auto lookup = [&](const char * sql) { return lookup_into(sql, out); };
    static const char * kByTitle =
        "SELECT id, title, prior, first_chunk, n_chunks FROM article WHERE norm_title = ?1 ORDER BY prior DESC LIMIT 1";
    // Other names for articles ("GPS"), from Wikipedia redirects; see tools/build_aliases.py.
    static const char * kByAlias =
        "SELECT a.id, a.title, a.prior, a.first_chunk, a.n_chunks FROM alias l "
        "JOIN article a ON a.id = l.article WHERE l.norm_title = ?1 ORDER BY l.views DESC LIMIT 1";

    // Acronyms: many are disambiguation pages on Wikipedia ("TCP", "BMI"), so they are in no
    // title or alias; tools/build_acronyms.py mines them from intros. A title spelled exactly
    // so wins ("NATO", "DNA"), then a mined meaning that shares a word with the question ("CFS"
    // in a Linux scheduling question), then a redirect ("PoW": Prisoner of war, not People of
    // Walmart), else the more viewed of the title and the top mined meaning.
    if (has_acronym_ && looks_like_acronym(title)) {
        if (lookup("SELECT id, title, prior, first_chunk, n_chunks FROM article WHERE norm_title = ?1 AND title = ?2 "
                   "ORDER BY prior DESC LIMIT 1")) {
            return true;
        }
        std::vector<ArticleRef> meanings;
        {
            Stmt st(db_, "SELECT a.id, a.title, a.prior, a.first_chunk, a.n_chunks FROM acronym x "
                         "JOIN article a ON a.id = x.article WHERE x.abbr = ?1 ORDER BY x.views DESC LIMIT 5");
            if (st.ok()) {
                sqlite3_bind_text(st.s, 1, title.c_str(), -1, SQLITE_TRANSIENT);
                while (sqlite3_step(st.s) == SQLITE_ROW) {
                    ArticleRef r;
                    r.id = sqlite3_column_int64(st.s, 0);
                    r.title = st.text(1);
                    r.prior = sqlite3_column_double(st.s, 2);
                    r.first_chunk = sqlite3_column_int64(st.s, 3);
                    r.n_chunks = sqlite3_column_int(st.s, 4);
                    meanings.push_back(std::move(r));
                }
            }
        }
        for (const auto & m : meanings) {
            if (!context.empty() && shares_word(m.title, context)) {
                out = m;
                return true;
            }
        }
        // A redirect is the editors' primary meaning: "RAM" -> Random-access memory, not a
        // slightly more viewed missile from the mined table.
        if (has_alias_ && lookup(kByAlias)) return true;
        bool found = !meanings.empty();
        if (found) out = meanings[0];
        ArticleRef r;
        if (lookup_into(kByTitle, r) && (!found || r.prior > out.prior)) {
            out = r;
            found = true;
        }
        if (found) return true;
    }
    if (lookup(kByTitle)) return true;
    if (has_alias_ && lookup(kByAlias)) return true;
    // Disambiguation pages aren't in the pack ("New York", "Mercury"): take the most viewed
    // "X (...)" or "X City" article, if it is popular enough (>= 10,000 views/month) to be
    // what people mean. Without the floor, "need" would become "Need (album)". Only for
    // names as written: "New York" yes, "broad" (-> "Broad City") no.
    if (!looks_like_name(title)) return false;
    return lookup("SELECT id, title, prior, first_chunk, n_chunks FROM article "
                  "WHERE ((norm_title >= ?1 || ' (' AND norm_title < ?1 || ' )') OR norm_title = ?1 || ' city') "
                  "AND prior >= 9.21 ORDER BY prior DESC LIMIT 1");
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

bool Corpus::coords(int64_t article, double & lat, double & lon) {
    if (!db_ || !has_coord_) return false;
    Stmt st(db_, "SELECT lat, lon FROM coord WHERE article = ?1");
    if (!st.ok()) return false;
    sqlite3_bind_int64(st.s, 1, article);
    if (sqlite3_step(st.s) != SQLITE_ROW) return false;
    lat = sqlite3_column_double(st.s, 0);
    lon = sqlite3_column_double(st.s, 1);
    return true;
}

std::vector<NearbyArticle> Corpus::nearby(double lat, double lon, double dlat, double dlon, int limit) {
    std::vector<NearbyArticle> out;
    if (!db_ || !has_rtree_) return out;
    Stmt st(db_, "SELECT a.id, a.title, a.prior, a.first_chunk, a.n_chunks, r.min_lat, r.min_lon FROM coord_rtree r "
                 "JOIN article a ON a.id = r.id WHERE r.min_lat BETWEEN ?1 AND ?2 AND r.min_lon BETWEEN ?3 AND ?4 "
                 "ORDER BY a.prior DESC LIMIT ?5");
    if (!st.ok()) return out;
    sqlite3_bind_double(st.s, 1, lat - dlat);
    sqlite3_bind_double(st.s, 2, lat + dlat);
    sqlite3_bind_double(st.s, 3, lon - dlon);
    sqlite3_bind_double(st.s, 4, lon + dlon);
    sqlite3_bind_int(st.s, 5, limit);
    while (sqlite3_step(st.s) == SQLITE_ROW) {
        NearbyArticle n;
        n.article.id = sqlite3_column_int64(st.s, 0);
        n.article.title = st.text(1);
        n.article.prior = sqlite3_column_double(st.s, 2);
        n.article.first_chunk = sqlite3_column_int64(st.s, 3);
        n.article.n_chunks = sqlite3_column_int(st.s, 4);
        n.lat = sqlite3_column_double(st.s, 5);
        n.lon = sqlite3_column_double(st.s, 6);
        out.push_back(std::move(n));
    }
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
