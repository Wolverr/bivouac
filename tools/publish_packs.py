"""Uploads the built packs to a HuggingFace dataset repo and points the app's
catalog (app/src/main/assets/catalog.json) at them.

  1. Make a HuggingFace account and a write token (Settings -> Access Tokens).
  2. tools/.venv/bin/pip install huggingface_hub     (once)
  3. tools/.venv/bin/hf auth login                   (paste the token)
  4. tools/.venv/bin/python tools/publish_packs.py <user>/bivouac-packs

Uploads resume if interrupted (run the same command again). wiki.db is ~18 GB and
places.db ~5 GB, so this takes a while on a home connection.
--dry-run shows what would happen without uploading.
"""

import argparse
import json
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "tools" / "out"
CATALOG = ROOT / "app" / "src" / "main" / "assets" / "catalog.json"
PACKS = {"wiki-en": "wiki.db", "places-world": "places.db"}


def meta(db: Path) -> dict:
    con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    try:
        return dict(con.execute("SELECT key, value FROM meta").fetchall())
    finally:
        con.close()


def card(repo: str, wiki: dict, places: dict) -> str:
    return f"""---
license: other
pretty_name: Bivouac offline packs
---

# Bivouac offline packs

Data packs for [Bivouac](https://github.com/Wolverr/bivouac), an offline research assistant
for Android. Download them on the phone, then add them in the app's Library.

| File | Contents | License |
|---|---|---|
| `wiki.db` | English Wikipedia, {wiki.get("articles", "?")} articles (FineWiki, Wikipedia dump 2025-08), SQLite + FTS5 + zstd, with {wiki.get("aliases", "?")} alternative names from redirects, {wiki.get("acronyms", "?")} acronyms and coordinates for {wiki.get("coords", "?")} articles (Wikipedia geo_tags, with an R*Tree index) | CC BY-SA 4.0 |
| `places.db` | {places.get("places", "?")} places worldwide ({places.get("source", "Overture Maps")}), SQLite + R*Tree | CDLA-Permissive-2.0 / Apache-2.0 / CC0-1.0 (per Overture source) |

Built with `tools/run_data_jobs.sh` and `tools/build_aliases.py`, `build_acronyms.py` and `build_coords.py` in the Bivouac repo.
Wikipedia text is © Wikipedia contributors, CC BY-SA 4.0; changes: HTML converted to text,
split into sections, compressed. Places data © Overture Maps Foundation and its sources.
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("repo", help="HuggingFace dataset repo, e.g. yourname/bivouac-packs")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    files = {pid: OUT / name for pid, name in PACKS.items()}
    for f in files.values():
        if not f.exists():
            raise SystemExit(f"missing {f}: build the packs first (tools/run_data_jobs.sh)")
    readme = card(a.repo, meta(files["wiki-en"]), meta(files["places-world"]))

    base = f"https://huggingface.co/datasets/{a.repo}/resolve/main"
    if a.dry_run:
        for pid, f in files.items():
            print(f"would upload {f} ({f.stat().st_size / 1e9:.1f} GB) -> {base}/{f.name}")
        print(readme)
        return

    from huggingface_hub import HfApi

    api = HfApi()
    api.create_repo(a.repo, repo_type="dataset", exist_ok=True)
    api.upload_file(path_or_fileobj=readme.encode(), path_in_repo="README.md", repo_id=a.repo, repo_type="dataset")
    for pid, f in files.items():
        print(f"uploading {f.name} ({f.stat().st_size / 1e9:.1f} GB)…", flush=True)
        if not hasattr(api, "upload_large_folder"):
            # huggingface_hub >= 2.0: uploads go through Xet, which skips chunks already on the
            # Hub, so running the command again resumes an interrupted upload.
            api.upload_file(path_or_fileobj=str(f), path_in_repo=f.name, repo_id=a.repo, repo_type="dataset")
            continue
        # upload_large_folder (huggingface_hub < 2.0) is resumable and handles multi-GB files.
        stage = OUT / f".publish-{pid}"
        stage.mkdir(exist_ok=True)
        link = stage / f.name
        if not link.exists():
            link.symlink_to(f)
        api.upload_large_folder(repo_id=a.repo, repo_type="dataset", folder_path=stage)

    cat = json.loads(CATALOG.read_text())
    for item in cat["items"]:
        if item["id"] in files:
            f = files[item["id"]]
            item["url"] = f"{base}/{f.name}"
            item["bytes"] = f.stat().st_size
    CATALOG.write_text(json.dumps(cat, indent=2, ensure_ascii=False) + "\n")
    print(f"done; catalog updated: {CATALOG}")


if __name__ == "__main__":
    main()
