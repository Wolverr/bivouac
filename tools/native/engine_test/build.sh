#!/usr/bin/env bash
# Builds engine_test: the app's engine.cpp on the desktop, against the PC build of the same
# llama.cpp (~/llama-pc, from third_party/llama.cpp). Run: build/engine_test model.gguf [budget]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
llama="$root/third_party/llama.cpp"
pc="${LLAMA_PC:-$HOME/llama-pc}"
mkdir -p "$here/build"
g++ -std=c++17 -O2 -o "$here/build/engine_test" \
    -DLLAMA_PC_BIN="\"$pc/bin\"" -DBIVOUAC_ROOT="\"$root\"" \
    -I"$here" -I"$root/app/src/main/cpp" -I"$llama/include" -I"$llama/common" -I"$llama/ggml/include" \
    "$here/engine_test.cpp" "$root/app/src/main/cpp/engine.cpp" \
    -L"$pc/bin" -lllama-common -lllama -lggml -lggml-base -Wl,-rpath,"$pc/bin" -lpthread
echo "built $here/build/engine_test"
