#!/usr/bin/env bash
# Install the launcher on a Fire TV over ADB and switch on the Home-button service.
# Usage: tools/firetv-install.sh <tv-ip> [path/to/tvhome.apk]
set -e
TV="${1:?usage: firetv-install.sh <tv-ip> [apk]}"
APK="${2:-launcher/out/tvhome.apk}"
PKG=com.mihir.tvhome
adb connect "$TV:5555" >/dev/null
A="adb -s $TV:5555"
$A get-state >/dev/null || { echo "TV did not answer. Turn on ADB debugging and accept the prompt on screen."; exit 1; }
$A install -r "$APK"
$A shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS
$A shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION || true   # lets the Wi-Fi name show
$A shell appops set $PKG SYSTEM_ALERT_WINDOW allow                       # alert cards over other apps
$A shell settings put secure enabled_accessibility_services null
$A shell settings put secure enabled_accessibility_services $PKG/$PKG.HomeKeyService
$A shell settings put secure accessibility_enabled 1
$A shell settings put secure screensaver_components $PKG/.ClockDream || true
$A shell am start -n $PKG/.MainActivity
echo "Installed. Press Home on the remote to try it. Undo with tools/firetv-undo.sh $TV"
