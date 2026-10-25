#!/system/bin/sh
# Started by Magisk as root after boot.

MODDIR=${0%/*}
LOG=/data/local/tmp/lingchat-rootd.log

: > "$LOG" 2>/dev/null
chmod 666 "$LOG" 2>/dev/null

failures=0
while [ "$failures" -lt 5 ]; do
  start=$(date +%s)
  "$MODDIR/bin/lingchat-rootd"
  status=$?
  end=$(date +%s)
  runtime=$((end - start))

  # Long runs reset the failure counter; quick repeated crashes stop the loop.
  if [ "$runtime" -ge 60 ]; then
    failures=0
  else
    failures=$((failures + 1))
    echo "[service] daemon exited status=$status runtime=${runtime}s failures=$failures" >> "$LOG"
  fi
  sleep 5
done

echo "[service] too many failures, stopping" >> "$LOG"

