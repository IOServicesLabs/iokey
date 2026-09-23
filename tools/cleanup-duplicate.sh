#!/usr/bin/env bash
# Remove the duplicate LocalBoard app, keeping the one actually in use.
#
# Two packages can coexist because the debug build sets
# `applicationIdSuffix = ".debug"`:
#     io.ioservices.iokey         (release)
#     io.ioservices.iokey.debug   (debug)
#
# Uninstalling is DESTRUCTIVE: it erases that package's learned dictionary,
# typing style, and settings. So this script does not assume which one is the
# stray. It asks the system which package is the ENABLED / DEFAULT input
# method — that is the keyboard the user actually configured — and removes
# only the other one.
#
# Usage:
#   tools/cleanup-duplicate.sh            # report only, change nothing
#   tools/cleanup-duplicate.sh --apply    # actually uninstall the unused one

set -euo pipefail
cd "$(dirname "$0")/.."

APPLY=0
[ "${1:-}" = "--apply" ] && APPLY=1

SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
ADB="$SDK/platform-tools/adb"

DEV="$("$ADB" devices | tr -d '\r' | awk 'NR>1 && $2=="device" {print $1; exit}')"
[ -n "$DEV" ] || { echo "no device online. Pair/connect first." >&2; exit 1; }
echo "==> device: $DEV"

REL=io.ioservices.iokey
DBG=io.ioservices.iokey.debug

# `tr -d '\r'` is load-bearing on Windows: adb emits CRLF, so an unstripped
# `grep -x` matches nothing, every package looks absent, and this script would
# draw exactly the wrong conclusion. (That bug already caused two bad installs.)
pkgs=$("$ADB" -s "$DEV" shell "pm list packages io.ioservices.iokey" 2>/dev/null | tr -d '\r')
HAS_REL=$(echo "$pkgs" | grep -cx "package:$REL" || true)
HAS_DBG=$(echo "$pkgs" | grep -cx "package:$DBG" || true)

echo "==> installed: release=$HAS_REL debug=$HAS_DBG"
if [ "$HAS_REL" -eq 0 ] || [ "$HAS_DBG" -eq 0 ]; then
  echo "==> only one variant present — nothing to clean up."
  exit 0
fi

# Evidence, printed for both so the decision is auditable.
DEFAULT_IME=$("$ADB" -s "$DEV" shell "settings get secure default_input_method" 2>/dev/null | tr -d '\r')
ENABLED_IME=$("$ADB" -s "$DEV" shell "settings get secure enabled_input_methods" 2>/dev/null | tr -d '\r')
echo "==> default IME : $DEFAULT_IME"
echo "==> enabled IMEs: $ENABLED_IME"

for p in "$REL" "$DBG"; do
  echo "--- $p"
  "$ADB" -s "$DEV" shell "dumpsys package $p | grep -E 'versionName|firstInstallTime|lastUpdateTime'" 2>/dev/null | tr -d '\r' | sed 's/^/    /'
done

# Decide. The default IME wins outright; failing that, an enabled-but-not-
# default IME wins. If neither package is enabled at all, the user has not set
# either one up as their keyboard, so refuse to guess.
KEEP=""
case "$DEFAULT_IME" in
  "$DBG"/*) KEEP=$DBG ;;
  "$REL"/*) KEEP=$REL ;;
esac
if [ -z "$KEEP" ]; then
  case "$ENABLED_IME" in
    *"$DBG"/*) KEEP=$DBG ;;
  esac
  case "$ENABLED_IME" in
    *"$REL"/*) [ -z "$KEEP" ] && KEEP=$REL ;;
  esac
fi
if [ -z "$KEEP" ]; then
  echo "!! Neither package is an enabled input method. Cannot tell which one" >&2
  echo "!! holds your setup — refusing to uninstall. Enable the keyboard you" >&2
  echo "!! use, then re-run." >&2
  exit 2
fi

DROP=$REL
[ "$KEEP" = "$REL" ] && DROP=$DBG

echo
echo "==> KEEP    $KEEP   (this is your active keyboard)"
echo "==> REMOVE  $DROP   (unused duplicate)"

if [ "$APPLY" != 1 ]; then
  echo
  echo "Report only. Re-run with --apply to uninstall $DROP."
  exit 0
fi

echo "==> uninstalling $DROP"
"$ADB" -s "$DEV" uninstall "$DROP"
echo "==> remaining:"
"$ADB" -s "$DEV" shell "pm list packages io.ioservices.iokey" 2>/dev/null | tr -d '\r' | sed 's/^/    /'
