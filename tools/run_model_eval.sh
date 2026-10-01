#!/usr/bin/env bash
# Answers all eval questions with models from ~/models, one llama-server at a
# time (smallest first, so the biggest models run last). Resumable: answer_proto.py
# skips questions already in eval/results-pc-<name><SUFFIX>.jsonl.
#
#   ONLY="qwen3.5-4b qwen3.5-9b"   only these models (names as below)
#   SUFFIX=-v2                     separate results files, e.g. after a pipeline change
#   SERVER_ARGS="--reasoning-budget 0"   extra llama-server flags (needed for LFM2.5)
#   ANSWER_ARGS="--think --only reason-01,reason-02"   extra answer_proto.py flags
#
# Run as a memory-capped service (see docs/ROADMAP.md "Dev notes"):
#   systemd-run --user --unit=bivouac-models --working-directory=$PWD/tools -p MemoryMax=12G \
#     -p MemorySwapMax=1G --collect bash -c './run_model_eval.sh > model_eval.log 2>&1'
# Models over ~6 GB swap on a 16 GB PC and don't fit the phones anyway.
# Progress: tail tools/model_eval.log
set -uo pipefail
cd "$(dirname "$0")"
MODELS="${MODELS:-$HOME/models}"
ONLY="${ONLY:-}"
SUFFIX="${SUFFIX:-}"
SERVER_ARGS="${SERVER_ARGS:-}"
ANSWER_ARGS="${ANSWER_ARGS:-}"
SERVER="$HOME/llama-pc/bin/llama-server"
PORT=8088

for gguf in $(ls -S -r "$MODELS"/*.gguf); do
    name=$(basename "$gguf" .gguf | tr 'A-Z' 'a-z' | sed 's/-q[0-9].*//; s/-ud$//')
    if [ -n "$ONLY" ] && [[ " $ONLY " != *" $name "* ]]; then continue; fi
    echo "=== $name$SUFFIX ($(du -h "$gguf" | cut -f1)) $(date +%T)"
    # --cache-ram 0: the host prompt cache (default 8 GB) pushes models over ~6.5 GB past the 12 GB cap;
    # every question has a new prompt, so it saves nothing.
    # shellcheck disable=SC2086
    "$SERVER" -m "$gguf" -c 8192 -t 16 --port $PORT --jinja --cache-ram 0 $SERVER_ARGS > "server-$name.log" 2>&1 &
    pid=$!
    ok=0
    for _ in $(seq 300); do
        if curl -s "localhost:$PORT/health" | grep -q '"ok"'; then ok=1; break; fi
        kill -0 $pid 2>/dev/null || break
        sleep 1
    done
    if [ $ok = 1 ]; then
        .venv/bin/python answer_proto.py --db out/wiki.db --name "$name$SUFFIX" --model-id "$name" --port $PORT $ANSWER_ARGS || echo "!!! $name: answer_proto failed"
    else
        echo "!!! $name: server did not start (see server-$name.log)"
    fi
    kill $pid 2>/dev/null
    wait $pid 2>/dev/null
done
echo "=== all done $(date +%T)"
