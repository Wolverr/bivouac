# Evaluation

Goal of the bounty: an offline tool that's ">50% as good as internet search + frontier AI".
We measure that directly:

1. **Questions:** `eval/questions.jsonl` — 60 questions across facts, explanation, comparison,
   synthesis, reasoning, travel, health, tech, science, history and places. 48 have "gold"
   Wikipedia articles for measuring retrieval (`tools/eval_retrieval.py`).
2. **Baseline:** `eval/baseline_frontier_web.jsonl` — answers written by a frontier model
   using live web search (with sources).
3. **Bivouac:** answers produced on a real phone, fully offline, with the app's eval mode.
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
| 9B / 4B / 2B, held-out | `results-pc-qwen3.5-{9b-v4,4b-v4,2b-v5}-holdout.jsonl` | `grading/pc-…-holdout.summary.json` |
| 9B / 4B / 2B, main set | `results-pc-qwen3.5-{9b-v4,4b-v4,2b-v5}.jsonl` | `grading/pc-….summary.json` |
| 6 GB tier (Q3_K_M vs Q4_K_M) | `results-pc-qwen3.5-4b-v6-{Q4_K_M,Q3_K_M}.jsonl` | `grading/pair-pc-qwen3.5-4b-v6-Q4_K_M--pc-qwen3.5-4b-v6-Q3_K_M.summary.json` |
| 12 GB: Gemma-4-12B vs 9B | `results-pc-{qwen3.5-9b,gemma-4-12b-it-qat}-v13.jsonl` | `grading/pair-pc-qwen3.5-9b-v13--pc-gemma-4-12b-it-qat-v13.summary.json` |
| 12 GB: 9B Q5 vs Q4 | `results-pc-qwen3.5-9b-{v13,v14-q5}.jsonl` | `grading/pair-pc-qwen3.5-9b-v13--pc-qwen3.5-9b-v14-q5.summary.json` |
| Think harder, reasoning questions | `results-pc-qwen3.5-{4b,9b}-v4-think1024.jsonl` | `grading/pair-pc-qwen3.5-{4b,9b}-v4--…-think1024.summary.json` |
| Think harder, all 60 questions (9B) | `results-pc-qwen3.5-9b-{v13,v15-think1024}.jsonl` | `grading/pair-pc-qwen3.5-9b-v13--pc-qwen3.5-9b-v15-think1024.summary.json` |

Pair gradings without a summary file are scored with `tools/grade.py pairscore`.
