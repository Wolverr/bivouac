#!/usr/bin/env bash
# First run on a new phone: install, copy models and packs, measure, report.
# Run in WSL from the repo root, phone plugged in with USB debugging on:
#   tools/phone/new_phone.sh            # 9B (12 GB tier) and 4B
#   MODELS="Qwen3.5-4B-Q4_K_M.gguf" tools/phone/new_phone.sh
# Safe to run again: copies skip files already there, the app eval resumes.
# Results: eval/phone-<model>-<date>/ (report.md, raw outputs, eval results).
set -euo pipefail
cd "$(dirname "$0")/../.."
REPO=$PWD
PKG=io.github.wolverr.bivouac
FILES=/sdcard/Android/data/$PKG/files
MODELS=${MODELS:-"Qwen3.5-9B-Q4_K_M.gguf Qwen3.5-4B-Q4_K_M.gguf"}
MODEL_DIR=${MODEL_DIR:-$HOME/models}
MTP_DIR=${MTP_DIR:-$HOME/models-mtp}
PACK_DIR=${PACK_DIR:-$REPO/tools/out}
BUILD=${BUILD:-$HOME/llama-android-build/bin}
APK=${APK:-$REPO/app/build/outputs/apk/debug/app-debug.apk}
EVAL_TIMEOUT_MIN=${EVAL_TIMEOUT_MIN:-120}

# Windows' own adb copies ~15x faster than WSL adb through usbipd (48 vs 3 MB/s).
WIN_ADB=/mnt/c/Users/${WIN_USER:-nicol}/AppData/Local/platform-tools-win/platform-tools/adb.exe
if [ -z "${ADB:-}" ]; then
  if [ -x "$WIN_ADB" ]; then ADB=$WIN_ADB; else ADB=$HOME/Android/Sdk/platform-tools/adb; fi
fi
host_path() { if [[ $ADB == *.exe ]]; then wslpath -w "$1"; else echo "$1"; fi; }
adb() { "$ADB" "$@"; }
sh_() { adb shell "$@" | tr -d '\r'; }

n=$(adb devices | tr -d '\r' | awk 'NR>1 && $2=="device"' | wc -l)
[ "$n" = 1 ] || { echo "need exactly one phone attached (found $n); check 'adb devices'"; exit 1; }

DEV=$(sh_ getprop ro.product.model | tr ' /' '__')
OUT=$REPO/eval/phone-$DEV-$(date +%F)
mkdir -p "$OUT"
log() { echo "[$(date +%T)] $*" | tee -a "$OUT/log.txt"; }
log "phone $DEV, adb $ADB, out $OUT"
sh_ svc power stayon usb || true

# 1. Chip, clocks, memory.
adb push "$(host_path "$REPO/tools/phone/cores.sh")" /data/local/tmp/ >/dev/null
sh_ sh /data/local/tmp/cores.sh | tee "$OUT/cores.txt"
TOTAL_KB=$(sh_ "grep MemTotal /proc/meminfo" | awk '{print $2}')
[ "$TOTAL_KB" -le 12600000 ] || log "WARNING: MemTotal $TOTAL_KB kB is above 12 GB: the bounty allows at most 12 GB"

# 2. App. Xiaomi phones ask on screen before each install.
log "installing $(basename "$APK") (tap Install on the phone if it asks)"
adb install -r "$(host_path "$APK")" | tail -1
sh_ am start -n $PKG/.ui.MainActivity >/dev/null
for i in $(seq 30); do sh_ "ls -d $FILES/models $FILES/packs $FILES/eval" >/dev/null 2>&1 && break; sleep 1; done
sh_ "ls -d $FILES/models $FILES/packs $FILES/eval" >/dev/null || { echo "app folders missing: open the app once, then run again"; exit 1; }

