"""Desktop mirror of the app's place lookup (PlaceIntent.kt + Places.kt), for
checking place questions against a real places.db without a phone.

  1. parse the question into place kind + location ("pharmacy" near "Shinjuku Station in Tokyo")
  2. map the kind to Overture categories
  3. geocode the location: Wikipedia infobox coordinates (if --wiki), else locality centroids
  4. nearest matches, widening the radius until there are enough

Usage: python places_proto.py out/places.db [--wiki out/wiki.db] "pharmacy near Shinjuku Station in Tokyo"
       python places_proto.py out/places.db --questions ../eval/questions.jsonl   (the "places" ones)
Keep in sync with the Kotlin code; this file is for testing, the app is the product.
"""

import argparse
import json
import math
import re
import sqlite3
import time

SYNONYMS = {
    "vegetarian": ["vegetarian", "vegan"], "veggie": ["vegetarian", "vegan"],
    "coffee": ["cafe", "coffee"], "chemist": ["pharmacy", "drugstore"], "drugstore": ["pharmacy", "drugstore"],
    "pharmacies": ["pharmacy", "drugstore"], "restroom": ["toilet"], "bathroom": ["toilet"], "toilets": ["toilet"],
    "wc": ["toilet"], "grocery": ["grocery", "supermarket"], "groceries": ["grocery", "supermarket"],
    "supermarket": ["grocery", "supermarket"],
    "cash": ["atm"], "doctor": ["doctor", "clinic", "medical"], "er": ["emergency", "hospital"],
    "accommodation": ["hotel", "hostel", "guest"], "stay": ["hotel", "hostel", "guest"],
    "eat": ["restaurant"], "food": ["restaurant"], "petrol": ["gas"], "fuel": ["gas"], "wifi": ["internet", "cafe"],
}
KNOWLEDGE = re.compile(r"\b(must[- ]see|famous|known for|history|worth (?:a )?visit|most important)\b")
GENERIC_ENDINGS = {"store", "shop", "station", "center", "centre", "office"}
CLAUSE = re.compile(r"\s+(?:and|that|which|where|with|for|so|because|to)\s+|[;:]")
NEAR_ME = re.compile(r"\b(near me|nearby|near here|around here|close by|closest|nearest|around me)\b")
LOCATION = re.compile(r"\b(?:near|around|close to|in|at|by|next to)\s+(?:the\s+)?(.+?)\s*[?.!]*$", re.I)
WORD = re.compile(r"[^\W\d_][\w'\-]*")
DECIMAL_COORDS = re.compile(r"(\d+(?:\.\d+)?)°\s*([NS])\s+(\d+(?:\.\d+)?)°\s*([EW])")
COORDS_LINE = re.compile(r"^(geographic )?coordinates", re.I)  # Places.COORDS_LINE


def singularize(w):
    if w.endswith("ies") and len(w) > 4:
        return w[:-3] + "y"
    if w.endswith("sses"):
        return w[:-2]
    if w.endswith("s") and not w.endswith("ss") and len(w) > 3:
        return w[:-1]
    return w


def parse_intent(question, place_nouns):
    q = question.strip()
    lower = q.lower()
    words = WORD.findall(lower)
    singular = [singularize(w) for w in words]
    noun_at = next((i for i, w in enumerate(singular)
                    if w in place_nouns or any(s in place_nouns for s in SYNONYMS.get(w, []))), -1)
    if noun_at < 0:
        return None
    if KNOWLEDGE.search(lower):
        return None
    near_me = bool(NEAR_ME.search(lower))
    loc = None
    if not near_me:
        frm = 0
        for i in range(noun_at + 1):
            at = lower.find(words[i], frm)
            frm = at + len(words[i]) if at >= 0 else 0
        m = LOCATION.search(q[frm:])
        loc = m.group(1).strip() if m else None
        if not loc:
            return None
    mods = []
    for w in singular[max(0, noun_at - 2):noun_at]:
        for s in SYNONYMS.get(w, [w]):
            if s not in mods:
                mods.append(s)
    noun = SYNONYMS.get(singular[noun_at], [singular[noun_at]])
    return {"modifiers": mods, "noun": noun, "location": loc}


def place_nouns_of(category):
    parts = category.split("_")
    nouns = [parts[-1]] + ([parts[-2]] if parts[-1] in GENERIC_ENDINGS and len(parts) >= 2 else [])
    return [n for n in nouns if len(n) > 2]


def haversine(lat1, lon1, lat2, lon2):
    d_lat, d_lon = math.radians(lat2 - lat1), math.radians(lon2 - lon1)
    a = math.sin(d_lat / 2) ** 2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(d_lon / 2) ** 2
    return 2 * 6_371_000 * math.asin(math.sqrt(a))


