#!/usr/bin/env bash
# Disable every package in a debloat list (one pm call per shell command; chained pm calls can stop early).
# Packages are disabled for user 0, never uninstalled. Undo with tools/restore.sh <same list>.
# Usage: tools/disable.sh examples/tcl-google-tv-g07/debloat.txt
set -euo pipefail
cd "$(dirname "$0")/.."
LIST="${1:?usage: tools/disable.sh <debloat list>}"
PY="${PY:-tools/venv/bin/python}"
grep -vE '^\s*(#|$)' "$LIST" | awk '{print "pm disable-user --user 0 " $1}' \
  | "$PY" -I tools/tv.py --batch
