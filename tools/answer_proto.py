"""Desktop harness: the app's research pipeline on a PC, for comparing models on quality.

Same system prompt (app/src/main/assets/prompts/system.txt), same routing rules,
retrieval, evidence compression and prompt layout as research/*.kt, with
llama-server doing the generation. Speed numbers from here mean nothing for
phones; answer quality does, which is what model selection needs first.

Usage:
  ~/llama-pc/bin/llama-server -m model.gguf -c 8192 -t 20 --port 8088 --jinja &
  python answer_proto.py --db out/wiki.db --name qwen3.5-2b [--port 8088] [--only fact-01,explain-02]
Output: eval/results-pc-<name>.jsonl (resumable)
"""

import argparse
import json
import re
import time
import urllib.request
from pathlib import Path

from places_proto import Places, parse_intent
from retrieve_proto import (Pack, compress, fts_query, is_namesake, is_sights_question, keywords, list_entries,
                            named_articles, sights_evidence)

ROOT = Path(__file__).resolve().parent.parent
SYSTEM = (ROOT / "app/src/main/assets/prompts/system.txt").read_text().strip()

# research/Router.kt, kept in sync by hand (small and rarely changes).
GREETING = re.compile(r"^(hi|hello|hey|yo|hiya|thanks|thank you|thx|ok|okay|cool|nice|great|good (morning|evening|night)|bye|lol)\b[\s!.?]*$")
PERSONAL = re.compile(
    r"\b(i feel|i'm feeling|im feeling|i am feeling|i feel like|i'm (so )?(sad|stressed|anxious|lonely|bored|tired|upset|angry|scared|worried)|"
    r"im (so )?(sad|stressed|anxious|lonely|bored|tired|upset|angry|scared|worried)|feeling (bad|down|low|sad|lonely)|"
    r"my (girlfriend|boyfriend|wife|husband|partner|friend|mom|mum|dad|mother|father|boss|family)|"
    r"should i|do you think i|can you help me|help me (write|decide|with)|write (me|a)|rewrite|translate|"
    r"how are you|who are you|what are you)\b"
)

# ResearchPipeline.Settings defaults.
MAX_SOURCES, PER_ARTICLE, EVIDENCE_CHARS, PRIOR_WEIGHT, NAMED_BOOST = 6, 2, 450, 0.5, 4.0
INFOBOX_FACT_CHARS = 200
LIST_SECTION = "Most viewed entries"  # ResearchPipeline.LIST_SECTION
SIGHTS_SECTION_NAME = "Main sights"  # ResearchPipeline.SIGHTS_SECTION
SAMPLING = dict(temperature=0.4, top_p=0.95, top_k=20, min_p=0.0, repeat_penalty=1.05, max_tokens=700)
# Fixed seed: an unchanged prompt gives an unchanged answer, so a before/after comparison
# (grade.py pair) measures the change instead of sampling luck. Unseeded reruns of identical
# prompts moved the 4B's paired mean by -0.48 on their own.
SEED = 1234
THINK_MAX_TOKENS = 4000


def overlap(text: str, words) -> int:
    """Evidence.overlap: question words mentioned, matched by their first five letters."""
    low = text.lower()
    return sum(1 for w in words if (w[:5] if len(w) > 5 else w) in low)


def route(q: str) -> str:
    m = q.strip().lower()
    return "chat" if GREETING.match(m) or PERSONAL.search(m) or not keywords(m) else "lookup"


def retrieve(pack: Pack, q: str):
    """Mirrors ResearchPipeline.retrieve: named articles' leads first, then boosted BM25."""
    words = keywords(q)
    if not words:
        return []
    named = named_articles(pack, q)
    named_ids = {a[0] for a in named}
    cands, facts, seen, whole, expanded = [], {}, set(), {}, set()
    sights_q = is_sights_question(q)
    for art_id, title, first_chunk, n_chunks in named:
        if title.startswith("List of"):
            entries = list_entries(pack, (art_id, title, first_chunk, n_chunks))
            if entries:  # its most viewed entries, not its first rows
                cands.append((first_chunk, art_id, title, LIST_SECTION))
                whole[first_chunk] = "\n".join(entries)
                expanded.add(art_id)  # no further chunks of it: they'd repeat rows
                continue
        cid, sec, fact_line, infobox = lead(pack, title, first_chunk, n_chunks, words)
        cands.append((cid, art_id, title, sec))
        facts[cid] = fact_line
        if infobox is not None:
            seen.add(infobox)  # its useful lines are already in the lead
        sights = sights_evidence(pack, (art_id, title, first_chunk, n_chunks)) if sights_q else None
        if sights:  # a city's "Main sights" list, whole: its first chunk is the source
            cands.append((sights[0], art_id, title, SIGHTS_SECTION_NAME))
            whole[sights[0]] = sights[1]
    n_leads = len(cands)  # named articles' leads (and sights lists) always pass the relevance gate
    for score, cid, title, sec in pack.search(fts_query(words), limit=MAX_SOURCES * 4, per_article=99,
                                              prior_weight=PRIOR_WEIGHT, boost_articles=named_ids, boost=NAMED_BOOST):
        art = pack.db.execute("SELECT article FROM chunk WHERE id = ?", (cid,)).fetchone()[0]
        cands.append((cid, art, title, sec))
    needed = (len(words) + 1) // 2
    picked, per = [], {}
    for i, (cid, art, title, sec) in enumerate(cands):
        if cid in seen or per.get(art, 0) >= PER_ARTICLE or (art in expanded and cid not in whole):
            continue
        # Relevance gate (Evidence.overlap): named articles' leads always pass.
        if i >= n_leads and (overlap(f"{title} {sec} {pack.text(cid)}", words) < needed
                                or is_namesake(title, q)):
            continue
        seen.add(cid)
        per[art] = per.get(art, 0) + 1
        picked.append((cid, title, sec))
        if len(picked) >= MAX_SOURCES:
            break
    out = []
    for i, (cid, title, sec) in enumerate(picked):
        if cid in whole:
            out.append((i + 1, title, sec, whole[cid]))
            continue
        f = facts.get(cid, "")
        ev = compress(pack.text(cid), words, EVIDENCE_CHARS - len(f))
        out.append((i + 1, title, sec, f"{f}\n{ev}" if f else ev))
    return out


