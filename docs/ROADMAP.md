# Bivouac roadmap

Offline research assistant for Android, built for
[poidh bounty #31](https://poidh.xyz/mainnet/bounty/31) (decision after 2026-10-31).

Requirements: Android/GrapheneOS, ≤12 GB RAM, ≤50 GB total storage, fully offline
after install, no Google Play Services, public repo, reproducible, demo on X/Farcaster.

## Where we want to beat existing entries

- **Install in minutes, no PC:** import or download packs inside the app. The strongest
  rival needs a 37 GB `adb push`.
- **Fast, grounded answers:** keep the fixed prompt prefix cached, keep evidence
  short (sentence-level), and cite every answer as it's written.
- **Places / "near me":** OSM places with diet/cuisine tags (the weak spot Vitalik named).
- **Honest evaluation:** blind-graded comparison against a frontier model with web
  search, with the questions and answers published.

## Resume here (handoff, 2026-09-30 evening)

Dev machine: home Windows PC, WSL2 Ubuntu-24.04, repo /home/nicolas/bivouac (Claude runs on
the Windows side and reaches WSL with `wsl -e bash -lc ...`; editing through
\\\\wsl.localhost drops the exec bit on .sh files, so re-run `chmod +x tools/*.sh`).

Running as systemd user services (check `tools/status.sh`):
- bivouac-wiki: FineWiki download -> pageviews -> build out/wiki.db (full pack)
- bivouac-places: Overture download -> build out/places.db
Done: 7 candidate models in ~/models; llama-server for the PC at ~/llama-pc/bin.
Eval: eval/questions.jsonl (60, 48 with gold articles), eval/baseline_frontier_web.jsonl
(frontier + web answers, done), tools/answer_proto.py (PC harness), tools/grade.py (blind).

Next steps, in order:
1. When out/wiki.db exists: `tools/.venv/bin/python tools/eval_retrieval.py out/wiki.db --show-misses`,
   fix misses (e.g. weight rare terms in the relevance gate), re-measure.
2. Model quality per RAM tier: for each model in ~/models start
   `~/llama-pc/bin/llama-server -m <gguf> -c 8192 -t 16 --port 8088 --jinja`, run
   `tools/answer_proto.py --db out/wiki.db --name <model>`, then `tools/grade.py packet ...`,
   have a grader agent fill eval/grading/<name>.scores.jsonl (GRADER_INSTRUCTIONS.txt), `grade.py score`.
3. places.db: test place questions; then Redmi (8 GB reference phone) end-to-end + on-phone eval.
4. Low-RAM work: auto repack by free RAM, context sizing, OOM crash marker (8 GB = main tier).
5. Host packs (HuggingFace), fill app/src/main/assets/catalog.json URLs; README; M8.
No 12 GB phone available yet (options in chat: borrow any 12 GB phone, cloud devices, used Pixel 8 Pro).
See docs/COMPETITORS.md for what the other entries do.

## Milestones

- [x] M0: toolchain (JDK 17 via mise, Android SDK/NDK r29, CMake 3.31.6), llama.cpp v0.5.0 submodule
- [x] M1: app runs a GGUF on the phone: import, load, streaming answer, prefill progress, benchmark
- [ ] M2: Wikipedia pack: `tools/build_corpus.py` → `wiki.db` (FTS5 + zstd blocks); measure size
- [~] M3: on-device retrieval (SQLite + zstd in native code), cited answers, sources view
  (code done and building; needs a real pack + device test)
- [ ] M4: model selection **per RAM tier** (6 / 8 / 12 GB): quality ranked on the PC harness,
  speed on phones. 8 GB is the main target (most phones; the Redmi is the 8 GB reference
  device); 12 GB is the ceiling, not the requirement. Auto repack/context sizing by free RAM.
- [~] M5: install UX: catalog + browser download + link-in-place (done); host packs, fill catalog URLs (todo)
- [~] M6: places (Overture Maps) + "near me" queries: app side done; places.db build pending download
- [ ] M7: eval harness vs frontier + web, published results
- [ ] M8: README, demo video, public repo, poidh claim

## Status log

- 2026-09-30: toolchain installed; app skeleton (Compose UI + JNI engine with
  prefix snapshot caching and a big-core threadpool) written; first build in progress.
  FineWiki download started (`tools/fetch_finewiki.py`, text columns only, ~13 GB).

- 2026-09-30: APK builds (69 MB debug; 7 ggml CPU variants armv8.0 → armv9.2).
  Corpus reader verified on desktop (`tools/native/corpus_cli`, 20–75 ms/query on a 50k-article sample).
  FTS5 index size vs raw text on the sample: detail=full 54%, column 32%, none 15%.
  Projected full English pack: ~19 GB (none) / ~25 GB (column) / ~33 GB (full).
  Plan: detail=column (keeps title/section weighting) unless storage gets tight.

- 2026-09-30: research pipeline in app (search → sentence-compressed numbered sources →
  cited answer; Library tab for models + packs). Builder parallelized (~10 s / 10k articles
  → ~2 h for all of English Wikipedia on the 4-core PC). Pageviews (2025-08) streaming from
  Wikimedia into tools/data/pageviews_en.tsv; FineWiki shards downloading (2/15 done).
  NEXT: device test of the APK with Qwen3.5-2B; when downloads finish, build full pack:
  `python build_corpus.py --pageviews data/pageviews_en.tsv --full-top 2000000 --out out/wiki.db`

- 2026-09-30: **first on-device run** — Redmi Note 15 Pro (MediaTek MT6789, 2×A76 @2.2 GHz +
  6×A55, 8 GB RAM, Android 16). ggml picked the armv8.2_2 variant (dotprod+fp16).
  Qwen3.5-2B Q4_K_M: load ~6 s, bench pp256 25.5 tok/s, tg 6.8 tok/s (2 threads);
  real question: 76-token prompt 22.5 tok/s, answer 7.6 tok/s, first token 3.4 s.
  App PSS 2.7 GB = 1.24 GB mmapped weights + 1.39 GB native heap (CPU repack copy of
  weights — test use_extra_bufts=false for big models).
  TODO: markdown rendering, thread-count setting (try using little cores for prefill),
  repack toggle, quieter llama.cpp logs, log bench/completion stats to logcat.
  Xiaomi notes: needs "Install via USB" in developer options; `adb shell input` blocked.

- 2026-09-30: thread/repack experiment, Qwen3.5-2B Q4_K_M on the Redmi (pp256 / tg64 tok/s, app PSS):
  2 big cores + repack 25.5 / 6.8, 2.7 GB · 8 cores + repack **34.0** / 6.7 · 2 big cores, no repack
  12.4 / 7.2, **1.6 GB**. Repack ≈ 2x prefill for +model-size RAM; little cores help prefill (+33%)
  but not decode (bandwidth-bound). NEXT: separate batch threadpool (all cores) vs decode
  threadpool (big cores); auto repack when RAM allows; Reload button in Library.

- 2026-09-30: router + conversation memory + chat UI; title matching in retrieval; fixed an
  FTS phrase-query crash (detail=column). Places (M6): Overture 2026-09-23.1, confidence ≥ 0.7,
  traveler categories, R*Tree pack; geocoding via Wikipedia infobox coordinates → locality
  centroids; GPS via LocationManager. Install (M5): link-in-place via /proc/self/fd, browser
  downloads, no INTERNET permission. Background jobs: pageviews → full wiki pack build
  (tools/build_full.log), places fetch (tools/places_fetch.log), then `python build_places.py`.
  Jobs must be started with `setsid nohup … &` — plain nohup died when the terminal closed.

## Dev notes

- Build: `export JAVA_HOME=$(mise where java@temurin-17.0.20+8); ./gradlew assembleDebug`
- Test phone: Redmi Note 15 Pro (mid-range MediaTek). Final numbers come from a borrowed Pixel.
- Don't copy code from other entries; ideas only (plagiarism disqualifies).
- Long jobs: run as memory-capped systemd user services, not from a terminal. On this 8 GB
  PC systemd-oomd kills the whole terminal scope (and anything started from it, setsid or
  not) when RAM+swap pass 90%. Example:
  `systemd-run --user --unit=bivouac-wiki --working-directory=$PWD/tools -p MemoryMax=3G -p MemorySwapMax=1G --collect bash -c '…'`
  Check with `tools/status.sh`; stop with `systemctl --user stop bivouac-wiki`.
