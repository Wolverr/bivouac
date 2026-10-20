# Evaluation

Goal of the bounty: an offline tool that's ">50% as good as internet search + frontier AI".
We measure that directly:

1. **Questions:** `eval/questions.jsonl` — 60 questions across facts, explanation, comparison,
   synthesis, reasoning, travel, health, tech, science, history and places. 48 have "gold"
   Wikipedia articles for measuring retrieval (`tools/eval_retrieval.py`).
2. **Baseline:** `eval/baseline_frontier_web.jsonl` — answers written by a frontier model
   using live web search (with sources).
3. **Bivouac:** answers produced on a real phone, fully offline, with the app's eval mode, or by the
   desktop harness below (same pipeline and prompt).
4. **Grading:** blind pairwise comparison per question (grader doesn't know which is which),
   scored for correctness, completeness and usefulness; results published with the answers.

## Running the eval on a phone

The model file must already be in the Library, packs are opened automatically.

Open the app once after installing it **before** pushing files: it creates its `models/`, `packs/` and
`eval/` folders itself. Folders made by `adb shell mkdir` (or by `adb push` into a missing folder) belong
to the shell user, the app can't list them, and the eval stops with "model … not found". To repair without
copying again: rename the folders aside, open the app, `mv` the files into the new folders.

For big copies on Windows + WSL, use Windows' own adb (platform-tools for Windows): through usbipd the
link ran at ~3 MB/s, natively at ~45 MB/s.

```bash
PKG=io.github.wolverr.bivouac
adb shell mkdir -p /sdcard/Android/data/$PKG/files/eval
adb push eval/questions.jsonl /sdcard/Android/data/$PKG/files/eval/
adb shell am start -n $PKG/.ui.MainActivity --es eval questions.jsonl --es model Qwen3.5-2B-Q4_K_M.gguf
adb logcat -s bivouac-eval          # progress: "eval 12/60 fact-06: 41234 ms, 212 tok, route=lookup"
adb pull /sdcard/Android/data/$PKG/files/eval/results-Qwen3.5-2B-Q4_K_M.jsonl eval/
```

Each result line has the answer, the sources and places shown, the route taken, total
wall-clock time and the engine's prefill/generation stats. Re-running resumes: questions
already in the results file are skipped. The screen stays on while the eval runs.

## Held-out questions

`eval/questions-holdout.jsonl` has 40 more questions in the same categories, written without
looking at how the pipeline handles them and never used for tuning, with their own baseline
(`eval/baseline_holdout_frontier_web.jsonl`). Pipeline fixes are found on the main set; the
held-out set checks that they generalize, and gives the numbers we publish.

## Desktop harness and grading

`tools/answer_proto.py` runs the app pipeline (same retrieval, prompt and sampling) against a
llama-server on the PC; `tools/run_model_eval.sh` runs it for each model in `~/models`.
Sampling uses a fixed seed, so an unchanged prompt gives an unchanged answer.

```bash
tools/.venv/bin/python tools/grade.py packet eval/results-pc-qwen3.5-4b.jsonl   # blind packet vs baseline
# a grader fills eval/grading/<name>.scores.jsonl (eval/grading/GRADER_INSTRUCTIONS.txt + the packet only)
tools/.venv/bin/python tools/grade.py score  eval/results-pc-qwen3.5-4b.jsonl   # % of the baseline
```

A single run is noisy: regrading identical answers moves the score by about ±3 points, and
unseeded reruns move it as much as most pipeline changes. To compare two versions of the
pipeline, grade their answers against each other in one blind packet:

```bash
tools/.venv/bin/python tools/grade.py pair      eval/results-OLD.jsonl eval/results-NEW.jsonl
tools/.venv/bin/python tools/grade.py pairscore eval/results-OLD.jsonl eval/results-NEW.jsonl
```

`pairscore` reports the mean difference with a 95 % confidence interval and a sign test;
questions with identical answers are counted as ties without being graded.

## Where the published numbers come from

Every answer, blind packet, answer key (`*.key.json`) and grader score is in `eval/` and
`eval/grading/`, so each number in the README can be checked or regraded:

| README number | Answers | Scores |
|---|---|---|
| 4B on the phone, held-out (63.3 %) | `results-redmi-qwen3.5-4b-v32-holdout.jsonl` | `grading/redmi-qwen3.5-4b-v32-holdout.summary.json` |
| Phone vs PC, same build (−0.15) | `results-{redmi,pc}-qwen3.5-4b-v32-holdout*.jsonl` | `grading/pair-pc-qwen3.5-4b-v32-holdout-thorough--redmi-qwen3.5-4b-v32-holdout.summary.json` |
| 4B / 9B / 2B on the PC, held-out | `results-pc-qwen3.5-{4b,9b,2b}-v32-holdout-thorough.jsonl` | `grading/pc-qwen3.5-{4b,9b,2b}-v32-holdout-thorough.summary.json` |
| 4B vs 9B (tie) | as above | `grading/pair-pc-qwen3.5-4b-v32-holdout-thorough--pc-qwen3.5-9b-v32-holdout-thorough.*` |
| 1B on its own (41.5 %; +2.8 for Bivouac head-to-head) | `results-pc-llama3.2-1b-alone-holdout.jsonl` | `grading/pc-llama3.2-1b-alone-holdout.summary.json`, `grading/pair-pc-llama3.2-1b-alone-holdout--redmi-qwen3.5-4b-v32-holdout.summary.json` |
| Boar's 61 questions (51 %) | `results-pc-qwen3.5-4b-v36-boar61.jsonl` | `grading/pc-qwen3.5-4b-v36-boar61.summary.json` |
| 6 GB tier (Q3_K_M vs Q4_K_M) | `results-pc-qwen3.5-4b-v6-{Q4_K_M,Q3_K_M}.jsonl` | `grading/pair-pc-qwen3.5-4b-v6-Q4_K_M--pc-qwen3.5-4b-v6-Q3_K_M.summary.json` |
| 12 GB: Gemma-4-12B vs 9B | `results-pc-{qwen3.5-9b,gemma-4-12b-it-qat}-v13.jsonl` | `grading/pair-pc-qwen3.5-9b-v13--pc-gemma-4-12b-it-qat-v13.summary.json` |
| 12 GB: 9B Q5 vs Q4 | `results-pc-qwen3.5-9b-{v13,v14-q5}.jsonl` | `grading/pair-pc-qwen3.5-9b-v13--pc-qwen3.5-9b-v14-q5.summary.json` |
| Think harder, reasoning questions | `results-pc-qwen3.5-{4b,9b}-v4-think1024.jsonl` | `grading/pair-pc-qwen3.5-{4b,9b}-v4--…-think1024.summary.json` |
| Think harder, all 60 questions (9B) | `results-pc-qwen3.5-9b-{v13,v15-think1024}.jsonl` | `grading/pair-pc-qwen3.5-9b-v13--pc-qwen3.5-9b-v15-think1024.summary.json` |

Pair gradings without a summary file are scored with `tools/grade.py pairscore`.

## Model choice

- **12 GB phones:** Qwen3.5-9B Q4_K_M. Gemma-4-12B (QAT) scored 1.73 points out of 10 below it, head-to-head
  (95 % CI −2.43 to −1.04), and needs more RAM; the 9B at Q5 instead of Q4 was no better (+0.02, CI −0.31 to
  +0.34) and 15 % slower. Against the 4B, head-to-head on the held-out set: a tie on the current build (9B −0.10, 95 % CI −0.70 to +0.50; better on 14 questions, worse on 18: better at reasoning, slightly worse on facts), and a tie on the build before (−0.28, CI −1.13 to +0.58). Files: `grading/pair-pc-qwen3.5-4b-v32-holdout-thorough--pc-qwen3.5-9b-v32-holdout-thorough.*`.
- **6 GB phones:** Qwen3.5-4B Q3_K_M, −0.38 points out of 10 against the Q4_K_M (95 % CI −0.80 to +0.03).
- **Screened out** (main set, an earlier pipeline): LFM2.5-8B-A1B 46 %, Gemma-4-E4B 44 %, both below the
  Qwen3.5-4B of the same run (55 %). Fast mixture-of-experts models (Ling-3.0-tiny, LFM2.5-8B-A1B) were
  quicker on the phone but clearly worse than the 4B.

## How the pipeline was tuned

Every change below was graded head-to-head against the version before it, in one blind packet.

| Build | 9B, held-out | 4B, held-out | 2B, held-out | 4B, main set |
|---|---|---|---|---|
| 2026-10-02 | 62.7 % | 54.0 % | 45.2 % | 60.2 % |
| 2026-10-19, "be concise" prompt | 62.0 % | 66.5 % | 44.5 % | — |
| current, "be thorough but focused" prompt | 59.4 % | 60.4 % (63.3 % on the phone) | 43.0 % | — |

Absolute shares move with the grading run (the same 4B answers scored 57.0 % to 66.5 % across three
gradings), so the head-to-head results are the ones to go by:

- **No-sources fixes (2026-10-02 → 10-19):** "what first aid should I give?" and "when should I see a doctor?"
  had been taken for personal chat, "the deepest lake in the world" and "RAM and storage in a phone" for map
  searches; together with retrieval changes, most of the 4B's gain.
- **Prompt "be thorough but focused" instead of "be concise" (2026-10-19):** held-out 4B +0.75 points out of
  10 (95 % CI +0.24 to +1.26, better on 27 questions, worse on 6), 9B +0.80 (CI +0.34 to +1.26, 24 vs 4), 2B
  +0.33 (CI −0.27 to +0.92, 22 vs 16); a second grader model on the 4B pair agreed (+0.55, 22 vs 8). Main set:
  4B +0.62, 9B +0.65. Answers are about 50 % longer (4B: 750 → 1,130 characters), roughly 25–30 s more per
  answer on a phone. Files: `grading/pair-pc-*-v29-holdout--pc-*-v32-holdout-thorough.*`.
- **Travel topic articles (2026-10-19):** reading the place's transport, climate or cuisine article: +1.1 points
  out of 10 on the 10 travel questions it changes (three paired rounds); a rule that dropped everyday words
  like "train" as sources lost 0.3 and was left out. Files: `results-pc-qwen3.5-4b-v33-*`, `grading/pair-pc-qwen3.5-4b-v33-*`.
- **Ethereum, NIST and Wikivoyage material (2026-10-19):** on Boar's 61 questions +1.13 points out of 10 (95 % CI
  +0.66 to +1.60, better on 35, worse on 8; Ethereum +1.25, restaurants +1.6, travel +0.9), 50 % → 51 % of the
  reference; neutral on our own questions (−0.19 on the 43 tuning questions whose prompt changes, held-out
  about even). Files: `results-pc-qwen3.5-4b-v3{5,6}-boar61.jsonl`, `grading/*boar61*`, `*v37*`.
