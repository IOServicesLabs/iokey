#!/usr/bin/env bash
# Rebuilds the two prebuilt native libraries into app/src/main/jniLibs/arm64-v8a:
#
#   liblocalboard_llm.so    llama.cpp + JNI bridge   (LlamaCppBridge)
#   liblocalboard_voice.so  whisper.cpp + JNI bridge (VoiceBridge, optional)
#
# Requires ANDROID_HOME with NDK r27+ installed. r27 is the floor because it is
# the first NDK that links with 16 KB page alignment by default; the cmake files
# also pass -Wl,-z,max-page-size=16384 explicitly so an older NDK would still
# produce a correct binary, but r27 is what these were verified against.
#
# Usage:
#   tools/build_native.sh          # both libraries
#   tools/build_native.sh llm      # just the LLM engine
#   tools/build_native.sh voice    # just the voice engine
set -euo pipefail
cd "$(dirname "$0")/.."

: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK first}"

# Pick the newest installed NDK unless one was named explicitly. Hard-coding a
# single version meant this script broke every time the SDK manager moved on.
if [ -n "${ANDROID_NDK_HOME:-}" ]; then
  NDK="$ANDROID_NDK_HOME"
else
  NDK="$(ls -d "$ANDROID_HOME"/ndk/*/ 2>/dev/null | sort -V | tail -1 || true)"
  NDK="${NDK%/}"
fi
if [ -z "$NDK" ] || [ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]; then
  echo "No usable NDK found (looked in $ANDROID_HOME/ndk). Install ndk;27.0.12077973" >&2
  exit 1
fi
echo "NDK: $NDK"

JOBS="$( (nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4) )"
OUT=app/src/main/jniLibs/arm64-v8a
mkdir -p "$OUT"

# Force Ninja. On Windows, cmake's default generator is Visual Studio, which
# cannot cross-compile with the Android NDK toolchain file and fails during
# configure with an opaque "BaseOutputPath property is not set" MSBuild error.
# The SDK ships a ninja binary, so there is nothing extra to install.
NINJA="$(ls "$ANDROID_HOME"/cmake/*/bin/ninja* 2>/dev/null | sort -V | tail -1 || true)"
GEN_ARGS=(-G Ninja)
[ -n "$NINJA" ] && GEN_ARGS+=(-DCMAKE_MAKE_PROGRAM="$NINJA")

READELF="$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-readelf* 2>/dev/null | head -1 || true)"

# A 4 KB-aligned .so cannot be mapped on an Android 15 device with 16 KB pages,
# and Play rejects the upload for anything targeting API 35+. Checking here —
# at the moment the file is produced — is the difference between finding out now
# and finding out from a store rejection.
verify_alignment() {
  local so="$1"
  [ -n "$READELF" ] || { echo "  (llvm-readelf not found; skipping alignment check)"; return 0; }
  local bad
  bad="$("$READELF" --program-headers "$so" | awk '/LOAD/ {print $NF}' | grep -v '0x4000' || true)"
  if [ -n "$bad" ]; then
    echo "  FAIL: $so has LOAD segments aligned to $bad, expected 0x4000 (16 KB)" >&2
    return 1
  fi
  echo "  OK: $so is 16 KB aligned"
}

build_llm() {
  echo "=== liblocalboard_llm.so"
  [ -d app/src/main/cpp/llama.cpp ] || tools/fetch_llama.sh
  local B=build/native/llm
  cmake -S app/src/main/cpp -B "$B" "${GEN_ARGS[@]}" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-31 \
    -DCMAKE_BUILD_TYPE=Release
  cmake --build "$B" --target localboard_llm -j"$JOBS"
  cp "$B/liblocalboard_llm.so" "$OUT/"
  verify_alignment "$OUT/liblocalboard_llm.so"
}

build_voice() {
  echo "=== liblocalboard_voice.so"
  [ -d app/src/main/cpp/whisper.cpp ] || tools/fetch_whisper.sh
  local B=build/native/voice
  cmake -S app/src/main/cpp/voice -B "$B" "${GEN_ARGS[@]}" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-31 \
    -DCMAKE_BUILD_TYPE=Release
  cmake --build "$B" --target localboard_voice -j"$JOBS"
  cp "$B/liblocalboard_voice.so" "$OUT/"
  verify_alignment "$OUT/liblocalboard_voice.so"
}

case "${1:-all}" in
  llm)   build_llm ;;
  voice) build_voice ;;
  all)   build_llm; build_voice ;;
  *)     echo "Usage: $0 [llm|voice|all]" >&2; exit 2 ;;
esac

echo
ls -la "$OUT"
