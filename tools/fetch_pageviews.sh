#!/usr/bin/env bash
# Monthly English Wikipedia pageviews (human traffic), one "page_id<TAB>views" line
# per access type (desktop, mobile-web, mobile-app); build_corpus.py sums them.
# Streams with O(1) memory: keeping every title in awk took >8 GB and got the
# terminal OOM-killed. Used as the popularity prior when
# ranking search results and when choosing which articles get full text.
#
# Downloads the ~5.6 GB all-wikis dump (resumable; Wikimedia resets long
# connections), then keeps only the English lines.
# Usage: tools/fetch_pageviews.sh [YYYY-MM] > data/pageviews_en.tsv
set -euo pipefail
month="${1:-2025-08}"
ym="${month/-/}"
url="https://dumps.wikimedia.org/other/pageview_complete/monthly/${month:0:4}/${month}/pageviews-${ym}-user.bz2"

dest="$(dirname "$0")/data/$(basename "$url")"
mkdir -p "$(dirname "$dest")"
remote_size=$(curl -sSLI "$url" | tr -d '\r' | awk 'tolower($1) == "content-length:" { n = $2 } END { print n }')
local_size=$(stat -c %s "$dest" 2>/dev/null || echo 0)
# A complete file makes `curl -C -` fail with "range not satisfiable", so skip it.
[ "$local_size" = "$remote_size" ] || until curl -sSL --retry 10 --retry-all-errors -C - -o "$dest" "$url"; do
    echo "download interrupted, resuming…" >&2
    sleep 5
done

# awk stops reading after the English block, so bzip2 then fails with "broken
# pipe"; that's expected. Success is judged by awk having seen the whole block.
set +o pipefail
bzip2 -dc "$dest" | awk '
    $1 == "en.wikipedia" { seen = 1; if ($3 != "null") print $3 "\t" $5; next }
    seen { done = 1; exit }   # the dump is sorted by wiki; stop after the English block
    END { if (!done) exit 3 }  # no English block, or the file ended inside it
'
