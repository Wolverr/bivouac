"""Download the English FineWiki shards, keeping only the columns Bivouac uses.

FineWiki (HuggingFaceFW/finewiki, CC BY-SA 4.0) is Wikipedia from the August 2025
HTML dumps, cleaned to Markdown-ish text. The raw `wikitext` column is two thirds
of each file and we never need it, so we read column subsets with HTTP range
requests instead of downloading whole files (~13 GB instead of ~38 GB).

Usage: python fetch_finewiki.py [--out data/finewiki_en]
"""

import argparse
import sys
import time
from pathlib import Path

import pyarrow.parquet as pq
from huggingface_hub import HfFileSystem

REPO = "datasets/HuggingFaceFW/finewiki/data/enwiki"
COLUMNS = ["page_id", "title", "text", "wikidata_id", "infoboxes", "bytes_html", "date_modified", "has_math"]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="data/finewiki_en")
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    fs = HfFileSystem()
    shards = sorted(p for p in fs.ls(REPO, detail=False) if p.endswith(".parquet"))
    print(f"{len(shards)} shards", flush=True)
    for path in shards:
        dest = out / Path(path).name
        if dest.exists():
            print(f"skip {dest.name}", flush=True)
            continue
        t0 = time.time()
        tmp = dest.with_suffix(".tmp")
        rows = 0
        # Stream batch by batch: a whole shard's text is several GB in memory.
        with fs.open(path, block_size=16 << 20) as f:
            src = pq.ParquetFile(f)
            writer = None
            for batch in src.iter_batches(columns=COLUMNS, batch_size=5_000):
                if writer is None:
                    writer = pq.ParquetWriter(tmp, batch.schema, compression="zstd")
                writer.write_batch(batch)
                rows += batch.num_rows
            writer.close()
        tmp.rename(dest)
        print(f"{dest.name}: {rows} rows in {time.time() - t0:.0f}s", flush=True)


if __name__ == "__main__":
    sys.exit(main())
