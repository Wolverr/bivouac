"""Adds a coordinate table to a built wiki.db: Florence Cathedral -> 43.7731, 11.2560.

FineWiki keeps an infobox's coordinates only when the infobox template itself has them;
many landmarks put theirs in a separate {{coord}} template (Florence Cathedral, Louvre,
Trevi Fountain, Times Square), so their infobox has none and "near the Duomo" fell back
to the city centre. Wikipedia's geo_tags dump has every article's primary coordinates:

  https://dumps.wikimedia.org/enwiki/latest/enwiki-latest-geo_tags.sql.gz  (~55 MB)

  coord(article INTEGER PRIMARY KEY, lat REAL, lon REAL)   article = article.id (the page id)

Places.landmark / Places.geocode (and tools/places_proto.py) use it before infobox lines.

Usage: python build_coords.py out/wiki.db enwiki-latest-geo_tags.sql.gz [--apply]
Without --apply it only counts. Safe to re-run.
"""

import argparse
import gzip
import re
import sqlite3
import time

# (gt_id, gt_page_id, 'earth', gt_primary=1, lat, lon, ...: the leading fields have no quotes to escape.
ROW_RE = re.compile(rb"\(\d+,(\d+),'earth',1,(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?),")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("db")
    ap.add_argument("dump", help="enwiki-latest-geo_tags.sql.gz")
    ap.add_argument("--apply", action="store_true", help="write the coord table into the db")
    a = ap.parse_args()

    t0 = time.time()
    ro = sqlite3.connect(f"file:{a.db}?mode=ro", uri=True)
    ids = {r[0] for r in ro.execute("SELECT id FROM article")}
    coords: dict[int, tuple[float, float]] = {}
    tagged = 0
    with gzip.open(a.dump, "rb") as f:
        for line in f:
            for m in ROW_RE.finditer(line):
                tagged += 1
                page = int(m[1])
                lat, lon = float(m[2]), float(m[3])
                if page in ids and -90 <= lat <= 90 and -180 <= lon <= 180:
                    coords.setdefault(page, (lat, lon))  # a page has one primary tag; keep the first if not
    print(f"{tagged:,} primary earth tags, {len(coords):,} of {len(ids):,} articles ({time.time() - t0:.0f} s)")

    if a.apply:
        rw = sqlite3.connect(a.db, timeout=60)
        rw.execute("DROP TABLE IF EXISTS coord")
        rw.execute("CREATE TABLE coord(article INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL)")
        rw.executemany("INSERT INTO coord VALUES (?, ?, ?)", sorted((k, v[0], v[1]) for k, v in coords.items()))
        rw.execute("INSERT OR REPLACE INTO meta VALUES ('coords', ?)", (str(len(coords)),))
        rw.commit()
        print("coord table written")


if __name__ == "__main__":
    main()
