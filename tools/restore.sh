#!/usr/bin/env bash
# Undo everything: re-enable every package in the debloat list, bring back the stock launcher (and
# Google TV's setup app) and make the stock launcher the default home again.
# Usage: tools/restore.sh <debloat list>
#   STOCK_LAUNCHER=com.google.android.tvlauncher/.MainActivity tools/restore.sh list.txt   (older Android TV)
set -euo pipefail
cd "$(dirname "$0")/.."
LIST="${1:?usage: tools/restore.sh <debloat list>}"
PY="${PY:-tools/venv/bin/python}"
STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.google.android.apps.tv.launcherx/.home.HomeActivity}"
{
  grep -vE '^\s*(#|$)' "$LIST" | awk '{print "pm enable " $1}'
  echo "pm enable com.google.android.tungsten.setupwraith"
  echo "pm enable ${STOCK_LAUNCHER%%/*}"
  echo "cmd package set-home-activity --user 0 $STOCK_LAUNCHER"
  echo "input keyevent KEYCODE_HOME"
} | "$PY" -I tools/tv.py --batch
echo "Restored. Press Home on the remote."