- **Phone runs:** the 2026-10-07 build scored 53.9 % on the held-out set in the app
  (`results-redmi-qwen3.5-4b-holdout.jsonl`), the 2026-10-19 build 63.3 %.
- **Tried and not kept:** 256 thinking tokens for calculations on slow phones (−1.2 on 6 questions: cut-off
  reasoning spilled into the answer); Wikipedia sub-articles like *Climate of X* for every place (−0.47);
  dropping search hits that don't name the travel guide's place (+0.43, CI −0.52 to +1.38: too noisy, branch
  `guide-gate`).
- **Prompt caching off in the harness:** with the server reusing the previous prompt's prefix, the same seeded
  question gave different answers depending on the question before it, so every eval request sends
  `cache_prompt: false`.

## Other entries on the same questions

Boar's 61 questions come with frontier + web reference answers and the answers of AndroidLM
(Qwen3.6-35B-A3B on a 12 GB Pixel 8 Pro), published in the AndroidLM repository. Head-to-head against those
answers, Bivouac's 4B (8 GB phone setup) is behind: −2.59 points out of 10 (95 % CI −3.23 to −1.95), −3.38
before the Ethereum, NIST and Wikivoyage material. Files: `results-androidlm-121-boar61.jsonl`,
`grading/pair-androidlm-121-boar61--pc-qwen3.5-4b-v3{5,6}-boar61.*`.
