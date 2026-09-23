#!/usr/bin/env bash
# Install LocalBoard to a connected device — IN PLACE, always.
#
# WHY THIS SCRIPT EXISTS
# ----------------------
# The debug build sets `applicationIdSuffix = ".debug"`, so it is package
# `io.ioservices.iokey.debug` — a DIFFERENT APP from the release
# `io.ioservices.iokey`. Installing the debug APK onto a phone that runs
# the release build does not update it; it adds a second keyboard with an
# empty dictionary, no learned style, and none of your settings.
#
# On a daily-driver phone that is never what you want. So this script does not
# guess: it ASKS THE DEVICE which variant is installed and updates that one.
# Whichever package you actually set up and use is the one that gets updated.
#
# Usage:
#   tools/install.sh              # build + install, matching the device
#   tools/install.sh --no-build   # install the existing APK
#   tools/install.sh --release    # force io.ioservices.iokey
#   tools/install.sh --debug      # force io.ioservices.iokey.debug

set -euo pipefail
cd "$(dirname "$0")/.."

VARIANT=""          # empty = auto-detect from what's on the device
BUILD=1
for arg in "$@"; do
  case "$arg" in
    --debug)    VARIANT=debug ;;
    --release)  VARIANT=release ;;
    --no-build) BUILD=0 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
ADB="$SDK/platform-tools/adb"

DEV="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
[ -n "$DEV" ] || { echo "no device online. Pair/connect first, or plug in USB." >&2; exit 1; }
echo "==> device: $DEV"

# AUTO-DETECT the variant already installed, and match it. This is the whole
# point of the script: whichever package the user actually set up and uses is
# the one that must be updated. Installing the other variant adds a second
# keyboard with an empty dictionary instead of updating anything.
# The carriage-return strip below is load-bearing. adb on Windows returns
# CRLF, so `grep -x "package:io.ioservices.iokey"` never matches, every
# package looks absent, and this script concludes "nothing installed" and
# installs the WRONG variant — the exact failure it exists to prevent.
# (Not hypothetical: it happened twice on 2026-07-28.)
pkgs=$("$ADB" -s "$DEV" shell "pm list packages io.ioservices.iokey" 2>/dev/null | tr -d '\r')
HAS_REL=$(echo "$pkgs" | grep -cx "package:io.ioservices.iokey" || true)
HAS_DBG=$(echo "$pkgs" | grep -cx "package:io.ioservices.iokey.debug" || true)

if [ -z "$VARIANT" ]; then
  if   [ "$HAS_DBG" -gt 0 ] && [ "$HAS_REL" -gt 0 ]; then
    echo "!! BOTH variants are installed. Pick one explicitly:" >&2
    echo "     tools/install.sh --release   (io.ioservices.iokey)" >&2
    echo "     tools/install.sh --debug     (io.ioservices.iokey.debug)" >&2
    exit 2
  elif [ "$HAS_DBG" -gt 0 ]; then
    VARIANT=debug;   echo "==> detected installed: io.ioservices.iokey.debug -> updating that, in place"
  elif [ "$HAS_REL" -gt 0 ]; then
    VARIANT=release; echo "==> detected installed: io.ioservices.iokey -> updating that, in place"
  else
    VARIANT=release; echo "==> nothing installed yet -> first install of the release build"
  fi
fi

APK="app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
PKG=io.ioservices.iokey
[ "$VARIANT" = debug ] && PKG=io.ioservices.iokey.debug

# Explicit flag that contradicts the device: warn loudly, don't silently
# create a second keyboard.
if { [ "$VARIANT" = release ] && [ "$HAS_DBG" -gt 0 ] && [ "$HAS_REL" -eq 0 ]; } ||
   { [ "$VARIANT" = debug ] && [ "$HAS_REL" -gt 0 ] && [ "$HAS_DBG" -eq 0 ]; }; then
  echo "!! The device has the OTHER variant installed."
  echo "!! Installing $PKG will add a SECOND keyboard with no learned data,"
  echo "!! and will not update the one you are using."
  printf '   continue anyway? [y/N] '
  read -r reply
  [ "$reply" = y ] || { echo "aborted."; exit 1; }
fi

if [ "$BUILD" = 1 ]; then
  echo "==> building $VARIANT"
  sh ./gradlew "assemble${VARIANT^}" --console=plain -q
fi
[ -f "$APK" ] || { echo "missing $APK" >&2; exit 1; }

# Signature check BEFORE installing. An APK signed with a different key than
# the installed copy cannot update it — Android rejects it outright
# (INSTALL_FAILED_UPDATE_INCOMPATIBLE) and the only fix is uninstalling,
# which destroys the user's dictionary and settings. Catch it here, while
# it is still a message instead of a lost profile.
INSTALLED="$("$ADB" -s "$DEV" shell "dumpsys package $PKG | grep -m1 -o 'signatures=\[[^]]*'" 2>/dev/null || true)"
if [ -n "$INSTALLED" ]; then
  echo "==> $PKG already installed — updating in place"
else
  echo "==> $PKG not present — first install"
fi

echo "==> installing $APK"
if ! "$ADB" -s "$DEV" install -r -d "$APK"; then
  echo
  echo "INSTALL FAILED. If this says UPDATE_INCOMPATIBLE, the installed copy" >&2
  echo "was signed with a different key. Do NOT blindly uninstall — that" >&2
  echo "erases the learned dictionary and style. Back up first via" >&2
  echo "  Settings -> Privacy -> what's stored on this device" >&2
  exit 1
fi

echo "==> installed. Version on device:"
"$ADB" -s "$DEV" shell "dumpsys package $PKG | grep -m1 versionName"
