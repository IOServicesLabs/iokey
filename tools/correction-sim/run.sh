#!/usr/bin/env bash
# Offline checks for the correction logic in engine/correction. No device, no Gradle.
#
#   tools/correction-sim/run.sh scenarios          the behaviour table on FakeIme, about a minute
#   tools/correction-sim/run.sh junit              the JUnit classes in app/src/test, about a minute
#   tools/correction-sim/run.sh gates [seed ...]   quality gates on the real engine, 5 minutes a seed
#   tools/correction-sim/run.sh hand               what the policy does to a list of typed words
#
# scenarios and junit compile only the pure files and their tests, so they work while the app's own
# sources do not build. Gradle runs the same tests through testFullDebugUnitTest.
#
# gates measures the policy on the real FastEngine against synthetic typing, with every number
# printed next to its threshold, and exits non-zero if any gate is missed. Seed 11 is the sample the
# thresholds were tuned on and seed 1009 is the fresh one, so read 1009 as the honest number. Until
# the engine itself gains correctionEvidence, the gates run on a scratch copy of the engine at
# commit bf247dd, patched by patch_engine.py: the repo's engine is not touched and half-finished
# edits to it cannot change the result. Once it does gain it, they run on the working tree. A copy
# of the tree with no git history can set PINNED_ENGINE_DIR to a folder holding those files.
#
# Balanced precision has no room. When this was written it ran 98.3 to 98.9 across six seeds against
# a threshold of 98.5, and missed on four of the five seeds the thresholds were not tuned on, so a
# miss by a few hundredths on a fresh seed says nothing about a change. Do not tune on these seeds:
# then there is no fresh sample left.
#
# Needs: JAVA_HOME (JDK 17+), the kotlinc bundled with Android Studio, python for the patch, and a
# kotlinx-coroutines-core-jvm jar in the Gradle cache for gates. KOTLINC, COROUTINES_JAR, JUNIT_JAR
# and SQLITE_JAR override where those are looked for.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
ENG_DIR="app/src/main/java/com/localboard/keyboard/engine"
ENG="$REPO/$ENG_DIR"
TESTS="$REPO/app/src/test/java/com/localboard/keyboard/engine/correction"
PURE=("$ENG/correction/CorrectionPolicy.kt" "$ENG/correction/CorrectionSession.kt" \
      "$ENG/correction/FieldPolicy.kt" "$ENG/correction/LegacyTypoVerdicts.kt" \
      "$ENG/correction/RejectionMemory.kt")
PINNED_COMMIT=bf247dd
AS="/c/Program Files/Android/Android Studio"
KC="${KOTLINC:-$AS/plugins/Kotlin/kotlinc/bin/kotlinc}"
STD="$(dirname "$KC")/../lib/kotlin-stdlib.jar"
OUT="$HERE/out"; mkdir -p "$OUT"
export PATH="$JAVA_HOME/bin:$PATH"

# Java reads Windows paths on Windows; everything else here reads POSIX ones.
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
classpath() {
  local sep=":" out="" p
  command -v cygpath >/dev/null 2>&1 && sep=";"
  for p in "$@"; do out="${out:+$out$sep}$(native "$p")"; done
  printf '%s' "$out"
}
compile() {  # output jar, classpath, then sources
  local jar="$1" cp="$2"; shift 2
  local log="$OUT/kotlinc.log"
  if ! "$KC" -nowarn -cp "$cp" -d "$jar" "$@" >"$log" 2>&1; then
    grep -E "error:" -A2 "$log" | head -60 >&2 || true
    echo "kotlinc failed, full output: $log" >&2
    exit 2
  fi
}

mode="${1:-scenarios}"; [ $# -gt 0 ] && shift
case "$mode" in
  scenarios)
    compile "$OUT/scenarios.jar" "$(classpath "$STD")" "${PURE[@]}" "$TESTS/FakeIme.kt" "$TESTS/Scenarios.kt"
    java -cp "$(classpath "$OUT/scenarios.jar" "$STD")" com.localboard.keyboard.engine.correction.Scenarios
    ;;
  junit)
    JUNIT="${JUNIT_JAR:-$AS/lib/junit4.jar}"
    SQLITE="${SQLITE_JAR:-$(ls ~/.gradle/caches/modules-2/files-2.1/org.xerial/sqlite-jdbc/*/*/sqlite-jdbc-*.jar 2>/dev/null | sort -V | tail -1)}"
    [ -f "$JUNIT" ] || { echo "no junit4.jar; set JUNIT_JAR" >&2; exit 1; }
    [ -f "$SQLITE" ] || { echo "no sqlite-jdbc jar in the Gradle cache; set SQLITE_JAR" >&2; exit 1; }
    compile "$OUT/junit.jar" "$(classpath "$STD" "$JUNIT")" "${PURE[@]}" "$TESTS"/*.kt
    P=com.localboard.keyboard.engine.correction
    java -cp "$(classpath "$OUT/junit.jar" "$STD" "$JUNIT" "$SQLITE")" org.junit.runner.JUnitCore \
      $P.CorrectionScenariosTest $P.ScenarioSuiteSizeTest $P.FieldCapabilitiesTest \
      $P.LegacyTypoVerdictsTest $P.RejectionPersistenceTest
    ;;
  gates|hand)
    COR="${COROUTINES_JAR:-$(ls ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.[6-9].*/*/kotlinx-coroutines-core-jvm-1.[6-9].*.jar 2>/dev/null | sort -V | tail -1)}"
    [ -f "$COR" ] || { echo "no kotlinx-coroutines-core-jvm 1.6-1.9 jar in the Gradle cache; set COROUTINES_JAR" >&2; exit 1; }
    FILES="FastEngine Trie KeyboardGeometry SwipeDecoder Suggestion BaseLanguageModel OffensiveWords"
    ENGINE=()
    if grep -q "fun correctionEvidence" "$ENG/FastEngine.kt"; then
      echo "engine: working tree"
      for f in $FILES; do ENGINE+=("$ENG/$f.kt"); done
    else
      echo "engine: $PINNED_COMMIT plus patch_engine.py, in $OUT/engine"
      mkdir -p "$OUT/engine"
      for f in $FILES; do
        if [ -n "${PINNED_ENGINE_DIR:-}" ]; then
          cp "$PINNED_ENGINE_DIR/$f.kt" "$OUT/engine/$f.kt"
        else
          git -C "$REPO" show "$PINNED_COMMIT:$ENG_DIR/$f.kt" > "$OUT/engine/$f.kt"
        fi
        ENGINE+=("$OUT/engine/$f.kt")
      done
      # On Windows python3 can be the store's stub, which runs nothing.
      for py in python3 python; do "$py" --version >/dev/null 2>&1 && break; done
      "$py" "$HERE/patch_engine.py" "$OUT/engine/FastEngine.kt"
    fi
    compile "$OUT/gates.jar" "$(classpath "$COR")" "$REPO/tools/swipe-sim/shims" "$HERE/src" \
      "${PURE[@]}" "$TESTS/FakeIme.kt" "$TESTS/Scenarios.kt" "${ENGINE[@]}"
    CP="$(classpath "$OUT/gates.jar" "$STD" "$COR")"
    ASSETS="$(native "$REPO/app/src/main/assets")"
    [ "$mode" = gates ] && [ $# -eq 0 ] && set -- 11 1009
    # Every engine load logs a line, and the reload model loads dozens.
    java -Xmx3g -cp "$CP" Gates "$ASSETS" "$mode" "$@" 2>&1 | grep --line-buffered -v '^I/FastEngine'
    ;;
  *)
    sed -n '2,8p' "$0"
    exit 1
    ;;
esac
