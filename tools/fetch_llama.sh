#!/usr/bin/env bash
# Downloads llama.cpp (master) into app/src/main/cpp/llama.cpp for native rebuilds.
# The shipped project already contains a prebuilt liblocalboard_llm.so, so this
# is only needed if you want to modify/rebuild the native code.
set -euo pipefail
cd "$(dirname "$0")/.."
DEST=app/src/main/cpp/llama.cpp
if [ -d "$DEST" ]; then
  echo "llama.cpp already present at $DEST"
  exit 0
fi
URL="https://codeload.github.com/ggerganov/llama.cpp/tar.gz/refs/heads/master"
echo "Downloading llama.cpp..."
curl -sSL --connect-timeout 20 -o /tmp/llama.tar.gz "$URL"
mkdir -p app/src/main/cpp
tar -xzf /tmp/llama.tar.gz -C app/src/main/cpp
mv app/src/main/cpp/llama.cpp-master "$DEST"
rm /tmp/llama.tar.gz
echo "Done: $DEST"
