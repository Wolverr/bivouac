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
