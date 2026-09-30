#!/usr/bin/env bash
# Monthly English Wikipedia pageviews (human traffic), summed over desktop and
# mobile, as "page_id<TAB>views<TAB>title". Used as the popularity prior when
# ranking search results and when choosing which articles get full text.
#
# Streams the ~5.6 GB all-wikis dump; only the English lines are kept.
# Usage: tools/fetch_pageviews.sh [YYYY-MM] > data/pageviews_en.tsv
set -euo pipefail
month="${1:-2025-08}"
ym="${month/-/}"
url="https://dumps.wikimedia.org/other/pageview_complete/monthly/${month:0:4}/${month}/pageviews-${ym}-user.bz2"

curl -sSL "$url" | bzip2 -dc | awk '
    $1 == "en.wikipedia" { seen = 1; if ($3 != "null") { views[$3] += $5; title[$3] = $2 } next }
    seen { exit }   # the dump is sorted by wiki; stop after the English block
    END { for (id in views) printf "%s\t%d\t%s\n", id, views[id], title[id] }
'
