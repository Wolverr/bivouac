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

## Resume here (handoff, 2026-10-01 ~16:00)

**User direction (16:00):** the **12 GB phone is the main demo device** (user gets one Sunday 2026-10-04,
model not known yet; keep 8 GB working). The user wants original ideas that improve on the other entries
drastically, not reimplementations (e.g. not AndroidLM's MoE expert streaming).

**Phone results (Redmi Note 15 Pro, MT6789, 8 GB), 4B Q4_K_M:**
- llama-bench (tools: ~/llama-android-build, cross-built llama-bench in /data/local/tmp, scripts bench.sh /
  setpref.sh / freq.sh in the session scratchpad): reading 10-12 tok/s at 4-8 threads, writing 3.7 tok/s on
  the 2 big cores (8 threads: 0.7). Repack gives nothing for Q4_K on this chip; Q3_K_M is *slower* (read
  6.4, write 2.8) so it is a poor 6 GB pick (still the catalog's 6 GB entry: decide again with a 6 GB
  candidate measured on a phone); Q4_0 + repack reads 13.4 but writes 2.2; IQ4_NL no gain; 2B reads 20-21,
  writes 6.1.
- In the app: ~6 tok/s reading, ~2.5 writing, ~3 min per answer (10-question test,
  eval/results-redmi-qwen3.5-4b-speed10.jsonl). Not an app bug as far as measured: the same llama-bench
  run gives 11.7 on a cool idle phone and 5.1 after an hour of load (Xiaomi hides the clock caps).
- The app sent 6 sources where the harness sends 3 (named-article boost applied after the cut): fixed
  (70d04e3), prompts 897 -> 649 tokens for the same question.

**Prompt/answer length (paired, blind, 4B, PC):** 300 chars per source = same quality (+0.02, CI
-0.37..0.40), prompts -16 %: adopted (451372b). 4 sources instead of 6: -0.17 (CI -0.50..0.17) for -9 %:
not taken. Places prompt "best 3 to 5, one short line each", model reads the 8 nearest (screen shows all):
0.00, answers 255 -> 189 tokens: adopted (17b5280); the old diet note made the model invent a restaurant.

**Speculative decoding: no gain with Qwen3.5 (measured on the PC, 9B):** no draft 12.7 tok/s; 0.8B draft
7-11 (rejected guesses cost a recurrent-state checkpoint restore in this hybrid model), with
`--spec-draft-p-min 0.8` 10.8-12.2 at 93-99 % acceptance; n-gram lookup 12.4 (2 % accepted). The GGUFs
have no MTP heads. Note: with `-md` this llama-server also needs `--spec-type draft-simple`. Worth
retrying only with a standard-transformer model (cheap rollback). ~/spec_try.sh runs the quick check.

**MTP (built-in draft heads; unsloth/Qwen3.5-*-MTP-GGUF, +0.1-0.45 GB):** `--spec-type draft-mtp` works
on the PC: 9B 12.0-12.7 -> 15.6-17.0 tok/s at `--spec-draft-n-max 2` (+30 %), 4B 21 -> 25 at n-max 1
(+18-20 %), same answers. On the Redmi (llama-speculative-simple cross-built, /data/local/tmp/llama-spec +
q4b-mtp.gguf, script mtp.sh): no gain (warm, loaded phone: baseline 1.2-2.8, MTP 1.5-1.9 tok/s, 75 %
accepted); the checkpoint save/restore per round probably costs more than it saves on a weak CPU. Retest on
the 12 GB phone (1 minute with the same files) before building it into engine.cpp (pattern:
examples/speculative-simple, 377 lines, handles the hybrid checkpoints).

**12 GB model: Qwen3.5-9B stays.** Gemma-4-12B QAT UD-Q4_K_XL (6.72 GB) vs 9B, paired blind, 60 main
questions: -1.73 (CI -2.43..-1.04, 8 better / 38 worse); it often declines ("excerpts don't cover"); 7.1 GB
RAM at ctx 4096 (8.1 at 8192), PC 28 tok/s read / 2.6 write vs the 9B's 49 / 12.2. (Run it with
`--no-repack -c 4096 -np 1` or it thrashes the 12 GB service cap.) Ministral-3-14B only fits at Q3: skipped.

