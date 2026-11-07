#!/usr/bin/env bash
# Offline glide-typing harness. Compiles the REAL engine sources
# (FastEngine, SwipeDecoder, BaseLanguageModel, ...) against tiny Android
# shims, on the device board laid out by KeyLayoutMath. Every accuracy figure
# it prints is simulated, with assumed fingers and an invented corpus: it
# compares decoders and says nothing about a real hand.
#
#   tools/swipe-sim/run.sh [norebuild] gates [seed...] [split=N] [reps=3]
#                          [verbose] [dwell] [bias] [boards] [probesonly]
#
#   gates     whole sentences of tools/rank-sim/corpus/chat.txt: word top-1 and
#             top-3, sentences entirely right, contractions; ideal, real-finger,
#             offset and hooked fingers; history none, x1, dirty, x30, x300, x3000,
#             repeated; decode p50 and p95. Then the probes: spellings that trace one
#             curve, such as its and it's, in both directions, typed pairs, and learned
#             words at the first load. Seeds default to 42 and 7, and exits 1 if a
#             gate fails. Seeds 42, 7 and 123 have floors of their own, read from a
#             run on the build server. Rows that have none for a run only report.
#   split=N   shuffle the corpus with N, not the seed, to fix the test sentences
#   dwell     a given dwell mask, on timed traces with and without a real pause
#   bias      the commonest words at the floor and the ceiling of the per-word bias
#   boards    the key heights, spacings and one-handed mode the settings offer
#   probesonly  the probes without the sentences, which takes seconds
#
#   tools/swipe-sim/run.sh [norebuild] [cases] [seed] [ctx|noctx] [exp=0.85]
#                          [verbose] [long] [explain] [predict]
#
#   Single dictionary words, half of them with a previous-word context.
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
TESTS="$REPO/app/src/test/java/com/localboard/keyboard/touch"
KC="${KOTLINC:-/c/Program Files/Android/Android Studio/plugins/Kotlin/kotlinc/bin/kotlinc}"
# Any 1.6-1.9 coroutines jar the Gradle cache happens to hold (newest first).
COR="${COROUTINES_JAR:-$(ls ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.[6-9].*/*/kotlinx-coroutines-core-jvm-1.[6-9].*.jar 2>/dev/null | sort -V | tail -1)}"
[ -n "$COR" ] || { echo "no kotlinx-coroutines-core-jvm 1.6-1.9 jar in the Gradle cache; set COROUTINES_JAR" >&2; exit 1; }
STD="$(dirname "$KC")/../lib/kotlin-stdlib.jar"
# On Windows JAVA_HOME is a C:\ path, and its colon would split a bash PATH in two.
JAVA_BIN="$JAVA_HOME/bin"
command -v cygpath >/dev/null 2>&1 && JAVA_BIN="$(cygpath -u "$JAVA_HOME")/bin"
export PATH="$JAVA_BIN:$PATH"
OUT="$HERE/out"; mkdir -p "$OUT"
if [ "${1:-}" != "norebuild" ]; then
  # A failed build has to stop here: the old jar would run and report the old decoder.
  if ! "$KC" -nowarn -cp "$COR" -d "$OUT/sim.jar" "$HERE/shims" "$HERE/src" \
    "$ENG/FastEngine.kt" "$ENG/Trie.kt" "$ENG/KeyboardGeometry.kt" \
    "$ENG/SwipeDecoder.kt" "$ENG/Suggestion.kt" "$ENG/BaseLanguageModel.kt" \
    "$ENG/OffensiveWords.kt" "$ENG/KeyLayoutMath.kt" "$ENG/TapResolver.kt" \
    "$ENG/TouchModel.kt" "$ENG/DwellMask.kt" "$TESTS/DeviceLayout.kt" > "$OUT/kotlinc.log" 2>&1; then
    grep -E "error:" -A2 "$OUT/kotlinc.log" >&2 || cat "$OUT/kotlinc.log" >&2
    exit 2
  fi
else
  shift
fi
if command -v cygpath >/dev/null 2>&1; then
  CP="$(cygpath -w "$OUT/sim.jar");$(cygpath -w "$STD");$(cygpath -w "$COR")"
  ASSETS="$(cygpath -w "$REPO/app/src/main/assets")"
  CORPUS="$(cygpath -w "$REPO/tools/rank-sim/corpus/chat.txt")"
else
  CP="$OUT/sim.jar:$STD:$COR"
  ASSETS="$REPO/app/src/main/assets"
  CORPUS="$REPO/tools/rank-sim/corpus/chat.txt"
fi
java -Xmx2g -cp "$CP" Sim "$ASSETS" "$@" "corpus=$CORPUS"
