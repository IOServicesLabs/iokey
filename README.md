# IOKey

[![Build](https://github.com/IOServicesLabs/iokey/actions/workflows/build.yml/badge.svg)](https://github.com/IOServicesLabs/iokey/actions/workflows/build.yml)
[![Play Store: coming soon](https://img.shields.io/badge/Play%20Store-coming%20soon-34a853?logo=googleplay&logoColor=white)](https://github.com/IOServicesLabs/iokey/releases)

An Android keyboard that runs a language model on the phone and keeps what it
learns there. Gboard-style typing — glide, autocorrect, emoji, themes — with an
on-device LLM for drafting replies, and no account, no telemetry, no analytics.

```
package    io.ioservices.iokey        (Play)
           io.ioservices.iokey.full   (direct download)
minSdk 31 · targetSdk 36 · arm64-v8a
```

---

## What it does

| Area | Detail |
|---|---|
| Typing | Custom-rendered layouts (QWERTY, QWERTZ, AZERTY, Colemak, Dvorak), two symbol pages, optional number row, long-press alternates with drag selection |
| Autocorrect | 114k-word dictionary in a char trie, trie-guided Damerau-Levenshtein search weighted by real key adjacency, ranked with a bundled 176k-entry English bigram model and blended with a personal unigram/bigram/trigram model. Splits run-on words when the space bar is missed |
| Next word | Bundled bigram model plus the user's own n-grams; optional on-device LLM predictions from the whole message (quick model, incremental KV cache, well under 150 ms per word) |
| Glide typing | Two-stage decoder: cheap order-aware letter fit and language prior over every candidate, reverse chamfer on the survivors. Dwell detection for doubled letters, per-user gesture bias, alternates held on the bar for one-tap correction |
| Fit to your hands | A calibration test measures where your fingers actually land and **moves the keys** to match, redistributing key boundaries so the row stays exactly tiled |
| Key customiser | Presets plus per-key switches with a live preview; every key you remove hands its width to the space bar |
| On-device LLM | llama.cpp and Liquid AI LEAP backends, GGUF models, streaming drafts that imitate your own writing. Hold the ✨ to instruct it directly |
| Voice | Offline transcription via whisper.cpp; audio never leaves the device |
| Emoji | Eight categories plus recents, skin-tone selection, Unicode 13–16 additions filtered to what the system font can actually render |
| Themes and fonts | Full 12-colour themes, live preview, shareable codes, per-app themes, auto-generated pastel palettes with guaranteed 4.5:1 contrast. Font picker filtered to faces the device can genuinely draw differently |
| Motion | Five animated background styles at reduced resolution, plus a fillable strip behind the navigation bar |
| Storage | Room + SQLCipher, key held in the Android Keystore |

### Free vs Pro

Everything protective is free, permanently. Paywalling privacy on a privacy
keyboard would undermine the only thing the app is selling.

**Free** — typing, autocorrect, glide, offline voice, every layout and key
customisation, the network kill switch, the egress ledger, incognito, per-app
rules, encrypted storage, backup and export, and every accessibility
affordance.

**Pro**, a single one-time purchase — AI drafting, Theme Studio, per-app
themes, animated backgrounds, custom fonts.

The line lives in exactly one file, `billing/Pro.kt`, so it cannot drift
across call sites. The entitlement is cached on the device and never
re-verified over the network: a privacy keyboard that stops working because it
could not phone home would be contradicting itself, and it has to work in
aeroplane mode.

### Language data

`base_bigrams.txt` is derived from Peter Norvig's `count_2w.txt` (the most
common two-word sequences of the Google Web Trillion Word Corpus, published at
norvig.com/ngrams), filtered to the shipped dictionary, with the corpus's
apostrophe-less spellings mapped back to real contractions. The build script
is `tools/build_bigrams.py`. The underlying corpus is distributed by the LDC;
check that this derivative is acceptable for your distribution before
publishing.

## Two distributions

Google Play will not accept everything this keyboard can do, so there are two
product flavours — and the difference is **removal, not a feature flag**.

| | `full` | `play` |
|---|---|---|
| Package | `io.ioservices.iokey.full` | `io.ioservices.iokey` |
| `READ_SMS` writing-style import | yes | **absent** |
| Accessibility screen reading | yes | **absent** |
| Play Billing | not compiled in | yes |
| Pro features | unlocked outright | one-time purchase |

The permission and the service are declared in the `full` source set only, so
the Play manifest never mentions them and a reviewer never has to take our
word for it. CI fails the build if either ever leaks into the Play manifest.

## Privacy, stated precisely

Typing never leaves the device. That is the actual guarantee, and it is worth
being exact about what backs it rather than claiming more than is true.

**The app declares `INTERNET`.** It is used for one thing: downloading a GGUF
model or the voice model, when you ask for one. There is no analytics SDK, no
crash reporting service, no account.

Because "trust us" is not a mechanism, the app also ships:

- **A network kill switch.** Turn it off and every download path refuses to
  open a socket — `NetGuard.require()` throws before a connection is made.
- **An egress ledger.** Every outbound request, allowed or blocked, is logged
  with its host and shown in Settings. An empty list is evidence; a surprising
  entry is a bug report.
- **Incognito support.** `IME_FLAG_NO_PERSONALIZED_LEARNING` is honoured, so
  Signal, private browser tabs and similar teach the keyboard nothing.
- **Per-app rules** for learning, screen reading and AI, plus redaction of
  one-time codes and card numbers from captured screen text.

Optional permissions, each requested only when you turn the feature on:

| Permission | Used for | Distribution |
|---|---|---|
| `RECORD_AUDIO` | Offline voice typing | both |
| `READ_CONTACTS` | Learning names so autocorrect stops mangling them | both |
| `READ_USER_DICTIONARY` | Importing words you already added system-wide | both |
| `READ_SMS` | One-time writing-style import during onboarding | `full` only |
| Accessibility service | Reading on-screen conversation text for AI context; RAM-only, off until you enable it | `full` only |

Full text: [Privacy Policy](PRIVACY.md) · [Terms of Use](TERMS.md). The same
content is a self-contained static site in [`docs/`](docs/) for hosting.

## Build

Requirements: **JDK 17 or newer** and the Android SDK with **platform 36** and
build-tools 35+. Opening the folder in Android Studio handles both.

```bash
# Point at your SDK if you are not using Android Studio
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

./gradlew assembleFullRelease     # direct-download build
./gradlew assemblePlayRelease     # store build
```

Outputs land in `app/build/outputs/apk/<flavour>/<type>/`. There is no plain
`assembleRelease` variant — every build names a flavour.

A release build with no signing configuration **falls back to the debug key**,
so a fresh clone always produces something installable. This is deliberate,
and it is silent: the build succeeds and the APK simply cannot update a real
install. Check before shipping:

```bash
apksigner verify --print-certs app/build/outputs/apk/full/release/app-full-release.apk
```

See [BUILD.md](BUILD.md) for release signing, CI secrets and the versioning
scheme.

### Native libraries

Both native libraries ship prebuilt in `app/src/main/jniLibs/arm64-v8a`, so
**no NDK is needed** for a normal build:

| Library | Engine | Source |
|---|---|---|
| `liblocalboard_llm.so` | llama.cpp | vendored in `app/src/main/cpp/llama.cpp` |
| `liblocalboard_voice.so` | whisper.cpp | fetched by `tools/fetch_whisper.sh` |

`tools/build_native.sh` rebuilds them (NDK **r27+**). Every bundled `.so` must
have its `LOAD` segments aligned to **16 KB** — Play rejects anything targeting
API 35+ that does not, and the build script verifies alignment before
installing the result.

## Install

```bash
tools/install.sh          # matches whichever variant is already on the device
```

Build variants are **different packages** (`.full`, `.debug` suffixes), so they
cannot update one another. The script checks the device rather than guessing;
`tools/cleanup-duplicate.sh` removes a duplicate if two ever get installed.

Installing over an existing IOKey keeps your settings and learned dictionary.
**Uninstalling erases them** — export a backup first from *Settings → Data and
network controls*. Changing the `applicationId` has the same effect as
uninstalling, because Android treats the result as an unrelated app.

## Layout

Source lives under `com.localboard.keyboard` — the Kotlin package and the
Gradle `namespace`, which only decide where `R` and `BuildConfig` are
generated. The shipped application id is `io.ioservices.iokey`; the two are
independent, and renaming the package would rewrite every `R` import for no
user-visible gain.

```
app/src/main/java/com/localboard/keyboard/
├── ime/          input pipeline, composing state, cursor pad, incognito
├── engine/       trie, autocorrect, glide decoder, keyboard geometry
├── context/      learning, style profile, screen-context capture
├── llm/          provider interface, llama.cpp + LEAP backends, model catalog
├── ui/           key rendering, layouts, prediction bar, emoji, live background
├── billing/      the free/Pro line (Pro.kt is the single source of truth)
├── data/db/      Room + SQLCipher
├── settings/     settings, theme studio, key customiser, privacy controls
└── util/         prefs, backup, network guard, frame counters
app/src/full/     screen reading + SMS import (absent from the Play build)
app/src/play/     Play Billing + stubs with matching signatures
```

## Working on it

Things worth knowing before changing the draw path, the engine, or the IME
lifecycle. Each of these has caused a shipped regression.

- **`KeyboardView` caches the key grid in a bitmap** and repaints single keys
  via dirty rects. Anything that moves keys must invalidate that cache;
  `computeKeyRects()` does it centrally for exactly this reason.
- **The cut-out and the invalidate are different rectangles.** `keyClipRect`
  removes only a key's own face; `keyPaintRect` sizes the repaint. A margin
  proportional to key width is safe on letters and eats neighbours on the
  3-column number pad.
- **`FastEngine`'s correction search is budgeted.** Raising the dictionary size
  without raising the budget silently degrades autocorrect.
- **Android recreates the input view on any configuration change** — rotation,
  day/night, density, an app going fullscreen. State cached on the *service*
  that describes the *view* goes stale silently. Cache it on the view instead.
- **A custom `View` named in a layout file needs a `(Context, AttributeSet)`
  constructor.** A Kotlin single-argument constructor compiles cleanly and then
  throws `InflateException` the first time the keyboard is shown.
- **A green build proves very little here.** Most of the above are invisible
  until the second view, the wide layout, or the rotated screen.

CI is manual-only — **Actions → Build → Run workflow** — because a full run
builds both flavours of an app that vendors llama.cpp and takes ~10 minutes.
Fork PR builds cannot read repository secrets, so their APK is debug-signed —
expected, and the workflow summary says so. Release tags are built by
`release.yml` automatically.

## Third-party

| Component | License |
|---|---|
| [llama.cpp](https://github.com/ggerganov/llama.cpp) (vendored) | MIT |
| [whisper.cpp](https://github.com/ggerganov/whisper.cpp) (fetched) | MIT |
| [Atkinson Hyperlegible](https://brailleinstitute.org/freefont) | SIL OFL 1.1 — see `app/src/main/assets/` |
| SQLCipher for Android | BSD-style |
| Liquid AI LEAP SDK | Vendor terms |

Downloaded models carry their own licenses (Qwen, LFM2, SmolLM2, Llama, Gemma,
Phi) — check the model card before redistributing anything.

## License

[MIT](LICENSE) — © IO Services Labs, 2026. The app sells a Pro unlock in the
Play flavour, but the source is open: anyone can build the `full` flavour with
every feature unlocked outright.

Contact: **support@ioservices.io**
