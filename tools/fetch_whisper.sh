#!/usr/bin/env bash
# Downloads whisper.cpp (master) into app/src/main/cpp/whisper.cpp so that
# liblocalboard_voice.so can be rebuilt. The shipped project contains a
# prebuilt copy, so this is only needed to rebuild the offline voice engine —
# for instance to re-link it with 16 KB page alignment for Play.
set -euo pipefail
cd "$(dirname "$0")/.."
DEST=app/src/main/cpp/whisper.cpp
if [ -d "$DEST" ]; then
  echo "whisper.cpp already present at $DEST"
  exit 0
fi
URL="https://codeload.github.com/ggerganov/whisper.cpp/tar.gz/refs/heads/master"
TMP="$(mktemp -d)"
echo "Downloading whisper.cpp..."
curl -sSL --connect-timeout 20 -o "$TMP/whisper.tar.gz" "$URL"
mkdir -p app/src/main/cpp
tar -xzf "$TMP/whisper.tar.gz" -C "$TMP"
mv "$TMP/whisper.cpp-master" "$DEST"
rm -rf "$TMP"
echo "Done: $DEST"
