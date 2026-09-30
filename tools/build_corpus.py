"""Build Bivouac's offline Wikipedia knowledge pack from FineWiki shards.

Output is a single SQLite file (`wiki.db`) that the app opens read-only:

  article(id, title, norm_title, prior, first_chunk, n_chunks)
      one row per Wikipedia article; chunk ids of an article are contiguous
  chunk(id, article, section, block, off, len)
      a passage of ~1200 characters that never crosses a section boundary
  block(id, data)
      zstd-compressed (with a trained dictionary) concatenation of chunk texts
  fts (FTS5, contentless)
      full-text index over (title, section, body); rowid = chunk id
  dict(id, data), meta(key, value)

Infoboxes become their own chunk (section "Infobox") because that's where the
quick facts (capital, population, birth date, ...) live.

Usage:
  python build_corpus.py --src data/finewiki_en --out out/wiki.db [--limit-shards 1] [--fts-detail full]
"""

import argparse
import json
import math
import re
import sqlite3
import sys
import time
from pathlib import Path

import pyarrow.parquet as pq
import zstandard as zstd

CHUNK_TARGET = 1200   # characters; ~250-300 tokens
CHUNK_MAX = 1800
BLOCK_TARGET = 32 * 1024
ZSTD_LEVEL = 12
DICT_SIZE = 112 * 1024

# Sections that are mostly lists of links or bibliography, not knowledge.
SKIP_SECTIONS = {
    "see also", "references", "notes", "external links", "further reading", "bibliography",
    "sources", "citations", "footnotes", "works cited", "general references", "notes and references",
}

HEADING_RE = re.compile(r"^(#{1,6})\s+(.*)$")
SENTENCE_RE = re.compile(r"(?<=[.!?])\s+(?=[A-Z0-9\"'(])")


def norm_title(title: str) -> str:
    return " ".join(title.casefold().replace("_", " ").split())


def sections(text: str):
    """Yields (section_path, body) pairs; section_path is '' for the lead."""
    path: list[str] = []
    buf: list[str] = []
    for line in text.split("\n"):
        m = HEADING_RE.match(line)
        if m:
            if buf:
                yield " > ".join(path), "\n".join(buf).strip()
                buf = []
            level = len(m.group(1))
            if level == 1:  # FineWiki starts with "# Title"
                path = []
                continue
            path = path[: max(0, level - 2)] + [m.group(2).strip()]
        else:
            buf.append(line)
    if buf:
        yield " > ".join(path), "\n".join(buf).strip()


def split_long(par: str):
    if len(par) <= CHUNK_MAX:
        return [par]
    out, cur = [], ""
    for sent in SENTENCE_RE.split(par):
        if cur and len(cur) + len(sent) + 1 > CHUNK_TARGET:
            out.append(cur)
            cur = sent
        else:
            cur = f"{cur} {sent}" if cur else sent
    if cur:
        out.append(cur)
    # A single enormous "sentence" (tables, lists): hard wrap.
    return [p[i:i + CHUNK_MAX] for p in out for i in range(0, len(p), CHUNK_MAX)]


def chunk_section(body: str):
    pieces = [p for par in re.split(r"\n\s*\n", body) if (p := par.strip()) for p in split_long(p)]
    cur = ""
    for p in pieces:
        if cur and len(cur) + len(p) + 2 > CHUNK_TARGET:
            yield cur
            cur = p
        else:
            cur = f"{cur}\n\n{p}" if cur else p
    if cur:
        yield cur


def infobox_text(raw: str | None) -> str:
    if not raw:
        return ""
    try:
        boxes = json.loads(raw)
    except json.JSONDecodeError:
        return ""
    lines = []
    for box in boxes[:2]:
        data = box.get("data") or {}
        for k, v in data.items():
            if isinstance(v, (list, dict)):
                v = json.dumps(v, ensure_ascii=False)
            v = " ".join(str(v).split())
            if k and v and len(v) < 300:
                lines.append(f"{k}: {v}")
    return "\n".join(lines)[:CHUNK_MAX]


