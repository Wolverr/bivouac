"""Desktop prototype of Bivouac's retrieval step, for tuning against a real wiki.db.

It mirrors what the app does before the LLM runs:
  1. turn the question into an FTS5 query (keywords, OR-ed, stop words dropped)
  2. BM25 over chunks, blended with the article popularity prior
  3. diversify (at most N chunks per article) and cut evidence down to the
     sentences that actually overlap the question, to keep the prompt short

Usage: python retrieve_proto.py out/wiki.db "why is the sky blue"
"""

import math
import re
import sqlite3
import sys
import time
from pathlib import Path

import zstandard as zstd

STOP = set("""
a an and are as at be but by can could did do does for from had has have how i if in into is it its
me my of on or should so than that the their them then there these they this to was we were what when
where which who whom why will with would you your about tell explain describe give some any much many
""".split())

WORD_RE = re.compile(r"[\w][\w'\-]*", re.UNICODE)
# looks_like_acronym() in corpus.cpp: "TCP", "PoS"; not "L2" (one capital) or "AC/DC".
ACRONYM_RE = re.compile(r"^(?=(?:[^A-Z]*[A-Z]){2})[A-Z][A-Za-z0-9&\-]{1,9}$")
SENT_RE = re.compile(r"(?<=[.!?])\s+|\s*\n\s*")  # infobox lines are sentences too
PADDING_RE = re.compile(r"[ \t]{2,}")


def keywords(question: str) -> list[str]:
    words = [w.strip("'-").lower() for w in WORD_RE.findall(question)]
    return [w for w in dict.fromkeys(words) if w and w not in STOP and len(w) > 1]


def fts_query(words: list[str]) -> str:
    # Quote every term so FTS5 syntax characters in user text can't break the query.
    # Split on punctuation first: "heat-pump" or "kazakhstan's" would otherwise be a
    # phrase query, which FTS5 rejects when the index has detail=column.
    terms = [t for w in words for t in re.split(r"[^\w]+", w) if len(t) > 1 and t not in STOP]
    return " OR ".join(f'"{t}"' for t in dict.fromkeys(terms))


def shares_word(title: str, context: str) -> bool:
    """corpus.cpp shares_word(): a 4+ letter word of the title, by its first five letters,
    starts a word of the context ("Completely Fair Scheduler" / "how does Linux schedule")."""
    ctx = re.findall(r"\w+", context.lower())
    return any(c.startswith(t[:5]) for t in re.findall(r"\w+", title.lower()) if len(t) >= 4 for c in ctx)


