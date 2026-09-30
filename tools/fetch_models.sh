#!/usr/bin/env bash
# Candidate models for the quality comparison (tools/answer_proto.py), into ~/models.
# Resumable; re-run to finish interrupted downloads.
set -uo pipefail
mkdir -p "$HOME/models"
cd "$HOME/models"
HF=https://huggingface.co
for f in \
    unsloth/Qwen3.5-4B-GGUF/Qwen3.5-4B-Q4_K_M.gguf \
    LiquidAI/LFM2.5-8B-A1B-GGUF/LFM2.5-8B-A1B-Q4_K_M.gguf \
    unsloth/gemma-4-E4B-it-GGUF/gemma-4-E4B-it-Q4_K_M.gguf \
    unsloth/Qwen3.5-9B-GGUF/Qwen3.5-9B-Q4_K_M.gguf \
    unsloth/gemma-4-26B-A4B-it-GGUF/gemma-4-26B-A4B-it-UD-Q2_K_XL.gguf \
    unsloth/Qwen3.6-35B-A3B-GGUF/Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf
do
    repo=${f%/*}
    name=${f##*/}
    echo "== $name"
    curl -sSL --retry 10 --retry-all-errors -C - -o "$name" "$HF/$repo/resolve/main/$name" && echo "ok $name"
done
