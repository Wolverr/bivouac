// corpus_cli <wiki.db> search "<fts query>" [limit]
// corpus_cli <wiki.db> title "<article title>"
// corpus_cli <wiki.db> chunk <id>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <string>

#include "corpus.h"

int main(int argc, char ** argv) {
    if (argc < 4) {
        fprintf(stderr, "usage: %s <wiki.db> search|title|chunk <arg> [limit]\n", argv[0]);
        return 2;
    }
    Corpus c;
    if (auto err = c.open(argv[1]); !err.empty()) {
        fprintf(stderr, "open: %s\n", err.c_str());
        return 1;
    }
    const std::string cmd = argv[2];
    const auto t0 = std::chrono::steady_clock::now();
    if (cmd == "search") {
        const int limit = argc > 4 ? atoi(argv[4]) : 8;
        auto hits = c.search(argv[3], limit, 0.5);
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        for (auto & h : hits) {
            std::string text, sec;
            int64_t art;
            c.chunk_text(h.chunk, text, sec, art);
            printf("[%6.2f bm25 %6.2f] %s — %s (#%lld)\n    %.160s\n", h.score, h.bm25, h.title.c_str(),
                   h.section.empty() ? "(lead)" : h.section.c_str(), (long long) h.chunk, text.c_str());
        }
        printf("%zu hits in %.1f ms %s\n", hits.size(), ms, c.last_error().c_str());
    } else if (cmd == "title") {
        ArticleRef a;
        if (!c.find_article(argv[3], a)) { puts("not found"); return 1; }
        printf("%s (id %lld, prior %.2f, %d chunks)\n", a.title.c_str(), (long long) a.id, a.prior, a.n_chunks);
        for (int i = 0; i < a.n_chunks && i < 3; i++) {
            std::string text, sec;
            int64_t art;
            c.chunk_text(a.first_chunk + i, text, sec, art);
            printf("--- %s\n%s\n", sec.empty() ? "(lead)" : sec.c_str(), text.c_str());
        }
    } else if (cmd == "chunk") {
        std::string text, sec;
        int64_t art;
        if (!c.chunk_text(atoll(argv[3]), text, sec, art)) { puts("not found"); return 1; }
        printf("article %lld, section %s\n%s\n", (long long) art, sec.c_str(), text.c_str());
    }
    return 0;
}
