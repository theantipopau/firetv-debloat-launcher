#!/usr/bin/env bash
# Find Android TVs on your Wi-Fi: scans the local /24 for ADB (5555) and Cast (8008) and lists
# Android TV remote names from mDNS. Saves the first TV with ADB open to tools/.tv_host.
# Usage: tools/find-tv.sh
set -uo pipefail
cd "$(dirname "$0")"

ip=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}')
[ -n "$ip" ] || { echo "Couldn't work out this computer's IP. Are you on Wi-Fi?"; exit 1; }
net=${ip%.*}
echo "Scanning $net.0/24 from $ip …"

if command -v dns-sd >/dev/null; then
  echo "Android TVs announcing themselves:"
  (dns-sd -B _androidtvremote2._tcp local. & sleep 3; kill $! 2>/dev/null) 2>/dev/null \
    | awk '/Add/ {for (i=7;i<=NF;i++) printf "%s ", $i; print ""}' | sed 's/^/  /'
fi

tmp=$(mktemp)
for i in $(seq 1 254); do
  ( nc -z -G 1 -w 1 "$net.$i" 5555 2>/dev/null && echo "adb $net.$i" >> "$tmp"
    nc -z -G 1 -w 1 "$net.$i" 8008 2>/dev/null && echo "cast $net.$i" >> "$tmp" ) &
done
wait

cast=$(awk '$1=="cast"{print $2}' "$tmp" | sort -t. -k4 -n)
adb=$(awk '$1=="adb"{print $2}' "$tmp" | sort -t. -k4 -n)
rm -f "$tmp"

for h in $cast; do
  name=$(curl -s -m 3 "http://$h:8008/setup/eureka_info?params=name" | sed -n 's/.*"name":"\([^"]*\)".*/\1/p')
  echo "Cast device: $h ${name:+($name)}"
done

if [ -n "$adb" ]; then
  first=$(echo "$adb" | head -1)
  echo "ADB open on: $(echo $adb)"
  echo "$first" > .tv_host
  echo "Saved $first to tools/.tv_host. Next: tools/venv/bin/python -I tools/tv.py --probe"
else
  echo "No TV with ADB open. On the TV turn on Developer options → USB debugging, then rerun."
  [ -n "$cast" ] && echo "(Your TV is probably one of the Cast devices above.)"
  exit 1
fi