**12 GB precision: Q4_K_M stays.** 9B UD-Q5_K_XL (6.87 GB) vs Q4_K_M (5.6 GB), paired blind, 60 main
questions: +0.02 (CI -0.31..+0.34, 20 better / 17 worse / 23 ties, p 0.74): no measurable gain. Q5 is
slower (PC 40 tok/s read / 10.5 write vs 49 / 12.2) and 1.3 GB bigger. Answer length the same (184 vs 183).
(It thrashed at first: llama-server's host prompt cache, default 8 GB, pushed it past the 12 GB cap;
run_model_eval.sh now passes `--cache-ram 0`. The app doesn't use llama-server, so it isn't affected.)

**12 GB: Think harder stays a button, not the default.** 9B with thinking (1,024 budget) vs without, paired
blind, all 60 main questions: -0.25 (CI -0.83..+0.33, 18 better / 24 worse / 18 ties, p 0.44), at 1,177
vs 183 tokens per answer (PC 99 s vs 21 s). It wins reasoning (+1.83) and places (+2.25, 4 questions)
but loses compare (-1.83), tech/science (-1.3), explain (-0.9): thought-through answers are shorter and
leave things out (662 vs 810 chars).

**Auto Think harder for calculations (2026-10-02, e62ba4b + this):** Router.needsReasoning (numbers + "how
long/many/much/far", "what time", "is that enough"; one number only with a rate or "if"; "by how much").
Built on the main set only (the 4 held-out reasoning questions had been seen in a listing, not used).
Main set, from the v13/v15 grades: 9B +0.25 over all 60 when on for these only (vs -0.25 always on).
Held-out h-reason-01..03 (v16, paired blind, Sonnet grader): 4B 5.0 -> 6.3, 9B 8.7 -> 9.0. Calculations
gained or tied in all 9 cases; timelines (Napoleon/Beethoven, Newton/Galileo) lost (-4 9B, -9 4B), so the
timeline rule was dropped. 0 false alarms on the other 90 questions and on 20 lookups with numbers.

**Next (12 GB):** (1) GPU prompt reading once the phone model is known (Snapdragon: OpenCL/Adreno path in
llama.cpp); (2) MTP retest on the phone (see above); (3) time an auto-think answer on the phone.

**Earlier this afternoon, done:**
- **Tiers** (paired, blind, seeded, 60 main questions vs the 4B Q4_K_M): UD-IQ3_XXS -1.25 (CI -1.71..-0.79,
  39 worse / 9 better): rejected. Q3_K_M (2.29 GB) -0.38 (CI -0.80..0.03, 27/18): now the **6 GB pick**
  (catalog id qwen3.5-4b-q3, min_ram_gb 5, ~3.0 GB free needed vs 3.5). The 2B moved to 4 GB phones
  (min_ram_gb 3). Not faster on the PC (13-16 s/answer for both); phone speed still to measure.
- **Places, 3 fixes** (app + harness mirrored): (1) `tools/build_coords.py` adds coord(article, lat, lon)
  to wiki.db from Wikipedia's geo_tags dump (1.22 M articles; FineWiki drops {{coord}} outside infoboxes, so
  Florence Cathedral, Louvre, Trevi Fountain, Times Square had none); find_article returns lat/lon; a bare
  "Duomo" also tries "Florence Duomo" / "Duomo of Florence" / "Grand Bazaar, Istanbul". (2) **Bug:**
  `within()` capped the R-tree box at 4000 rows *before* the category filter; a city centre has more (4488
  within 800 m of the Duomo), so the nearest matches were cut. Now filtered in SQL. Duomo: vegetarian
  restaurants 0.8-2 km -> Wegetarian at 150 m; Shibuya Crossing: Starbucks at 38 m. (3) Pharmacies and
  hospitals whose name says so (farmacia, 薬局, クリニック, ...) rank first: Overture tags law firms and
  izakayas near Shinjuku as pharmacies (all conf 0.85; that value is fine data in GB/IT/US, so no filter).
  Paired blind grading of the places questions (before (1)-(3) vs after (1)+(2)): Duomo 5 -> 7 for 4B and
  9B, Shibuya +1/+2; category mean flat (n = 7, noise; 9B pair also differs by the calc2 prompt).
