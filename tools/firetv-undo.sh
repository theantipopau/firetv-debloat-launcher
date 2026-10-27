#!/usr/bin/env bash
# Put a Fire TV back to stock. Usage: tools/firetv-undo.sh <tv-ip>
TV="${1:?usage: firetv-undo.sh <tv-ip>}"
adb connect "$TV:5555" >/dev/null
A="adb -s $TV:5555"
$A shell settings put secure enabled_accessibility_services null
$A shell am force-stop com.mihir.tvhome
LOG="backup/disabled-$TV.txt"
if [ -f "$LOG" ]; then while read -r p; do [ -n "$p" ] && $A shell pm enable "$p"; done < "$LOG"; fi
for s in window_animation_scale transition_animation_scale animator_duration_scale; do $A shell settings put global $s 1.0; done
$A shell settings delete secure screensaver_components
$A shell pm uninstall --user 0 com.mihir.tvhome
echo "Back to Amazon's home. (Ad-tracking limits were left on.)"
