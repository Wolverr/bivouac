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

    def category_ids(self, keywords):
        out = set()
        for k in keywords:
            for cid, name in self.categories:
                if k in name.split("_") or (len(k) >= 5 and k in name):
                    out.add(cid)
        return out

    def categories_for(self, intent):
        return self.category_ids(intent["modifiers"]) or self.category_ids(intent["noun"])

    def geocode(self, phrase):
        core = CLAUSE.split(phrase, maxsplit=1)[0].strip()
        cands = [phrase, core] + [p.strip() for p in reversed(re.split(r"\s+in\s+|,\s*", core)) if p.strip()]
        cands = list(dict.fromkeys(cands))
        if self.wiki:
            for c in cands:
                row = self.wiki.db.execute(
                    "SELECT title, first_chunk FROM article WHERE norm_title = ? ORDER BY prior DESC LIMIT 1",
                    (norm_title(c),)).fetchone()
                if not row:
                    continue
                sec = self.wiki.db.execute("SELECT section FROM chunk WHERE id = ?", (row[1],)).fetchone()
                if not sec or sec[0] != "Infobox":
                    continue
                line = next((l for l in self.wiki.text(row[1]).splitlines() if l.lower().startswith("coordinates")), None)
                ms = list(DECIMAL_COORDS.finditer(line or ""))
                if ms:
                    m = ms[-1]
                    lat = float(m[1]) * (-1 if m[2] == "S" else 1)
                    lon = float(m[3]) * (-1 if m[4] == "W" else 1)
                    return lat, lon, f"wiki:{row[0]}"
        for c in cands:
            row = self.db.execute("SELECT lat, lon, country, n FROM locality WHERE name = ? ORDER BY n DESC LIMIT 1",
                                  (c.lower(),)).fetchone()
            if row:
                return row[0], row[1], f"locality:{c} ({row[2]}, {row[3]} places)"
        return None

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
