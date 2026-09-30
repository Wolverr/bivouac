"""Adds an alias table to a built wiki.db: other names people use for articles
("GPS" -> Global Positioning System, "TCP" -> Transmission Control Protocol,
"Treaty of Westphalia" -> Peace of Westphalia). FineWiki has no redirects, so
title matching alone can't find these.

Two sources, both limited to names people actually looked up in the month:
  * the pageviews dump logs a view of a redirect under the *target's* page id
    ("GPS 11866", where 11866 is Global_Positioning_System)
  * the redirect dump (rd_from -> target title), joined with the pageviews rows
    of the redirect page's own id

  alias(norm_title, article, views)   looked up after article.norm_title misses; also gives
                                      the local names of places ("Florence" -> "firenze")

Usage: python build_aliases.py out/wiki.db [--pageviews-dump data/pageviews-202508-user.bz2]
                               [--redirects data/enwiki-latest-redirect.sql.gz] [--min-views 10]
Downloads the redirect dump (~190 MB) if it's missing. Safe to re-run (replaces the table).
"""

import argparse
import gzip
import re
import sqlite3
import subprocess
import time
import urllib.request
from pathlib import Path

import duckdb
import pyarrow as pa

from build_corpus import norm_title

REDIRECTS_URL = "https://dumps.wikimedia.org/enwiki/latest/enwiki-latest-redirect.sql.gz"
ROW_RE = re.compile(rb"\((\d+),0,'((?:[^'\\]|\\.)*)',")


def extract_titles(dump: Path, out: Path):
    """English pageview rows as title<TAB>page_id<TAB>views (the pageviews TSV drops titles)."""
    if out.exists() and out.stat().st_size > 0:
        return
    tmp = out.with_suffix(".part")
    awk = r'''$1 == "en.wikipedia" { seen = 1; if ($3 != "null") print $2 "\t" $3 "\t" $5; next }
              seen { exit }'''
    with open(tmp, "wb") as f:
        bz = subprocess.Popen(["bzip2", "-dc", str(dump)], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        rc = subprocess.run(["awk", awk], stdin=bz.stdout, stdout=f).returncode
        bz.kill()
    if rc != 0:
        raise SystemExit("awk failed extracting pageview titles")
    tmp.rename(out)


def extract_redirects(dump: Path, out: Path):
    """Main-namespace redirects as from_id<TAB>target_title."""
    if out.exists() and out.stat().st_size > 0:
        return
    if not dump.exists():
        print(f"downloading {REDIRECTS_URL}", flush=True)
        urllib.request.urlretrieve(REDIRECTS_URL, dump)
    tmp = out.with_suffix(".part")
    n = 0
    with gzip.open(dump, "rb") as src, open(tmp, "wb") as f:
        for line in src:
            for m in ROW_RE.finditer(line):
                title = m.group(2).replace(b"\\'", b"'").replace(b'\\"', b'"').replace(b"\\\\", b"\\")
                if b"\t" in title or b"\n" in title:
                    continue
                f.write(m.group(1) + b"\t" + title + b"\n")
                n += 1
    tmp.rename(out)
    print(f"redirects: {n:,}", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("db")
    ap.add_argument("--pageviews-dump", default="data/pageviews-202508-user.bz2")
    ap.add_argument("--redirects", default="data/enwiki-latest-redirect.sql.gz")
    ap.add_argument("--min-views", type=int, default=10)
    a = ap.parse_args()
    t0 = time.time()

    titles_tsv = Path("data/pageviews_en_titles.tsv")
    redirects_tsv = Path("data/redirects_en.tsv")
    print("extracting pageview titles…", flush=True)
    extract_titles(Path(a.pageviews_dump), titles_tsv)
    print("extracting redirects…", flush=True)
    extract_redirects(Path(a.redirects), redirects_tsv)

    db = sqlite3.connect(a.db)
    print("reading article titles…", flush=True)
    con = duckdb.connect()
    con.execute("SET memory_limit='3GB'; SET threads=4; SET preserve_insertion_order=false;")
    ids, titles = [], []
    for art_id, title in db.execute("SELECT id, title FROM article"):
        ids.append(art_id)
        titles.append(title)
    art = pa.table({"id": pa.array(ids, pa.int64()), "title": pa.array(titles, pa.string())})
    del ids, titles
    con.register("art_arrow", art)
    con.execute("CREATE TABLE art AS SELECT * FROM art_arrow")
    con.unregister("art_arrow")
    del art
    print(f"  {con.execute('SELECT count(*) FROM art').fetchone()[0]:,} articles", flush=True)

    csv = "delim='\t', header=false, quote='', escape='', ignore_errors=true"
    rows = con.sql(f"""
        WITH pv AS (
            SELECT replace(column0, '_', ' ') AS t, column1::BIGINT AS pid, sum(column2::BIGINT) AS v
            FROM read_csv('{titles_tsv}', {csv}, columns={{'column0':'VARCHAR','column1':'VARCHAR','column2':'VARCHAR'}})
            WHERE regexp_full_match(column1, '[0-9]+')
            GROUP BY 1, 2
        ),
        rd AS (
            SELECT column0::BIGINT AS from_id, replace(column1, '_', ' ') AS target
            FROM read_csv('{redirects_tsv}', {csv}, columns={{'column0':'VARCHAR','column1':'VARCHAR'}})
        ),
        -- views logged under the target's id with another title
        direct AS (
            SELECT pv.t, art.id, pv.v FROM pv JOIN art ON art.id = pv.pid WHERE pv.t <> art.title
        ),
        -- views of the redirect page itself
        via_rd AS (
            SELECT pv.t, art.id, pv.v FROM pv JOIN rd ON rd.from_id = pv.pid JOIN art ON art.title = rd.target
        ),
        named AS (SELECT * FROM direct UNION ALL SELECT * FROM via_rd)
        SELECT t, id, sum(v) AS v FROM named GROUP BY 1, 2 HAVING sum(v) >= {a.min_views}
    """).fetchall()
    print(f"  {len(rows):,} (name, article) pairs with >= {a.min_views} views", flush=True)

    # One article per name: the most viewed; names that are already article titles are skipped.
    best: dict[str, tuple[int, int]] = {}
    for t, art_id, v in rows:
        n = norm_title(t)
        if n and (n not in best or v > best[n][1]):
            best[n] = (art_id, v)
    existing = set()
    names = list(best)
    for i in range(0, len(names), 50_000):
        part = names[i:i + 50_000]
        q = "SELECT norm_title FROM article WHERE norm_title IN (%s)" % ",".join("?" * len(part))
        existing.update(r[0] for r in db.execute(q, part))

    db.execute("DROP TABLE IF EXISTS alias")
    db.execute("CREATE TABLE alias(norm_title TEXT NOT NULL, article INTEGER NOT NULL, views INTEGER NOT NULL)")
    db.executemany("INSERT INTO alias VALUES (?, ?, ?)",
                   ((n, art_id, v) for n, (art_id, v) in best.items() if n not in existing))
    db.execute("CREATE INDEX alias_norm ON alias(norm_title)")
    # Places geocoding asks the other way round: an article's other names ("Florence" -> "firenze").
    db.execute("CREATE INDEX alias_article ON alias(article, views)")
    db.execute("INSERT OR REPLACE INTO meta VALUES ('aliases', (SELECT count(*) FROM alias))")
    db.commit()
    n = db.execute("SELECT count(*) FROM alias").fetchone()[0]
    db.close()
    print(f"done: {n:,} aliases in {(time.time() - t0) / 60:.1f} min", flush=True)


if __name__ == "__main__":
    main()
