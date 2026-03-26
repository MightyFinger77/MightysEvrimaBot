#!/usr/bin/env bash
# EvrimaServerBot 1.2.0 launcher for Linux (and macOS). Keep this script next to the shaded JAR from: mvn package
# First run: chmod +x start-bot.sh

set -euo pipefail

cd "$(dirname "$0")"
JAR="evrima-server-bot-1.2.0.jar"

if [[ ! -f "$JAR" ]]; then
  echo "[ERROR] $JAR not found in this folder."
  echo "Put start-bot.sh next to the shaded JAR from mvn package (target/evrima-server-bot-1.2.0.jar)."
  exit 1
fi

JARSIZE=$(wc -c < "$JAR" | tr -d ' ')
if (( JARSIZE < 5000000 )); then
  echo "[ERROR] This JAR is only $JARSIZE bytes — not the runnable fat build."
  echo
  echo "From the project directory run:  mvn -q package"
  echo "Then use target/evrima-server-bot-1.0.2.jar (~35 MB with dependencies), not original-evrima-server-bot-1.0.2.jar"
  exit 1
fi

if ! command -v java >/dev/null 2>&1; then
  echo "[ERROR] java not on PATH. Install Java 17+ (Temurin, Oracle, etc.)."
  exit 1
fi

# JDK 24+: SQLite JDBC loads a native library; this flag silences "restricted method" warnings when supported.
JVM_EXTRA=()
if java --enable-native-access=ALL-UNNAMED -version >/dev/null 2>&1; then
  JVM_EXTRA=(--enable-native-access=ALL-UNNAMED)
fi

echo "Starting EvrimaServerBot 1.2.0..."
echo "Working directory: $(pwd)"
echo

set +e
java "${JVM_EXTRA[@]}" -jar "$JAR"
ERR=$?
set -e

echo
if (( ERR != 0 )); then
  echo "Exited with code $ERR."
fi

exit "$ERR"
