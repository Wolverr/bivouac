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

Article popularity comes from Wikimedia pageviews (tools/fetch_pageviews.sh).
With --full-top N, only the N most-viewed articles keep their full text; the
rest keep their infobox and lead section, which is what people look up about
obscure topics anyway and saves a lot of space.

Chunking and compression run in worker processes; SQLite writes stay in the
main process.

Usage:
  python build_corpus.py --src data/finewiki_en --out out/wiki.db \
      [--pageviews data/pageviews_en.tsv] [--full-top 2000000] [--fts-detail column]
"""

import argparse
import json
import math
import multiprocessing as mp
import os
import re
import sqlite3
import sys
import time
from pathlib import Path

import numpy as np
import pyarrow.parquet as pq
import zstandard as zstd

CHUNK_TARGET = 1200   # characters; ~250-300 tokens
CHUNK_MAX = 1800
BLOCK_TARGET = 32 * 1024
ZSTD_LEVEL = 9
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
    pieces = [piece for par in re.split(r"\n\s*\n", body) if par.strip() for piece in split_long(par.strip())]
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


def load_pageviews(path: str | None) -> np.ndarray | None:
    """page_id -> monthly views (summed over access types), as a dense array by page id.

    Aggregated with DuckDB, which spills to disk: the file has tens of millions of lines.
    """
    if not path:
        return None
    import duckdb
    con = duckdb.connect()
    con.execute("SET memory_limit='1GB'; SET threads=2;")
    res = con.sql(f"""
        SELECT column0::BIGINT AS id, sum(column1::BIGINT) AS v
        FROM read_csv('{path}', delim='\t', header=false, columns={{'column0': 'VARCHAR', 'column1': 'VARCHAR'}})
        WHERE regexp_full_match(column0, '[0-9]+')
        GROUP BY 1
    """).fetchnumpy()
    ids, views = res["id"], res["v"]
    arr = np.zeros(int(ids.max()) + 1, dtype=np.int64)
    arr[ids] = views
    print(f"pageviews: {len(ids):,} pages, {int(arr.sum()):,} views", flush=True)
    return arr


# ---- worker side ----------------------------------------------------------

_cctx = None


def _init_worker(zdict: bytes):
    global _cctx
    _cctx = zstd.ZstdCompressor(level=ZSTD_LEVEL, dict_data=zstd.ZstdCompressionDict(zdict))


def process_batch(articles):
    """articles: [(page_id, title, text, infobox_json, full_text)].
    Returns (blocks, fts_rows, article_rows) with chunk numbers local to the batch:
      blocks:       [(compressed, [(local_chunk, page_id, section, off, len), ...])]
      fts_rows:     [(local_chunk, title, section, body)]
      article_rows: [(page_id, title, local_first_chunk, n_chunks)]
    """
    blocks, fts_rows, article_rows = [], [], []
    buf, pending, n = bytearray(), [], 0

    def flush():
        nonlocal buf, pending
        if buf:
            blocks.append((_cctx.compress(bytes(buf)), pending))
            buf, pending = bytearray(), []

    for page_id, title, text, infobox_raw, full in articles:
        first = n
        for sec, c in article_chunks(title, text, infobox_text(infobox_raw)):
            if not full and sec not in ("", "Infobox"):
                continue
            enc = c.encode()
            pending.append((n, page_id, sec, len(buf), len(enc)))
            buf += enc
            fts_rows.append((n, title, sec, c))
            n += 1
            if len(buf) >= BLOCK_TARGET:
                flush()
        if n > first:
            article_rows.append((page_id, title, first, n - first))
    flush()
    return blocks, fts_rows, article_rows


# ---- main side --------------------------------------------------------------

class Builder:
    def __init__(self, out: Path, fts_detail: str, zdict: bytes, views: np.ndarray | None):
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
        self.views = views
        self.next_chunk = 1
        self.next_block = 1
        self.n_articles = 0
        self.raw_bytes = 0
        self.comp_bytes = 0

    def prior(self, page_id: int, size_hint: int) -> float:
        if self.views is not None:
            v = int(self.views[page_id]) if page_id < len(self.views) else 0
            return math.log1p(v)
        return math.log1p(size_hint) / 2  # rough stand-in: longer articles tend to be more important

    def add_batch(self, result, size_hints: dict[int, int]):
        blocks, fts_rows, article_rows = result
        base = self.next_chunk
        chunk_rows = []
        for data, chunks in blocks:
            for local, pid, sec, off, ln in chunks:
                chunk_rows.append((base + local, pid, sec, self.next_block, off, ln))
                self.raw_bytes += ln
            self.db.execute("INSERT INTO block VALUES (?, ?)", (self.next_block, data))
            self.comp_bytes += len(data)
            self.next_block += 1
        self.db.executemany("INSERT INTO chunk VALUES (?,?,?,?,?,?)", chunk_rows)
        self.db.executemany("INSERT INTO fts(rowid, title, section, body) VALUES (?,?,?,?)",
                            [(base + l, t, s, b) for l, t, s, b in fts_rows])
        self.db.executemany("INSERT INTO article VALUES (?,?,?,?,?,?)",
                            [(pid, t, norm_title(t), self.prior(pid, size_hints.get(pid, 0)), base + first, n)
                             for pid, t, first, n in article_rows])
        self.next_chunk = base + (max((l for l, *_ in fts_rows), default=-1) + 1)
        self.n_articles += len(article_rows)

    def finish(self, meta: dict):
        self.db.execute("CREATE INDEX article_norm ON article(norm_title)")
        self.db.execute("CREATE INDEX chunk_article ON chunk(article)")
        for k, v in meta.items():
            self.db.execute("INSERT INTO meta VALUES (?, ?)", (k, str(v)))
        self.db.commit()
        print("optimizing FTS index…", flush=True)
        self.db.execute("INSERT INTO fts(fts) VALUES ('optimize')")
        self.db.commit()
        print("vacuum…", flush=True)
        self.db.execute("VACUUM")
        self.db.close()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default="data/finewiki_en")
    ap.add_argument("--out", default="out/wiki.db")
    ap.add_argument("--pageviews", default=None)
    ap.add_argument("--full-top", type=int, default=0, help="only the N most-viewed articles keep full text")
    ap.add_argument("--limit-shards", type=int, default=0)
    ap.add_argument("--limit-articles", type=int, default=0)
    ap.add_argument("--fts-detail", default="column", choices=["full", "column", "none"])
    ap.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 2) - 1))
    args = ap.parse_args()

    shards = sorted(Path(args.src).glob("*.parquet"))
    if args.limit_shards:
        shards = shards[: args.limit_shards]
    if not shards:
        sys.exit(f"no parquet shards in {args.src}")

    t0 = time.time()
    views = load_pageviews(args.pageviews)
    full_cutoff = -1
    if args.full_top and views is not None:
        nz = np.sort(views[views > 0])[::-1]
        full_cutoff = int(nz[min(args.full_top, len(nz)) - 1]) if len(nz) else 0
        print(f"full text for articles with >= {full_cutoff} views/month", flush=True)

    print("training zstd dictionary…", flush=True)
    zdict = train_dictionary(shards[0])
    b = Builder(Path(args.out), args.fts_detail, zdict, views)

    def batches():
        cols = ["page_id", "title", "text", "infoboxes", "bytes_html"]
        seen = 0
        for shard in shards:
            for batch in pq.ParquetFile(shard).iter_batches(columns=cols, batch_size=1000):
                rows, hints = [], {}
                for pid, title, text, ib, size in zip(*(batch.column(i).to_pylist() for i in range(len(cols)))):
                    pid = int(pid)
                    v = int(views[pid]) if views is not None and pid < len(views) else 0
                    full = full_cutoff < 0 or v >= full_cutoff
                    rows.append((pid, title, text or "", ib, full))
                    hints[pid] = int(size or 0)
                    seen += 1
                    if args.limit_articles and seen >= args.limit_articles:
                        yield rows, hints
                        return
                yield rows, hints

    last_report = 0
    with mp.Pool(args.workers, initializer=_init_worker, initargs=(zdict,)) as pool:
        pending = []
        for rows, hints in batches():
            pending.append((pool.apply_async(process_batch, (rows,)), hints))
            # Keep a bounded number of batches in flight to cap memory use.
            while len(pending) > args.workers * 3 or (pending and pending[0][0].ready()):
                res, h = pending.pop(0)
                b.add_batch(res.get(), h)
            if b.n_articles - last_report >= 100_000:
                last_report = b.n_articles
                el = time.time() - t0
                print(f"{b.n_articles:>9,} articles  {b.next_chunk - 1:>11,} chunks  "
                      f"{b.raw_bytes / 1e9:6.2f} GB text -> {b.comp_bytes / 1e9:5.2f} GB  {el / 60:5.1f} min", flush=True)
        for res, h in pending:
            b.add_batch(res.get(), h)

    b.finish({
        "format": 1,
        "source": "HuggingFaceFW/finewiki enwiki (Wikipedia HTML dump 2025-08), CC BY-SA 4.0",
        "articles": b.n_articles,
        "chunks": b.next_chunk - 1,
        "fts_detail": args.fts_detail,
        "full_top": args.full_top,
        "pageviews": Path(args.pageviews).name if args.pageviews else "",
        "built": time.strftime("%Y-%m-%d"),
    })
    size = Path(args.out).stat().st_size
    print(f"done: {b.n_articles:,} articles, {b.next_chunk - 1:,} chunks, "
          f"text {b.raw_bytes / 1e9:.2f} GB -> {b.comp_bytes / 1e9:.2f} GB, db {size / 1e9:.2f} GB, "
          f"{(time.time() - t0) / 60:.1f} min", flush=True)


if __name__ == "__main__":
    main()
