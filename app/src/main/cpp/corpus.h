#pragma once

#include <cstdint>
#include <list>
#include <string>
#include <unordered_map>
#include <vector>

struct sqlite3;
struct ZSTD_DCtx_s;
struct ZSTD_DDict_s;

struct ArticleRef {
    int64_t id = 0;
    std::string title;
    double prior = 0;
    int64_t first_chunk = 0;
    int n_chunks = 0;
};

struct ChunkHit {
    int64_t chunk = 0;
    int64_t article = 0;
    std::string title;
    std::string section;
    double bm25 = 0;   // SQLite's bm25(): more negative is better
    double score = 0;  // combined score, higher is better
};

// Read-only access to a knowledge pack built by tools/build_corpus.py.
// Not thread-safe; callers serialize access.
class Corpus {
public:
    ~Corpus();

    // Returns an empty string on success, otherwise an error message.
    std::string open(const std::string & path);
    void close();
    bool is_open() const { return db_ != nullptr; }

    std::string meta(const std::string & key);

    // Full-text search. `fts_query` is FTS5 query syntax. Returns up to `limit`
    // chunks ranked by colrank (BM25, see fts_rank.c) blended with the article popularity prior.
    std::vector<ChunkHit> search(const std::string & fts_query, int limit, double prior_weight);

    // Exact (case-insensitive, whitespace-normalized) title lookup, then the alias table;
    // acronyms also via the acronym table, where `context` (the question) picks the meaning.
    bool find_article(const std::string & title, ArticleRef & out, const std::string & context = "");
    // Chunks matching the query, counted up to `cap`: cheap test for "is this an everyday word".
    int doc_freq(const std::string & fts_query, int cap);
    // Other names of an article from the alias table, most viewed first ("firenze" for Florence).
    std::vector<std::string> other_names(int64_t article, int limit);
    // The article's primary coordinates from the coord table (tools/build_coords.py).
    bool coords(int64_t article, double & lat, double & lon);

    // Chunk text and metadata.
    bool chunk_text(int64_t chunk, std::string & text, std::string & section, int64_t & article);

    std::string last_error() const { return error_; }

private:
    bool read_block(int64_t block, std::string & out);

    sqlite3 * db_ = nullptr;
    ZSTD_DCtx_s * dctx_ = nullptr;
    ZSTD_DDict_s * ddict_ = nullptr;
    std::string error_;
    bool has_alias_ = false;  // packs built before build_aliases.py have no alias table
    bool has_acronym_ = false;  // nor before build_acronyms.py an acronym table
    bool has_coord_ = false;  // nor before build_coords.py a coord table

    // Small LRU of decompressed blocks: neighbouring chunks share blocks.
    static constexpr size_t kCacheBlocks = 64;
    std::list<int64_t> lru_;
    std::unordered_map<int64_t, std::pair<std::string, std::list<int64_t>::iterator>> cache_;
};

std::string normalize_title(const std::string & title);
