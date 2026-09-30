# Bivouac roadmap

Offline research assistant for Android, built for
[poidh bounty #31](https://poidh.xyz/mainnet/bounty/31) (decision after 2026-10-31).

Requirements: Android/GrapheneOS, ≤12 GB RAM, ≤50 GB total storage, fully offline
after install, no Google Play Services, public repo, reproducible, demo on X/Farcaster.

## Where we want to beat existing entries

- **Install in minutes, no PC:** import or download packs inside the app. The strongest
  rival needs a 37 GB `adb push`.
- **Fast, grounded answers:** keep the fixed prompt prefix cached, keep evidence
  short (sentence-level), and cite every answer as it's written.
- **Places / "near me":** OSM places with diet/cuisine tags (the weak spot Vitalik named).
- **Honest evaluation:** blind-graded comparison against a frontier model with web
  search, with the questions and answers published.

## Milestones

- [x] M0: toolchain (JDK 17 via mise, Android SDK/NDK r29, CMake 3.31.6), llama.cpp v0.5.0 submodule
- [ ] M1: app runs a GGUF on the phone: import, load, streaming answer, prefill progress, benchmark
- [ ] M2: Wikipedia pack: `tools/build_corpus.py` → `wiki.db` (FTS5 + zstd blocks); measure size
- [ ] M3: on-device retrieval (SQLite + zstd in native code), cited answers, sources view
- [ ] M4: model selection on real hardware (Qwen3.6-35B-A3B vs Gemma 4 26B-A4B vs smaller MoEs)
- [ ] M5: install UX: pack manifest, hash check, in-app import/download
- [ ] M6: places pack (OSM) + "near me" queries
- [ ] M7: eval harness vs frontier + web, published results
- [ ] M8: README, demo video, public repo, poidh claim

## Status log

- 2026-09-30: toolchain installed; app skeleton (Compose UI + JNI engine with
  prefix snapshot caching and a big-core threadpool) written; first build in progress.
  FineWiki download started (`tools/fetch_finewiki.py`, text columns only, ~13 GB).

## Dev notes

- Build: `export JAVA_HOME=$(mise where java@temurin-17.0.20+8); ./gradlew assembleDebug`
- Test phone: Redmi Note 15 Pro (mid-range MediaTek). Final numbers come from a borrowed Pixel.
- Don't copy code from other entries; ideas only (plagiarism disqualifies).
