"""Blind pairwise grading of Bivouac answers against the frontier + web baseline.

  python grade.py packet  eval/results-pc-qwen3.5-9b.jsonl   -> eval/grading/<name>.packet.jsonl + .key.json
  (a grader fills eval/grading/<name>.scores.jsonl, see GRADER_INSTRUCTIONS)
  python grade.py score   eval/results-pc-qwen3.5-9b.jsonl   -> summary + eval/grading/<name>.summary.json

The packet shows each question with answers "A" and "B" in random order; the key
mapping A/B to system is kept in a separate file the grader never sees.
"""

import argparse
import json
import random
import statistics
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASELINE = ROOT / "eval/baseline_frontier_web.jsonl"
GRADING = ROOT / "eval/grading"

GRADER_INSTRUCTIONS = """You are grading answers from two assistants to the same questions, blind.
For each line of the packet, read the question and answers A and B. Score EACH answer 1-10 on:
- correctness: are the facts right? (verify with your own knowledge; penalize confident errors hard)
- completeness: does it cover what a knowledgeable person would expect?
- usefulness: would it actually help the person asking, on a phone?
Then give "overall" 1-10 for each answer. Ignore formatting, citation markers like [1] or [P2],
and length beyond whether it helps. Write one JSON line per question:
{"id": "...", "A": {"correctness": n, "completeness": n, "usefulness": n, "overall": n},
 "B": {...}, "note": "<one short sentence on the main difference>"}"""


def load(path: Path) -> dict:
    return {r["id"]: r for r in map(json.loads, open(path))}


def packet(results_path: Path):
    name = results_path.stem.replace("results-", "")
    ours, base = load(results_path), load(BASELINE)
    questions = {q["id"]: q for q in map(json.loads, open(ROOT / "eval/questions.jsonl"))}
    rng = random.Random(name)  # deterministic per results file
    GRADING.mkdir(parents=True, exist_ok=True)
    key, lines = {}, []
    for qid, q in questions.items():
        if qid not in ours or qid not in base:
            continue
        pair = [("bivouac", ours[qid]["answer"]), ("frontier", base[qid]["answer"])]
        rng.shuffle(pair)
        key[qid] = {"A": pair[0][0], "B": pair[1][0]}
        lines.append({"id": qid, "category": q.get("cat", ""), "question": q["q"], "A": pair[0][1], "B": pair[1][1]})
    (GRADING / f"{name}.packet.jsonl").write_text("".join(json.dumps(l, ensure_ascii=False) + "\n" for l in lines))
    (GRADING / f"{name}.key.json").write_text(json.dumps(key, indent=1))
    (GRADING / "GRADER_INSTRUCTIONS.txt").write_text(GRADER_INSTRUCTIONS + "\n")
    print(f"{len(lines)} questions -> {GRADING / (name + '.packet.jsonl')}")


def score(results_path: Path):
    name = results_path.stem.replace("results-", "")
    key = json.loads((GRADING / f"{name}.key.json").read_text())
    questions = {q["id"]: q for q in map(json.loads, open(ROOT / "eval/questions.jsonl"))}
    per_cat = defaultdict(lambda: {"bivouac": [], "frontier": []})
    rows = []
    for s in map(json.loads, open(GRADING / f"{name}.scores.jsonl")):
        k = key[s["id"]]
        b, f = s[[l for l in "AB" if k[l] == "bivouac"][0]], s[[l for l in "AB" if k[l] == "frontier"][0]]
        cat = questions[s["id"]].get("cat", "")
        per_cat[cat]["bivouac"].append(b["overall"])
        per_cat[cat]["frontier"].append(f["overall"])
        rows.append((s["id"], b["overall"], f["overall"], s.get("note", "")))
    tot_b = sum(r[1] for r in rows)
    tot_f = sum(r[2] for r in rows)
    summary = {
        "results": results_path.name,
        "questions": len(rows),
        "bivouac_mean": round(tot_b / len(rows), 2),
        "frontier_mean": round(tot_f / len(rows), 2),
        "parity_pct": round(100 * tot_b / tot_f, 1),
        "bivouac_wins_or_ties": sum(1 for r in rows if r[1] >= r[2]),
        "by_category": {c: {"bivouac": round(statistics.mean(v["bivouac"]), 2),
                            "frontier": round(statistics.mean(v["frontier"]), 2),
                            "parity_pct": round(100 * sum(v["bivouac"]) / sum(v["frontier"]), 1)}
                        for c, v in sorted(per_cat.items())},
    }
    (GRADING / f"{name}.summary.json").write_text(json.dumps(summary, indent=1))
    print(json.dumps(summary, indent=1))
    print("\nlargest gaps:")
    for qid, b, f, note in sorted(rows, key=lambda r: r[1] - r[2])[:8]:
        print(f"  {qid:12s} bivouac {b:>2} vs {f:>2}  {note}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["packet", "score"])
    ap.add_argument("results", type=Path)
    a = ap.parse_args()
    (packet if a.cmd == "packet" else score)(a.results)


if __name__ == "__main__":
    main()
