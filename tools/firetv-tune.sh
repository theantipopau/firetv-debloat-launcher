#!/usr/bin/env bash
# Reversible clean-up and speed-ups for a Fire TV. Usage: tools/firetv-tune.sh <tv-ip>
# Disables Amazon shopping / ad-ID / content-recognition packages, limits ad tracking, halves animation times and
# turns off Amazon's own screensaver (ours replaces it). The list of disabled packages goes to backup/disabled-<ip>.txt.
set -e
TV="${1:?usage: firetv-tune.sh <tv-ip>}"
adb connect "$TV:5555" >/dev/null
A="adb -s $TV:5555"
mkdir -p backup
LOG="backup/disabled-$TV.txt"
: > "$LOG"
for p in com.amazon.shoptv.firetv.client com.amazon.shoptv.client com.amazon.tv.acr com.amazon.hybridadidservice \
         com.amazon.sneakpeek com.amazon.ftv.screensaver; do
  if $A shell pm list packages "$p" | grep -q "$p"; then
    if $A shell pm disable-user --user 0 "$p" | grep -q "disabled-user"; then echo "$p" >> "$LOG"; echo "disabled $p"; fi
  fi
done
$A shell settings put secure amz_limit_ad_tracking 1
$A shell settings put secure non_amz_limit_ad_tracking 1
for s in window_animation_scale transition_animation_scale animator_duration_scale; do $A shell settings put global $s 0.5; done
echo "Done. Undo with tools/firetv-undo.sh $TV"
