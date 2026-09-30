#!/usr/bin/env bash
# LFM2.5's chat template ignores enable_thinking=false: it reasons until the token
# limit and the answer comes back empty. The server-side reasoning budget turns it off.
# Resumable like run_model_eval.sh. Progress: tail tools/lfm_eval.log
set -uo pipefail
cd "$(dirname "$0")"
"$HOME/llama-pc/bin/llama-server" -m "$HOME/models/LFM2.5-8B-A1B-Q4_K_M.gguf" -c 8192 -t 16 --port 8088 --jinja \
    --reasoning-budget 0 > server-lfm2.5-8b-a1b.log 2>&1 &
pid=$!
for _ in $(seq 120); do curl -s localhost:8088/health | grep -q '"ok"' && break; sleep 1; done
.venv/bin/python answer_proto.py --db out/wiki.db --name lfm2.5-8b-a1b
kill $pid
wait $pid 2>/dev/null
