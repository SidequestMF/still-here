#!/usr/bin/env bash
# Compiles the shared Kotlin code with any Kotlin compiler on the machine and runs:
#  1. CoreChecks: unit checks of rules, messages and relay JSON
#  2. ReplayCheck: replays recorded JavaScript nights and requires identical decisions
# Uses `kotlinc` if installed, otherwise the compiler bundled inside Gradle.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d)"; TRACES="$OUT/traces"
SRC=$(find "$ROOT/android/shared/src" -name '*.kt')

if command -v kotlinc >/dev/null; then
  kotlinc $SRC -d "$OUT/classes" 2>&1 | grep -v warning || true
  STDLIB="$(dirname "$(command -v kotlinc)")/../lib/kotlin-stdlib.jar"
else
  L="$(dirname "$(readlink -f "$(command -v gradle)")")/../lib"
  STDLIB=$(ls "$L"/kotlin-stdlib-*.jar | head -1)
  CP=$(ls "$L"/kotlin-compiler-embeddable-*.jar "$L"/kotlin-stdlib-*.jar "$L"/kotlin-reflect-*.jar \
          "$L"/kotlin-script-runtime-*.jar "$L"/kotlin-daemon-embeddable-*.jar "$L"/annotations-*.jar \
          "$L"/trove4j*.jar "$L"/kotlinx-coroutines-core-jvm*.jar | tr '\n' ':')
  java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -cp "$STDLIB" $SRC -d "$OUT/classes" 2>&1 | grep -v -E "warning|JAVA_TOOL_OPTIONS" || true
fi

java -Dstdout.encoding=UTF-8 -cp "$OUT/classes:$STDLIB" stillhere.core.CoreChecksKt 2>&1 | grep -v JAVA_TOOL_OPTIONS
java -Dstdout.encoding=UTF-8 -cp "$OUT/classes:$STDLIB" stillhere.core.MonitorChecksKt 2>&1 | grep -v JAVA_TOOL_OPTIONS
TRACE_DIR="$TRACES" node "$ROOT/tests/simulate-nights.mjs" >/dev/null
java -Dstdout.encoding=UTF-8 -cp "$OUT/classes:$STDLIB" stillhere.core.ReplayCheckKt "$TRACES" 2>&1 | grep -v JAVA_TOOL_OPTIONS
rm -rf "$OUT"
