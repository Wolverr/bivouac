#!/usr/bin/env python3
"""Summarizes a phone run (tools/phone/new_phone.sh output folder, or any folder with
results-*.jsonl from the app eval) as markdown: per-question timing and per-model totals.

    python3 tools/phone/report.py eval/phone-Pixel_9-2026-10-20
"""
import json
import statistics
import sys
from pathlib import Path


def rate(n, ms):
    return n / ms * 1000 if ms else 0.0


def model_table(path: Path) -> str:
    rows = [json.loads(line) for line in path.open(encoding="utf-8") if line.strip()]
    out = [f"### {path.stem.removeprefix('results-')}", ""]
    if rows:
        out.append(f"Device: {rows[0].get('device', '?')} · model: {rows[0].get('model', '?')}")
        out.append("")
    out += ["| question | route | search s | prompt tok | read tok/s | answer tok | write tok/s | think tok | total s |",
            "|---|---|---|---|---|---|---|---|---|"]
    totals, reads, writes = [], [], []
    for r in rows:
        s = r.get("stats") or {}
        if r.get("error") or s.get("error"):
            out.append(f"| {r['id']} | error: {r.get('error') or s.get('error')} | | | | | | | |")
            continue
        rd = rate(s.get("n_prefill", 0), s.get("t_prefill_ms", 0))
        wr = rate(s.get("n_gen", 0), s.get("t_gen_ms", 0))
        tot = r.get("total_ms", 0) / 1000
        totals.append(tot)
        if s.get("n_prefill", 0) >= 100:
            reads.append(rd)
        writes.append(wr)
        out.append(f"| {r['id']} | {r.get('route', '')} | {(r.get('search_ms') or 0) / 1000:.1f} | {s.get('n_prefill', 0)} | "
                   f"{rd:.1f} | {s.get('n_gen', 0)} | {wr:.2f} | {s.get('n_think', 0)} | {tot:.0f} |")
    if totals:
        out += ["", f"**{len(totals)} answers: median {statistics.median(totals):.0f} s, mean {statistics.mean(totals):.0f} s, "
                    f"max {max(totals):.0f} s · reading {statistics.median(reads) if reads else 0:.1f} tok/s, "
                    f"writing {statistics.median(writes):.2f} tok/s (medians)**"]
    return "\n".join(out)


def main():
    d = Path(sys.argv[1])
    parts = [f"# Phone run: {d.name}", ""]
    for name, title in [("cores.txt", "Chip, clocks, memory"), ("mtp.txt", "MTP (9B, 200 tokens)")]:
        if (d / name).exists():
            parts += [f"## {title}", "", "```", (d / name).read_text(encoding="utf-8", errors="replace").strip(), "```", ""]
    for b in sorted(d.glob("bench-*.txt")):
        parts += [f"## llama-bench {b.stem.removeprefix('bench-')}", "",
                  b.read_text(encoding="utf-8", errors="replace").strip(), ""]
    for m in sorted(d.glob("meminfo-*.txt")):
        parts += [f"Memory after eval, {m.stem.removeprefix('meminfo-')}: "
                  + " / ".join(x.strip() for x in m.read_text(encoding="utf-8").splitlines() if x.strip()), ""]
    res = sorted(d.glob("results-*.jsonl"))
    if res:
        parts += ["## App: search + prompt reading + answer", ""]
        for r in res:
            parts += [model_table(r), ""]
    print("\n".join(parts))


if __name__ == "__main__":
    main()
