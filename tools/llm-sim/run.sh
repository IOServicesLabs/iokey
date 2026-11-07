#!/usr/bin/env bash
# Offline harness for the conversation memory and prompt code. Compiles the REAL
# sources (the pure files under memory/ and llm/) with no Android and no Gradle, then
# runs assertions over them on a desktop JVM. Every line of text in it is scripted:
# no chat, name or number here came from a real person.
#
#   tools/llm-sim/run.sh [norebuild]
#
# Exits non-zero on a compile error or a failed assertion, so it can gate a build.
# No model runs here: this checks what is sent and what is kept, not how well a
# model answers.
#
# Needs: JAVA_HOME (JDK 17+) and the kotlinc bundled with Android Studio, or KOTLINC.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
PKG="$REPO/app/src/main/java/com/localboard/keyboard"
KC="${KOTLINC:-/c/Program Files/Android/Android Studio/plugins/Kotlin/kotlinc/bin/kotlinc}"
STD="$(dirname "$KC")/../lib/kotlin-stdlib.jar"
OUT="$HERE/out"; mkdir -p "$OUT"
# Named one by one: memory/ also holds code that needs Room and Android.
SOURCES=(
  "$PKG/memory/Turn.kt"
  "$PKG/memory/ControlTokens.kt"
  "$PKG/memory/ConversationKeyResolver.kt"
  "$PKG/memory/TranscriptMerger.kt"
  "$PKG/memory/BubbleClassifier.kt"
  "$PKG/memory/Bm25.kt"
  "$PKG/memory/ConversationLexicon.kt"
  "$PKG/llm/DeltaStream.kt"
  "$PKG/llm/OutputSanitizer.kt"
  "$PKG/llm/PromptAssembler.kt"
  "$PKG/llm/ChatFormatter.kt"
)
if command -v cygpath >/dev/null 2>&1; then
  JAVA="$(cygpath -u "$JAVA_HOME")/bin/java"
else
  JAVA="$JAVA_HOME/bin/java"
fi
if [ "${1:-}" != "norebuild" ]; then
  # A stale jar must never answer for sources that no longer compile.
  rm -f "$OUT/sim.jar"
  if ! "$KC" -nowarn -d "$OUT/sim.jar" "$HERE/src" "${SOURCES[@]}" > "$OUT/compile.log" 2>&1; then
    grep -E "error:" -A2 "$OUT/compile.log" >&2 || cat "$OUT/compile.log" >&2
    echo "llm-sim: compile failed" >&2
    exit 1
  fi
else
  shift
fi
if command -v cygpath >/dev/null 2>&1; then
  CP="$(cygpath -w "$OUT/sim.jar");$(cygpath -w "$STD")"
else
  CP="$OUT/sim.jar:$STD"
fi
"$JAVA" -cp "$CP" MainKt "$@"