# 3. Copy (skip files already there with the same size).
push() {  # push <local file> <phone dir>
  local f=$1 d=$2 name size have
  name=$(basename "$f"); size=$(stat -c %s "$f")
  have=$(sh_ "stat -c %s '$d/$name' 2>/dev/null" || true)
  if [ "$have" = "$size" ]; then log "have $name"; return; fi
  log "copying $name ($((size / 1000000)) MB)"
  adb push "$(host_path "$f")" "$d/" | tail -1
}
for m in $MODELS; do push "$MODEL_DIR/$m" $FILES/models; done
for p in wiki.db places.db; do push "$PACK_DIR/$p" $FILES/packs; done
push "$REPO/eval/questions-speed10.jsonl" $FILES/eval
for t in llama-bench; do push "$BUILD/$t" /data/local/tmp; done
[ -f "$BUILD/llama-speculative-simple" ] && { cp -u "$BUILD/llama-speculative-simple" /tmp/llama-spec; push /tmp/llama-spec /data/local/tmp; }
for s in bench.sh mtp.sh; do adb push "$(host_path "$REPO/tools/phone/$s")" /data/local/tmp/ >/dev/null; done
sh_ chmod 755 /data/local/tmp/llama-bench /data/local/tmp/llama-spec 2>/dev/null || true

# 4. Raw speed per model: llama-bench at 2/4/all threads, reading 256 and writing 64 tokens.
NCPU=$(sh_ nproc)
for m in $MODELS; do
  log "llama-bench $m"
  sh_ "cd /data/local/tmp && sh bench.sh $FILES/models/$m $m -t 2,4,$NCPU -p 256 -n 64" | tee "$OUT/bench-$m.txt"
done

# 5. MTP (built-in draft heads), 9B: plain vs draft-mtp.
MTP9=$MTP_DIR/Qwen3.5-9B-MTP-Q4_K_M.gguf
if [ -f "$MTP9" ] && [[ " $MODELS " == *" Qwen3.5-9B-Q4_K_M.gguf "* ]]; then
  push "$MTP9" /data/local/tmp
  for args in "base --spec-draft-n-max 0" "mtp1 --spec-type draft-mtp --spec-draft-n-max 1" "mtp2 --spec-type draft-mtp --spec-draft-n-max 2"; do
    sh_ "cd /data/local/tmp && sh mtp.sh $(basename "$MTP9") $args" | tee -a "$OUT/mtp.txt"
  done
  sh_ rm -f "/data/local/tmp/$(basename "$MTP9")"  # 6 GB the app doesn't use
fi

# 6. The app itself: 10 questions per model (search + prompt reading + answer), resumable.
for m in $MODELS; do
  res=$FILES/eval/results-${m%.gguf}.jsonl
  log "app eval $m (10 questions)"
  adb logcat -c
  sh_ am start -n $PKG/.ui.MainActivity --es eval questions-speed10.jsonl --es model "$m" >/dev/null
  deadline=$(( $(date +%s) + EVAL_TIMEOUT_MIN * 60 ))
  while :; do
    done_n=$(sh_ "cat '$res' 2>/dev/null | wc -l" || echo 0)
    err=$(adb logcat -d -s bivouac-eval:E | tr -d '\r' | grep -v '^-' | tail -1 || true)
    [ -n "$err" ] && { log "eval error: $err"; break; }
    [ "${done_n:-0}" -ge 10 ] && break
    [ "$(date +%s)" -gt "$deadline" ] && { log "eval timeout after $EVAL_TIMEOUT_MIN min ($done_n/10)"; break; }
    sleep 30
  done
  adb logcat -d -s bivouac-eval bivouac-engine | tr -d '\r' > "$OUT/logcat-${m%.gguf}.txt"
  adb pull "$res" "$(host_path "$OUT")" >/dev/null 2>&1 || true
  sh_ "dumpsys meminfo $PKG | grep -E 'TOTAL PSS|Native Heap' | head -2" | tee "$OUT/meminfo-${m%.gguf}.txt"
done

# 7. Report.
python3 "$REPO/tools/phone/report.py" "$OUT" | tee "$OUT/report.md"
log "done: $OUT/report.md"
