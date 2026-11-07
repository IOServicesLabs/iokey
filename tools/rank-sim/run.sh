#!/usr/bin/env bash
# Offline next-word ranking harness. Compiles the REAL FastEngine and asks it to
# predict the next word of invented chat messages, on a personal history shaped
# like the rows the database holds once every n-gram order is written. It scores
# how personal counts enter the next-word score, linear or ln-scaled, on the same
# history and the same test text.
#
#   tools/rank-sim/run.sh [norebuild] [seed=42,7] [zipf=0.8] [show]
#
#   seed=   comma-separated seeds, each a different person and a different text;
#           more than two also prints the seed-to-seed spread
#   zipf=   how sharply a person repeats favourite messages, higher is more habitual
#   show    also print the bar under both formulas for a few sample contexts, and how
#           many sightings a new pair needs to reach the bar under each
#
# The formula is not switchable in the app. The build below patches a copy of
# FastEngine under out/ and leaves the source alone, so the same sim keeps working
# whichever formula the source holds, and a failed patch stops the run.
#
# Needs what tools/swipe-sim/run.sh needs: JAVA_HOME, the kotlinc bundled with
# Android Studio and a kotlinx-coroutines-core-jvm jar in the Gradle cache.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
ENG="$REPO/app/src/main/java/com/localboard/keyboard/engine"
SHIMS="$REPO/tools/swipe-sim/shims"
KC="${KOTLINC:-/c/Program Files/Android/Android Studio/plugins/Kotlin/kotlinc/bin/kotlinc}"
COR="${COROUTINES_JAR:-$(ls ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.[6-9].*/*/kotlinx-coroutines-core-jvm-1.[6-9].*.jar 2>/dev/null | sort -V | tail -1)}"
[ -n "$COR" ] || { echo "no kotlinx-coroutines-core-jvm 1.6-1.9 jar in the Gradle cache; set COROUTINES_JAR" >&2; exit 1; }
STD="$(dirname "$KC")/../lib/kotlin-stdlib.jar"
export PATH="$JAVA_HOME/bin:$PATH"
OUT="$HERE/out"; mkdir -p "$OUT/patched"
if [ "${1:-}" != "norebuild" ]; then
  sed -E \
    -e 's/scores\.merge\(w, (ln\(1\.0 \+ count\)|count) \* WEIGHT_TRIGRAM \+ CONTEXT_FLOOR_TRIGRAM, Double::plus\)/scores.merge(w, ranktune.RankTune.term(count) * WEIGHT_TRIGRAM + CONTEXT_FLOOR_TRIGRAM, Double::plus)/' \
    -e 's/scores\.merge\(w, (ln\(1\.0 \+ count\)|count) \* WEIGHT_BIGRAM \* damp \+ CONTEXT_FLOOR_BIGRAM, Double::plus\)/scores.merge(w, ranktune.RankTune.term(count) * WEIGHT_BIGRAM * damp + CONTEXT_FLOOR_BIGRAM, Double::plus)/' \
    "$ENG/FastEngine.kt" > "$OUT/patched/FastEngine.kt"
  PATCHED="$(grep -c 'ranktune\.RankTune\.term(count)' "$OUT/patched/FastEngine.kt" || true)"
  [ "$PATCHED" = "2" ] || { echo "expected to patch 2 lines in FastEngine.kt, patched $PATCHED; update the sed above" >&2; exit 1; }
  "$KC" -nowarn -cp "$COR" -d "$OUT/rank.jar" "$SHIMS" "$HERE/src" \
    "$OUT/patched/FastEngine.kt" "$ENG/Trie.kt" "$ENG/KeyboardGeometry.kt" \
    "$ENG/Suggestion.kt" "$ENG/BaseLanguageModel.kt" "$ENG/OffensiveWords.kt" \
    "$ENG/ContextTokens.kt" 2>&1 | { grep -E "error:" -A2 || true; }
else
  shift
fi
if command -v cygpath >/dev/null 2>&1; then
  CP="$(cygpath -w "$OUT/rank.jar");$(cygpath -w "$STD");$(cygpath -w "$COR")"
  ASSETS="$(cygpath -w "$REPO/app/src/main/assets")"
  CORPUS="$(cygpath -w "$HERE/corpus")"
else
  CP="$OUT/rank.jar:$STD:$COR"
  ASSETS="$REPO/app/src/main/assets"
  CORPUS="$HERE/corpus"
fi
java -Xmx2g -cp "$CP" RankSim "$ASSETS" "$CORPUS" "$@"