- **Sights questions** (0725b6e): the city article's sights sections (any heading level with sights /
  attractions / landmarks / monuments) as one whole source: opening prose + bullets of the first 3 chunks.
  Barcelona now gets Sagrada Família, Park Güell, Casa Milà; Prague its castle and bridge; Florence the Duomo.
  Weak: Bruges's bullets are cinemas. Not yet measured with a model.
- **Published packs:** https://huggingface.co/datasets/Wolverrr/bivouac-packs (wiki.db 18.33 GB with the
  coord table, places.db 5.32 GB; public, sizes verified); catalog URLs filled. `hf auth login` done in WSL
  (user Wolverrr). publish_packs.py works with huggingface_hub 2.0 (upload_file via Xet, resumable).
- **Phone connected and set up:** the Redmi is attached; the new APK is installed (the laptop build had to be
  uninstalled: different debug key; its 2B copy was lost). places.db is on the phone; wiki.db and the 2B/4B/
  4B-Q3/9B models are copying.

**Phone copy, important:** usbipd + WSL adb gave 3 MB/s (USB 2.0 link through usbip). Windows' own adb is
16x faster (48 MB/s): `%LOCALAPPDATA%\platform-tools-win\platform-tools\adb.exe`, using the WSL adb key
(copied to %USERPROFILE%\.android, so no new prompt). For big copies: `usbipd detach --busid 1-8`, then push
from Windows reading `\\wsl.localhost\Ubuntu-24.04\home\nicolas\...` (script in the session scratchpad:
loop over files, skip same-size). For WSL adb again: `usbipd attach --wsl --busid 1-8` (already bound; the
WSL udev rule is /etc/udev/rules.d/51-android.rules, written with `wsl -u root`). Xiaomi asks on the phone
before each `adb install` ("Install via USB" screen). `adb shell svc power stayon usb` keeps it awake.

**Next:** phone tests (see Next steps 4). Then the full on-phone eval (1-2 h per model).

**Ideas measured but not built:** "nearby Wikipedia articles" for non-shop "near X" questions (hikes near
Chamonix: Lac Blanc, Le Brévent, Aiguilles Rouges are all within 12 km in the coord table). A plain lat/lon
range on coord + FTS restricted to those articles took 1.2-14 s on the PC: needs a spatial index on coord
(R-tree, in build_coords.py; changes the pack -> re-upload, Xet makes that cheap, and re-copy to phones) and
a cheaper restriction than `rowid IN (...)`.

The user asked about storage: total ~25-30 GB (APK ~70 MB; Wikipedia 18 GB, places 5 GB, model). A
"lite" option (top articles only, places per continent, <10 GB) was offered for later; not started.

Start a new session with: "Continue the Bivouac project. Read docs/ROADMAP.md, Resume here."

Dev machine: home Windows PC, WSL2 Ubuntu-24.04, repo /home/nicolas/bivouac (from the
Windows side, reach WSL with `wsl -e bash -lc ...`; editing through
\\\\wsl.localhost drops the exec bit on .sh files, so re-run `chmod +x tools/*.sh`).
Build: `export JAVA_HOME=$HOME/.local/opt/jdk-17.0.20+8; ./gradlew testDebugUnitTest assembleDebug`,
then `./gradlew --stop` — the Gradle + Kotlin daemons hold ~2 GB, enough to make a running 9B eval thrash.
Commits: no AI attribution lines, ever (user rule). Commit messages with quotes or apostrophes:
write them to a file and use `git commit -F file` (heredocs through `wsl -e bash -lc` break).
Keep an Ubuntu terminal open when leaving jobs running overnight (WSL can stop the VM otherwise).

Data (built): tools/out/wiki.db (6.49 M articles, 18.33 GB, 2.28 M aliases, 40 k acronyms, 1.22 M coords) and
tools/out/places.db (30.0 M places, 5.3 GB). Desktop tools need `tools/native/build/fts_rank.so`:
`export PATH=$HOME/Android/Sdk/cmake/3.31.6/bin:$PATH; cmake -S tools/native -B tools/native/build -G Ninja && ninja -C tools/native/build`.
Models in ~/models; llama-server for the PC at ~/llama-pc/bin. **Run the 9B with `--no-repack`**
(`SERVER_ARGS=--no-repack`): with repacking it needs ~12 GB and thrashes under the 12 GB service cap
(answers went from 28 s to 340 s). For several runs in a row, chain them in a queue script like
~/bivouac-queue*.sh (each waits for the previous unit) and run each from a frozen `git worktree`, so
editing the repo meanwhile can't change a run halfway.

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

