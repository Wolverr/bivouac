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

## Resume here (handoff, 2026-10-01 night session, interim ~02:15)

Start a new session with: "Continue the Bivouac project. Read docs/ROADMAP.md, Resume here."

Dev machine: home Windows PC, WSL2 Ubuntu-24.04, repo /home/nicolas/bivouac (from the
Windows side, reach WSL with `wsl -e bash -lc ...`; editing through
\\\\wsl.localhost drops the exec bit on .sh files, so re-run `chmod +x tools/*.sh`).
Build: `export JAVA_HOME=$HOME/.local/opt/jdk-17.0.20+8; ./gradlew testDebugUnitTest assembleDebug`,
then `./gradlew --stop` — the Gradle + Kotlin daemons hold ~2 GB, enough to make a running 9B eval thrash.
Commits: no AI attribution lines, ever (user rule). Commit messages with quotes or apostrophes:
write them to a file and use `git commit -F file` (heredocs through `wsl -e bash -lc` break).
Keep an Ubuntu terminal open when leaving jobs running overnight (WSL can stop the VM otherwise).

Data (built): tools/out/wiki.db (6.49 M articles, 18.3 GB, 2.28 M aliases, 40 k acronyms) and
tools/out/places.db (30.0 M places, 5.3 GB). Desktop tools need `tools/native/build/fts_rank.so`:
`export PATH=$HOME/Android/Sdk/cmake/3.31.6/bin:$PATH; cmake -S tools/native -B tools/native/build -G Ninja && ninja -C tools/native/build`.
Models in ~/models; llama-server for the PC at ~/llama-pc/bin. **Run the 9B with `--no-repack`**
(`SERVER_ARGS=--no-repack`): with repacking it needs ~12 GB and thrashes under the 12 GB service cap
(answers went from 28 s to 340 s).

### Eval method (changed tonight)

- `grade.py pair old.jsonl new.jsonl` / `pairscore`: old vs new answers in one blind packet, with mean
  difference, 95 % CI and sign test. First use showed that **unseeded reruns of identical prompts move the
  4B's paired mean by −0.48** (CI −0.89..−0.08), bigger than most pipeline effects. The harness now sends
  a fixed seed (`answer_proto.py` SEED), so unchanged prompts give unchanged answers and `pair` skips them.
  Single unseeded runs (the table below) are ±3 points; don't read v1-vs-v2 differences from them.
- **Held-out set**: eval/questions-holdout.jsonl (40 questions, written blind, never used for tuning) +
  eval/baseline_holdout_frontier_web.jsonl (frontier + live web search). Run with
  `ANSWER_ARGS="--questions ../eval/questions-holdout.jsonl" SUFFIX=-holdout`; grade.py reads both sets.
  Use it to check that a fix generalizes before trusting main-set gains — it caught two regressions tonight.
- Retrieval hit@6 (`tools/eval_retrieval.py`, `--questions` for the held-out set):
  main 41/48 -> **44/48**, held-out 33/36 -> **34/36** (old code 5077db9 vs d29b13e).

### Retrieval changes tonight (app + harness, mirrored)

