"""Append supplement documents (tools/build_supplements.py) to a copy of wiki.db.

Each document becomes an article like a Wikipedia one: chunked by build_corpus.article_chunks, compressed with the
pack's own zstd dictionary, indexed in fts, with its aliases ("ML-DSA" -> FIPS 204). Ids start above Wikipedia's
page ids. Where a title already names a Wikipedia article ("ERC-721"), that article stays the title match: the
supplement ranks just below it and is still found by search.

Usage: python append_supplements.py --db out/wiki.db --docs out/supp-eth.jsonl out/supp-voyage.jsonl --out out/wiki-v2.db
"""

import argparse
import json
import math
import shutil
import sqlite3
import time
from pathlib import Path

import zstandard as zstd

from build_corpus import BLOCK_TARGET, ZSTD_LEVEL, article_chunks, norm_title

FIRST_ID = 200_000_000
SOURCES = {
    "eips": "Ethereum Improvement Proposals and ERCs (github.com/ethereum/EIPs, /ERCs), CC0",
    "ethereum.org": "ethereum.org documentation (github.com/ethereum/ethereum-org-website), MIT",
    "upgrading-ethereum": "Upgrading Ethereum by Ben Edgington (eth2book.info), CC BY-SA 4.0",
    "nist": "NIST FIPS 203/204/205, SP 800-208, IR 8547, public domain",
    "wikivoyage": "English Wikivoyage, CC BY-SA 4.0",
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--docs", nargs="+", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    out = Path(args.out)
    t0 = time.time()
    shutil.copyfile(args.db, out)
    print(f"copied in {time.time() - t0:.0f} s", flush=True)
    db = sqlite3.connect(out)
    db.execute("PRAGMA journal_mode = OFF")
    db.execute("PRAGMA synchronous = OFF")
    cctx = zstd.ZstdCompressor(level=ZSTD_LEVEL,
                               dict_data=zstd.ZstdCompressionDict(db.execute("SELECT data FROM dict WHERE id = 1").fetchone()[0]))
    next_article = max(FIRST_ID, db.execute("SELECT max(id) FROM article").fetchone()[0] + 1)
    next_chunk = db.execute("SELECT max(id) FROM chunk").fetchone()[0] + 1
    next_block = db.execute("SELECT max(id) FROM block").fetchone()[0] + 1
    has_alias = db.execute("SELECT 1 FROM sqlite_master WHERE name = 'alias'").fetchone() is not None

    buf, pending = bytearray(), []

    def taken(n: str) -> bool:
        return db.execute("SELECT 1 FROM article WHERE norm_title = ? AND id < ? UNION ALL "
                          "SELECT 1 FROM alias WHERE norm_title = ? LIMIT 1", (n, FIRST_ID, n)).fetchone() is not None

    def flush():
        nonlocal buf, pending, next_block
        if buf:
            db.execute("INSERT INTO block VALUES (?, ?)", (next_block, cctx.compress(bytes(buf))))
            db.executemany("INSERT INTO chunk VALUES (?,?,?,?,?,?)", [(c, a, s, next_block, o, n) for c, a, s, o, n in pending])
            next_block += 1
            buf, pending = bytearray(), []

    counts, n_chunks = {}, 0
    for path in args.docs:
        for line in open(path):
            doc = json.loads(line)
            title, norm = doc["title"], norm_title(doc["title"])
            prior = doc["prior"]
            existing = db.execute("SELECT max(prior) FROM article WHERE norm_title = ?", (norm,)).fetchone()[0]
            if existing is not None:
                prior = min(prior, existing - 0.5)
            first = next_chunk
            fts = []
            for sec, text in article_chunks(title, doc["text"], ""):
                enc = text.encode()
                pending.append((next_chunk, next_article, sec, len(buf), len(enc)))
                fts.append((next_chunk, title, sec, text))
                buf += enc
                next_chunk += 1
                if len(buf) >= BLOCK_TARGET:
                    flush()
            if next_chunk == first:
                continue
            flush()  # an article's chunks never share a block with the next article's (simpler bookkeeping)
            db.executemany("INSERT INTO fts(rowid, title, section, body) VALUES (?,?,?,?)", fts)
            db.execute("INSERT INTO article VALUES (?,?,?,?,?,?)", (next_article, title, norm, prior, first, next_chunk - first))
            if has_alias:
                views = int(math.expm1(prior))
                for a in dict.fromkeys(doc.get("aliases", [])):
                    n = norm_title(a)
                    # A page's own name never takes a name Wikipedia already has, nor a single word ("Slashing").
                    if doc["source"] in ("ethereum.org", "upgrading-ethereum") and (len(n.split()) < 2 or taken(n)):
                        continue
                    db.execute("INSERT INTO alias VALUES (?,?,?)", (n, next_article, views))
            counts[doc["source"]] = counts.get(doc["source"], 0) + 1
            n_chunks += next_chunk - first
            next_article += 1
    flush()
    n_art = db.execute("SELECT count(*) FROM article").fetchone()[0]
    n_ch = db.execute("SELECT count(*) FROM chunk").fetchone()[0]
    supp = "; ".join(f"{SOURCES.get(s, s)}: {n}" for s, n in counts.items())
    for k, v in (("articles", n_art), ("chunks", n_ch), ("supplements", supp), ("supplements_built", time.strftime("%Y-%m-%d"))):
        db.execute("INSERT OR REPLACE INTO meta VALUES (?, ?)", (k, str(v)))
    db.commit()
    print(f"added {sum(counts.values())} articles, {n_chunks} chunks: {counts}", flush=True)
    db.execute("INSERT INTO fts(fts, rank) VALUES ('merge', 16)")  # fold the new segments in a little
    db.commit()
    db.close()
    print(f"done in {time.time() - t0:.0f} s, {out.stat().st_size / 1e9:.2f} GB", flush=True)


if __name__ == "__main__":
    main()