def infobox_facts(infobox: str, words, max_chars: int = INFOBOX_FACT_CHARS) -> str:
    """Evidence.infoboxFacts: the infobox lines that mention a question word. Stricter than overlap():
    words must match at a word start ("roughly" must not match "through")."""
    pats = [re.compile(r"\b" + re.escape(w[:5] if len(w) > 5 else w)) for w in words]
    out = []
    for line in infobox.splitlines():
        line = line.strip().lstrip("• ").strip()
        if not line or not any(p.search(line.lower()) for p in pats):
            continue
        if sum(len(l) + 2 for l in out) + len(line) > max_chars:
            break
        out.append(line)
    return "; ".join(out)


def lead(pack: Pack, title: str, first_chunk: int, n_chunks: int, words):
    """ResearchPipeline.lead: a named article's intro, led by its relevant infobox lines. The infobox
    chunk alone is cut before what matters (Napoleon's never reaches his birth and death dates).
    Infobox lines count when they mention a question word other than the article's own name.
    Returns (chunk_id, section, facts line or "", infobox_chunk_id or None)."""
    sec = pack.db.execute("SELECT section FROM chunk WHERE id = ?", (first_chunk,)).fetchone()[0]
    if sec != "Infobox" or n_chunks < 2:
        return first_chunk, sec, "", None
    intro_sec = pack.db.execute("SELECT section FROM chunk WHERE id = ?", (first_chunk + 1,)).fetchone()[0]
    if intro_sec == "Infobox":
        return first_chunk, sec, "", None
    own = set(keywords(title))
    facts = infobox_facts(pack.text(first_chunk), [w for w in words if w not in own])
    return first_chunk + 1, intro_sec, facts, first_chunk


# ResearchPipeline.PLACES_HEADER / placesHeader(): the diet sentence only for diet requests.
PLACES_HEADER = ("Places from the offline map (straight-line distances). Recommend the best 3 to 5 "
                 "from this list, nearest first, one short line each, using the labels like [P1] and saying how far "
                 "each is; never invent places that aren't listed.")
DIET_NOTE = ("The list may be incomplete, so you may add which kinds of cuisine usually suit this diet, "
             "without naming places that aren't listed.")
PROMPT_PLACES = 8  # ResearchPipeline.PROMPT_PLACES: the model reads the nearest few, the screen shows all
DIET_WORDS = {"vegetarian", "vegan", "halal", "kosher", "gluten", "gluten-free"}  # PlaceIntent.DIET_WORDS


def format_distance(m: float) -> str:
    return f"{int(m / 10) * 10} m" if m < 1000 else f"{m / 1000:.1f} km"


def find_places(places: Places, q: str):
    """ResearchPipeline.findPlaces for questions with a written location (no GPS on the desktop).
    Returns (intent, places) or None when the question isn't about places."""
    intent = parse_intent(q, places.place_nouns)
    if intent is None:
        return None
    if intent["location"] is None:
        return intent, []
    center = places.geocode(intent["location"])
    if not center:
        return intent, []
    found, _ = places.nearby(center[0], center[1], places.categories_for(intent))
    return intent, found


def build_places_prompt(q: str, intent, found) -> str:
    if not found:
        return q
    diet = any(w in DIET_WORDS for w in intent["modifiers"] + intent["noun"])
    lines = [PLACES_HEADER + (" " + DIET_NOTE if diet else "")]
    for i, p in enumerate(found[:PROMPT_PLACES]):
        name = p["name"] + (f" ({p['name_en']})" if p["name_en"] else "")
        line = f"[P{i + 1}] {name} — {p['cat'].replace('_', ' ')} — {format_distance(p['dist'])}"
        where = ", ".join(x for x in (p["street"], p["locality"]) if x)
        if where:
            line += f" — {where}"
        if p["phone"]:
            line += f" — {p['phone']}"
        lines.append(line)
    return "\n".join(lines) + "\n\nQuestion: " + q


