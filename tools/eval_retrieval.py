"""Measures retrieval quality on the eval questions that have gold articles.

hit@k: at least one gold article is among the top-k sources the model would see.
Compares plain BM25 against BM25 + title matching.

Usage: python eval_retrieval.py out/wiki.db [--k 6] [--show-misses]
"""

import argparse
import json
import time
from pathlib import Path

from retrieve_proto import Pack, fts_query, keywords, named_articles


def retrieve(pack: Pack, question: str, k: int, use_titles: bool):
    words = keywords(question)
    hits = []
    matched = named_articles(pack, question, k=99) if use_titles else []
    matched_ids = {a[0] for a in matched}
    # Title-matched articles contribute their lead first: that's where definitions live.
    for art_id, title, first_chunk, n_chunks in matched[:3]:
        hits.append((100.0, first_chunk, title, "lead"))
    bm25 = pack.search(fts_query(words), limit=k * 3, boost_articles=matched_ids) if words else []
    seen = {h[1] for h in hits}
    for h in bm25:
        if h[1] not in seen:
            hits.append(h)
            seen.add(h[1])
    return hits[:k]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("db")
    ap.add_argument("--k", type=int, default=6)
    ap.add_argument("--show-misses", action="store_true")
    ap.add_argument("--questions", default=str(Path(__file__).parent.parent / "eval" / "questions.jsonl"))
    args = ap.parse_args()

    pack = Pack(args.db)
    qs = [json.loads(l) for l in open(args.questions)]
    qs = [q for q in qs if q.get("gold")]
    for use_titles in (False, True):
        hit, ms, misses = 0, 0.0, []
        for q in qs:
            t0 = time.time()
            got = retrieve(pack, q["q"], args.k, use_titles)
            ms += (time.time() - t0) * 1000
            titles = {h[2] for h in got}
            # Wikipedia titles start with a capital: gold "mRNA vaccine" is the article "MRNA vaccine".
            gold = {g[:1].upper() + g[1:] for g in q["gold"]}
            if titles & gold:
                hit += 1
            else:
                misses.append((q["id"], q["gold"], sorted(titles)[:4]))
        name = "bm25 + titles" if use_titles else "bm25 only"
        print(f"{name:14s} hit@{args.k}: {hit}/{len(qs)} ({100 * hit / len(qs):.0f}%)  avg {ms / len(qs):.0f} ms")
        if args.show_misses:
            for m in misses:
                print(f"   miss {m[0]}: want {m[1]}, got {m[2]}")


if __name__ == "__main__":
    main()
