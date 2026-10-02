#!/system/bin/sh
# Chip, core clusters (max clock per core), clocks now, temperatures, memory.
echo "soc: $(getprop ro.soc.manufacturer) $(getprop ro.soc.model) | board $(getprop ro.board.platform) | $(getprop ro.product.model)"
for d in /sys/devices/system/cpu/cpu[0-9]*; do
  c=${d##*cpu}; f=$d/cpufreq
  echo "cpu$c hw_max=$(cat $f/cpuinfo_max_freq 2>/dev/null) cur=$(cat $f/scaling_cur_freq 2>/dev/null) cap=$(cat $f/scaling_max_freq 2>/dev/null)"
done
for z in /sys/class/thermal/thermal_zone*; do
  t=$(cat $z/type 2>/dev/null); v=$(cat $z/temp 2>/dev/null)
  case "$t" in *cpu*|*soc*|*skin*|*gpu*|*battery*) echo "$t $v";; esac
done 2>/dev/null | head -14
grep -E "MemTotal|MemAvailable" /proc/meminfo
