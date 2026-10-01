"""
Place names the places pack alone gets wrong, for apps without the Wikipedia pack.

Overture names localities in the local language and the places pack geocodes a phrase
to the biggest locality of that name. Without the wiki pack's aliases that fails two ways:
  - English names of foreign cities: "munich" has no locality (Overture: "münchen");
  - ambiguous names: "florence" picks Florence, Alabama (8,472 places) over Florence, Italy,
    whose places are tagged "firenze" (only 355 say "florence").
With the wiki pack the app takes the article's coordinates instead. This writes those
coordinates for the names where the two answers differ, so the app gives the same answer
without the pack:
  - every locality name with several localities;
  - every English name of a big locality (n >= 300): wiki aliases equal to the local name
    whose article lies within 25 km of it ("münchen" -> article Munich), unless it only adds
    to the local name ("Bodø Municipality").
A name is kept when the wiki article's coordinates are over 25 km from the biggest locality
of that name (or there is none). Suburbs never redirect to their city: the target is always
the article for that exact name ("greenwich" -> Greenwich, London, not London's centre).

Output: app/src/main/assets/place_names.tsv, "name<TAB>lat<TAB>lon<TAB>article title", sorted.

Usage: python build_place_names.py [--wiki out/wiki.db] [--places out/places.db] [--out …]
"""

import argparse
import math
import sqlite3
from collections import defaultdict
from pathlib import Path

MAX_KM = 25
EXONYM_MIN_PLACES = 300   # localities big enough that someone asks for them by an English name
AMBIGUOUS_MIN_PLACES = 50


def km(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    a = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2
    return 6371 * 2 * math.asin(math.sqrt(a))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--wiki", default="out/wiki.db")
    ap.add_argument("--places", default="out/places.db")
    ap.add_argument("--out", default=str(Path(__file__).resolve().parent.parent / "app/src/main/assets/place_names.tsv"))
    args = ap.parse_args()
    wiki = sqlite3.connect(f"file:{args.wiki}?mode=ro", uri=True)
    places = sqlite3.connect(f"file:{args.places}?mode=ro", uri=True)

    by_name = defaultdict(list)
    for name, cc, lat, lon, n in places.execute("SELECT name, country, lat, lon, n FROM locality"):
        by_name[name].append((lat, lon, n))

    def biggest(name):
        return max(by_name[name], key=lambda r: r[2]) if name in by_name else None

    def article(name):
        """The wiki's main article of that title, as the app's Corpus.findArticle picks it."""
        return wiki.execute("""SELECT a.title, c.lat, c.lon FROM article a JOIN coord c ON c.article = a.id
                               WHERE a.norm_title = ? ORDER BY a.prior DESC LIMIT 1""", (name,)).fetchone()

    targets = {}  # name -> (lat, lon, title)
    for name, rows in by_name.items():
        if len(rows) >= 2 and max(r[2] for r in rows) >= AMBIGUOUS_MIN_PLACES:
            if hit := article(name):
                targets[name] = hit[1], hit[2], hit[0]
    for name, rows in by_name.items():
        lat, lon, n = biggest(name)
        if n < EXONYM_MIN_PLACES:
            continue
        for title, alat, alon in wiki.execute("""SELECT a.title, c.lat, c.lon FROM alias al JOIN article a ON a.id = al.article
                                                 JOIN coord c ON c.article = a.id WHERE al.norm_title = ?
                                                 ORDER BY al.views DESC LIMIT 3""", (name,)):
            english = title.split(",")[0].split(" (")[0].strip().casefold()
            # "pasir puteh district", "bodø municipality": the local name already finds it.
            if name not in english and km(lat, lon, alat, alon) < MAX_KM:
                targets.setdefault(english, (alat, alon, title))
                break

    kept = []
    for name, (lat, lon, title) in targets.items():
        b = biggest(name)
        if b is None or km(b[0], b[1], lat, lon) > MAX_KM:
            kept.append((name, lat, lon, title))
    kept.sort()
    Path(args.out).write_text("".join(f"{n}\t{lat:.5f}\t{lon:.5f}\t{t}\n" for n, lat, lon, t in kept), encoding="utf-8")
    print(f"{len(kept)} names -> {args.out}")


if __name__ == "__main__":
    main()
