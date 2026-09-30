# Other bounty entries (as of 2026-09-30)

Notes from reading the three published entries' repos, to learn from their
approaches. **Ideas only — no code is copied** (plagiarism disqualifies).

## AndroidLM — github.com/Phineas1500/AndroidLM (Apache-2.0) — the strongest rival

- **Model:** Qwen3.6-35B-A3B UD-Q2_K_XL (12.3 GB), CPU only, on a Pixel 8 Pro (12 GB).
- **Engine:** fork of BigMoeOnEdge (Apache-2.0) over llama.cpp: routed experts are read
  from flash with O_DIRECT into a ~5 GB in-RAM LRU expert cache; dense weights pinned;
  5 threads pinned to big cores, high priority, persistent threadpool. Runs as a
  subprocess (no JNI).
- **Speed (Pixel 8 Pro):** decode 4–6 tok/s, prefill 24–30 tok/s; load ~28 s; ~7.9 GB RAM.
  "Answer first" ~50 s total; retrieval-first median ~98 s; cited "source check" ~103 s.
- **Knowledge:** FineWiki (2025-08) in SQLite, 21.3 GB: top 2M articles (pageviews) full,
  rest lead only; zstd blocks + contentless FTS5 BM25; redirects table; Wikivoyage 0.3 GB;
  places DB 2.9 GB (Overture/OSM/GeoNames, 21M POIs); small Ethereum/NIST pack.
- **Pipeline:** LLM plans up to 4 article titles → lead + best sections → k=6 passages
  (~4000 chars). Popularity router: popular topics answered from memory first, then a
  cited source check.
- **Self-eval:** 61 questions blind-graded vs Opus with web search: **64%** parity.
- **Weaknesses:** slow (1.5–2 min per cited answer); install needs a PC, adb and a 37 GB
  push (15–40 min); answer-first responses are uncited; tuned for one device; build not
  pinned; English-centric.

## Boar — github.com/rferrari/boar-app (MIT)

- React Native + llama.rn; default Qwen3-4B (compact: Qwen2.5-1.5B); bge-small embeddings.
- Knowledge: intros only (300 built in; optional 50k "Vital Articles" pack, 164 MB);
  hybrid FTS5 + cosine, relevance gate, drops a citation when its source lacks the words.
- Published on-phone benchmark (Dimensity 8300, 12 GB): Qwen2.5-1.5B 11 tok/s, first
  token 13.6 s; LFM2.5-8B-A1B 14.8 tok/s but thinking often ate the token budget;
  Qwen2.5-7B 2.7 tok/s with timeouts. Heat drops speed ~40%.
- Weak: intros only, no frontier comparison, voice input needs Google services.

## Field Atlas — github.com/0x94t3z/fieldatlas (declares Apache-2.0)

- Native Kotlin/Compose on llama.cpp's Android example (manual patch step); Qwen3.5-2B.
- No general encyclopedia: Wikivoyage places pack + a biology pack; Vosk speech, Tesseract OCR.
- Own benchmark: ~14% of an online model; answers take 2–3.5 min.

## What we take from this

- Grounded answers with citations every time, but fast: short evidence, cached prefix.
- Install without a PC (browser download + link in place), no INTERNET permission.
- Places / "near me" done properly (Vitalik's named weak spot).
- A published, blind, reproducible comparison against frontier + web (`docs/EVAL.md`).
- Works well on 8 GB phones, not only 12 GB.
