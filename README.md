<p align="center"><img src="docs/icon.svg" width="120" alt="Bivouac icon"></p>

# Bivouac

**An offline research assistant for Android.** Ask a question and get a cited answer from
Wikipedia, or find places near you, with no internet connection, no account and no
Google Play Services. Everything runs on the phone.

Built for [poidh bounty #31](https://poidh.xyz/mainnet/bounty/31): an offline tool that is
more than half as good as a frontier AI with web search, on a phone with at most 12 GB of RAM.

> Version 0.1.0 · [download](https://github.com/Wolverr/bivouac/releases/latest)

## For judges

**The bounty's requirements, one by one:**

| Requirement | Bivouac |
|---|---|
| Android / GrapheneOS, ≤ 12 GB RAM | arm64, Android 10 or newer. Developed on an **8 GB** Redmi Note 15 Pro (MediaTek MT6789); the 4B uses about 4 GB of RAM. Model tiers for 4, 6, 8 and 12 GB phones. |
| ≤ 50 GB in total | 27 GB with the 4B (model 2.7 + knowledge pack 18.7 + places 5.3), 30 GB with the 9B. APK 12 MB. |
| Offline, no network requests | The APK declares **no INTERNET permission**, so Android blocks every network call: `aapt2 dump permissions bivouac-0.1.0.apk` lists only location (optional, for "near me") and file access, plus one internal AndroidX permission. Downloads go through the phone's browser. |
| No Google Play Services | None used (location comes from the platform's LocationManager). |
| Usable speed | On the 8 GB phone: the best source sentence shows after the search (~6 s), first words after ~30 s, a whole cited answer in a median 110 s. Places lists appear at once. |
| Public repo, reproducible | MIT code, llama.cpp pinned as a submodule, scripts that rebuild both data packs from public dumps ([Build from source](#build-from-source)), every eval question, answer and grade in `eval/`. |
| Set up on a real device in minutes | No PC: install the APK, tap the three **Download** buttons (the browser saves to Downloads), then **Add downloaded files**; the model loads by itself. The taps take a minute; the 27 GB download takes as long as the connection needs (about 40 min at 100 Mbit/s). |
| > 50 % as good as internet + frontier AI | **63 %** of a frontier model with live web search, blind-graded, on 40 held-out questions never used for tuning, measured in the app on the 8 GB phone. A 1B model on its own scores 41.5 % on the same questions. [Evaluation](#evaluation) |

**Questions to try** (airplane mode on):

- Ethereum and cryptography: "What does EIP-7702 let an EOA do?", "What is EIP-4844 and what are blobs?",
  "What is the difference between ML-KEM and ML-DSA?"
- Places: "Vegetarian restaurants in Chiang Mai"; "pharmacy near me" (GPS);
  "Best hikes near Interlaken"; "Day trips from Lisbon".
- Emergencies and health: "What should I do after a snake bite?", "Symptoms of altitude sickness and what to do".
- Explanations and history: "Why did the Roman Empire split in two?", "How does HTTPS keep my data private?"
- Calculations: "23 km with a 900 m climb, how long will it take?" (tap **Think harder** for the worked-out version).

**Known limitations** (measured, not guessed):

- An answer takes 1–2 minutes on a mid-range 8 GB phone; the 9B on a 12 GB phone is unmeasured on hardware.
- Reasoning and travel questions are the weakest categories (about 48 % and 47 % on the phone);
  **Think harder** fixes most calculations but takes several minutes on an 8 GB phone.
- Knowledge is frozen at the packs' dates (Wikipedia 2025-08, Wikivoyage and Ethereum docs 2026-10,
  places 2026-09); opening hours and prices may be stale.
- A small model sometimes adds a general sentence the sources don't support; the colored citations show
  which sentences rest on a source.
- English only.

## What it does

- **Ethereum and travel knowledge Wikipedia lacks.** The same pack carries every finalized EIP and ERC,
  the ethereum.org developer docs, Ben Edgington's *Upgrading Ethereum*, NIST's post-quantum standards
  (ML-KEM, ML-DSA, SLH-DSA, LMS/XMSS) and all 27,000 Wikivoyage guides (+0.3 GB). "What does EIP-7702
  let an EOA do?" reads the EIP; "vegetarian restaurants in Chiang Mai" adds the guide's vegetarian
  picks to the map's list; travel questions about a country read the matching section of its guide.
- **Cited answers from all of English Wikipedia.** 6.49 M articles (2025-08) in an 18.4 GB
  pack: full text for the 2 M most-read articles, intro and infobox for the rest. Every
  answer is written from numbered excerpts and cites them, like `[1][3]`. Each source has its
  own color, and its markers in the answer are highlighted in the same color, so you can see
  at a glance which sentence rests on which excerpt; tap a source to read the whole excerpt.
- **Places, including "near me".** 30 M places worldwide (Overture Maps, 5.3 GB):
  "vegetarian restaurants near the Duomo in Florence", "pharmacy near Shinjuku Station",
  "ATM near me". Distances come from the phone's GPS or from Wikipedia coordinates, and
  English names work for places the map stores in the local language (Florence → Firenze,
  Moscow → Москва).
- **What's around a place.** "Best hikes near Interlaken", "temples near Siem Reap", "day trips
  from Lisbon": Wikipedia articles located near the place (1.2 M articles with coordinates, in a
  spatial index) become sources, each labelled with its distance and direction ("51 km west of
  Edinburgh"), so the answer names real places at real distances instead of guessing. Castles,
  lakes, temples and towns rank above events, companies and racetracks, and a day-trip list
  names each destination once (Florence: Pisa, San Gimignano, Siena, Lucca, Volterra). A city's
  "Main sights" list is used when it has one.
- **Something useful while it thinks.** An 8 GB phone reads the excerpts for about 30 s before
  the first word. Meanwhile the app shows the one source sentence that best answers the
  question (for "what should I do" questions, the treatment section), in that source's color.
- **Fast on ordinary phones.** The target is 8 GB phones, not just flagships. The app reads the
  model's header and the phone's RAM before loading, then picks the context length and weight
  layout that fit. On an 8 GB phone it repacks the weights into a CPU-friendly layout, which
  doubles prompt reading (6.5 → 13.4 tokens/s on a Redmi Note 15 Pro) for 0.8 GB more RAM. If
  Android ever kills it for memory, the next load uses smaller settings.
- **Conversations.** Follow-up questions ("and its population?") keep the topic; personal or
  casual messages get a normal reply instead of a Wikipedia search.
- **Think harder.** One tap asks the last question again with the model reasoning first
  (hidden, up to 1,024 tokens). On the reasoning questions of the eval it lifts the 4B from
  4.2 to 8.7 out of 10 and the 9B from 3.5 to 8.8, for a few minutes more per answer. Over all
  question types it is no better (the answers get shorter) and about 5× slower, so it turns
  on by itself only for calculations ("23 km with a 900 m climb, how long?"), where it gained
  or tied in all 9 graded cases, including held-out questions. It starts by itself only on
  phones that write at least 6 tokens/s (on a mid-range 8 GB phone a thought-through answer
  takes several minutes, so there it stays a button).
- **Private by construction.** The app has no INTERNET permission. Downloads go through the
  phone's browser; the app only reads the files you add.

## Install (no PC needed)

1. Install the APK from [the latest release](https://github.com/Wolverr/bivouac/releases/latest).
2. On first launch, Bivouac shows the model that fits your phone and the two packs, with one
   **Download** button after the other (your browser saves them to Downloads), or pick others in the
   **Library**.
3. Tap **Add downloaded files** and allow "All files access" when asked. The model loads by itself,
   and again every time the app starts, until you load another one. Bivouac uses the files
   where the browser saved them, without copying (no second 27 GB). It needs that permission
   because llama.cpp and SQLite open files by path; the app has no internet permission, so
   nothing it reads can leave the phone. Tested on the Redmi Note 15 Pro (Android 16).

Which model to pick:

| Phone RAM | Model | File | Quality (% of frontier + web, held-out questions) | Speed on phone (Redmi Note 15 Pro, 8 GB) |
|---|---|---|---|---|
| 4 GB | Qwen3.5-2B Q4_K_M | 1.3 GB | 43 % (below the bounty's bar) | 20 tok/s reading, 6 tok/s writing |
| 6 GB | Qwen3.5-4B Q3_K_M | 2.3 GB | slightly below the 4B Q4_K_M¹ | 6 tok/s reading, 2.8 tok/s writing |
| 8 GB | Qwen3.5-4B Q4_K_M | 2.7 GB | **63 %** in the app on the phone (60 % on the PC; graded side by side, the phone loses nothing) | in the app: 12.7 tok/s reading, 3.2 tok/s writing; first words after 29 s, whole answer 110 s (medians, 40 questions) |
| 12 GB | Qwen3.5-9B Q4_K_M | 5.7 GB | 59 % (PC harness; a tie with the 4B head-to-head) | not measured: no 12 GB test phone |

¹ Graded head-to-head against the 4B Q4_K_M: −0.38 points out of 10 (95 % CI −0.80 to +0.03).
The 8 GB row is the app itself on the phone; the other speeds are llama-bench on the phone's CPU,
and a warm phone under load runs slower (the clocks drop).

Storage: model + knowledge pack (18.7 GB) + places pack = about 27 GB, under the bounty's 50 GB limit.

## How it works

1. **Route:** is this a research question, a place search, or conversation?
2. **Retrieve:** full-text search over 34 M Wikipedia sections (SQLite FTS5 with a BM25
   ranker that works on a compact index, blended with article popularity from pageviews).
   Articles named in the question lead with their intro: exact titles, 2.3 M alternative names
   from Wikipedia redirects ("GPS", "WW2"), 40 k acronyms mined from article intros for the ones
   Wikipedia only has as disambiguation pages ("TCP", "BMI"; the question picks the meaning), and
   list articles for list questions, read as their most viewed entries ("must-see museums in
   Berlin" → Pergamon Museum, …) or in rank order ("tallest buildings in Dubai"). Albums,
   songs and films that merely share the question's words are left out unless the question is
   about one ("why does ice float" is not about the jazz album *Floating Ice*). Travel questions
   about a named place also read its own article: "getting around Istanbul" → *Public transport in
   Istanbul*, "weather in Iceland" → *Climate of Iceland*, "dishes in Peru" → *Peruvian cuisine*.
3. **Compress:** each source is cut down to the sentences that match the question.
4. **Answer:** llama.cpp on the phone's CPU. The fixed instructions are evaluated once and
   reused from a cache, so only the question and excerpts are read each time.

Place searches parse the kind of place and the location, geocode it (Wikipedia coordinates
for 1.2 M articles, from the geo_tags dump, then town centroids), and pass the nearest matches with distances to the model.
When the "place" isn't on the map ("the deepest lake in the world"), the question goes to
Wikipedia instead.
"Near X" and "day trips from X" questions also get the most viewed Wikipedia articles within
20 km (day trips: 15–80 km) of X, from an R-tree over those coordinates.

## Evaluation

**Method.** 40 held-out questions across facts, explanations, comparisons, synthesis, reasoning,
travel, health, tech, history and places (`eval/questions-holdout.jsonl`), written without looking
at how the pipeline handles them and never used for tuning. Each answer is graded blind against an
answer from a frontier model with live web search, 1–10 for correctness, completeness and
usefulness, and reported as a share of the frontier answer's score. A separate set of 60 questions
(`eval/questions.jsonl`) is used for development. Details and how to reproduce: [docs/EVAL.md](docs/EVAL.md).

**Results on the held-out questions** (one blind grading run each):

| System | Where it ran | % of frontier + web |
|---|---|---|
| Bivouac, Qwen3.5-4B | in the app on a Redmi Note 15 Pro (8 GB, MediaTek MT6789) | **63.3 %** |
| Bivouac, Qwen3.5-4B | PC harness (same pipeline, prompt and sampling as the app) | 60.4 % |
| Bivouac, Qwen3.5-9B | PC harness | 59.4 % |
| Bivouac, Qwen3.5-2B | PC harness | 43.0 % |
| Llama 3.2 1B on its own, no sources | PC | 41.5 % |

Regrading the same answers moves a share by about ±3 points, so comparisons are made head-to-head,
with both answers in one blind packet (`tools/grade.py pair`). Graded that way, the phone and the PC
give the same quality (phone −0.15 points out of 10, 95 % CI −0.75 to +0.45; better on 11 questions,
worse on 13, 16 ties), and the 4B and the 9B are a tie (9B −0.10, CI −0.70 to +0.50; the 9B is better
at reasoning, the 4B slightly better on facts). The 2B, for 4 GB phones, is below the bounty's 50 % bar.

**On the phone:** median 110 s per answer (search 5.7 s, first words after 29 s; prompt reading
12.7 tokens/s, writing 3.2 tokens/s), longest 267 s; the app answered all 40 without being killed.
Strongest categories: explanations (75 %) and history (72 %); weakest: travel (47 %) and reasoning
(48 %). The phone run was made on 2026-10-19; each change since (travel topic articles, the Ethereum,
NIST and Wikivoyage material) was graded head-to-head on the questions it affects and was neutral
or better.

**A 1B model on its own**, the comparison the bounty asks the demo to show: Llama 3.2 1B Instruct
(Q4_K_M, greedy, no sources). Graded side by side with Bivouac's answers from the phone, Bivouac scores
2.8 points out of 10 higher (95 % CI +1.95 to +3.65; better on 34 of 40 questions, worse on 5). The 1B
never matched the frontier answer, and its errors are confident: EIP-7702 "lets an EOA claim a reward
for deploying an event", Brazil uses "Type A and Type B plugs, 127V", vegetarian restaurants in Chiang
Mai include a Bangkok shopping mall (`tools/model_alone.py`).

**A shared question set:** Boar's 61 Vitalik-style questions (vegan restaurants, Ethereum and
post-quantum cryptography, travel, emergencies, travel arithmetic; MIT), graded against the frontier +
web reference answers published in the AndroidLM repository. The 4B, as it runs on the 8 GB phone,
reaches **51 %**. The Ethereum, NIST and Wikivoyage material in the pack accounts for +1.13 points out
of 10 there (graded with and without it; 95 % CI +0.66 to +1.60, better on 35 questions, worse on 8).

**Retrieval:** a correct Wikipedia article is among the 6 sources for 92 % of the development
questions that have one (44/48) and 94 % of the held-out ones (34/36).

Why these models and not Gemma-4-12B, a Q5 9B or the fast MoE models, and how each pipeline change
was measured: [docs/EVAL.md](docs/EVAL.md#model-choice).

Files: phone `eval/results-redmi-qwen3.5-4b-v32-holdout.jsonl`; PC `eval/results-pc-qwen3.5-{4b,9b}-v32-holdout-thorough.jsonl`,
`eval/results-pc-qwen3.5-2b-v32-holdout-thorough.jsonl`; 1B `eval/results-pc-llama3.2-1b-alone-holdout.jsonl`;
shared set `eval/results-pc-qwen3.5-4b-v36-boar61.jsonl`. Blind packets, answer keys and grades for each
are in `eval/grading/` under the same names.

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
- Travel guides: [Wikivoyage](https://en.wikivoyage.org) (English, 2026-10 dump), CC BY-SA 4.0.
- Ethereum: [EIPs](https://github.com/ethereum/EIPs) and [ERCs](https://github.com/ethereum/ERCs) (CC0),
  [ethereum.org](https://github.com/ethereum/ethereum-org-website) docs (MIT),
  [Upgrading Ethereum](https://github.com/benjaminion/upgrading-ethereum-book) by Ben Edgington (CC BY-SA 4.0).
- Post-quantum: NIST FIPS 203, 204, 205, SP 800-208, IR 8547 (public domain).
  Built by `tools/build_supplements.py` and `tools/append_supplements.py`.
- Places: [Overture Maps](https://overturemaps.org) places theme (2026-09), CDLA-Permissive-2.0.
- Models: Qwen3.5 (Apache-2.0) as GGUF files.
- Engine: [llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT), SQLite (public domain),
  zstd (BSD).
- Font: [Inter](https://github.com/rsms/inter), SIL Open Font License 1.1
  ([third_party/fonts/Inter-OFL.txt](third_party/fonts/Inter-OFL.txt)).

App code: [MIT](LICENSE). The data packs and models keep their own licenses (above).