### Retrieval and places changes tonight (app + harness, mirrored)

Namesakes dropped ("Rome, New York" for "Rome or New York?"); words after . ! ? aren't names ("Check" the
film); "Ethereum's" -> Ethereum; everyday-word titles (North, Time, Week) dropped when a *name* matched
(not when any specific word matched: "snake bite venomous" keeps Snakebite); named articles lead with their
intro + the infobox lines that mention the question (Napoleon's dates); acronym table
(tools/build_acronyms.py, `--apply` on a pack; TCP/UDP/TLS/BMI/ICU were disambiguation pages = no article,
no redirect): exact-case title, then a mined meaning sharing a word with the question ("CFS" in a Linux
question), then the redirect ("RAM"), then the more viewed; "<noun> of the <name>" ("Soviet Union collapse"
-> Dissolution of the Soviet Union); list expansion ("must-see museums in Berlin": the list's most viewed
entries, or rank order for "tallest" lists, instead of one 450-char slice of a padded table);
sights/attractions/landmarks -> "List of tourist attractions in X" (Rome: Colosseum, Pantheon, St Peter's…).
Places: "X in Y" geocodes the landmark X before the city (its coordinates, else its infobox Location line:
Shibuya Crossing -> Shibuya, not Tokyo's centroid in Aoyama); "Geographic coordinates" lines count.

### Results (PC harness, % of the frontier + web baseline; single runs, ±3 points)

| Model | File | Main 60 q | Held-out 40 q | PC s/answer |
|---|---|---|---|---|
| Qwen3.5-9B | 5.3 GB | 60.7 (v4; earlier 57.7, 55.2) | 62.7 | ~28 (`--no-repack`) |
| Qwen3.5-4B | 2.6 GB | 60.2 (v4; earlier 54.7, 57.1) | 54.0 | ~15 |
| LFM2.5-8B-A1B | 4.9 GB | 46.4 (old pipeline) | – | ~6 |
| Gemma-4-E4B | 4.7 GB | 44.1 (old pipeline) | – | 12.6 |
| Qwen3.5-2B | 1.2 GB | 40.7 (v5; old pipeline 44.0) | 45.2 | ~9 |

Weakest categories (v4 + held-out): places (28–48 %; the held-out Shibuya failure is the geocoding bug fixed
after these runs), reasoning (35–57 %), travel (39–63 %). Strongest: explain, science, fact (60–76 %).
Tier picks (catalog.json): 6 GB -> 2B, 8 GB -> 4B, 12 GB -> 9B. Rival AndroidLM claims 64 %.
**The 2B is below the bounty's 50 % bar** (40.7 / 45.2): consider the 4B for 6 GB phones too (2.6 GB file;
without repacking it should fit a 6 GB phone's budget — check MemoryPlanner's numbers on a device).

### Measured tonight (paired, blind, seeded; 4B unless noted)

| Comparison | Mean diff (1-10) | 95 % CI | better / worse / same |
|---|---|---|---|
| old retrieval (5077db9) -> v3 retrieval | -0.13 | -0.54..0.27 | 17 / 27 / 16 |
| v3 -> v4 (list expansion, context acronyms, reorder) | 0.00 | -0.31..0.31 | 6 / 9 / 45 identical |
| v4 -> calc prompt | +0.25 | -0.35..0.85 | 28 / 20 / 12 |
| v4 -> calc2 prompt | -0.05 | -0.55..0.45 | 22 / 15 / 23 |
| 9B: v4 -> calc2 prompt | +0.43 | -0.10..0.96 | 20 / 21 / 19 |
| no thinking -> thinking, 6 reasoning q | +2.17 | -1.28..5.61 | 3 / 2 / 1 |
| 9B: no thinking (v2) -> thinking, 6 reasoning q | +5.17 | 1.40..8.93 | 5 / 1 / 0 |
| 4B: thinking, budget 512 tokens (~640 tok/answer), 6 reasoning q | +2.00 | -1.12..5.12 | 2 / 2 / 2 |
| 4B: thinking, budget 1024 (~1,070 tok/answer) | **+4.50** | 1.65..7.35 | 5 / 1 / 0 |
| 9B: thinking, budget 512 (~700 tok/answer) | +3.67 | 0.69..6.65 | 4 / 0 / 2 |
| 9B: thinking, budget 1024 (~1,140 tok/answer) | **+5.33** | 2.92..7.74 | 6 / 0 / 0 |

Reading:
- Retrieval fixes do what they target (Berlin museums 2 -> 8, Linux scheduler 3 -> 6, fact +1.7) but at
  n = 60 they don't move the 4B's mean: small evidence changes flip other answers by 1-3 points either way.
  The bottleneck is answer writing, not retrieval (hit@6 main 44/48, held-out 34/36).
- Reasoning answers fail mostly by stating a wrong number first and correcting it later. The calc prompts
  fix that (reasoning +1 to +3.7) but the 4B then talks about "the provided excerpts" / half-refuses in 3-4
  of 60 answers (0 with the app prompt), which cancels the gain. calc2 helps the 9B (+0.43, reasoning +3.7,
  no extra excerpt talk). **App prompt unchanged.** Option: use tools/prompts/system-calc2.txt for the 12 GB
  (9B) tier only — needs a per-model prompt (assets/prompts/ + catalog field) and breaks nothing else.
- Thinking fixes reasoning (9B 6/6 right) but unbounded it costs 700-4,000 tokens per answer (4B avg
  ~2,600, 9B ~1,500): 5-10+ minutes on the Redmi at 4-7 tok/s. **A 1,024-token thinking budget keeps
  nearly all of the gain (4B +4.5, 9B +5.3, all 6 right for both) at ~1,100 tokens per answer**, i.e.
  roughly 3-5 minutes on the Redmi (4B decode speed there not measured yet). 512 is too little for the 4B.

Eval files: results in eval/results-pc-*.jsonl (v3/v4/s-old/calc/calc2/think/holdout), gradings and
summaries in eval/grading/ (pair-* for paired). They're not committed yet (neither were earlier ones);
commit them when publishing the eval. Worktrees ~/bivouac-old (5077db9) and ~/bivouac-v4 (d29b13e) were only
for tonight's frozen runs (also ~/bivouac-v5 for the 2B): `git worktree remove` them when done.

Next steps, in order (updated 2026-10-02):
1. Done: "Think harder" (1,024-token budget), calc2 for the 9B, 6 GB tier (4B Q3_K_M), places fixes,
   sights sources, packs published. 2026-10-01/02: 12 GB model and precision (9B Q4), Think harder stays
   a button but turns on by itself for calculation/timeline questions (Router.needsReasoning), place
   names without the wiki pack, README tiers + held-out scores, MIT license, eval files committed.
2. 12 GB phone (user gets it 2026-10-04; model not known yet): MTP retest (see MTP above), speed of the
   9B, GPU prompt reading if Snapdragon, Think harder timing on the phone.
3. Phone tests (the phone has the new APK; check the copy finished): test the automatic memory settings
   (Library shows "auto: ctx …"; compare `adb shell dumpsys meminfo io.github.wolverr.bivouac` with the
   estimate; the phone had only 3.0 GB MemAvailable at idle, so the 4B Q4_K_M may land on "no repack";
   load the 9B to force an OOM and check the crash notice), measure speed of 2B/4B/4B-Q3 (if Q3 decodes
   clearly faster on the phone, consider it for 8 GB too), Think harder timing, list-expansion latency
   (1-3 s on the PC: up to 200 title lookups), sights latency (reads every chunk of a city article).
4. On-phone eval (docs/EVAL.md), 4B first; it gives the published numbers.
5. Measure the sights change with the 4B (h-places-03 and a few new sights questions, paired).
6. Publish the rest: README.md TBDs (phone speeds, release link), GitHub release. The release key exists
   (2026-10-02): ~/bivouac-release/{bivouac-release.jks,keystore.properties}, outside the repo, cert
   SHA-256 5206…ed90; `./gradlew assembleRelease` signs with it (11.6 MB APK, R8). **Test the R8
   release build on a phone before publishing it** (JNI and serialization classes); user must keep a
   backup of the key (lost key = no updates under the same app).
   Then demo video (user), make the repo public, poidh claim. Deadline 2026-10-31.
7. Later, if the pack is rebuilt anyway: collapse markdown-table padding in build_corpus.py (27 % of raw
   text, ~7 % of compressed blocks); spatial index on coord for "nearby articles" (above).

What the user does (only these): record the demo video; make the repo public; submit the claim; plug in
the phones for tests. License chosen (MIT, 2026-10-01). Phone setup and HuggingFace are done.
Note: the user often works from the phone over SSH; editing repo files from Windows (\\wsl.localhost)
drops the executable bit on scripts, so check `git diff --summary` for mode changes before committing.
See docs/COMPETITORS.md for the other entries.

## Milestones

- [x] M0: toolchain (JDK 17 via mise, Android SDK/NDK r29, CMake 3.31.6), llama.cpp v0.5.0 submodule
- [x] M1: app runs a GGUF on the phone: import, load, streaming answer, prefill progress, benchmark
- [x] M2: Wikipedia pack: `tools/build_corpus.py` → `wiki.db` (FTS5 + zstd blocks): 18.3 GB, 6.49 M articles
- [~] M3: on-device retrieval (SQLite + zstd in native code), cited answers, sources view
  (code done and building; needs a real pack + device test)
- [~] M4: model selection **per RAM tier** (4 / 6 / 8 / 12 GB: 2B, 4B Q3_K_M, 4B Q4_K_M, 9B): quality
  ranked on the PC harness, speed on phones (todo). 8 GB is the main target (most phones; the Redmi is the
  8 GB reference device); 12 GB is the ceiling, not the requirement. Auto repack/context sizing by free RAM.
- [~] M5: install UX: catalog + browser download + link-in-place; packs hosted on HuggingFace, catalog URLs
  filled (2026-10-01). Left: try a browser download on the phone.
- [~] M6: places (Overture Maps) + "near me" queries: app side done; places.db built; desktop-tested;
  landmark coordinates, nearest-first fix, health-place ranking, sights sources (2026-10-01)
- [~] M7: eval harness vs frontier + web, published results (harness, paired grading, held-out set done; publish)
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
  Done since (f3bd14f, 32ab435): markdown rendering, "use efficiency cores" and repack switches,
  quieter llama.cpp logs, bench/completion stats to logcat.
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
  2026-10-01: both done. Pharmacy junk: names that say what the place is rank first (NAME_HINTS).
  Exonyms: without the wiki pack "Rome", "Florence", "Athens", "Vienna" found the bigger US towns (both
  eval place questions with a city were wrong that way: places-01, h-places-01). assets/place_names.tsv
  (299 names, 16 KB, tools/build_place_names.py) holds the wiki article's coordinates for the names
  where they differ from the biggest locality, so the app answers as if the pack were there. Rejected:
  pairing co-located localities (turns suburbs into their city: Greenwich -> London centre) and the
  biggest neighbouring locality (a Sydney suburb beat Concord, California).
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

- 2026-10-01 (night, unattended): eval method fixed (paired grading, seeded harness, 40-question
  held-out set with its own frontier + web baseline); retrieval: namesakes, sentence starts, intro + infobox
  leads, 40 k mined acronyms, "<noun> of the <name>", list expansion, tourist-attraction lists; places:
  landmark-first geocoding (Shibuya Crossing). hit@6 main 41 -> 44/48, held-out 33 -> 34/36. Single-run
  parity: 2B 40.7 % / held-out 45.2 %, 4B 60.2 % / 54.0 %, 9B 60.7 % / 62.7 %. Paired: retrieval changes neutral for the 4B's
  answers overall; calc prompts help reasoning but the 4B then talks about the excerpts (app prompt kept);
  thinking with a 1,024-token budget: reasoning 4B 4.2 -> 8.7, 9B 3.5 -> 8.8 at ~1,100 tokens/answer.
  Found: the 9B with weight repacking thrashes under a 12 GB cap (use --no-repack on the PC).

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
