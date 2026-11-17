#!/system/bin/sh
# Mede CPU e memoria de um app no projetor. Uso (no PC):
#   adb -s 192.168.0.50:5555 push tools/medir.sh /data/local/tmp/ && \
#   adb -s 192.168.0.50:5555 shell sh /data/local/tmp/medir.sh <pacote> [segundos]
# CPU = soma de utime+stime de todos os processos do pacote (e dos servicos de midia do sistema),
# em % de UM nucleo (100% = um nucleo cheio; o T950S tem 4).
PKG=$1
SECS=${2:-60}
PIDS=$(ps -A -o PID,NAME | awk -v p="$PKG" '$2==p || index($2, p":")==1 {print $1}')
SYS="$(pidof audioserver) $(pidof android.hardware.media.c2@1.2-service) $(pidof media.swcodec) $(pidof mediaserver) $(pidof media.extractor)"

ticks() { t=0; for p in $1; do v=$(awk '{print $14+$15}' /proc/$p/stat 2>/dev/null); t=$((t + ${v:-0})); done; echo $t; }
total() { awk '/^cpu /{print $2+$3+$4+$5+$6+$7+$8}' /proc/stat; }
idle() { awk '/^cpu /{print $5+$6}' /proc/stat; }

a1=$(ticks "$PIDS"); s1=$(ticks "$SYS"); T1=$(total); I1=$(idle)
sleep $SECS
a2=$(ticks "$PIDS"); s2=$(ticks "$SYS"); T2=$(total); I2=$(idle)

echo "pacote=$PKG pids=$(echo $PIDS) janela=${SECS}s"
echo "cpu_app_pct_1nucleo=$(( (a2-a1) * 100 / (SECS * 100) ))"
echo "cpu_servicos_midia_pct_1nucleo=$(( (s2-s1) * 100 / (SECS * 100) ))"
echo "cpu_sistema_total_pct_de_400=$(( ( (T2-T1) - (I2-I1) ) * 400 / (T2-T1) ))"
grep -E 'MemAvailable' /proc/meminfo
for p in $PIDS; do
  n=$(cat /proc/$p/cmdline | tr '\0' ' ')
  rss=$(awk '/VmRSS/{print $2}' /proc/$p/status)
  pss=$(dumpsys meminfo $p | awk '/TOTAL PSS:/{print $3}')
  echo "proc=$n rss_kb=$rss pss_kb=$pss"
done
