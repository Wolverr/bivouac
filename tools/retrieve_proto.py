"""Desktop prototype of Bivouac's retrieval step, for tuning against a real wiki.db.

It mirrors what the app does before the LLM runs:
  1. turn the question into an FTS5 query (keywords, OR-ed, stop words dropped)
  2. BM25 over chunks, blended with the article popularity prior
  3. diversify (at most N chunks per article) and cut evidence down to the
     sentences that actually overlap the question, to keep the prompt short

Usage: python retrieve_proto.py out/wiki.db "why is the sky blue"
"""

import math
import re
import sqlite3
import sys
import time

import zstandard as zstd

STOP = set("""
a an and are as at be but by can could did do does for from had has have how i if in into is it its
me my of on or should so than that the their them then there these they this to was we were what when
where which who whom why will with would you your about tell explain describe give some any much many
""".split())

WORD_RE = re.compile(r"[\w][\w'\-]*", re.UNICODE)
SENT_RE = re.compile(r"(?<=[.!?])\s+")


def keywords(question: str) -> list[str]:
    words = [w.strip("'-").lower() for w in WORD_RE.findall(question)]
    return [w for w in dict.fromkeys(words) if w and w not in STOP and len(w) > 1]


def fts_query(words: list[str]) -> str:
    # Quote every term so FTS5 syntax characters in user text can't break the query.
    # Split on punctuation first: "heat-pump" or "kazakhstan's" would otherwise be a
    # phrase query, which FTS5 rejects when the index has detail=column.
    terms = [t for w in words for t in re.split(r"[^\w]+", w) if len(t) > 1 and t not in STOP]
    return " OR ".join(f'"{t}"' for t in dict.fromkeys(terms))


class Pack:
    def __init__(self, path: str):
        self.db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        zdict = zstd.ZstdCompressionDict(self.db.execute("SELECT data FROM dict WHERE id=1").fetchone()[0])
        self.dctx = zstd.ZstdDecompressor(dict_data=zdict)
        self.cache: dict[int, bytes] = {}

    def text(self, chunk_id: int) -> str:
        block, off, ln = self.db.execute("SELECT block, off, len FROM chunk WHERE id=?", (chunk_id,)).fetchone()
        if block not in self.cache:
            data = self.db.execute("SELECT data FROM block WHERE id=?", (block,)).fetchone()[0]
            self.cache[block] = self.dctx.decompress(data)
        return self.cache[block][off:off + ln].decode()

    def search(self, q: str, limit=8, per_article=2, prior_weight=0.5, candidates=200, boost_articles=frozenset(), boost=4.0):
        rows = self.db.execute(
            "SELECT f.id, f.s, c.article, c.section, a.title, a.prior FROM "
            "(SELECT rowid AS id, bm25(fts, 6.0, 2.0, 1.0) AS s FROM fts WHERE fts MATCH ? ORDER BY s LIMIT ?) f "
            "JOIN chunk c ON c.id=f.id JOIN article a ON a.id=c.article",
            (q, candidates),
        ).fetchall()
        scored = sorted(((-s + prior_weight * prior + (boost if art in boost_articles else 0.0), cid, title, sec)
                         for cid, s, art, sec, title, prior in rows), reverse=True)
        out, per = [], {}
        for score, cid, title, sec in scored:
            if per.get(title, 0) >= per_article:
                continue
            per[title] = per.get(title, 0) + 1
            out.append((score, cid, title, sec))
            if len(out) >= limit:
                break
        return out


TOKEN_RE = re.compile(r"[\w][\w'\-.]*", re.UNICODE)


def title_matches(pack: "Pack", question: str, max_n: int = 5):
    """Articles whose title equals a phrase of the question, longest phrases first.

    Returns [(article_id, title, first_chunk, n_chunks)]. Words inside a longer
    match aren't tried again ("Roman Empire" beats "Roman" and "Empire").
    """
    toks = [t.strip(".'-") for t in TOKEN_RE.findall(question)]
    used = [False] * len(toks)
    found, seen = [], set()
    for n in range(min(max_n, len(toks)), 0, -1):
        for i in range(len(toks) - n + 1):
            if any(used[i:i + n]):
                continue
            words = toks[i:i + n]
            if all(w.lower() in STOP for w in words) or (n == 1 and len(words[0]) < 3):
                continue
            phrase = " ".join(words)
            cands = [phrase]
            if phrase.lower().endswith("s") and len(phrase) > 3:
                cands.append(phrase[:-1])  # "tardigrades" -> "tardigrade"
            for c in cands:
                row = pack.db.execute(
                    "SELECT id, title, first_chunk, n_chunks FROM article WHERE norm_title = ? ORDER BY prior DESC LIMIT 1",
                    (" ".join(c.casefold().split()),),
                ).fetchone()
                if row and row[0] not in seen:
                    found.append(row)
                    seen.add(row[0])
                    for j in range(i, i + n):
                        used[j] = True
                    break
    return found


def compress(text: str, words: list[str], max_chars=500) -> str:
    """Keeps the sentences with the most question words, in original order."""
    sents = SENT_RE.split(text)
    if len(text) <= max_chars:
        return text
    scored = []
    for i, s in enumerate(sents):
        low = s.lower()
        hits = sum(1 for w in words if w in low)
        scored.append((hits + (0.5 if i == 0 else 0), i, s))
    keep, total = set(), 0
    for hits, i, s in sorted(scored, reverse=True):
        if total + len(s) > max_chars and keep:
            continue
        keep.add(i)
        total += len(s) + 1
    return " ".join(s for i, s in enumerate(sents) if i in keep)


def main():
    pack, question = Pack(sys.argv[1]), " ".join(sys.argv[2:])
    words = keywords(question)
    q = fts_query(words)
    t0 = time.time()
    hits = pack.search(q)
    ms = (time.time() - t0) * 1000
    print(f"query: {q}   ({ms:.0f} ms)\n")
    total = 0
    for score, cid, title, sec in hits:
        ev = compress(pack.text(cid), words)
        total += len(ev)
        print(f"[{score:5.2f}] {title} — {sec or '(lead)'}\n  {ev[:400]}\n")
    print(f"evidence: {total} chars ≈ {math.ceil(total / 4)} tokens")


if __name__ == "__main__":
    main()
