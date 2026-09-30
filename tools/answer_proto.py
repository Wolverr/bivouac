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

from retrieve_proto import Pack, compress, fts_query, keywords, title_matches

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
SAMPLING = dict(temperature=0.4, top_p=0.95, top_k=20, min_p=0.0, repeat_penalty=1.05, max_tokens=700)


def route(q: str) -> str:
    m = q.strip().lower()
    return "chat" if GREETING.match(m) or PERSONAL.search(m) or not keywords(m) else "lookup"


def retrieve(pack: Pack, q: str):
    """Mirrors ResearchPipeline.retrieve: named articles' leads first, then boosted BM25."""
    words = keywords(q)
    if not words:
        return []
    named = title_matches(pack, q)[:3]
    named_ids = {a[0] for a in named}
    cands = []
    for art_id, title, first_chunk, _ in named:
        sec = pack.db.execute("SELECT section FROM chunk WHERE id = ?", (first_chunk,)).fetchone()[0]
        cands.append((first_chunk, art_id, title, sec))
    for score, cid, title, sec in pack.search(fts_query(words), limit=MAX_SOURCES * 4, per_article=99,
                                              prior_weight=PRIOR_WEIGHT, boost_articles=named_ids, boost=NAMED_BOOST):
        art = pack.db.execute("SELECT article FROM chunk WHERE id = ?", (cid,)).fetchone()[0]
        cands.append((cid, art, title, sec))
    picked, per, seen = [], {}, set()
    for cid, art, title, sec in cands:
        if cid in seen or per.get(art, 0) >= PER_ARTICLE:
            continue
        seen.add(cid)
        per[art] = per.get(art, 0) + 1
        picked.append((cid, title, sec))
        if len(picked) >= MAX_SOURCES:
            break
    return [(i + 1, title, sec, compress(pack.text(cid), words, EVIDENCE_CHARS)) for i, (cid, title, sec) in enumerate(picked)]


def build_prompt(q: str, sources) -> str:
    if not sources:
        return q
    parts = ["Wikipedia excerpts:"]
    for n, title, sec, ev in sources:
        parts.append(f"[{n}] {title} — {sec}" if sec else f"[{n}] {title}")
        parts.append(ev)
        parts.append("")
    return "\n".join(parts) + "\nQuestion: " + q


def generate(port: int, user: str):
    body = json.dumps({
        "messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": user}],
        "chat_template_kwargs": {"enable_thinking": False},
        **SAMPLING,
    }).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{port}/v1/chat/completions", body, {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=900) as r:
        res = json.load(r)
    return res["choices"][0]["message"]["content"].strip(), res.get("usage", {})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", default="out/wiki.db")
    ap.add_argument("--name", required=True, help="label for the results file, e.g. qwen3.5-2b")
    ap.add_argument("--port", type=int, default=8088)
    ap.add_argument("--only", default="", help="comma-separated question ids")
    args = ap.parse_args()

    pack = Pack(args.db)
    qs = [json.loads(l) for l in open(ROOT / "eval/questions.jsonl")]
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
        sources = retrieve(pack, q["q"]) if r == "lookup" else []
        answer, usage = generate(args.port, build_prompt(q["q"], sources))
        rec = {"id": q["id"], "question": q["q"], "answer": answer, "route": r,
               "sources": [f"{t} — {s}" if s else t for _, t, s, _ in sources],
               "model": args.name, "device": "pc-harness", "total_ms": int((time.time() - t0) * 1000), "usage": usage}
        with open(out, "a") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        print(f"{q['id']:12s} {rec['total_ms'] / 1000:6.1f}s  {len(sources)} sources  {len(answer)} chars", flush=True)


if __name__ == "__main__":
    main()
