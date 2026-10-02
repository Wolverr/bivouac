#!/system/bin/sh
# llama-bench on the phone with peak RSS. Usage (from /data/local/tmp):
#   sh bench.sh <model.gguf> <label> [llama-bench args, e.g. -t 2,4,8 -p 256 -n 64]
cd /data/local/tmp
M=$1; L=$2; shift 2
./llama-bench -m "$M" -o md "$@" > bench.out 2> bench.err &
pid=$!
peak=0
while kill -0 $pid 2>/dev/null; do
  r=$(awk "/VmRSS/{print \$2}" /proc/$pid/status 2>/dev/null)
  [ -n "$r" ] && [ "$r" -gt "$peak" ] && peak=$r
  sleep 0.5
done
echo "=== $L | peak RSS $((peak / 1024)) MB | avail now $(awk "/MemAvailable/{print int(\$2/1024)}" /proc/meminfo) MB"
grep -E "^\|" bench.out | grep -v -- "---"
grep -iE "error|failed|killed" bench.err | head -3
