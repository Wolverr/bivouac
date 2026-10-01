# Bivouac

**An offline research assistant for Android.** Ask a question and get a cited answer from
Wikipedia, or find places near you, with no internet connection, no account and no
Google Play Services. Everything runs on the phone.

Built for [poidh bounty #31](https://poidh.xyz/mainnet/bounty/31): an offline tool that is
more than half as good as a frontier AI with web search, on a phone with at most 12 GB of RAM.

> Status: work in progress. Numbers marked *TBD* are not measured on a phone yet.

## What it does

- **Cited answers from all of English Wikipedia.** 6.49 M articles (2025-08) in an 18.3 GB
  pack: full text for the 2 M most-read articles, intro and infobox for the rest. Every
  answer is written from numbered excerpts and cites them, like `[1][3]`.
- **Places, including "near me".** 30 M places worldwide (Overture Maps, 5.3 GB):
  "vegetarian restaurants near the Duomo in Florence", "pharmacy near Shinjuku Station",
  "ATM near me". Distances come from the phone's GPS or from Wikipedia coordinates, and
  English names work for places the map stores in the local language (Florence → Firenze,
  Moscow → Москва).
- **Fast on ordinary phones.** The target is 8 GB phones, not just flagships. The app reads the
  model's header and the free RAM before loading, then picks the context length and weight
  layout that fit. If Android ever kills it for memory, the next load uses smaller settings.
- **Conversations.** Follow-up questions ("and its population?") keep the topic; personal or
  casual messages get a normal reply instead of a Wikipedia search.
- **Private by construction.** The app has no INTERNET permission. Downloads go through the
  phone's browser; the app only reads the files you add.

## Install (no PC needed)

1. Install the APK (*TBD: release link*).
2. Open **Library → Download** for a model and the packs. Your browser saves them to Downloads.
3. Tap **Add downloaded files**. Bivouac links them in place, without copying.

Which model to pick:

| Phone RAM | Model | File | Quality (PC eval, % of frontier + web) | Speed on phone |
|---|---|---|---|---|
| 6 GB | Qwen3.5-2B Q4_K_M | 1.2 GB | 44 % | 25 tok/s reading, 7 tok/s writing (Redmi Note 15 Pro) |
| 8 GB | Qwen3.5-4B Q4_K_M | 2.6 GB | 55 % | *TBD* |
| 12 GB | Qwen3.5-9B Q4_K_M | 5.3 GB | 58 % | *TBD* |

Storage: model + Wikipedia pack + places pack = about 25–30 GB, under the bounty's 50 GB limit.

## How it works

1. **Route:** is this a research question, a place search, or conversation?
2. **Retrieve:** full-text search over 34 M Wikipedia sections (SQLite FTS5 with a BM25
   ranker that works on a compact index, blended with article popularity from pageviews).
   Articles named in the question lead with their intro: exact titles, 2.3 M alternative names
   from Wikipedia redirects ("GPS", "WW2"), 40 k acronyms mined from article intros for the ones
   Wikipedia only has as disambiguation pages ("TCP", "BMI"; the question picks the meaning), and
   list articles for list questions, read as their most viewed entries ("must-see museums in
   Berlin" → Pergamon Museum, …) or in rank order ("tallest buildings in Dubai").
3. **Compress:** each source is cut down to the sentences that match the question.
4. **Answer:** llama.cpp on the phone's CPU. The fixed instructions are evaluated once and
   reused from a cache, so only the question and excerpts are read each time.

Place searches parse the kind of place and the location, geocode it (Wikipedia coordinates
for 1.2 M articles, from the geo_tags dump, then town centroids), and pass the nearest matches with distances to the model.

## Evaluation

60 questions across facts, explanations, comparisons, synthesis, reasoning, travel, health,
tech, science, history and places (`eval/questions.jsonl`), plus 40 held-out questions that were
never used for tuning (`eval/questions-holdout.jsonl`). Each Bivouac answer is compared
blind with an answer from a frontier model using live web search, scored 1–10 for correctness,
completeness and usefulness. Details and how to reproduce: [docs/EVAL.md](docs/EVAL.md).

PC harness (same pipeline and prompt as the app, llama.cpp on a desktop CPU):

| Model | Score vs frontier + web |
|---|---|
| Qwen3.5-9B | 57.7 % |
| Qwen3.5-4B | 54.7 % |
| LFM2.5-8B-A1B | 46.4 % |
| Gemma-4-E4B | 44.1 % |
| Qwen3.5-2B | 44.0 % |

A single run varies by about ±3 points (grader and sampling noise); pipeline changes are
compared head-to-head in one blind packet instead (`tools/grade.py pair`).

On-phone results: *TBD*. Retrieval finds a correct Wikipedia article in the top 6 for 92 %
of the questions that have one (44/48), and 94 % on the held-out set (34/36).

## Build from source

Requirements: Linux or WSL2, JDK 17, Android SDK + NDK r29, CMake 3.31. See
[docs/DEV_SETUP.md](docs/DEV_SETUP.md).

```bash
git clone --recursive https://github.com/Wolverr/bivouac
cd bivouac
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
```

Rebuilding the data packs (downloads ~20 GB; a few hours on a 16 GB PC, mostly download time):

```bash
tools/setup_dev.sh               # Python venv for the tools
tools/run_data_jobs.sh           # FineWiki + pageviews -> wiki.db, Overture -> places.db
tools/status.sh                  # progress
# (run_data_jobs.sh also adds the alias, acronym and coordinate tables: tools/build_aliases.py,
#  tools/build_acronyms.py, tools/build_coords.py)
```

## Data and licenses

- Wikipedia text: [FineWiki](https://huggingface.co/datasets/HuggingFaceFW/finewiki)
  (English Wikipedia, 2025-08), CC BY-SA 4.0. Pageviews and redirects: Wikimedia dumps.
- Places: [Overture Maps](https://overturemaps.org) places theme (2026-09), CDLA-Permissive-2.0.
- Models: Qwen3.5 (Apache-2.0) as GGUF files.
- Engine: [llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT), SQLite (public domain),
  zstd (BSD).

App license: *TBD*.
