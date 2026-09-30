#!/usr/bin/env bash
# Answers all eval questions with every model in ~/models, one llama-server at a
# time (smallest first, so the biggest models run last). Resumable: answer_proto.py
# skips questions already in eval/results-pc-<name>.jsonl.
#
# Run as a memory-capped service (see docs/ROADMAP.md "Dev notes"):
#   systemd-run --user --unit=bivouac-models --working-directory=$PWD/tools -p MemoryMax=14G \
#     -p MemorySwapMax=1G --collect bash -c './run_model_eval.sh > model_eval.log 2>&1'
# Progress: tail tools/model_eval.log
set -uo pipefail
cd "$(dirname "$0")"
MODELS="${MODELS:-$HOME/models}"
SERVER="$HOME/llama-pc/bin/llama-server"
PORT=8088

for gguf in $(ls -S -r "$MODELS"/*.gguf); do
    name=$(basename "$gguf" .gguf | tr 'A-Z' 'a-z' | sed 's/-q[0-9].*//; s/-ud$//')
    echo "=== $name ($(du -h "$gguf" | cut -f1)) $(date +%T)"
    "$SERVER" -m "$gguf" -c 8192 -t 16 --port $PORT --jinja > "server-$name.log" 2>&1 &
    pid=$!
    ok=0
    for _ in $(seq 300); do
        if curl -s "localhost:$PORT/health" | grep -q '"ok"'; then ok=1; break; fi
        kill -0 $pid 2>/dev/null || break
        sleep 1
    done
    if [ $ok = 1 ]; then
        .venv/bin/python answer_proto.py --db out/wiki.db --name "$name" --port $PORT || echo "!!! $name: answer_proto failed"
    else
        echo "!!! $name: server did not start (see server-$name.log)"
    fi
    kill $pid 2>/dev/null
    wait $pid 2>/dev/null
done
echo "=== all done $(date +%T)"