def article_chunks(title: str, text: str, infobox: str):
    """Yields (section, chunk_text)."""
    if infobox:
        yield "Infobox", infobox
    for sec, body in sections(text):
        if not body:
            continue
        top = sec.split(" > ")[0].casefold() if sec else ""
        if top in SKIP_SECTIONS:
            continue
        for c in chunk_section(body):
            if len(c) >= 40:
                yield sec, c


SCHEMA = """
PRAGMA page_size = 8192;
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE dict(id INTEGER PRIMARY KEY, data BLOB);
CREATE TABLE article(id INTEGER PRIMARY KEY, title TEXT NOT NULL, norm_title TEXT NOT NULL,
                     prior REAL NOT NULL, first_chunk INTEGER NOT NULL, n_chunks INTEGER NOT NULL);
CREATE TABLE chunk(id INTEGER PRIMARY KEY, article INTEGER NOT NULL, section TEXT NOT NULL,
                   block INTEGER NOT NULL, off INTEGER NOT NULL, len INTEGER NOT NULL);
CREATE TABLE block(id INTEGER PRIMARY KEY, data BLOB NOT NULL);
"""


def train_dictionary(shard: Path) -> bytes:
    samples = []
    pf = pq.ParquetFile(shard)
    for batch in pf.iter_batches(columns=["title", "text", "infoboxes"], batch_size=2000):
        for title, text, ib in zip(*(batch.column(i).to_pylist() for i in range(3))):
            for _, c in article_chunks(title, text or "", infobox_text(ib)):
                samples.append(c.encode())
        if len(samples) > 60_000:
            break
    return zstd.train_dictionary(DICT_SIZE, samples).as_bytes()


