#!/usr/bin/env bash
# Shows what the long-running data jobs are doing. Usage: tools/status.sh
cd "$(dirname "$0")"
running() { pgrep -f "$1" >/dev/null && echo "running" || echo "not running"; }
size() { [ -f "$1" ] && du -h "$1" | cut -f1 || echo "-"; }

echo "1. Pageviews:       $(running 'bzip2 -dc ./data/pageviews')  (pages so far: $(wc -l < data/pageviews_en.tsv 2>/dev/null || echo 0))"
echo "   Wikipedia pack:  $(running build_corpus.py)  (out/wiki.db: $(size out/wiki.db))"
echo "   last log line:   $(tail -1 build_full.log 2>/dev/null)"
echo "2. Places download: $(running fetch_places.py)  (data/places.parquet: $(size data/places.parquet))"
echo "3. Places pack:     $(running build_places.py)  (out/places.db: $(size out/places.db))"
echo "   last log line:   $(tail -1 build_places.log 2>/dev/null)"
