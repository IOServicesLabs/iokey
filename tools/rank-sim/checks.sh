#!/usr/bin/env bash
# Runs checks/EngineChecks.kt: FastEngine's learning and lookup calls against the
# unpatched engine source and the bundled dictionary and language model, and the
# learned model across a restart and an erase, on a real SQLite. Exits non zero if
# any check fails.
#
#   tools/rank-sim/checks.sh
#
# Needs what tools/rank-sim/run.sh needs, and the sqlite-jdbc jar the unit tests use.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
ENG="$REPO/app/src/main/java/com/localboard/keyboard/engine"
DATA="$REPO/app/src/main/java/com/localboard/keyboard/data/db"
SHIMS="$REPO/tools/swipe-sim/shims"
KC="${KOTLINC:-/c/Program Files/Android/Android Studio/plugins/Kotlin/kotlinc/bin/kotlinc}"
COR="${COROUTINES_JAR:-$(ls ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.[6-9].*/*/kotlinx-coroutines-core-jvm-1.[6-9].*.jar 2>/dev/null | sort -V | tail -1)}"
[ -n "$COR" ] || { echo "no kotlinx-coroutines-core-jvm 1.6-1.9 jar in the Gradle cache; set COROUTINES_JAR" >&2; exit 1; }
JDBC="${SQLITE_JDBC_JAR:-$(ls ~/.gradle/caches/modules-2/files-2.1/org.xerial/sqlite-jdbc/*/*/sqlite-jdbc-*.jar 2>/dev/null | grep -v sources | sort -V | tail -1)}"
[ -n "$JDBC" ] || { echo "no sqlite-jdbc jar in the Gradle cache; run the unit tests once or set SQLITE_JDBC_JAR" >&2; exit 1; }
STD="$(dirname "$KC")/../lib/kotlin-stdlib.jar"
export PATH="$JAVA_HOME/bin:$PATH"
OUT="$HERE/out"; mkdir -p "$OUT"
"$KC" -nowarn -cp "$COR" -d "$OUT/checks.jar" "$SHIMS" "$HERE/checks" \
  "$ENG/FastEngine.kt" "$ENG/Trie.kt" "$ENG/KeyboardGeometry.kt" "$ENG/SwipeDecoder.kt" \
  "$ENG/Suggestion.kt" "$ENG/BaseLanguageModel.kt" "$ENG/OffensiveWords.kt" \
  "$ENG/TapResolver.kt" "$ENG/TouchModel.kt" "$ENG/correction/LegacyTypoVerdicts.kt" \
  "$DATA/NGramRows.kt" 2>&1 | { grep -E "error:" -A2 || true; }
if command -v cygpath >/dev/null 2>&1; then
  CP="$(cygpath -w "$OUT/checks.jar");$(cygpath -w "$STD");$(cygpath -w "$COR");$(cygpath -w "$JDBC")"
  ASSETS="$(cygpath -w "$REPO/app/src/main/assets")"
else
  CP="$OUT/checks.jar:$STD:$COR:$JDBC"
  ASSETS="$REPO/app/src/main/assets"
fi
java -Xmx2g -cp "$CP" EngineChecks "$ASSETS"
