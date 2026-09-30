"""Adds an acronym table to a built wiki.db: "TCP" -> Transmission Control Protocol.

Many common acronyms are disambiguation pages on Wikipedia (TCP, UDP, TLS, BMI, ICU, ...),
which FineWiki leaves out and which aren't redirects, so neither the article titles nor the
alias table know them. Article intros do: "The Transmission Control Protocol (TCP) is ...".
This mines "<title> (<ABBR>" from the start of each intro, keeps ABBR only when its capitals
spell the title in order (T-C-P in "Transmission Control Protocol"), and keeps the most viewed
articles per acronym; the question picks among them.

  acronym(abbr, article, views)   up to 5 rows per abbr, case-sensitive ("PoS"); looked up only for acronym-shaped
                                  question words, see Corpus::find_article / retrieve_proto.py

Usage: python build_acronyms.py out/wiki.db [--min-views 50] [--apply]
Without --apply it only writes out/acronyms.tsv for inspection. Safe to re-run.
"""

import argparse
import math
import re
import sqlite3
import time
from pathlib import Path

import zstandard as zstd

# "(TCP)", "(TCP;", "(TCP, ", "(abbreviated TCP", "(abbreviation: BTC"
ABBR_RE = re.compile(r"\(\s*(?:abbreviated:?\s+|abbreviation:?\s+|or\s+)?([A-Z][A-Za-z0-9&\-]{1,9})\s*[);,]")
SHAPE_RE = re.compile(r"^(?=(?:.*[A-Z]){2})[A-Z][A-Za-z0-9&\-]+$")
TITLE_WORD_RE = re.compile(r"[\w]+")
INTRO_CHARS = 300
MEANINGS = 5  # kept per acronym; the question picks among them (Corpus::find_article)


def spells(abbr: str, title: str) -> bool:
    """The acronym's capitals (and digits) appear in the title in order, the first one
    starting the title: TCP in "Transmission Control Protocol", BTC in "Bitcoin"."""
    letters = [c.lower() for c in abbr if c.isupper() or c.isdigit()]
    t = title.lower()
    if not letters or not t.startswith(letters[0]):
        return False
    pos = 1
    for c in letters[1:]:
        pos = t.find(c, pos)
        if pos < 0:
            return False
        pos += 1
    return True


def find_abbr(title: str, intro: str):
    """ABBR when the intro starts by naming the article ("The <title> (ABBR) ...")."""
    head = intro[:INTRO_CHARS]
    base = re.sub(r"\s*\([^)]*\)$", "", title)  # "Rollup (blockchain)" -> "Rollup"
    i = head.lower().find(base.lower())
    if i < 0 or i > 40:
        return None
    m = ABBR_RE.match(head, i + len(base) + (1 if head[i + len(base):i + len(base) + 1] == " " else 0))
    if not m:
        return None
    abbr = m.group(1)
    if not SHAPE_RE.match(abbr) or abbr.upper() == base.upper() or not spells(abbr, base):
        return None
    return abbr


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("db")
    ap.add_argument("--min-views", type=int, default=50)
    ap.add_argument("--apply", action="store_true", help="write the acronym table into the db")
    ap.add_argument("--limit", type=int, default=0, help="scan only this many articles (testing)")
    a = ap.parse_args()

    ro = sqlite3.connect(f"file:{a.db}?mode=ro", uri=True)
    dctx = zstd.ZstdDecompressor(dict_data=zstd.ZstdCompressionDict(
        ro.execute("SELECT data FROM dict WHERE id=1").fetchone()[0]))
    rows = ro.execute(
        "SELECT a.id, a.title, a.prior, a.first_chunk, a.n_chunks FROM article a WHERE a.prior >= ? "
        "ORDER BY a.first_chunk" + (f" LIMIT {a.limit}" if a.limit else ""), (math.log1p(a.min_views),))
    cur_block, cur_data = -1, b""
    found: dict[str, list[tuple[int, int, str]]] = {}  # abbr -> [(views, article, title)]
    t0, n = time.time(), 0

    def text(chunk_id: int):
        nonlocal cur_block, cur_data
        sec, block, off, ln = ro.execute("SELECT section, block, off, len FROM chunk WHERE id=?", (chunk_id,)).fetchone()
        if block != cur_block:
            cur_block, cur_data = block, dctx.decompress(ro.execute("SELECT data FROM block WHERE id=?", (block,)).fetchone()[0])
        return sec, cur_data[off:off + ln].decode(errors="replace")

    for art, title, prior, first, n_chunks in rows.fetchall():
        n += 1
        sec, intro = text(first)
        if sec == "Infobox" and n_chunks > 1:
            sec, intro = text(first + 1)
        if sec not in ("", "Infobox"):
            continue
        abbr = find_abbr(title, intro)
        if abbr:
            found.setdefault(abbr, []).append((int(math.expm1(prior)), art, title))
        if n % 500_000 == 0:
            print(f"{n:,} articles, {len(found):,} acronyms, {time.time() - t0:.0f} s", flush=True)
    best = {k: sorted(v, reverse=True)[:MEANINGS] for k, v in found.items()}

    out = Path(a.db).parent / "acronyms.tsv"
    with open(out, "w") as f:
        for abbr, meanings in sorted(best.items(), key=lambda kv: -kv[1][0][0]):
            f.write(f"{abbr}\t" + "\t".join(f"{title}\t{views}" for views, _, title in meanings) + "\n")
    print(f"{n:,} articles scanned, {len(best):,} acronyms -> {out} ({time.time() - t0:.0f} s)")

    if a.apply:
        rw = sqlite3.connect(a.db, timeout=60)
        rw.execute("DROP TABLE IF EXISTS acronym")
        rw.execute("CREATE TABLE acronym(abbr TEXT NOT NULL, article INTEGER NOT NULL, views INTEGER NOT NULL)")
        rw.executemany("INSERT INTO acronym VALUES (?, ?, ?)",
                       [(k, art, views) for k, meanings in best.items() for views, art, _ in meanings])
        rw.execute("CREATE INDEX acronym_abbr ON acronym(abbr)")
        rw.execute("INSERT OR REPLACE INTO meta VALUES ('acronyms', ?)", (str(len(best)),))
        rw.commit()
        print("acronym table written")


if __name__ == "__main__":
    main()
