"""Build Bivouac's offline places pack (places.db) from fetch_places.py output.

Schema:
  category(id, name, top, n)          Overture taxonomy category, its top-level group, place count
  place(id, name, name_en, brand, cat, alt, conf, lat, lon, street, locality, region, country, phone)
      alt = comma-separated ids of alternate categories (e.g. a cafe that's also vegan)
  place_geo (R*Tree)                  bounding box per place (a point), for radius queries
  locality(name, country, lat, lon, n) city/town centroids, a geocoding fallback
  meta(key, value)

Places are inserted in geographic order so that nearby places share disk pages.

Usage: python build_places.py [--src data/places.parquet] [--out out/places.db]
"""

import argparse
import sqlite3
import time
from pathlib import Path

import duckdb

SCHEMA = """
PRAGMA page_size = 8192;
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE category(id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, top TEXT, n INTEGER NOT NULL);
CREATE TABLE place(
    id INTEGER PRIMARY KEY, name TEXT NOT NULL, name_en TEXT, brand TEXT,
    cat INTEGER NOT NULL, alt TEXT, conf REAL NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL,
    street TEXT, locality TEXT, region TEXT, country TEXT, phone TEXT
);
CREATE VIRTUAL TABLE place_geo USING rtree(id, lat0, lat1, lon0, lon1);
CREATE TABLE locality(name TEXT NOT NULL, country TEXT, lat REAL NOT NULL, lon REAL NOT NULL, n INTEGER NOT NULL);
"""


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default="data/places.parquet")
    ap.add_argument("--out", default="out/places.db")
    ap.add_argument("--release", default="2026-09-23.1")
    args = ap.parse_args()

    t0 = time.time()
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    if out.exists():
        out.unlink()
    db = sqlite3.connect(out)
    db.executescript(SCHEMA)
    db.execute("PRAGMA journal_mode = OFF")
    db.execute("PRAGMA synchronous = OFF")
    db.execute("PRAGMA cache_size = -262144")

    con = duckdb.connect()
    con.execute("SET threads=3; SET memory_limit='2GB';")
    src = f"read_parquet('{args.src}')"

    # Categories, including ones that only appear as alternates.
    cats = con.sql(f"""
        WITH all_cats AS (
            SELECT category AS name FROM {src}
            UNION ALL SELECT unnest(alt_categories) FROM {src}
        )
        SELECT name, count(*) n FROM all_cats WHERE name IS NOT NULL GROUP BY 1 ORDER BY 2 DESC
    """).fetchall()
    cat_id = {name: i + 1 for i, (name, _) in enumerate(cats)}
    db.executemany("INSERT INTO category(id, name, n) VALUES (?, ?, ?)", [(cat_id[n], n, c) for n, c in cats])
    print(f"{len(cats):,} categories", flush=True)

    # Places in rough geographic order (0.5-degree cells, row by row).
    rel = con.sql(f"""
        SELECT name, name_en, brand, category, alt_categories, confidence, lat, lon,
               street, locality, region, country, phone
        FROM {src} ORDER BY floor(lat * 2), floor(lon * 2), lat, lon
    """)
    n = 0
    while batch := rel.fetchmany(100_000):
        rows, geo = [], []
        for (name, name_en, brand, cat, alts, conf, lat, lon, street, loc, region, cc, phone) in batch:
            n += 1
            alt = ",".join(str(cat_id[a]) for a in (alts or []) if a in cat_id) or None
            en = name_en if name_en and name_en != name else None
            rows.append((n, name, en, brand, cat_id[cat], alt, conf, lat, lon, street, loc, region, cc, phone))
            geo.append((n, lat, lat, lon, lon))
        db.executemany("INSERT INTO place VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)", rows)
        db.executemany("INSERT INTO place_geo VALUES (?,?,?,?,?)", geo)
        if n % 1_000_000 < 100_000:
            print(f"{n:>11,} places  {(time.time() - t0) / 60:5.1f} min", flush=True)

    # Locality centroids: median position of the places that list the locality.
    locs = con.sql(f"""
        SELECT lower(locality), country, median(lat), median(lon), count(*) n
        FROM {src} WHERE locality IS NOT NULL GROUP BY 1, 2 HAVING count(*) >= 5
    """).fetchall()
    db.executemany("INSERT INTO locality VALUES (?,?,?,?,?)", locs)
    db.execute("CREATE INDEX locality_name ON locality(name)")
    db.execute("CREATE INDEX category_name ON category(name)")
    for k, v in {
        "format": 1,
        "source": f"Overture Maps places {args.release} (CDLA-Permissive-2.0, Apache-2.0, CC0-1.0)",
        "places": n,
        "built": time.strftime("%Y-%m-%d"),
    }.items():
        db.execute("INSERT INTO meta VALUES (?, ?)", (k, str(v)))
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"done: {n:,} places, {len(locs):,} localities, {out.stat().st_size / 1e9:.2f} GB, "
          f"{(time.time() - t0) / 60:.1f} min", flush=True)


if __name__ == "__main__":
    main()
