#!/usr/bin/env python3
"""Regenerate the emoji palette (Unicode emoji-test.txt order) for IOKey.

Reads the downloaded Unicode data files in tools/emoji/data/ and rewrites:
  - app/src/main/java/com/localboard/keyboard/util/EmojiData.kt
      categories = every fully-qualified emoji in emoji-test.txt that has no
      skin-tone modifier, in file order, grouped by the file's own
      "# group:" headers (labels are the Unicode group names).
      The hand-curated wordToEmoji keyword block is preserved verbatim.
  - app/src/main/java/com/localboard/keyboard/util/EmojiTone.kt
      acceptsTone allowlist = the Emoji_Modifier_Base property from
      emoji-data.txt, emitted as merged hex ranges.

Deterministic: same input files produce byte-identical output. Run it twice
and diff to confirm.

Usage:
  python -I generate.py [--data DIR] [--repo ROOT]

To update to a newer Unicode version, replace the files in data/ (keep
unicode-license.txt alongside) and rerun. Data source: https://unicode.org
(Public/emoji/). The palette intentionally ships every fully-qualified
sequence, including ones an older system font draws as boxes; EmojiCatalog
filters at runtime to what the device can render.
"""
import argparse
import re
import sys
from pathlib import Path

TONES = {0x1F3FB, 0x1F3FC, 0x1F3FD, 0x1F3FE, 0x1F3FF}

# Expected per-group palette sizes, from the Unicode 18.0 audit
# (overhaul/look/study-work/emoji/out/report.json). The generator fails
# loudly if a data update changes these, so the numbers are re-confirmed
# rather than silently drifting.
EXPECTED_GROUPS = {
    "Smileys & Emotion": 172,
    "People & Body": 390,
    "Animals & Nature": 161,
    "Food & Drink": 132,
    "Travel & Places": 221,
    "Activities": 85,
    "Objects": 268,
    "Symbols": 224,
    "Flags": 270,
}
PER_LINE = 12


def parse_emoji_test(path: Path):
    """Ordered [(group, [emoji])] of fully-qualified, non-skin-tone entries."""
    line_re = re.compile(r"^([0-9A-F ]+?)\s*;\s*([a-z-]+)\s*#\s*\S+\s+E[0-9.]+\s+.*$")
    group = None
    groups = {}  # name -> list, insertion-ordered
    for raw in path.read_text(encoding="utf-8").splitlines():
        if raw.startswith("# group:"):
            group = raw.split(":", 1)[1].strip()
            continue
        if not raw or raw.startswith("#"):
            continue
        m = line_re.match(raw)
        if not m:
            continue
        if m.group(2) != "fully-qualified":
            continue
        s = "".join(chr(int(x, 16)) for x in m.group(1).split())
        if any(ord(c) in TONES for c in s):
            continue
        groups.setdefault(group, []).append(s)
    return [(g, groups[g]) for g in groups]


def parse_modifier_base(path: Path):
    """Emoji_Modifier_Base codepoints from emoji-data.txt."""
    out = set()
    for raw in path.read_text(encoding="utf-8").splitlines():
        raw = raw.split("#", 1)[0].strip()
        if not raw:
            continue
        rng, prop = [x.strip() for x in raw.split(";")]
        if prop != "Emoji_Modifier_Base":
            continue
        if ".." in rng:
            a, b = rng.split("..")
            out.update(range(int(a, 16), int(b, 16) + 1))
        else:
            out.add(int(rng, 16))
    return out


def emit_categories(groups):
    lines = []
    for name, emoji in groups:
        lines.append(f'        "{name}" to listOf(')
        for i in range(0, len(emoji), PER_LINE):
            row = ", ".join(f'"{e}"' for e in emoji[i:i + PER_LINE])
            comma = "," if i + PER_LINE < len(emoji) else ""
            lines.append(f"            {row}{comma}")
        lines.append("        ),")
    return "\n".join(lines)


