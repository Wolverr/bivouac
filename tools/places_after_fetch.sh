#!/usr/bin/env bash
# Waits for fetch_places.py, then builds out/places.db, but only if the
# download finished (a broken download leaves a partial parquet file).
cd "$(dirname "$0")"
while pgrep -f "^.venv/bin/python fetch_places.py" >/dev/null; do sleep 30; done
if grep -q " places in " places_fetch.log; then
    .venv/bin/python build_places.py --src data/places.parquet --out out/places.db
else
    echo "places download did not finish (see places_fetch.log); rerun: .venv/bin/python fetch_places.py"
fi
