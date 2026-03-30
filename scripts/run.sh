#!/usr/bin/env bash
# Run the app after: mvn clean package
# Requires JDK 21+ on PATH. From repo root: ./scripts/run.sh

set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="$ROOT/target"
LIB="$TARGET/lib"

JAR="$(ls "$TARGET"/xml-compare-desktop-*.jar 2>/dev/null | grep -v sources | grep -v javadoc | head -1)"
if [[ ! -f "$JAR" ]]; then
  echo "No application JAR in target/. Run: mvn clean package" >&2
  exit 1
fi
if [[ ! -d "$LIB" ]]; then
  echo "No target/lib. Run: mvn clean package" >&2
  exit 1
fi

cd "$TARGET"
exec java --module-path lib --add-modules javafx.controls -cp "$(basename "$JAR")" com.xmlcompare.app.XmlCompareApp
