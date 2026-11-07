# Emoji palette generator

Regenerates the keyboard's emoji palette from the Unicode emoji data files.

- `generate.py` — reads `data/emoji-test.txt` and `data/emoji-data.txt`,
  rewrites `app/src/main/java/com/localboard/keyboard/util/EmojiData.kt`
  (category lists, in emoji-test.txt order, fully-qualified entries without
  skin-tone modifiers, labelled with the Unicode group names) and the
  `acceptsTone` allowlist in `util/EmojiTone.kt` (the Emoji_Modifier_Base
  property). The hand-curated `wordToEmoji` keyword map in EmojiData.kt is
  carried over verbatim. Deterministic: rerunning produces byte-identical
  output.
- `verify.py` — checks the generated EmojiData.kt against emoji-test.txt
  (order-exact, no duplicates) and the audit's per-group expectations in
  report.json (path passed as the first argument; defaults to the study-work
  copy under the profile's .claude directory).

Run from anywhere:

```
python -I generate.py
python -I verify.py [path/to/report.json]
```

To move to a newer Unicode version: download fresh `emoji-test.txt` and
`emoji-data.txt` from https://unicode.org/Public/emoji/ into `data/` (keep
`unicode-license.txt` alongside — the Unicode data files carry the Unicode
license), update `EXPECTED_GROUPS` in `generate.py` to the new group counts,
then run `generate.py` and `verify.py`.
