#!/usr/bin/env bash
# One-time setup on macOS: Python ADB client, JDK 17 and the Android SDK pieces the launcher build needs.
set -euo pipefail
cd "$(dirname "$0")"

python3 -m venv tools/venv
tools/venv/bin/pip install -q --no-cache-dir -r tools/requirements.txt
echo "✓ Python ADB client in tools/venv"

if [ "${1:-}" = "--no-launcher" ]; then exit 0; fi
command -v brew >/dev/null || { echo "Install Homebrew first: https://brew.sh"; exit 1; }
brew list openjdk@17 >/dev/null 2>&1 || brew install openjdk@17
brew list --cask android-commandlinetools >/dev/null 2>&1 || brew install --cask android-commandlinetools
SDK=/opt/homebrew/share/android-commandlinetools
# Installing the SDK packages means accepting Google's Android SDK licence.
yes | JAVA_HOME=/opt/homebrew/opt/openjdk@17 sdkmanager --sdk_root="$SDK" \
  "platforms;android-34" "build-tools;34.0.0" >/dev/null
echo "✓ JDK 17 + Android SDK (platform 34, build-tools 34.0.0)"