def emit_tone_ranges(base):
    ranges = []
    for cp in sorted(base):
        if ranges and cp == ranges[-1][1] + 1:
            ranges[-1][1] = cp
        else:
            ranges.append([cp, cp])
    parts = [f"0x{a:04X}..0x{b:04X}" for a, b in ranges]
    lines = []
    per = 3
    for i in range(0, len(parts), per):
        chunk = " || ".join(f"cp in {p}" for p in parts[i:i + per])
        suffix = " ||" if i + per < len(parts) else ""
        lead = "        " if i == 0 else "            "
        lines.append(f"{lead}{chunk}{suffix}")
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=str(Path(__file__).parent / "data"))
    ap.add_argument("--repo", default=str(Path(__file__).resolve().parent.parent.parent))
    args = ap.parse_args()
    data = Path(args.data)
    repo = Path(args.repo)
    util = repo / "app/src/main/java/com/localboard/keyboard/util"

    groups = parse_emoji_test(data / "emoji-test.txt")
    mod_base = parse_modifier_base(data / "emoji-data.txt")

    got = {g: len(e) for g, e in groups}
    if got != EXPECTED_GROUPS:
        print("ERROR: group counts differ from the Unicode 18.0 audit:", file=sys.stderr)
        for g in sorted(set(got) | set(EXPECTED_GROUPS)):
            if got.get(g) != EXPECTED_GROUPS.get(g):
                print(f"  {g}: got {got.get(g)}, expected {EXPECTED_GROUPS.get(g)}", file=sys.stderr)
        print("If the data files were updated on purpose, adjust EXPECTED_GROUPS.", file=sys.stderr)
        sys.exit(1)
    total = sum(got.values())
    print(f"palette: {total} entries in {len(groups)} groups")
    print(f"modifier bases: {len(mod_base)}")

    # wordToEmoji is a hand-curated keyword map; carry it over verbatim.
    old = (util / "EmojiData.kt").read_text(encoding="utf-8")
    tail_start = old.index("    /** Keyword")
    tail = old[tail_start:].rstrip("\n")
    if not tail.endswith("}"):
        raise SystemExit("EmojiData.kt tail does not end with the object brace")
    tail = tail[:-1]  # object close re-added by the template below

    version = next(
        l for l in (data / "emoji-test.txt").read_text(encoding="utf-8").splitlines()
        if l.startswith("# Version")
    ).split(":", 1)[1].strip()

    out = f"""package com.localboard.keyboard.util

/** Emoji catalog: categories for the panel + keyword index for predictions.
 *
 * GENERATED FILE — do not edit by hand. The category lists below come from
 * the Unicode emoji-test.txt (version {version}) in tools/emoji/data/ and
 * follow its order exactly: every fully-qualified sequence without a
 * skin-tone modifier, grouped by the file's own group headers. Rerun
 * tools/emoji/generate.py after replacing the data files.
 *
 * Entries a device's font cannot draw are filtered at display time by
 * EmojiCatalog, so shipping the full set is safe on older phones.
 */
object EmojiData {{

    // (label, emoji rows) — displayed in the emoji panel, in emoji-test.txt
    // order. Labels are the Unicode group names.
    val categories: List<Pair<String, List<String>>> = listOf(
{emit_categories(groups)}
    )

{tail}}}"""
    (util / "EmojiData.kt").write_text(out, encoding="utf-8")
    print("wrote", util / "EmojiData.kt")

    tone = (util / "EmojiTone.kt").read_text(encoding="utf-8")
    start = tone.index("    private fun acceptsTone(cp: Int): Boolean =")
    end = tone.index("    /** Re-render")
    replacement = (
        "    private fun acceptsTone(cp: Int): Boolean =\n"
        + emit_tone_ranges(mod_base)
        + "\n\n"
    )
    tone = tone[:start] + replacement + tone[end:]
    # Record the source of the table in the doc comment above it.
    tone = tone.replace(
        "     * allowlist rather than a blanket append.\n     */",
        "     * allowlist rather than a blanket append.\n     *\n"
        "     * The table is generated from the Emoji_Modifier_Base property in\n"
        "     * emoji-data.txt by tools/emoji/generate.py; do not edit by hand.\n     */",
    )
    (util / "EmojiTone.kt").write_text(tone, encoding="utf-8")
    print("wrote", util / "EmojiTone.kt")


if __name__ == "__main__":
    main()
