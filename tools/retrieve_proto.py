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
from pathlib import Path

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
        # colrank(): BM25 that works on contentless detail=column tables (the built-in
        # bm25() scores every row 0 there). Same C code as the app; build with
        # cmake -B native/build -S native -G Ninja && ninja -C native/build
        ext = Path(__file__).parent / "native" / "build" / "fts_rank.so"
        if not ext.exists():
            sys.exit(f"missing {ext}: build tools/native first (see retrieve_proto.py)")
        self.db.enable_load_extension(True)
        self.db.load_extension(str(ext), entrypoint="sqlite3_ftsrank_init")
        self.db.enable_load_extension(False)
        zdict = zstd.ZstdCompressionDict(self.db.execute("SELECT data FROM dict WHERE id=1").fetchone()[0])
        self.dctx = zstd.ZstdDecompressor(dict_data=zdict)
        self.cache: dict[int, bytes] = {}
        self.has_alias = self.db.execute(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='alias'").fetchone() is not None

    def find_article(self, title: str):
        """(id, title, first_chunk, n_chunks, prior) by exact title, else by alias ("GPS"), else the
        popular "X (...)" / "X City" article for disambiguation pages ("New York"); like Corpus::find_article."""
        norm = " ".join(title.replace("_", " ").casefold().split())
        row = self.db.execute("SELECT id, title, first_chunk, n_chunks, prior FROM article WHERE norm_title = ? "
                              "ORDER BY prior DESC LIMIT 1", (norm,)).fetchone()
        if row is None and self.has_alias:
            row = self.db.execute(
                "SELECT a.id, a.title, a.first_chunk, a.n_chunks, a.prior FROM alias l JOIN article a "
                "ON a.id = l.article WHERE l.norm_title = ? ORDER BY l.views DESC LIMIT 1", (norm,)).fetchone()
        # Only for names as written ("New York", not "broad" -> "Broad City").
        if row is None and all(w[:1].isupper() for w in title.split() if w.lower() not in STOP):
            row = self.db.execute(
                "SELECT id, title, first_chunk, n_chunks, prior FROM article "
                "WHERE ((norm_title >= ?1 || ' (' AND norm_title < ?1 || ' )') OR norm_title = ?1 || ' city') "
                "AND prior >= 9.21 ORDER BY prior DESC LIMIT 1", (norm,)).fetchone()
        return row

    def text(self, chunk_id: int) -> str:
        block, off, ln = self.db.execute("SELECT block, off, len FROM chunk WHERE id=?", (chunk_id,)).fetchone()
        if block not in self.cache:
            data = self.db.execute("SELECT data FROM block WHERE id=?", (block,)).fetchone()[0]
            self.cache[block] = self.dctx.decompress(data)
        return self.cache[block][off:off + ln].decode()

    def search(self, q: str, limit=8, per_article=2, prior_weight=0.5, candidates=200, boost_articles=frozenset(), boost=4.0):
        rows = self.db.execute(
            "SELECT f.id, f.s, c.article, c.section, a.title, a.prior FROM "
            "(SELECT rowid AS id, colrank(fts, 6.0, 2.0, 1.0) AS s FROM fts WHERE fts MATCH ? ORDER BY s LIMIT ?) f "
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

# A lowercase word in more chunks than this is everyday language. An article titled
# only with such words counts when it's a real topic ("Season", "Age of the universe")
# and is skipped when obscure ("Determine" 272 views/month, "Broad Strokes" 87).
COMMON_DF = 20_000
COMMON_MIN_VIEWS = 3_000


def everyday(pack: "Pack", words: list[str], at_start: bool) -> bool:
    """No name-like word and every content word common."""
    if any(is_named(w, at_start and k == 0) for k, w in enumerate(words)):
        return False
    return all(doc_freq(pack, w) > COMMON_DF for w in words if w.lower() not in STOP)


def is_named(tok: str, first: bool) -> bool:
    """Written as a name: capitalized mid-sentence, or any inner capital ("GPS", "mRNA")."""
    if any(c.isupper() for c in tok[1:]):
        return True
    return tok[:1].isupper() and not first


def doc_freq(pack: "Pack", word: str, cap: int = COMMON_DF + 1) -> int:
    """Chunks containing the word, counted up to `cap` (cheap even for common words)."""
    # Punctuated tokens ("don't", "CFS/EEVDF") would be phrase queries, unsupported with detail=column.
    parts = re.findall(r"\w+", word)
    if not parts:
        return cap
    q = " AND ".join(f'"{p}"' for p in parts)
    return pack.db.execute("SELECT count(*) FROM (SELECT 1 FROM fts WHERE fts MATCH ? LIMIT ?)", (q, cap)).fetchone()[0]


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
                row = pack.find_article(c)
                if row and row[4] < math.log1p(COMMON_MIN_VIEWS) and everyday(pack, words, i == 0):
                    continue
                if row and row[0] not in seen:
                    found.append(row[:4])
                    seen.add(row[0])
                    for j in range(i, i + n):
                        used[j] = True
                    break
    return found


LIST_QUERY = re.compile(r"((?:[^\W\d_][\w-]*\s+){0,2}[^\W\d_][\w-]*s)\s+(in|of|near|around)\s+(?:the\s+)?([^\W\d_][\w'\- ]*)")
EXPLAIN_QUERY = re.compile(r"^\s*(why|how)\b|\bcaus", re.I)
PLACE_STOP = {"and", "or", "for", "with", "that", "which", "what", "to", "is", "are"}


def list_article(pack: "Pack", question: str):
    """ResearchPipeline.listArticle: "museums in Berlin" -> "List of museums in Berlin" (via aliases:
    "List of museums and galleries in Berlin"). Returns (id, title, first_chunk, n_chunks) or None."""
    if EXPLAIN_QUERY.search(question):
        return None
    for m in LIST_QUERY.finditer(question):
        nouns = [w for w in m.group(1).split() if w.lower() not in STOP]
        place = []
        for w in m.group(3).split():
            if w.lower() in PLACE_STOP:
                break
            place.append(w)
        place = place[:4]
        if not nouns or not place or not place[0][0].isupper():
            continue
        for n in range(len(nouns), 0, -1):
            what = " ".join(nouns[-n:]).lower()
            for k in range(len(place), 0, -1):
                where = " ".join(place[:k])
                for prep in ("in", "of"):
                    row = pack.find_article(f"List of {what} {prep} {where}")
                    if row:
                        return row[:4]
    return None


def named_articles(pack: "Pack", question: str, k: int = 3):
    """The list article first, then title matches, deduplicated (ResearchPipeline.retrieve)."""
    out, seen = [], set()
    for row in [list_article(pack, question)] + title_matches(pack, question):
        if row and row[0] not in seen:
            out.append(row)
            seen.add(row[0])
    return out[:k]


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
