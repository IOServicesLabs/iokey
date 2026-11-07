#!/usr/bin/env bash
# Offline checks for the touch logic. Compiles the REAL app sources (TapResolver,
# TouchModel, TouchCheck, KeyLayoutMath, Trie, PrefsMigrations) and runs them with no
# Android, no Gradle and no device. Every accuracy figure this prints is simulated, on
# an assumed finger: it compares designs and says nothing about a real hand.
#
#   tools/touch-sim/run.sh                property checks, the margin comparison and the
#                                         Monte Carlo over the app's own dictionary
#   tools/touch-sim/run.sh quick          the same without the Monte Carlo
#   tools/touch-sim/run.sh junit          the JVM tests in app/src/test/.../touch, run with
#                                         the junit that ships with Android Studio, for
#                                         when the app itself does not compile through Gradle
#   add norebuild as the first argument to reuse the last build
#
# Needs: JAVA_HOME (JDK 17+) and the kotlinc bundled with Android Studio.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
MAIN="$REPO/app/src/main/java/com/localboard/keyboard"
TESTS="$REPO/app/src/test/java/com/localboard/keyboard/touch"
STUDIO="${ANDROID_STUDIO:-/c/Program Files/Android/Android Studio}"
KC="${KOTLINC:-$STUDIO/plugins/Kotlin/kotlinc/bin/kotlinc}"
STD="$(dirname "$KC")/../lib/kotlin-stdlib.jar"
JUNIT="${JUNIT_JAR:-$STUDIO/lib/junit4.jar}"
OUT="$HERE/out"; mkdir -p "$OUT"
# On Windows JAVA_HOME is a C:\ path, and its colon would split a bash PATH in two.
JAVA_BIN="$JAVA_HOME/bin"
command -v cygpath >/dev/null 2>&1 && JAVA_BIN="$(cygpath -u "$JAVA_HOME")/bin"
export PATH="$JAVA_BIN:$PATH"

SOURCES=(
  "$MAIN/engine/TapResolver.kt" "$MAIN/engine/TouchModel.kt" "$MAIN/engine/TouchCheck.kt"
  "$MAIN/engine/KeyLayoutMath.kt" "$MAIN/engine/Trie.kt" "$MAIN/util/PrefsMigrations.kt"
)

win() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
SEP=":"; command -v cygpath >/dev/null 2>&1 && SEP=";"

REBUILD=1
if [ "${1:-}" = "norebuild" ]; then REBUILD=0; shift; fi
MODE="${1:-full}"

if [ "$MODE" = "junit" ]; then
  if [ "$REBUILD" = 1 ]; then
    "$KC" -nowarn -cp "$(win "$JUNIT")" -d "$OUT/touch-junit.jar" "${SOURCES[@]}" \
      "$MAIN/ui/KeyboardModel.kt" "$MAIN/ui/KeyboardLayouts.kt" "$HERE/shims" "$TESTS"
  fi
  CLASSES=()
  for f in "$TESTS"/*Test.kt; do CLASSES+=("com.localboard.keyboard.touch.$(basename "$f" .kt)"); done
  java -cp "$(win "$OUT/touch-junit.jar")$SEP$(win "$STD")$SEP$(win "$JUNIT")" \
    org.junit.runner.JUnitCore "${CLASSES[@]}"
  exit $?
fi

if [ "$REBUILD" = 1 ]; then
  "$KC" -nowarn -d "$OUT/touch-sim.jar" "${SOURCES[@]}" "$TESTS/DeviceLayout.kt" "$HERE/src"
fi
ARGS=()
[ "$MODE" = "quick" ] || ARGS+=("$(win "$REPO/app/src/main/assets/base_dictionary.txt")")
java -Xmx1500m -cp "$(win "$OUT/touch-sim.jar")$SEP$(win "$STD")" ProposedTestsKt ${ARGS[@]+"${ARGS[@]}"}