class Pack:
    def __init__(self, path: str):
        self.db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        # colrank(): BM25 that works on contentless detail=column tables (the built-in
        # bm25() scores every row 0 there). Same C code as the app; build with
        # cmake -B native/build -S native -G Ninja && ninja -C native/build
        ext = Path(__file__).parent / "native" / "build" / "fts_rank.so"
        if not ext.exists():
            sys.exit(f"missing {ext}: build tools/native first (see retrieve_proto.py)")
        self.db.enable_load_extension(True)
        self.db.load_extension(str(ext), entrypoint="sqlite3_ftsrank_init")
        self.db.enable_load_extension(False)
        zdict = zstd.ZstdCompressionDict(self.db.execute("SELECT data FROM dict WHERE id=1").fetchone()[0])
        self.dctx = zstd.ZstdDecompressor(dict_data=zdict)
        self.cache: dict[int, bytes] = {}
        self.has_alias = self.db.execute(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='alias'").fetchone() is not None
        self.has_acronym = self.db.execute(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='acronym'").fetchone() is not None
        self.has_rtree = self.db.execute(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='coord_rtree'").fetchone() is not None

    def find_article(self, title: str, context: str = ""):
        """(id, title, first_chunk, n_chunks, prior) by exact title, else by alias ("GPS"), else the
        popular "X (...)" / "X City" article for disambiguation pages ("New York"); like Corpus::find_article."""
        norm = " ".join(title.replace("_", " ").casefold().split())
        by_title = lambda: self.db.execute(  # noqa: E731
            "SELECT id, title, first_chunk, n_chunks, prior FROM article WHERE norm_title = ? "
            "ORDER BY prior DESC LIMIT 1", (norm,)).fetchone()
        by_alias = lambda: self.db.execute(  # noqa: E731
            "SELECT a.id, a.title, a.first_chunk, a.n_chunks, a.prior FROM alias l JOIN article a "
            "ON a.id = l.article WHERE l.norm_title = ? ORDER BY l.views DESC LIMIT 1", (norm,)).fetchone() \
            if self.has_alias else None
        # Acronyms (Corpus::find_article): exact spelling as a title wins ("NATO"), then a meaning
        # that shares a word with the question ("CFS" in a Linux scheduling question), then a
        # Wikipedia redirect (editors' primary meaning: "RAM" -> Random-access memory, not a
        # slightly more viewed missile), else the more viewed of title and mined acronym.
        if self.has_acronym and ACRONYM_RE.match(title):
            row = self.db.execute("SELECT id, title, first_chunk, n_chunks, prior FROM article "
                                  "WHERE norm_title = ? AND title = ? ORDER BY prior DESC LIMIT 1", (norm, title)).fetchone()
            if row:
                return row
            meanings = self.db.execute(
                "SELECT a.id, a.title, a.first_chunk, a.n_chunks, a.prior FROM acronym x JOIN article a "
                "ON a.id = x.article WHERE x.abbr = ? ORDER BY x.views DESC LIMIT 5", (title,)).fetchall()
            for m in meanings:
                if context and shares_word(m[1], context):
                    return m
            alias = by_alias()
            if alias:
                return alias
            found = [r for r in (by_title(), meanings[0] if meanings else None) if r]
            if found:
                return max(found, key=lambda r: r[4])
        row = by_title()
        if row is None:
            row = by_alias()
        # Only for names as written ("New York", not "broad" -> "Broad City").
        if row is None and all(w[:1].isupper() for w in title.split() if w.lower() not in STOP):
            row = self.db.execute(
                "SELECT id, title, first_chunk, n_chunks, prior FROM article "
                "WHERE ((norm_title >= ?1 || ' (' AND norm_title < ?1 || ' )') OR norm_title = ?1 || ' city') "
                "AND prior >= 9.21 ORDER BY prior DESC LIMIT 1", (norm,)).fetchone()
        return row

    def text(self, chunk_id: int) -> str:
        block, off, ln = self.db.execute("SELECT block, off, len FROM chunk WHERE id=?", (chunk_id,)).fetchone()
        if block not in self.cache:
            data = self.db.execute("SELECT data FROM block WHERE id=?", (block,)).fetchone()[0]
            self.cache[block] = self.dctx.decompress(data)
        return self.cache[block][off:off + ln].decode()

    def search(self, q: str, limit=8, per_article=2, prior_weight=0.5, candidates=200, boost_articles=frozenset(), boost=4.0):
        rows = self.db.execute(
            "SELECT f.id, f.s, c.article, c.section, a.title, a.prior FROM "
            "(SELECT rowid AS id, colrank(fts, 6.0, 2.0, 1.0) AS s FROM fts WHERE fts MATCH ? ORDER BY s LIMIT ?) f "
            "JOIN chunk c ON c.id=f.id JOIN article a ON a.id=c.article",
            (q, candidates),
        ).fetchall()
        scored = sorted(((-s + prior_weight * prior + (boost if art in boost_articles else 0.0), cid, title, sec)
                         for cid, s, art, sec, title, prior in rows), reverse=True)
        out, per = [], {}
        for score, cid, title, sec in scored:
            if per.get(title, 0) >= per_article:
                continue
            per[title] = per.get(title, 0) + 1
            out.append((score, cid, title, sec))
            if len(out) >= limit:
                break
        return out


TOKEN_RE = re.compile(r"[\w][\w'\-.]*", re.UNICODE)

# A lowercase word in more chunks than this is everyday language. An article titled
# only with such words counts when it's a real topic ("Season", "Age of the universe")
# and is skipped when obscure ("Determine" 272 views/month, "Broad Strokes" 87).
COMMON_DF = 20_000
COMMON_MIN_VIEWS = 3_000


def everyday(pack: "Pack", words: list[str], starts: list[bool]) -> bool:
    """No name-like word and every content word common. starts[k]: words[k] begins a sentence."""
    if any(is_named(w, s) for w, s in zip(words, starts)):
        return False
    return all(doc_freq(pack, w) > COMMON_DF for w in words if w.lower() not in STOP)


def is_named(tok: str, first: bool) -> bool:
    """Written as a name: capitalized mid-sentence, or any inner capital ("GPS", "mRNA")."""
    if any(c.isupper() for c in tok[1:]):
        return True
    return tok[:1].isupper() and not first and tok != "I"  # "should I try" is not the song "I Try"


def doc_freq(pack: "Pack", word: str, cap: int = COMMON_DF + 1) -> int:
    """Chunks containing the word, counted up to `cap` (cheap even for common words)."""
    # Punctuated tokens ("don't", "CFS/EEVDF") would be phrase queries, unsupported with detail=column.
    parts = re.findall(r"\w+", word)
    if not parts:
        return cap
    q = " AND ".join(f'"{p}"' for p in parts)
    return pack.db.execute("SELECT count(*) FROM (SELECT 1 FROM fts WHERE fts MATCH ? LIMIT ?)", (q, cap)).fetchone()[0]


def title_matches(pack: "Pack", question: str, max_n: int = 5):
    """Articles whose title equals a phrase of the question, longest phrases first.

    Returns [(article_id, title, first_chunk, n_chunks)]. Words inside a longer
    match aren't tried again ("Roman Empire" beats "Roman" and "Empire").
    """
    toks = [t.strip(".'-") for t in TOKEN_RE.findall(question)]
    starts = sentence_starts(question)
    used = [False] * len(toks)
    found, seen = [], set()
    plain = set()  # everyday-word matches ("North", "Time"), dropped when a name matched
    named_hit = False
    for n in range(min(max_n, len(toks)), 0, -1):
        for i in range(len(toks) - n + 1):
            if any(used[i:i + n]):
                continue
            words = toks[i:i + n]
            if all(w.lower() in STOP for w in words) or (n == 1 and len(words[0]) < 3 and not words[0].isupper()):
                continue
            # "in Mexico" (a photo book) inside "in Mexico City"; "The Hague" as written still counts.
            if n > 1 and (words[0] in STOP or words[-1] in STOP):
                continue
            phrase = " ".join(words)
            cands = [phrase]
            if phrase.lower().endswith("'s"):
                cands = [phrase[:-2]]  # "Ethereum's" -> "Ethereum"
            elif phrase.lower().endswith("s") and len(phrase) > 3:
                cands.append(phrase[:-1])  # "tardigrades" -> "tardigrade"
            for c in cands:
                row = pack.find_article(c, question)
                if not row or row[0] in seen:
                    continue
                common = everyday(pack, words, starts[i:i + n])
                obscure = row[4] < math.log1p(COMMON_MIN_VIEWS)
                if common and obscure:
                    continue
                named = any(is_named(w, s) for w, s in zip(words, starts[i:i + n]))
                # "two a day" is not about the number 2; "monsoon season" is not the mixtape.
                if n == 1 and words[0].lower() in NUMBER_WORDS:
                    continue
                if not named and is_work(pack, row, question):
                    continue
                # A plain word's redirect must keep the word: "doctor" is not ELIZA (its DOCTOR script).
                if not named and not same_stem(row[1], c):
                    continue
                # A name and the lowercase noun after it: "Soviet Union collapse" -> "Collapse of the
                # Soviet Union", "UK parliament" -> "Parliament of the UK"; ahead of the bare name.
                k = i + n
                if (k < len(toks) and not used[k] and toks[k].islower() and len(toks[k]) > 3
                        and toks[k] not in STOP and any(is_named(w, s) for w, s in zip(words, starts[i:i + n]))):
                    for form in (f"{toks[k]} of the {c}", f"{toks[k]} of {c}"):
                        about = pack.find_article(form)
                        if about and about[0] not in seen:
                            found.append(about[:4])
                            seen.add(about[0])
                            used[k] = True
                            break
                # "monsoon season in Thailand" -> Climate of Thailand, "get around Istanbul" -> Transport
                # in Istanbul: the main article only touches these; ahead of the bare name.
                if named:
                    for about in aspect_articles(pack, row[1], question):
                        if about[0] not in seen:
                            found.append(about[:4])
                            seen.add(about[0])
                found.append(row[:4])
                seen.add(row[0])
                named_hit = named_hit or named
                # Also a lone lowercase word naming something obscure ("whereabouts" -> an album).
                if common or (n == 1 and obscure and not named):
                    plain.add(row[0])
                for j in range(i, i + n):
                    used[j] = True
                break
    # "Which is further north: Rome or New York?" is about Rome and New York, not "North". Only
    # names push these out: "why do we have seasons" keeps "Season", and "is a snake bite
    # venomous" keeps "Snakebite" next to "Venom".
    if named_hit:
        found = [r for r in found if r[0] not in plain]
    return found


NUMBER_WORDS = set("one two three four five six seven eight nine ten eleven twelve hundred thousand million".split())

# Creative works: by the title's qualifier ("Big (film)") or what the intro says the article is
# ("Floating Ice is an album by ...", "Monsoon Season is the debut mixtape from ...").
WORK_TITLE = re.compile(r"\((?:[^()]* )?(album|song|single|film|movie|novel|band|EP|mixtape|TV series|series|"
                        r"video game|musical|opera|painting|poem|book|soundtrack)\)$", re.I)
WORK_KIND = re.compile(r"\b(is|was|are|were) (an?|the)\b[^.]{0,60}?\b(album|song|single|mixtape|EP|film|movie|novel|"
                       r"novella|television series|TV series|sitcom|soap opera|video game|band|musical|opera|painting|"
                       r"poem|short story|documentary|comic|manga|anime|magazine|podcast|soundtrack)s?\b")
# The question asks about works, so they stay ("which album has the song Floating Ice").
MEDIA_ASK = re.compile(r"\b(albums?|songs?|singles?|mixtapes?|films?|movies?|novels?|bands?|books?|shows?|series|"
                       r"episodes?|games?|sang|sing|singers?|lyrics|directed|director|starring|actors?|plot|wrote|"
                       r"written|authors?|paintings?|poems?|operas?|musicals?|soundtracks?|rapper|tracks?|records?)\b", re.I)


def same_stem(title: str, phrase: str) -> bool:
    """Evidence.sameStem: a word of each starts with the same three letters ("liters" / Litre, "cats" / Cat,
    "snake bite" / Snakebite), unlike "doctor" / ELIZA or "diesel" / Shaquille O'Neal."""
    tw = [w for w in re.findall(r"\w+", title.lower()) if len(w) >= 3]
    return any(t[:3] == p[:3] for p in re.findall(r"\w+", phrase.lower()) if len(p) >= 3 for t in tw)


def is_work(pack: "Pack", row, question: str) -> bool:
    """Evidence.isWork: an album, song, film... that the question doesn't ask about."""
    if MEDIA_ASK.search(question):
        return False
    if WORK_TITLE.search(row[1]):
        return True
    cache = pack.__dict__.setdefault("work_cache", {})
    if row[0] not in cache:
        first, n = row[2], row[3]
        cid = intro_chunk(pack, first, n)
        sent = SENT_RE.split(PADDING_RE.sub(" ", pack.text(cid)).strip(), 1)[0][:250]
        cache[row[0]] = bool(WORK_KIND.search(sent))
    return cache[row[0]]


# Topic sub-articles of a named place or thing: question words -> Wikipedia's title forms.
ASPECTS = [  # (question words, sub-article titles, section heading in the main article or None)
    (re.compile(r"\b(climates?|weather|rain\w*|monsoons?|seasons?|temperatures?|humid\w*|snow\w*|typhoons?|hurricanes?)\b", re.I),
     ("Climate of {}",), "climate"),
    (re.compile(r"\b(get(ting)? around|transport\w*|metro|subway|bus|buses|trams?|taxis?|ferr(y|ies)|public transit)\b", re.I),
     ("Transport in {}", "Public transport in {}", "Transportation in {}"), "transport"),
    (re.compile(r"\b(food|foods|eat|eating|dish(es)?|cuisine|meals?)\b", re.I), ("Cuisine of {}",), "cuisine"),
    (re.compile(r"\b(economy|economic|gdp|industr\w*)\b", re.I), ("Economy of {}",), "economy"),
    (re.compile(r"\b(history|historical|independen\w*)\b", re.I), ("History of {}",), None),
    (re.compile(r"\b(culture|customs?|etiquette|tipping|traditions?)\b", re.I), ("Culture of {}",), "culture"),
    (re.compile(r"\b(languages?|speak|spoken)\b", re.I), ("Languages of {}",), "language"),
    (re.compile(r"\b(religions?|religious)\b", re.I), ("Religion in {}",), "religion"),
    (re.compile(r"\b(population|demographics?|ethnic\w*)\b", re.I), ("Demographics of {}",), "demographic"),
    (re.compile(r"\b(tap water|drinking water|drink the water)\b", re.I), ("Water supply and sanitation in {}",), None),
]
ASPECT_TITLE = re.compile(r"^(climate|transport|public transport|transportation|cuisine|economy|history|culture|languages|"
                          r"religion|demographics|water supply and sanitation) (of|in) |.* cuisine$", re.I)


def aspect_articles(pack: "Pack", title: str, question: str) -> list:
    """ResearchPipeline.aspectArticles: [(id, title, first_chunk, n_chunks, prior)] for the topics the
    question asks about ("Climate of Thailand"). Redirects count when they keep the topic word
    ("Cuisine of Thailand" -> Thai cuisine)."""
    out = []
    for pat, forms, _ in ASPECTS:
        if not pat.search(question):
            continue
        for form in forms:
            row = pack.find_article(form.format(title))
            if row and form.split()[0].lower() in row[1].lower():
                out.append(row)
                break
    return out


def aspect_sections(pack: "Pack", article, question: str, titles) -> list[tuple[int, str]]:
    """ResearchPipeline.aspectSections: when no sub-article was found, the main article's own section
    for the topic ("Thailand — Geography > Climate"), first chunk only. titles: the articles already
    picked (a "Climate of X" makes the section redundant). Returns [(chunk_id, section)]."""
    out = []
    mine = [t.lower() for t in titles if article[1].lower() in t.lower()]  # "Climate of Seattle" is not Denver's
    for pat, forms, heading in ASPECTS:
        if heading is None or not pat.search(question) or any(forms[0].split()[0].lower() in t for t in mine):
            continue
        for row in pack.db.execute("SELECT id, section FROM chunk WHERE article = ? AND lower(section) LIKE ? "
                                   "ORDER BY id", (article[0], f"%{heading}%")):
            if heading in row[1].lower().rsplit(">", 1)[-1]:  # "Geography > Climate", not "Climate > Flora"
                out.append(row)
                break
    return out


def sentence_starts(question: str) -> list[bool]:
    """Per TOKEN_RE token: does it begin a sentence? There a capital doesn't make a name
    ("Could Napoleon have met Beethoven? Check their lifetimes" is not about the film "Check")."""
    out, prev_end, prev_tok = [], 0, ""
    for m in TOKEN_RE.finditer(question):
        gap = question[prev_end:m.start()]
        out.append(not out or any(c in gap for c in ".!?")
                   or (prev_tok.endswith(".") and prev_tok.count(".") == 1))  # "cats. Dogs", not "U.S. Army"
        prev_end, prev_tok = m.end(), m.group()
    return out


LIST_QUERY = re.compile(r"((?:[^\W\d_][\w-]*\s+){0,2}[^\W\d_][\w-]*s)\s+(in|of|near|around)\s+(?:the\s+)?([^\W\d_][\w'\- ]*)")
EXPLAIN_QUERY = re.compile(r"^\s*(why|how)\b|\bcaus", re.I)
PLACE_STOP = {"and", "or", "for", "with", "that", "which", "what", "to", "is", "are"}


def list_article(pack: "Pack", question: str):
    """ResearchPipeline.listArticle: "museums in Berlin" -> "List of museums in Berlin" (via aliases:
    "List of museums and galleries in Berlin"). Returns (id, title, first_chunk, n_chunks) or None."""
    if EXPLAIN_QUERY.search(question):
        return None
    for m in LIST_QUERY.finditer(question):
        nouns = [w for w in m.group(1).split() if w.lower() not in STOP]
        place = []
        for w in m.group(3).split():
            if w.lower() in PLACE_STOP:
                break
            place.append(w)
        place = place[:4]
        if not nouns or not place or not place[0][0].isupper():
            continue
        for n in range(len(nouns), 0, -1):
            what = " ".join(nouns[-n:]).lower()
            for k in range(len(place), 0, -1):
                where = " ".join(place[:k])
                for prep in ("in", "of"):
                    row = pack.find_article(f"List of {what} {prep} {where}")
                    if row:
                        return row[:4]
        # "must-see sights in Barcelona": Wikipedia's words are "tourist attractions" / "Tourism in X".
        if nouns[-1].lower().rstrip("s") in SIGHT_NOUNS:
            for k in range(len(place), 0, -1):
                where = " ".join(place[:k])
                for title in (f"List of tourist attractions in {where}", f"Tourism in {where}"):
                    row = pack.find_article(title)
                    if row and row[1].startswith(("List of", "Tourism")):
                        return row[:4]
    return None


SIGHT_NOUNS = {"sight", "attraction", "landmark", "monument"}  # ResearchPipeline.SIGHT_NOUNS
# Any level of the heading path: "Main sights", "Tourism > Main attractions > Old Town", "Heritage > Important monuments".
SIGHTS_SECTION = re.compile(r"(^|> )[^>]*\b(sights|attractions|landmarks|monuments|points of interest)\b", re.I)
SIGHTS_PROSE_CHARS = 600
SIGHTS_LINES = 8
SIGHTS_BULLET_CHUNKS = 3  # bullets further down are subsections (a square's history, the islands)


def is_sights_question(question: str) -> bool:
    """ResearchPipeline.isSightsQuestion: "must-see sights in Barcelona", "landmarks of Rome"."""
    words = [w.lower().rstrip("s") for w in re.findall(r"[^\W\d_]+", question)]
    return "must-see" in question.lower() or any(w in SIGHT_NOUNS for w in words)


def sights_evidence(pack: "Pack", article) -> tuple[int, str] | None:
    """ResearchPipeline.sightsEvidence: a city's sights sections as one source (first chunk, text):
    the start of their opening prose ("The best-known site of Florence is ... the Duomo") and the
    bullet lines of the first few chunks ("- Minor basilica of Sagrada Família, the symbol of
    Barcelona."). Compressing to the question's words kept "Barcelona is home to many World
    Heritage Sites:" and dropped the list after it."""
    chunks = [cid for cid, sec in pack.db.execute(
        "SELECT id, section FROM chunk WHERE article = ? ORDER BY id", (article[0],)) if SIGHTS_SECTION.search(sec)]
    if not chunks:
        return None
    prose = [l for l in pack.text(chunks[0]).split("\n") if l.strip() and not l.startswith(("- ", "|"))]
    intro = " ".join(prose)[:SIGHTS_PROSE_CHARS]
    if len(intro) == SIGHTS_PROSE_CHARS and ". " in intro:
        intro = intro[:intro.rindex(". ") + 1]  # whole sentences
    lines = [l[2:].strip()[:LIST_LINE_CHARS] for cid in chunks[:SIGHTS_BULLET_CHUNKS]
             for l in pack.text(cid).split("\n") if l.startswith("- ")][:SIGHTS_LINES]
    if not lines:
        return None  # prose-only sections (Lisbon: what the 1755 earthquake destroyed) led answers astray
    text = "\n".join(x for x in [intro] + lines if x)
    return (chunks[0], text) if text else None


# "Hikes near Interlaken", "day trips from Edinburgh", "things to see around Lake Garda": Wikipedia
# articles whose coordinates lie near the place (coord_rtree, tools/build_coords.py). FTS can't do
# this: the hikes are separate articles (Harder Kulm, Schynige Platte) that rarely name the town.
NEAR_RE = re.compile(r"\b(?:near|around|close to|surrounding|day trips? from|excursions? from)\s+(?:the\s+)?(.+)", re.I)
# The question must ask for things to find, not whether two places are near each other.
NEAR_ASK = re.compile(r"\b(what|which|where|any|best|good|some|things|places|worth|visit|see|do)\b", re.I)
DAY_TRIP = re.compile(r"\b(day trips?|excursions?)\b", re.I)
# "the best way to get around Istanbul" is about transport, not what's near Istanbul.
GET_AROUND = re.compile(r"\b(get|getting|go|going|travel\w*|mov\w*|driv\w*|cycl\w*|walk\w*)\s+around\b", re.I)
NAME_JOINERS = {"de", "del", "di", "du", "la", "le", "am", "of"}
NEARBY_KM, DAY_TRIP_KM = 20.0, 80.0
# A day trip leaves the city: Arthur's Seat (2 km) and Edinburgh Castle crowded out Stirling and
# St Andrews, and the model filled the list from memory with made-up distances.
DAY_TRIP_MIN_KM = 15.0
NEARBY_CANDIDATES = 60
NEARBY_SOURCES, DAY_TRIP_SOURCES = 4, 5
# Not something to visit: events, organisations, people, works. In the title ("Air India Flight 101",
# "Interlaken Ost railway station") or as what the intro says the article is ("... was a scheduled flight",
# "... is a national research university", "... is a district").
_NOT_A_SIGHT = (r"flight|crash|disaster|incident|attack|bombing|battle|siege|massacre|murder|shooting|earthquake|avalanche|"
                r"company|corporation|manufacturer|brand|university|college|school|airport|station|club|team|band|album|"
                r"song|film|novel|book|election|treaty|agreement|footballer|politician|player|singer|actor|person|"
                r"stadium|arena|casino|hotel|motorway|tunnel|championship|tournament|"
                r"assassination|death|funeral|riot|protest|explosion|region|prefecture|circuit|racetrack|speedway|"
                r"race|festival|constructor|roller coaster")
NEARBY_SKIP_TITLE = re.compile(rf"\b({_NOT_A_SIGHT})s?\b", re.I)
# Four words before the kind: "is an Italian Formula One racing team".
NEARBY_SKIP_KIND = re.compile(
    rf"\b(is|was|are|were) (an?|the)( [\w\-]+){{0,4}} ({_NOT_A_SIGHT}|district|county|province|region|canton|diocese|"
    rf"parish|constituency|municipality|ward|state|country|venue|highway|road|census-designated place)s?\b", re.I)
# Intros that open with the article's coordinates ("44°18′28″N 11°51′42″E / 44.30°N 11.86°E Scuderia Toro Rosso
# SpA is ..."): without them the first sentence says what the article is.
PARENS = re.compile(r"\s*\([^()]*\)")


def first_sentence(text: str) -> str:
    """ResearchPipeline.firstSentence: without parentheses, where "(Italian pronunciation: ...; lit. ...)"
    would end the sentence before "was an Italian Formula One racing team"."""
    return SENT_RE.split(PARENS.sub("", text), 1)[0][:300]


COORD_PREFIX = re.compile(r"^\s*\d{1,3}°[^A-Za-z]{0,60}[NS][^A-Za-z]{0,60}[EW](?:[^A-Za-z]{0,60}[NS][^A-Za-z]{0,60}[EW])?\W*")
NEARBY_GENERIC = set("""best good great nice top near around close surrounding worth visit visiting see seeing do doing
things thing places place trip trips day excursion excursions there any some go most can""".split())
# Things people go and see, in the title or the intro's first sentence: Stirling Castle and St Andrews
# ("a town") ahead of the Stone of Scone and Dundee on popularity alone.
SIGHT_KIND = re.compile(r"\b(castle|palace|abbey|cathedral|church|chapel|temple|shrine|monastery|loch|lake|national park|"
                        r"island|bridge|monument|museum|gardens?|ruins?|mountain|peak|hill|beach|waterfall|falls|valley|"
                        r"glen|gorge|fort|fortress|village|town|old town|world heritage)s?\b", re.I)
SIGHT_BONUS = 1.0
DAY_TRIP_SPREAD_KM = 5.0  # one pick per destination: Stirling or Stirling Castle, not both
COMPASS = ["north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"]


def compass(dlat: float, dlon_km: float) -> str:
    """ResearchPipeline.compass: "northwest" for a point 51 km away up and to the left."""
    deg = math.degrees(math.atan2(dlon_km, dlat)) % 360
    return COMPASS[int((deg + 22.5) // 45) % 8]


def stem(w: str) -> str:
    """hikes / hiking -> hik, lakes -> lak, temples -> templ: matched as substrings."""
    w = w.lower()
    for suf in ("ing", "es", "s"):
        if w.endswith(suf) and len(w) - len(suf) >= 3:
            w = w[:-len(suf)]
            break
    return w[:-1] if w.endswith("e") and len(w) > 3 else w


def nearby_location(pack: "Pack", question: str):
    """ResearchPipeline.nearbyLocation: (article row, lat, lon, km, phrase) for "... near <Place>" when
    the place has coordinates, else None. The longest capitalised phrase that is an article wins."""
    if not pack.has_rtree or not NEAR_ASK.search(question) or GET_AROUND.search(question):
        return None
    m = NEAR_RE.search(question)
    if not m:
        return None
    names = []
    for w in re.findall(r"[\w'’\-]+", m.group(1)):
        if not (w[:1].isupper() or (names and w.lower() in NAME_JOINERS)):
            break
        names.append(w)
    for n in range(len(names), 0, -1):
        phrase = " ".join(names[:n])
        row = pack.find_article(phrase)
        c = row and pack.db.execute("SELECT lat, lon FROM coord WHERE article = ?", (row[0],)).fetchone()
        if c:
            if DAY_TRIP.search(question):
                return row, c[0], c[1], DAY_TRIP_KM, phrase, DAY_TRIP_MIN_KM, DAY_TRIP_SOURCES
            return row, c[0], c[1], NEARBY_KM, phrase, 0.0, NEARBY_SOURCES
    return None


def intro_chunk(pack: "Pack", first_chunk: int, n_chunks: int) -> int:
    sec = pack.db.execute("SELECT section FROM chunk WHERE id = ?", (first_chunk,)).fetchone()[0]
    return first_chunk + 1 if sec == "Infobox" and n_chunks > 1 else first_chunk


def nearby_articles(pack: "Pack", question: str, loc) -> list[tuple]:
    """ResearchPipeline.nearbyArticles: the most viewed articles within the radius that are things to
    visit, ranked by popularity plus the question's topic words ("hikes", "lakes", "temples") in their
    title and intro, and kinds of sights. Returns [(chunk_id, article_id, title, km, intro_text, direction)]."""
    row, lat, lon, km, phrase, min_km, n_sources = loc
    dlat = km / 111.0
    coslat = max(0.1, math.cos(math.radians(lat)))
    dlon = km / (111.0 * coslat)
    cands = pack.db.execute(
        "SELECT a.id, a.title, a.first_chunk, a.n_chunks, a.prior, r.min_lat, r.min_lon FROM coord_rtree r "
        "JOIN article a ON a.id = r.id WHERE r.min_lat BETWEEN ? AND ? AND r.min_lon BETWEEN ? AND ? "
        "ORDER BY a.prior DESC LIMIT ?", (lat - dlat, lat + dlat, lon - dlon, lon + dlon, NEARBY_CANDIDATES)).fetchall()
    place_words = set(keywords(row[1])) | set(keywords(phrase))
    topic = [stem(w) for w in keywords(question) if w not in NEARBY_GENERIC and w not in place_words]
    scored = []
    for aid, title, first, n, prior, alat, alon in cands:
        if aid == row[0] or title.startswith("List of") or NEARBY_SKIP_TITLE.search(title):
            continue
        dist = math.hypot((alat - lat) * 111.0, (alon - lon) * 111.0 * coslat)
        if dist > km or dist < min_km:
            continue
        cid = intro_chunk(pack, first, n)
        text = COORD_PREFIX.sub("", PADDING_RE.sub(" ", pack.text(cid)))
        first_sent = first_sentence(text)
        if NEARBY_SKIP_KIND.search(first_sent):
            continue
        low_t, low = title.lower(), text.lower()
        hits = sum(2.0 if t in low_t else 1.0 if t in low else 0.0 for t in topic)
        sight = SIGHT_BONUS if SIGHT_KIND.search(f"{title} {first_sent}") else 0.0
        direction = compass((alat - lat) * 111.0, (alon - lon) * 111.0 * coslat)
        scored.append((prior + 2.0 * hits + sight, cid, aid, title, dist, text, direction, alat, alon))
    scored.sort(key=lambda s: -s[0])
    out = []
    for _, cid, aid, title, dist, text, direction, alat, alon in scored:
        if min_km > 0 and any(math.hypot((alat - o[0]) * 111.0, (alon - o[1]) * 111.0 * coslat) < DAY_TRIP_SPREAD_KM
                              for o in [x[-2:] for x in out]):
            continue
        out.append((cid, aid, title, dist, text, direction, alat, alon))
        if len(out) >= n_sources:
            break
    return [x[:6] for x in out]


LIST_SKIP_CELL = re.compile(r"^(:?-+:?|name|names|image|picture|photo|rank|no\.?|#)$", re.I)
CITATION_RE = re.compile(r"\[\s*\d+\s*\]")
HAS_LETTER = re.compile(r"[^\W\d_]")


def list_rows(text: str) -> list[tuple[str, list[str], bool]]:
    """Evidence.listRows: the entries of a list article chunk as (name, other cells, ranked).
    Markdown table rows ("| Pergamon Museum | Museum Island | ... |"; a leading rank column like
    "1" or "=4" is dropped and sets ranked) and bullet items ("- Café Procope – the oldest ...")."""
    out = []
    for line in text.split("\n"):
        line = CITATION_RE.sub("", line).strip()
        ranked = False
        if line.startswith("|"):
            cells = [x.strip() for x in line.strip("|").split("|")]
            cells = [x for x in cells if x]
            while cells and not HAS_LETTER.search(cells[0]):
                cells.pop(0)
                ranked = True
            if not cells or LIST_SKIP_CELL.match(cells[0]):
                continue
            name, rest = cells[0], cells[1:]
        elif line[:2] in ("- ", "* "):
            item = line[2:].strip()
            name = re.split(r"\s[–—-]\s|,|\(|:", item, maxsplit=1)[0].strip()
            rest = [item[len(name):].strip(" ,–—-:")]
        else:
            continue
        if 3 <= len(name) <= 80 and HAS_LETTER.search(name):
            out.append((name, [x for x in rest if x], ranked))
    return out


def list_entries(pack: "Pack", article, k: int = 8, max_chunks: int = 80, max_names: int = 200) -> list[str]:
    """ResearchPipeline.listEntries: the k most viewed entries of a list article that are articles
    themselves, as "Name: cell — cell" lines. "Must-see museums in Berlin" wants the Pergamon Museum,
    not the first rows alphabetically, and one 450-character excerpt of a padded table is one row.
    Ranked lists ("tallest buildings", a rank column) keep their order instead."""
    _, _, first, n = article
    found, names, ranked = {}, set(), 0
    for c in range(first, first + min(n, max_chunks)):
        for name, rest, is_ranked in list_rows(pack.text(c)):
            if name in names or len(names) >= max_names:
                continue
            names.add(name)
            row = pack.find_article(name)
            if row and row[0] not in found:
                line = name + (": " + " — ".join(rest) if rest else "")
                found[row[0]] = (row[4], len(found), line[:LIST_LINE_CHARS])
                ranked += is_ranked
    entries = list(found.values())
    if ranked * 2 >= len(entries):
        entries.sort(key=lambda e: e[1])  # list order
    else:
        entries.sort(key=lambda e: -e[0])  # most viewed first
    return [line for _, _, line in entries[:k]]


LIST_LINE_CHARS = 160


def named_articles(pack: "Pack", question: str, k: int = 3):
    """The list article first, then title matches, deduplicated (ResearchPipeline.retrieve)."""
    out, seen = [], set()
    for row in [list_article(pack, question)] + title_matches(pack, question):
        if row and row[0] not in seen:
            out.append(row)
            seen.add(row[0])
    # Topic sub-articles don't take a name's place: "climates of Seattle and Denver" keeps Denver.
    extra = sum(1 for r in out if ASPECT_TITLE.match(r[1]))
    return out[:min(k + extra, 5)]


QUALIFIER = re.compile(r"^(.*?)\s*\(([^()]*)\)\s*$")
NON_WORD = re.compile(r"[^\w]+")


def is_namesake(title: str, question: str) -> bool:
    """Evidence.isNamesake: "Rome, New York" (or "X (Rome, New York)") when the question names
    Rome and New York separately ("Rome or New York?"): a namesake town, not what was asked."""
    q = f" {NON_WORD.sub(' ', question.lower())} "
    m = QUALIFIER.match(title)
    for part in ([m.group(1), m.group(2)] if m else [title]):
        names = [NON_WORD.sub(" ", n.lower()).strip() for n in part.split(",")]
        if (len(names) == 2 and all(n and f" {n} " in q for n in names)
                and f" {names[0]} {names[1]} " not in q):
            return True
    return False


def compress(text: str, words: list[str], max_chars=500) -> str:
    """Keeps the sentences with the most question words, in original order."""
    text = PADDING_RE.sub(" ", text)  # markdown tables are padded with long runs of spaces
    sents = SENT_RE.split(text)
    if len(text) <= max_chars:
        return text
    scored = []
    for i, s in enumerate(sents):
        low = s.lower()
        hits = sum(1 for w in words if w in low)
        scored.append((hits + (0.5 if i == 0 else 0), i, s))
    keep, total = set(), 0
    for hits, i, s in sorted(scored, reverse=True):
        if total + len(s) > max_chars and keep:
            continue
        keep.add(i)
        total += len(s) + 1
    return " ".join(s for i, s in enumerate(sents) if i in keep)


def main():
    pack, question = Pack(sys.argv[1]), " ".join(sys.argv[2:])
    words = keywords(question)
    q = fts_query(words)
    t0 = time.time()
    hits = pack.search(q)
    ms = (time.time() - t0) * 1000
    print(f"query: {q}   ({ms:.0f} ms)\n")
    total = 0
    for score, cid, title, sec in hits:
        ev = compress(pack.text(cid), words)
        total += len(ev)
        print(f"[{score:5.2f}] {title} — {sec or '(lead)'}\n  {ev[:400]}\n")
    print(f"evidence: {total} chars ≈ {math.ceil(total / 4)} tokens")


if __name__ == "__main__":
    main()
