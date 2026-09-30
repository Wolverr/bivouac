#!/usr/bin/env bash
# Status of the long-running data jobs. They run as memory-capped systemd user
# services (bivouac-wiki, bivouac-places), independent of any terminal.
# Start them as in docs/ROADMAP.md "Dev notes". Usage: tools/status.sh
cd "$(dirname "$0")"
size() { [ -f "$1" ] && du -h "$1" | cut -f1 || echo "-"; }
state() {
    local s mem
    s=$(systemctl --user is-active "$1" 2>/dev/null)
    mem=$(systemctl --user show "$1" -p MemoryCurrent --value 2>/dev/null)
    [[ "$mem" =~ ^[0-9]+$ ]] && s="$s, using $((mem / 1000000)) MB RAM"
    echo "$s"
}

echo "Wikipedia job:  $(state bivouac-wiki)"
echo "  pageviews:    $(wc -l < data/pageviews_en.tsv 2>/dev/null || echo 0) lines"
echo "  wiki.db:      $(size out/wiki.db)   log: $(tail -1 build_full.log 2>/dev/null)"
echo "Places job:     $(state bivouac-places)"
echo "  download:     $(size data/places.parquet)   log: $(tail -1 places_fetch.log 2>/dev/null)"
echo "  places.db:    $(size out/places.db)   log: $(tail -1 build_places.log 2>/dev/null)"