Namesakes dropped ("Rome, New York" for "Rome or New York?"); words after . ! ? aren't names ("Check" the
film); "Ethereum's" -> Ethereum; everyday-word titles (North, Time, Week) dropped when a name matched;
named articles lead with their intro + the infobox lines that mention the question (Napoleon's dates);
acronym table (tools/build_acronyms.py, needs `--apply` on a pack; TCP/UDP/TLS/BMI/ICU were
disambiguation pages = no article, no redirect) with context ("CFS" in a Linux question) and redirects
first ("RAM"); "<noun> of the <name>" ("Soviet Union collapse" -> Dissolution of the Soviet Union);
list expansion ("must-see museums in Berlin": the list article's most viewed entries, or rank order for
"tallest" lists, instead of one 450-char slice of a padded table).

### Results

| Model | File | Score (main, single run) | PC s/answer |
|---|---|---|---|
| Qwen3.5-9B | 5.3 GB | 57.7 (v2: 55.2) | 26.5 |
| Qwen3.5-4B | 2.6 GB | 54.7 (v2: 57.1) | 16.4 |
| LFM2.5-8B-A1B | 4.9 GB | 46.4 (needs `--reasoning-budget 0`) | ~6 |
| Gemma-4-E4B | 4.7 GB | 44.1 | 12.6 |
| Qwen3.5-2B | 1.2 GB | 44.0 | 9.7 |

Tier picks (in catalog.json): 6 GB -> 2B, 8 GB -> 4B, 12 GB -> 9B. Rival AndroidLM claims 64 %.

### Measured tonight (paired, blind, seeded; 4B unless noted)

| Comparison | Mean diff (1-10) | 95 % CI | better / worse / same |
|---|---|---|---|
| old retrieval (5077db9) -> v3 retrieval | -0.13 | -0.54..0.27 | 17 / 27 / 16 |
| v3 -> v4 (list expansion, context acronyms, reorder) | 0.00 | -0.31..0.31 | 6 / 9 / 45 identical |
| v4 -> v4-calc prompt | +0.25 | -0.35..0.85 | 28 / 20 / 12 |
| no thinking -> thinking, 6 reasoning q | +2.17 | -1.28..5.61 | 3 / 2 / 1 |
| 9B: no thinking (v2) -> thinking, 6 reasoning q | +5.17 | 1.40..8.93 | 5 / 1 / 0 |

Reading: retrieval fixes do what they target (Berlin museums 2 -> 8, Linux scheduler 3 -> 6, fact +1.7)
but at n = 60 they don't move the 4B's mean: small evidence changes flip other answers by 1-3 points
either way. The bottleneck is answer writing. Reasoning answers fail mostly by stating a wrong number
first and correcting it later; calc fixes that (reasoning +3.2) but made the 4B talk about "the provided
excerpts" in 3/60 answers (health-04 9 -> 2), so calc2 (working-out rule folded into the research rule)
is being tested. Thinking fixes reasoning (9B: 6/6 right) but costs 700-4,000 tokens per answer (4B avg
~2,600, 9B ~1,500): 5-10+ minutes on the Redmi at 4-7 tok/s. Thinking budgets 512/1024 are queued.

### Running / queued overnight (check `systemctl --user list-units 'bivouac-*'`, tools/queue.log, tools/queue2.log)

- ~/bivouac-queue.sh: 4B v3 (seeded, retrieval at ~c5d7af5 minus context acronyms) -> eval/;
  4B s-old (old retrieval 5077db9, seeded; worktree ~/bivouac-old) -> ~/bivouac-old/eval/;
  4B + 9B `--think` on the 6 reasoning questions -> eval/results-pc-*-v3-think.jsonl ("Think harder" test).
- ~/bivouac-queue2.sh (after queue 1; frozen worktree ~/bivouac-v4 at d29b13e, results in ~/bivouac-v4/eval/):
  4B v4, 4B v4-calc, 9B v4, 9B v4-calc (**the 9B "calc" run uses calc2**: the prompt file in the worktree was
  swapped before it started).
- ~/bivouac-queue3.sh: 4B and 9B on the held-out set (v4 code) -> results-pc-*-v4-holdout.jsonl.
- ~/bivouac-queue4.sh: 4B v4-calc2 (all 60), then 4B/9B thinking with --reasoning-budget 512 and 1024. "calc" = tools/prompts/system-calc.txt: the app prompt plus
  "work calculations out before stating the result" (the 4B/9B state a wrong number first, then correct
  themselves; graders punish that).

Next steps, in order:
1. Read the overnight results (see the status log entry for 2026-10-01 night; grade what isn't graded):
   paired v3 vs s-old (retrieval effect), v4 vs v4-calc (prompt), think vs no-think on reasoning.
   Adopt system-calc.txt into app/src/main/assets/prompts/system.txt if it wins.
2. "Think harder" button: proposed to the user, not decided. Decide with the think-run numbers
   (quality on the 6 reasoning questions and completion tokens -> phone time at ~4-7 tok/s).
3. Held-out runs for the published numbers: 4B and 9B on eval/questions-holdout.jsonl.
4. Phone (user connects the Redmi via usbipd, see "What the user does"): install the APK, copy the packs
   (~24 GB; wiki.db now also has the acronym table), test the automatic memory settings (Library shows
   "auto: ctx …"; compare `adb shell dumpsys meminfo io.github.wolverr.bivouac` with the estimate; load the
   9B to force an OOM and check the crash notice), measure speed of 2B/4B, list expansion latency (~1-3 s
   on the PC), run the on-phone eval (docs/EVAL.md).
5. Publish: user makes a HuggingFace account + token, then `tools/publish_packs.py <user>/bivouac-packs`
   (uploads, fills catalog URLs). README.md is a draft with TBDs (phone speeds, release link, license).
   Then demo video (user), make the repo public, poidh claim. Deadline 2026-10-31.

What the user does (only these): connect the phone (`winget install usbipd`, then as admin
`usbipd list`, `usbipd bind --busid X`, `usbipd attach --wsl --busid X`); choose the app license;
HuggingFace account; record the demo video; submit the claim. No 12 GB phone yet (borrow one,
cloud device farm, or used Pixel 8 Pro).
See docs/COMPETITORS.md for the other entries.

## Milestones

- [x] M0: toolchain (JDK 17 via mise, Android SDK/NDK r29, CMake 3.31.6), llama.cpp v0.5.0 submodule
- [x] M1: app runs a GGUF on the phone: import, load, streaming answer, prefill progress, benchmark
- [x] M2: Wikipedia pack: `tools/build_corpus.py` → `wiki.db` (FTS5 + zstd blocks): 18.3 GB, 6.49 M articles
- [~] M3: on-device retrieval (SQLite + zstd in native code), cited answers, sources view
  (code done and building; needs a real pack + device test)
- [~] M4: model selection **per RAM tier** (6 / 8 / 12 GB): quality ranked on the PC harness,
  speed on phones. 8 GB is the main target (most phones; the Redmi is the 8 GB reference
  device); 12 GB is the ceiling, not the requirement. Auto repack/context sizing by free RAM.
- [~] M5: install UX: catalog + browser download + link-in-place (done); host packs, fill catalog URLs (todo)
- [~] M6: places (Overture Maps) + "near me" queries: app side done; places.db built; desktop-tested
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

- 2026-09-30: places.db built (30.0 M places, 5.3 GB, 4.3 min). Place questions tested on the PC with
  tools/places_proto.py: city queries work (Lisbon hotels, Paris restaurants, Kathmandu hospitals, 15–50 ms).
  Fixed: generic category endings (grocery_store -> "grocery", "supermarket"), knowledge questions
  ("must-see museums in Berlin", "history of …") now go to Wikipedia, trailing clauses cut from locations.
  Open: exonyms without the wiki pack (see Resume here, step 3); Overture's pharmacy category has some junk.
- 2026-09-30: low-RAM work (M4). `model_probe.cpp` reads the GGUF header and computes KV bytes per token
  (full / sliding-window layers, hybrid attention intervals, Gemma shared-KV layers); matches llama.cpp's
  own KV allocation exactly for all 7 candidate models at ctx 8192. KV at 8192: Qwen3.5-2B / LFM2.5 96 MB,
  Qwen3.5-4B/9B 256 MB, Qwen3.6-35B 160 MB, Gemma-4-26B 410 MB, Gemma-4-E4B 158 MB (hybrids are cheap).
  `MemoryPlanner`: need = file × (2 if repack) + KV + 160 MB, budget = availMem × safety − 450 MB; prefers
  repack at ctx ≥ 4096, then the largest context without repack, else a clear "not enough memory" error.
  `CrashGuard`: marker file around load/answer + ApplicationExitInfo (Android 11+); a memory kill cuts that
  model's budget by 25% (floor 40%), regained 5% per good answer. Prompt budget follows the context
  (`Settings.forContext`). "Automatic memory settings" switch in Library (on by default). APK builds;
  not yet run on a phone.

- 2026-09-30: **full wiki.db built** (6,492,540 articles, 34.2 M chunks, 26.4 GB text -> 7.1 GB zstd,
  18.1 GB db, 24 min with a 9 GB memory cap; 3 GB was OOM-killed). FineWiki has several revisions of
  66k pages; the builder keeps the first. fetch_pageviews.sh no longer fails on bzip2's expected broken pipe.
  **Found: bm25() returned 0 for every row** (contentless + detail=column can't count term hits), so ranking
  had been prior + titles only. New `fts_rank.c` colrank() = BM25 with tf=1 from column presence and
  column sizes; same C in the app and as tools/native/build/fts_rank.so for Python.
  Retrieval hit@6: BM25 alone 0% -> 75%; with titles 69% -> 85% after also: everyday-word titles need
  >= 3,000 views/month ("Determine", "Broad Strokes" skipped; "Season" kept), and an alias table
  (`tools/build_aliases.py`: redirects + pageview titles, 2.28 M names, +180 MB): GPS -> Global Positioning
  System, Treaty of Westphalia -> Peace of Westphalia, WW2, Obama. Aliases also geocode places by local name
  (Florence -> firenze, Rome -> "roma, italy" -> roma, Moscow -> москва). Search ~1.5 s/question on the PC.

- 2026-10-01: model eval (PC harness). Fixed on the way: place instructions moved from the system
  prompt into the places list (small models leaked "cuisine notes" into unrelated answers; 2B went
  36.6% -> 44.0%); the harness now uses places.db like the app. Found by reading graded answers: list
  questions need the "List of X in Y" article, "New York" is a disambiguation page (fallback to the most
  viewed "X (...)"/"X City" article, names only), the diet sentence belongs only to diet requests, LFM2.5
  reasoning must be filtered (research/ThinkFilter.kt). Retrieval hit@6 stays 85%.

## Dev notes

- Build: `export JAVA_HOME=$HOME/.local/opt/jdk-17.0.20+8; ./gradlew assembleDebug` (mise isn't on PATH in
  non-interactive `wsl -e bash -lc` shells)
- adb: `~/Android/Sdk/platform-tools/adb` (WSL needs usbipd to see a USB phone)
- Test phone: Redmi Note 15 Pro (mid-range MediaTek). Final numbers come from a borrowed Pixel.
- Don't copy code from other entries; ideas only (plagiarism disqualifies).
- Long jobs: run as memory-capped systemd user services, not from a terminal. On this 8 GB
  PC systemd-oomd kills the whole terminal scope (and anything started from it, setsid or
  not) when RAM+swap pass 90%. Example:
  `systemd-run --user --unit=bivouac-wiki --working-directory=$PWD/tools -p MemoryMax=3G -p MemorySwapMax=1G --collect bash -c '…'`
  Check with `tools/status.sh`; stop with `systemctl --user stop bivouac-wiki`.