def norm_title(t):
    return " ".join(t.replace("_", " ").casefold().split())


class Places:
    def __init__(self, path, wiki=None):
        self.db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        rows = self.db.execute("SELECT id, name, n FROM category").fetchall()
        self.categories = [(i, n) for i, n, _ in rows]
        self.place_nouns = {w for _, n, c in rows if c >= 200 for w in place_nouns_of(n)}
        self.wiki = None
        if wiki:
            from retrieve_proto import Pack
            self.wiki = Pack(wiki)
            self.has_coord = self.wiki.db.execute(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='coord'").fetchone() is not None

    def category_ids(self, keywords):
        out = set()
        for k in keywords:
            for cid, name in self.categories:
                if k in name.split("_") or (len(k) >= 5 and k in name):
                    out.add(cid)
        return out

    def categories_for(self, intent):
        return self.category_ids(intent["modifiers"]) or self.category_ids(intent["noun"])

    def infobox(self, title):
        """(article title, infobox text) or None (Places.infobox)."""
        row = self.wiki.find_article(title)  # like Corpus.findArticle: title, then alias
        if not row:
            return None
        sec = self.wiki.db.execute("SELECT section FROM chunk WHERE id = ?", (row[2],)).fetchone()
        if not sec or sec[0] != "Infobox":
            return None
        return row[1], self.wiki.text(row[2])

    @staticmethod
    def coords(infobox_text):
        line = next((l for l in infobox_text.splitlines() if COORDS_LINE.match(l)), None)
        ms = list(DECIMAL_COORDS.finditer(line or ""))
        if not ms:
            return None
        m = ms[-1]
        return float(m[1]) * (-1 if m[2] == "S" else 1), float(m[3]) * (-1 if m[4] == "W" else 1)

    def article_coords(self, title):
        """(article title, lat, lon) from the coord table (build_coords.py), else the infobox
        coordinates line, else None (Places.articleCoords)."""
        row = self.wiki.find_article(title)
        if not row:
            return None
        if self.has_coord:
            c = self.wiki.db.execute("SELECT lat, lon FROM coord WHERE article = ?", (row[0],)).fetchone()
            if c:
                return row[1], c[0], c[1]
        box = self.infobox(title)
        c = self.coords(box[1]) if box else None
        return (box[0], c[0], c[1]) if c else None

    def mentions(self, row, where):
        """The article's infobox or intro names one of `where` (its city)."""
        text = " ".join(self.wiki.text(row[2] + i) for i in range(min(2, row[3]))).lower()
        return any(w.lower() in text for w in where)

    def landmark(self, place, where):
        """Places.landmark: "Shibuya Crossing" in "Tokyo". The landmark's own coordinates, else
        the first part of its infobox Location line ("Shibuya, Tokyo, Japan" -> Shibuya), never
        the city centre a few km away. Its infobox or intro must mention the city, so a generic
        "old town" in Prague can't match some other town's article. A bare "Duomo" (an article
        about the word) also tries "Florence Duomo" and "Duomo of Florence"."""
        titles = [place] + [t for w in where[:1] for t in (f"{w} {place}", f"{place} of {w}", f"{place}, {w}", f"{place} ({w})")]
        for t in titles:
            row = self.wiki.find_article(t)
            if not row or not self.mentions(row, where):
                continue
            c = self.article_coords(row[1])
            if c:
                return c[1], c[2], f"wiki:{c[0]}"
            box = self.infobox(row[1])
            line = next((l for l in box[1].splitlines() if l.lower().startswith("location:")), "") if box else ""
            area = line.split(":", 1)[1].split(",")[0].strip() if line else ""
            if area and area.lower() not in (w.lower() for w in where):
                sub = self.article_coords(area)
                if sub:
                    return sub[1], sub[2], f"wiki:{sub[0]} (location of {row[1]})"
                loc = self.db.execute("SELECT lat, lon, country, n, name FROM locality WHERE name IN (?, ?) ORDER BY n DESC LIMIT 1",
                                      (area.lower(), area.lower() + " city")).fetchone()
                if loc:
                    return loc[0], loc[1], f"locality:{loc[4]} (location of {row[1]})"
        return None

    def geocode(self, phrase):
        core = CLAUSE.split(phrase, maxsplit=1)[0].strip()
        parts = [p.strip() for p in re.split(r"\s+in\s+|,\s*", core) if p.strip()]
        cands = [phrase, core] + list(reversed(parts))
        cands = list(dict.fromkeys(cands))
        if self.wiki:
            if len(parts) >= 2:
                hit = self.landmark(parts[0], parts[1:])
                if hit:
                    return hit
            for c in cands:
                c2 = self.article_coords(c)
                if c2:
                    return c2[1], c2[2], f"wiki:{c2[0]}"
        for c in cands:
            # Overture uses local names: "Florence" alone finds Florence, Alabama; the
            # article's aliases add "firenze", and the biggest locality wins.
            # "roma, italy" -> also "roma"
            names = list(dict.fromkeys(x for n in [c.lower()] + self.local_names(c) for x in (n, n.split(",")[0].strip())))
            row = self.db.execute(
                "SELECT lat, lon, country, n, name FROM locality WHERE name IN (%s) ORDER BY n DESC LIMIT 1"
                % ",".join("?" * len(names)), names).fetchone()
            if row:
                return row[0], row[1], f"locality:{row[4]} ({row[2]}, {row[3]} places)"
        return None

    def local_names(self, phrase, limit=20):
        """Other names of the Wikipedia article called `phrase` (most viewed first)."""
        if not self.wiki or not self.wiki.has_alias:
            return []
        row = self.wiki.find_article(phrase)
        if not row:
            return []
        return [r[0] for r in self.wiki.db.execute(
            "SELECT norm_title FROM alias WHERE article = ? ORDER BY views DESC LIMIT ?", (row[0], limit))]

    def within(self, lat, lon, radius, cats):
        d_lat = radius / 111_320.0
        d_lon = radius / (111_320.0 * max(math.cos(math.radians(lat)), 0.01))
        rows = self.db.execute("""
            SELECT p.name, p.name_en, k.name, p.lat, p.lon, p.street, p.locality, p.phone, p.conf, p.alt, p.cat
            FROM place_geo g JOIN place p ON p.id = g.id JOIN category k ON k.id = p.cat
            WHERE g.lat0 >= ? AND g.lat1 <= ? AND g.lon0 >= ? AND g.lon1 <= ? LIMIT 4000""",
            (lat - d_lat, lat + d_lat, lon - d_lon, lon + d_lon)).fetchall()
        out = []
        for r in rows:
            alts = [int(a) for a in (r[9] or "").split(",") if a.isdigit()]
            if r[10] not in cats and not any(a in cats for a in alts):
                continue
            d = haversine(lat, lon, r[3], r[4])
            if d <= radius:
                out.append({"name": r[0], "name_en": r[1], "cat": r[2], "street": r[5], "locality": r[6],
                            "phone": r[7], "conf": r[8], "dist": d})
        return out

    def nearby(self, lat, lon, cats, limit=12):
        if not cats:
            return [], 0
        radius, found = 800.0, []
        while radius <= 12_000:
            found = self.within(lat, lon, radius, cats)
            if len(found) >= limit // 2:
                break
            radius *= 2.5
        return sorted(found, key=lambda p: p["dist"] * (1.6 - p["conf"]))[:limit], radius


def run(places, q):
    t0 = time.time()
    intent = parse_intent(q, places.place_nouns)
    print(f"\nQ: {q}")
    if not intent:
        print("  -> not a place question (goes to Wikipedia)")
        return
    cats = places.categories_for(intent)
    names = sorted(n for i, n in places.categories if i in cats)
    print(f"  intent: {intent}")
    print(f"  categories ({len(names)}): {', '.join(names[:12])}{' …' if len(names) > 12 else ''}")
    if intent["location"] is None:
        print("  -> near me: needs GPS, skipped on desktop")
        return
    center = places.geocode(intent["location"])
    if not center:
        print(f"  -> location not found: {intent['location']!r}")
        return
    found, radius = places.nearby(center[0], center[1], cats)
    print(f"  center: {center[0]:.4f},{center[1]:.4f} from {center[2]}; radius {radius:.0f} m; "
          f"{len(found)} results in {(time.time() - t0) * 1000:.0f} ms")
    for p in found:
        name = p["name"] + (f" ({p['name_en']})" if p["name_en"] else "")
        where = ", ".join(x for x in (p["street"], p["locality"]) if x)
        print(f"    {p['dist']:6.0f} m  {name} — {p['cat']} — conf {p['conf']:.2f}{' — ' + where if where else ''}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("db")
    ap.add_argument("question", nargs="*")
    ap.add_argument("--wiki")
    ap.add_argument("--questions", help="eval questions.jsonl; runs the cat=places ones")
    a = ap.parse_intermixed_args()
    places = Places(a.db, a.wiki)
    qs = list(a.question)
    if a.questions:
        with open(a.questions) as f:
            qs += [json.loads(l)["q"] for l in f if l.strip() and json.loads(l).get("cat") == "places"]
    for q in qs:
        run(places, q)


if __name__ == "__main__":
    main()
