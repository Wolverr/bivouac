"""Blind pairwise grading of Bivouac answers against the frontier + web baseline.

  python grade.py packet  eval/results-pc-qwen3.5-9b.jsonl   -> eval/grading/<name>.packet.jsonl + .key.json
  (a grader fills eval/grading/<name>.scores.jsonl, see GRADER_INSTRUCTIONS)
  python grade.py score   eval/results-pc-qwen3.5-9b.jsonl   -> summary + eval/grading/<name>.summary.json

Paired mode compares two runs of our own pipeline (e.g. before/after a fix) in one blind
packet, which cancels most of the grader's run-to-run drift:

  python grade.py pair       eval/results-pc-qwen3.5-4b.jsonl eval/results-pc-qwen3.5-4b-v2.jsonl
  python grade.py pairscore  eval/results-pc-qwen3.5-4b.jsonl eval/results-pc-qwen3.5-4b-v2.jsonl

The packet shows each question with answers "A" and "B" in random order; the key
mapping A/B to system is kept in a separate file the grader never sees.
"""

import argparse
import json
import math
import random
import statistics
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# Main set and the held-out set (ids "h-..."); results files hold one or the other. Extra sets without a
# baseline (eval/questions-sights.jsonl, ids "s-...") are for paired old-vs-new comparisons only.
QUESTION_FILES = [ROOT / "eval/questions.jsonl", ROOT / "eval/questions-holdout.jsonl"] + sorted(
    p for p in (ROOT / "eval").glob("questions-*.jsonl") if p.name != "questions-holdout.jsonl")
BASELINE_FILES = [ROOT / "eval/baseline_frontier_web.jsonl", ROOT / "eval/baseline_holdout_frontier_web.jsonl"]
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


def load_all(paths) -> dict:
    out = {}
    for p in paths:
        if p.exists():
            out.update(load(p))
    return out


def packet(results_path: Path):
    name = results_path.stem.replace("results-", "")
    ours, base = load(results_path), load_all(BASELINE_FILES)
    questions = load_all(QUESTION_FILES)
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
    questions = load_all(QUESTION_FILES)
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


def pair_name(old: Path, new: Path) -> str:
    return f"pair-{old.stem.replace('results-', '')}--{new.stem.replace('results-', '')}"


def pair_packet(old_path: Path, new_path: Path):
    name = pair_name(old_path, new_path)
    old, new = load(old_path), load(new_path)
    questions = load_all(QUESTION_FILES)
    rng = random.Random(name)
    GRADING.mkdir(parents=True, exist_ok=True)
    key, lines = {}, []
    for qid, q in questions.items():
        if qid not in old or qid not in new:
            continue
        if old[qid]["answer"].strip() == new[qid]["answer"].strip():
            key[qid] = "same"  # counted as a tie, not shown to the grader
            continue
        pair = [("old", old[qid]["answer"]), ("new", new[qid]["answer"])]
        rng.shuffle(pair)
        key[qid] = {"A": pair[0][0], "B": pair[1][0]}
        lines.append({"id": qid, "category": q.get("cat", ""), "question": q["q"], "A": pair[0][1], "B": pair[1][1]})
    (GRADING / f"{name}.packet.jsonl").write_text("".join(json.dumps(l, ensure_ascii=False) + "\n" for l in lines))
    (GRADING / f"{name}.key.json").write_text(json.dumps(key, indent=1))
    (GRADING / "GRADER_INSTRUCTIONS.txt").write_text(GRADER_INSTRUCTIONS + "\n")
    same = sum(1 for v in key.values() if v == "same")
    print(f"{len(lines)} questions ({same} identical answers skipped) -> {GRADING / (name + '.packet.jsonl')}")


def sign_test_p(wins: int, losses: int) -> float:
    """Two-sided exact binomial test, ties dropped."""
    n = wins + losses
    if n == 0:
        return 1.0
    k = min(wins, losses)
    tail = sum(math.comb(n, i) for i in range(k + 1)) / 2 ** n
    return min(1.0, 2 * tail)


def pair_score(old_path: Path, new_path: Path):
    name = pair_name(old_path, new_path)
    key = json.loads((GRADING / f"{name}.key.json").read_text())
    questions = load_all(QUESTION_FILES)
    scores = {s["id"]: s for s in map(json.loads, open(GRADING / f"{name}.scores.jsonl"))}
    rows = []  # (id, cat, old, new, note)
    for qid, k in key.items():
        cat = questions[qid].get("cat", "")
        if k == "same":
            rows.append((qid, cat, None, None, "identical answers"))
            continue
        s = scores[qid]
        o, n = s[[l for l in "AB" if k[l] == "old"][0]], s[[l for l in "AB" if k[l] == "new"][0]]
        rows.append((qid, cat, o["overall"], n["overall"], s.get("note", "")))
    diffs = [0 if r[2] is None else r[3] - r[2] for r in rows]
    mean = statistics.mean(diffs)
    se = statistics.stdev(diffs) / math.sqrt(len(diffs)) if len(diffs) > 1 else 0.0
    wins, losses = sum(d > 0 for d in diffs), sum(d < 0 for d in diffs)
    per_cat = defaultdict(list)
    for r, d in zip(rows, diffs):
        per_cat[r[1]].append(d)
    graded = [r for r in rows if r[2] is not None]
    summary = {
        "old": old_path.name,
        "new": new_path.name,
        "questions": len(rows),
        "identical": len(rows) - len(graded),
        "old_mean": round(statistics.mean(r[2] for r in graded), 2) if graded else None,
        "new_mean": round(statistics.mean(r[3] for r in graded), 2) if graded else None,
        "mean_diff": round(mean, 2),
        "ci95": [round(mean - 1.96 * se, 2), round(mean + 1.96 * se, 2)],
        "new_better": wins,
        "old_better": losses,
        "ties": len(diffs) - wins - losses,
        "sign_test_p": round(sign_test_p(wins, losses), 3),
        "by_category": {c: round(statistics.mean(v), 2) for c, v in sorted(per_cat.items())},
    }
    (GRADING / f"{name}.summary.json").write_text(json.dumps(summary, indent=1))
    print(json.dumps(summary, indent=1))
    print("\nbiggest changes:")
    for qid, _, o, n, note in sorted(graded, key=lambda r: abs(r[3] - r[2]), reverse=True)[:10]:
        print(f"  {qid:12s} old {o:>2} -> new {n:>2}  {note}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["packet", "score", "pair", "pairscore"])
    ap.add_argument("results", type=Path)
    ap.add_argument("new", type=Path, nargs="?", help="second results file (pair modes)")
    a = ap.parse_args()
    if a.cmd in ("pair", "pairscore"):
        if not a.new:
            ap.error(f"{a.cmd} needs two results files: old new")
        (pair_packet if a.cmd == "pair" else pair_score)(a.results, a.new)
    else:
        (packet if a.cmd == "packet" else score)(a.results)


if __name__ == "__main__":
    main()
