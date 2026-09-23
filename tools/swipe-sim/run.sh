#!/usr/bin/env bash
# Offline glide-typing harness. Compiles the REAL engine sources
# (FastEngine, SwipeDecoder, BaseLanguageModel, ...) against tiny Android
# shims and measures top-1 / top-3 accuracy and decode latency on
# synthesised gestures — clean, normal and sloppy — for real dictionary words,
# half of them with a realistic previous-word context.
#
#   tools/swipe-sim/run.sh [norebuild] [cases] [seed] [ctx|noctx] [exp=0.85]
#                          [verbose] [long] [explain] [predict]
#
#   exp=      frequency exponent for sampling test words (0.85 ≈ real usage,
#             0.55 over-samples rare words)
#   verbose   print misses;  long = only words >= 7 letters
#   explain   per-miss term-by-term breakdown via SwipeDecoder.explain()
#   predict   print next-word / completion / autocorrect samples and exit
#
# Needs: JAVA_HOME (JDK 17+), the kotlinc bundled with Android Studio, and a
# kotlinx-coroutines-core-jvm jar in the Gradle cache (any 1.6-1.8 build works
# with the bundled kotlinc 1.9; 1.10+ needs a Kotlin 2.x compiler).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
ENG="$REPO/app/src/main/java/com/localboard/keyboard/engine"
KC="${KOTLINC:-/c/Program Files/Android/Android Studio/plugins/Kotlin/kotlinc/bin/kotlinc}"
# Any 1.6-1.9 coroutines jar the Gradle cache happens to hold (newest first).
COR="${COROUTINES_JAR:-$(ls ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.[6-9].*/*/kotlinx-coroutines-core-jvm-1.[6-9].*.jar 2>/dev/null | sort -V | tail -1)}"
[ -n "$COR" ] || { echo "no kotlinx-coroutines-core-jvm 1.6-1.9 jar in the Gradle cache; set COROUTINES_JAR" >&2; exit 1; }
STD="$(dirname "$KC")/../lib/kotlin-stdlib.jar"
export PATH="$JAVA_HOME/bin:$PATH"
OUT="$HERE/out"; mkdir -p "$OUT"
if [ "${1:-}" != "norebuild" ]; then
  "$KC" -nowarn -cp "$COR" -d "$OUT/sim.jar" "$HERE/shims" "$HERE/src" \
    "$ENG/FastEngine.kt" "$ENG/Trie.kt" "$ENG/KeyboardGeometry.kt" \
    "$ENG/SwipeDecoder.kt" "$ENG/Suggestion.kt" "$ENG/BaseLanguageModel.kt" \
    "$ENG/OffensiveWords.kt" 2>&1 | grep -E "error:" -A2 || true
else
  shift
fi
if command -v cygpath >/dev/null 2>&1; then
  CP="$(cygpath -w "$OUT/sim.jar");$(cygpath -w "$STD");$(cygpath -w "$COR")"
  ASSETS="$(cygpath -w "$REPO/app/src/main/assets")"
else
  CP="$OUT/sim.jar:$STD:$COR"
  ASSETS="$REPO/app/src/main/assets"
fi
java -Xmx2g -cp "$CP" Sim "$ASSETS" "$@"