class Builder:
    def __init__(self, out: Path, fts_detail: str, zdict: bytes):
        out.parent.mkdir(parents=True, exist_ok=True)
        if out.exists():
            out.unlink()
        self.db = sqlite3.connect(out)
        self.db.executescript(SCHEMA)
        self.db.execute(
            "CREATE VIRTUAL TABLE fts USING fts5(title, section, body, content='', "
            f"detail={fts_detail}, tokenize='porter unicode61 remove_diacritics 2')"
        )
        self.db.execute("PRAGMA journal_mode = OFF")
        self.db.execute("PRAGMA synchronous = OFF")
        self.db.execute("PRAGMA cache_size = -262144")  # 256 MB
        self.db.execute("INSERT INTO dict VALUES (1, ?)", (zdict,))
        self.cctx = zstd.ZstdCompressor(level=ZSTD_LEVEL, dict_data=zstd.ZstdCompressionDict(zdict))
        self.next_chunk = 1
        self.next_block = 1
        self.block_buf = bytearray()
        self.pending_chunks = []   # chunk rows waiting for their block to be written
        self.fts_rows = []
        self.article_rows = []
        self.n_articles = 0
        self.raw_bytes = 0
        self.comp_bytes = 0

    def flush_block(self):
        if not self.block_buf:
            return
        data = self.cctx.compress(bytes(self.block_buf))
        self.db.execute("INSERT INTO block VALUES (?, ?)", (self.next_block, data))
        self.db.executemany("INSERT INTO chunk VALUES (?,?,?,?,?,?)", self.pending_chunks)
        self.raw_bytes += len(self.block_buf)
        self.comp_bytes += len(data)
        self.next_block += 1
        self.block_buf = bytearray()
        self.pending_chunks = []

    def add_article(self, page_id: int, title: str, text: str, infobox: str, size_hint: int):
        first = self.next_chunk
        for sec, c in article_chunks(title, text, infobox):
            enc = c.encode()
            self.pending_chunks.append((self.next_chunk, page_id, sec, self.next_block, len(self.block_buf), len(enc)))
            self.block_buf += enc
            self.fts_rows.append((self.next_chunk, title, sec, c))
            self.next_chunk += 1
            if len(self.block_buf) >= BLOCK_TARGET:
                self.flush_block()
        n = self.next_chunk - first
        if n == 0:
            return
        # Popularity prior; replaced by real pageviews in a later pass when available.
        prior = math.log1p(size_hint)
        self.article_rows.append((page_id, title, norm_title(title), prior, first, n))
        self.n_articles += 1
        if len(self.fts_rows) >= 20_000:
            self.flush_rows()

    def flush_rows(self):
        self.db.executemany("INSERT INTO fts(rowid, title, section, body) VALUES (?,?,?,?)", self.fts_rows)
        self.db.executemany("INSERT INTO article VALUES (?,?,?,?,?,?)", self.article_rows)
        self.fts_rows = []
        self.article_rows = []

    def finish(self, meta: dict):
        self.flush_block()
        self.flush_rows()
        self.db.execute("CREATE INDEX article_norm ON article(norm_title)")
        self.db.execute("CREATE INDEX chunk_article ON chunk(article)")
        for k, v in meta.items():
            self.db.execute("INSERT INTO meta VALUES (?, ?)", (k, str(v)))
        self.db.commit()
        print("optimizing FTS index…", flush=True)
        self.db.execute("INSERT INTO fts(fts) VALUES ('optimize')")
        self.db.commit()
        self.db.execute("VACUUM")
        self.db.close()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default="data/finewiki_en")
    ap.add_argument("--out", default="out/wiki.db")
    ap.add_argument("--limit-shards", type=int, default=0)
    ap.add_argument("--limit-articles", type=int, default=0)
    ap.add_argument("--fts-detail", default="full", choices=["full", "column", "none"])
    args = ap.parse_args()

    shards = sorted(Path(args.src).glob("*.parquet"))
    if args.limit_shards:
        shards = shards[: args.limit_shards]
    if not shards:
        sys.exit(f"no parquet shards in {args.src}")

    t0 = time.time()
    print("training zstd dictionary…", flush=True)
    b = Builder(Path(args.out), args.fts_detail, train_dictionary(shards[0]))
    cols = ["page_id", "title", "text", "infoboxes", "bytes_html"]
    done = False
    for shard in shards:
        for batch in pq.ParquetFile(shard).iter_batches(columns=cols, batch_size=2000):
            for pid, title, text, ib, size in zip(*(batch.column(i).to_pylist() for i in range(len(cols)))):
                b.add_article(int(pid), title, text or "", infobox_text(ib), int(size or 0))
                if args.limit_articles and b.n_articles >= args.limit_articles:
                    done = True
                    break
            if done:
                break
            if b.n_articles % 50_000 < 2000:
                el = time.time() - t0
                print(f"{b.n_articles:>9,} articles  {b.next_chunk - 1:>11,} chunks  "
                      f"{b.raw_bytes / 1e9:6.2f} GB text -> {b.comp_bytes / 1e9:5.2f} GB  {el / 60:5.1f} min", flush=True)
        if done:
            break

    b.finish({
        "format": 1,
        "source": "HuggingFaceFW/finewiki enwiki (Wikipedia HTML dump 2025-08), CC BY-SA 4.0",
        "articles": b.n_articles,
        "chunks": b.next_chunk - 1,
        "fts_detail": args.fts_detail,
        "built": time.strftime("%Y-%m-%d"),
    })
    size = Path(args.out).stat().st_size
    print(f"done: {b.n_articles:,} articles, {b.next_chunk - 1:,} chunks, "
          f"text {b.raw_bytes / 1e9:.2f} GB -> {b.comp_bytes / 1e9:.2f} GB, db {size / 1e9:.2f} GB, "
          f"{(time.time() - t0) / 60:.1f} min", flush=True)


if __name__ == "__main__":
    main()
