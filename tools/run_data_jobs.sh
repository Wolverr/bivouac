#!/usr/bin/env bash
# Downloads the source data and builds both knowledge packs, as two background
# jobs that survive closing the terminal:
#   bivouac-wiki:   FineWiki (~13 GB) + pageviews (5.6 GB) -> out/wiki.db   (~25 GB)
#   bivouac-places: Overture Maps places                    -> out/places.db
# Every step skips work that's already done, so it's safe to re-run.
#
# Usage: tools/run_data_jobs.sh [wiki|places|all]      Progress: tools/status.sh
# Memory caps (useful on small machines): WIKI_MEM=3G PLACES_MEM=2500M tools/run_data_jobs.sh
set -euo pipefail
cd "$(dirname "$0")"
what="${1:-all}"
PY=.venv/bin/python

wiki_cmd="$PY fetch_finewiki.py --out data/finewiki_en > fetch_finewiki.log 2>&1 \
 && { [ -s data/pageviews_en.tsv ] || ./fetch_pageviews.sh 2025-08 > data/pageviews_en.tsv 2> pageviews.log; } \
 && $PY build_corpus.py --src data/finewiki_en --out out/wiki.db --pageviews data/pageviews_en.tsv \
        --full-top 2000000 --fts-detail column > build_full.log 2>&1"
places_cmd="{ [ -s data/places.parquet ] && grep -q ' places in ' places_fetch.log 2>/dev/null || $PY fetch_places.py > places_fetch.log 2>&1; } \
 && $PY build_places.py --src data/places.parquet --out out/places.db > build_places.log 2>&1"

start() {  # name, memory cap, command
    if command -v systemd-run >/dev/null && systemctl --user show-environment >/dev/null 2>&1; then
        if systemctl --user is-active --quiet "$1"; then
            echo "$1 is already running"
            return
        fi
        # A failed or finished run leaves the unit name taken until it's reset.
        systemctl --user reset-failed "$1" 2>/dev/null || true
        systemctl --user stop "$1" 2>/dev/null || true
        local caps=()
        [ -n "$2" ] && caps=(-p "MemoryMax=$2" -p MemorySwapMax=1G)
        systemd-run --user --unit="$1" --working-directory="$PWD" --collect "${caps[@]}" bash -c "$3"
    else
        # No systemd (e.g. older WSL): detach as well as we can.
        setsid nohup bash -c "$3" > "$1.out" 2>&1 < /dev/null &
        echo "started $1 (pid $!)"
    fi
}

mkdir -p data out
[[ "$what" == wiki || "$what" == all ]] && start bivouac-wiki "${WIKI_MEM:-}" "$wiki_cmd"
[[ "$what" == places || "$what" == all ]] && start bivouac-places "${PLACES_MEM:-}" "$places_cmd"
echo "Check progress with tools/status.sh"
