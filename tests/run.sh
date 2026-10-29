#!/usr/bin/env bash
# Plain-JVM tests for the launcher's pure helpers. No Gradle: javac against the JDK only.
#   tests/run.sh
set -euo pipefail
cd "$(dirname "$0")/.."

JAVAC="${JAVAC:-javac}"
JAVA="${JAVA:-java}"
# The Windows build ships its own JDK under toolchain/; use it when there is no system one.
if [ -x "toolchain/jdk/jdk-17.0.20.1+1/bin/javac.exe" ] && ! command -v "$JAVAC" >/dev/null 2>&1; then
  JAVAC="toolchain/jdk/jdk-17.0.20.1+1/bin/javac.exe"
  JAVA="toolchain/jdk/jdk-17.0.20.1+1/bin/java.exe"
fi

OUT=tests/build
rm -rf "$OUT" && mkdir -p "$OUT"
"$JAVAC" -encoding UTF-8 --release 11 -d "$OUT" \
  launcher/src/com/mihir/tvhome/Times.java \
  launcher/src/com/mihir/tvhome/Feed.java \
  tests/com/mihir/tvhome/TimesTest.java \
  tests/com/mihir/tvhome/FeedTest.java
"$JAVA" -cp "$OUT" com.mihir.tvhome.TimesTest
"$JAVA" -cp "$OUT" com.mihir.tvhome.FeedTest