def build_prompt(q: str, sources) -> str:
    if not sources:
        return q
    parts = ["Wikipedia excerpts:"]
    for n, title, sec, ev in sources:
        parts.append(f"[{n}] {title} — {sec}" if sec else f"[{n}] {title}")
        parts.append(ev)
        parts.append("")
    return "\n".join(parts) + "\nQuestion: " + q


def catalog_prompt(model_id: str) -> str:
    """The model's system prompt from assets/catalog.json ("prompt"), as AppViewModel.promptFor; "" = default."""
    items = json.loads((ROOT / "app/src/main/assets/catalog.json").read_text())["items"]
    name = next((i.get("prompt", "") for i in items if i["id"] == model_id), "")
    return (ROOT / "app/src/main/assets/prompts" / name).read_text().strip() if name else ""


def generate(port: int, user: str, think: bool = False, system: str = ""):
    """think: reasoning on (the proposed "Think harder" button). The budget covers reasoning
    plus answer; usage.completion_tokens tells how long that would take on a phone."""
    body = json.dumps({
        "messages": [{"role": "system", "content": system or SYSTEM}, {"role": "user", "content": user}],
        "chat_template_kwargs": {"enable_thinking": think},
        **SAMPLING, "seed": SEED,
        **({"max_tokens": THINK_MAX_TOKENS} if think else {}),
    }).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{port}/v1/chat/completions", body, {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=1800) as r:
        res = json.load(r)
    choice = res["choices"][0]
    usage = dict(res.get("usage", {}), finish_reason=choice.get("finish_reason"))
    if "timings" in res:  # llama-server: speeds and, with speculative decoding, draft_n / draft_n_accepted
        usage["timings"] = res["timings"]
    text = choice["message"]["content"] or ""
    # Like the app (research/ThinkFilter.kt): reasoning blocks and stray special tokens are not the answer.
    text = re.sub(r"<think>.*?</think>", "", text, flags=re.S)
    text = re.sub(r"<\|[a-z_]+\|>", "", text)
    return text.strip(), usage


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", default="out/wiki.db")
    ap.add_argument("--name", required=True, help="label for the results file, e.g. qwen3.5-2b")
    ap.add_argument("--port", type=int, default=8088)
    ap.add_argument("--only", default="", help="comma-separated question ids")
    ap.add_argument("--questions", default=str(ROOT / "eval/questions.jsonl"), help="question set (eval/questions-holdout.jsonl)")
    ap.add_argument("--places", default="out/places.db", help="places pack ('' to skip, like an app without one)")
    ap.add_argument("--think", action="store_true", help="reasoning on (measures a 'Think harder' mode)")
    ap.add_argument("--system", default="", help="system prompt file to try instead of the app's")
    ap.add_argument("--model-id", default="", help="catalog id (qwen3.5-9b): use that model's prompt, like the app")
    ap.add_argument("--max-sources", type=int, default=0, help="sources per answer (app: Settings.maxSources, 6)")
    ap.add_argument("--evidence-chars", type=int, default=0, help="characters per source (app: Settings.evidenceChars, 450)")
    args = ap.parse_args()
    global MAX_SOURCES, EVIDENCE_CHARS  # prompt-length experiments; the app reads them from Settings
    MAX_SOURCES = args.max_sources or MAX_SOURCES
    EVIDENCE_CHARS = args.evidence_chars or EVIDENCE_CHARS
    system = Path(args.system).read_text().strip() if args.system else catalog_prompt(args.model_id)

    pack = Pack(args.db)
    places = Places(args.places) if args.places else None
    if places:
        places.wiki = pack  # geocoding by Wikipedia title / aliases, as in the app
    qs = [json.loads(l) for l in open(args.questions)]
    if args.only:
        keep = set(args.only.split(","))
        qs = [q for q in qs if q["id"] in keep]
    out = ROOT / f"eval/results-pc-{args.name}.jsonl"
    done = {json.loads(l)["id"] for l in open(out)} if out.exists() else set()
    for q in qs:
        if q["id"] in done:
            continue
        t0 = time.time()
        r = route(q["q"])
        hit = find_places(places, q["q"]) if places else None
        found = None
        if hit is not None:
            intent, found = hit
            r, sources = "places", []
            prompt = build_places_prompt(q["q"], intent, found)
        else:
            sources = retrieve(pack, q["q"]) if r == "lookup" else []
            prompt = build_prompt(q["q"], sources)
        answer, usage = generate(args.port, prompt, args.think, system)
        rec = {"id": q["id"], "question": q["q"], "answer": answer, "route": r,
               "sources": [f"{t} — {s}" if s else t for _, t, s, _ in sources] +
                          [f"[P{i + 1}] {p['name']} ({p['cat']}, {format_distance(p['dist'])})" for i, p in enumerate(found or [])],
               "model": args.name, "device": "pc-harness", "total_ms": int((time.time() - t0) * 1000), "usage": usage}
        with open(out, "a") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        print(f"{q['id']:12s} {rec['total_ms'] / 1000:6.1f}s  {len(sources)} sources  {len(answer)} chars", flush=True)


if __name__ == "__main__":
    main()
