# Building IOKey

This file records the toolchain, the exact build commands, and the gotchas
that have actually bitten.

## TL;DR

```bash
cd iokey

./gradlew assembleFullDebug      # -> app/build/outputs/apk/full/debug/app-full-debug.apk
./gradlew assembleFullRelease    # -> app/build/outputs/apk/full/release/app-full-release.apk
```

No environment variables need to be set by hand — `local.properties` points
Gradle at the SDK.

## What is installed

| Component | Version present | Where | Needed? |
|---|---|---|---|
| JDK | **21.0.11** (Eclipse Adoptium) | `%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-21...` | **Yes** — `JAVA_HOME` already points here |
| Android SDK Platform | **36** (also 30/33/34/35) | `%LOCALAPPDATA%\Android\Sdk\platforms` | **Yes** — `compileSdk = 36` |
| Build-tools | 34.0.0, 35.0.0, **35.0.1** | `…\Sdk\build-tools` | **Yes** |
| Platform-tools (`adb`) | present | `…\Sdk\platform-tools` | Only to install onto a phone |
| Gradle | **8.13** | `~/.gradle/wrapper/dists` | **Yes** — fetched by `./gradlew`, no install needed |
| Android Studio | installed | `C:\Program Files\Android\Android Studio` | Optional — the CLI build does not use it |
| NDK | 25.1, 26.3, 27.0 | `…\Sdk\ndk` | **No** — see below |
| CMake | 3.22.1 | `…\Sdk\cmake` | **No** — see below |

### Why the NDK is not required

`app/build.gradle.kts` says it outright:

> The prebuilt `liblocalboard_llm.so` ships in `src/main/jniLibs` — **NO NDK
> required to build this project.** To rebuild the native lib yourself, run
> `tools/build_native.sh` (needs Android NDK r26+).

The LEAP SDK (`ai.liquid.leap:leap-sdk`) also bundles its own arm64 native
libraries. So a normal build is pure Kotlin/Java + resources. The NDK and
CMake above are only needed if you change the C++ under `app/src/main/cpp`.

## What was configured (and why it was needed)

The toolchain was complete, but two things were missing:

1. **`local.properties` did not exist.** Without it — and without
   `ANDROID_HOME` in the environment — Gradle fails with *"SDK location not
   found."* Created:

   ```properties
   sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
   ```

   This file is machine-specific. Do not commit it; Android Studio regenerates
   it automatically.

2. **`ANDROID_HOME` / `ANDROID_SDK_ROOT` were not set** at user or machine
   level. Now persisted as user variables, and `…\Sdk\platform-tools` was
   appended to the user `PATH` so `adb` works from any terminal.

   > These take effect in **newly opened** terminals only.

3. **`gradlew` (the POSIX script) was broken** and failed on any non-Windows
   shell — Git Bash, WSL, macOS, Linux — with:

   ```
   Error: Could not find or load main class "-Xmx64m"
   ```

   This copy of the wrapper is a trimmed 147-line variant (stock Gradle ships
   ~249 lines). It expands `$DEFAULT_JVM_OPTS` **bare** in its final
   `set -- \` block, but kept the stock value `'"-Xmx64m" "-Xms64m"'`, which
   embeds double quotes. Stock Gradle strips those with an
   `eval set -- $(printf … | xargs -n1 | sed … )` pass that this copy dropped,
   so word-splitting produced the literal tokens `"-Xmx64m"` and `"-Xms64m"` —
   quotes included — and `java` treated the first as a class name.

   Fixed by removing the embedded quotes:

   ```sh
   DEFAULT_JVM_OPTS='-Xmx64m -Xms64m'
   ```

   `gradlew.bat` was never affected, which is why Android Studio and `cmd`
   builds always worked and this went unnoticed.

## Toolchain gotcha: two Javas

`java` on `PATH` resolves to **Java 8**
(`C:\Program Files (x86)\Common Files\Oracle\Java\java8path\java.exe`).

That is harmless for Gradle, which uses `JAVA_HOME` (JDK 21) — but any tool
that shells out to `java` from `PATH` gets 1.8. If something ever complains
about an unsupported class file version, that is why. To fix it permanently,
move the JDK 21 `bin` ahead of the Oracle `java8path` entry in `PATH`.

The project targets Java 17 bytecode (`sourceCompatibility`/`jvmTarget = 17`),
which JDK 21 emits fine.

## Installing on a phone

```bash
adb devices                  # confirm the phone is listed & authorised
adb install -r app/build/outputs/apk/full/debug/app-full-debug.apk
```

Debug and release can be installed side by side — debug carries
`applicationIdSuffix = ".debug"`.

## Release signing

The release key is what lets an APK install as an **update** over an existing
install. Anyone holding it can publish something that silently replaces this
app on a user's phone, so it lives outside the repository and outside
`app/build.gradle.kts`.

Gradle resolves it in this order:

1. `keystore.properties` in the project root — gitignored, for local builds
2. `IOKEY_*` environment variables — for CI
3. Neither → **debug signing**, so a fresh clone and every fork pull request
   still build an installable APK (it just cannot update a release install)

### Local

Copy the example and fill it in. Keep the keystore itself outside the repo:

```bash
cp keystore.properties.example keystore.properties
$EDITOR keystore.properties
```

```properties
storeFile=../secrets/release.keystore
storePassword=…
keyAlias=localboard
keyPassword=…
```

Confirm which key actually signed a build — worth doing before pushing to a
phone, because a debug-signed release will refuse to install over a real one:

```bash
apksigner verify --print-certs app/build/outputs/apk/full/release/app-full-release.apk
# Signer #1 certificate DN: CN=LocalBoard, …   ← release key
# Signer #1 certificate DN: CN=Android Debug   ← fallback
```

### CI

Four repository secrets (**Settings → Secrets and variables → Actions**):

| Secret | Value |
|---|---|
| `IOKEY_KEYSTORE_B64` | `base64 -w0 release.keystore` |
| `IOKEY_STORE_PASSWORD` | keystore password |
| `IOKEY_KEY_ALIAS` | `localboard` |
| `IOKEY_KEY_PASSWORD` | key password |

`build.yml` is manual-only (Actions → Build → Run workflow) and uploads both
APKs as artifacts.
`release.yml` runs on a `v*` tag, refuses to publish if the signing secret is
missing, checks the tag matches `versionName`, and attaches the APK plus a
SHA-256 to a GitHub Release.

### Creating a new key

Only if you are starting fresh. **A new key cannot update installs signed with
the old one** — users would have to uninstall, losing their learned dictionary.

```bash
keytool -genkeypair -v -keystore release.keystore \
  -alias localboard -keyalg RSA -keysize 4096 -validity 10000
```

### Versioning

`versionCode` must only ever increase; Android refuses an install whose code is
lower than what is on the device. The scheme is `major*10000 + minor*100 +
patch`, so 1.0.0 is `10000`. Pre-1.0 builds reached 52, which is why 1.0.0
does not start from 1.

## First build is slow, later builds are not

The very first run downloads Gradle 8.13 and, because `compileSdk = 36` was not
installed, Android SDK Platform 36 (~4 min total). After that, `assembleDebug`
is well under a minute.

If Gradle ever wedges: `./gradlew --stop`, then rebuild.

## Native libraries and the 16 KB page size

Two `.so` files are checked in under `app/src/main/jniLibs/arm64-v8a`:

| Library | Engine | Source |
|---|---|---|
| `liblocalboard_llm.so` | llama.cpp + `llm_jni.cpp` | vendored at `app/src/main/cpp/llama.cpp` |
| `liblocalboard_voice.so` | whisper.cpp + `voice_jni.cpp` | **not vendored** — `tools/fetch_whisper.sh` |

Android 15 introduced devices whose kernel page size is **16 KB**. A shared
library whose ELF `LOAD` segments are aligned to the old 4 KB boundary cannot be
mapped on them, so **Google Play rejects any upload targeting API 35+ that
contains one**. This is not a warning — it blocks the release.

Both cmake files pass `-Wl,-z,max-page-size=16384`, and
`tools/build_native.sh` verifies the result before installing it. To check a
library by hand:

```bash
llvm-readelf --program-headers liblocalboard_llm.so | grep LOAD
# every LOAD line must end in 0x4000. 0x1000 means 4 KB — rejected.
```

The same requirement applies to third-party natives. `net.zetetic:sqlcipher-android`
is pinned to **4.6.1 or later** for exactly this reason: 4.5.4 ships a 4 KB
`libsqlcipher.so`. The LEAP SDK's libraries were already 16 KB aligned.

### Rebuilding

```bash
tools/build_native.sh          # both
tools/build_native.sh llm      # llama.cpp only
tools/build_native.sh voice    # whisper.cpp only (fetches it first)
```

Requires NDK **r27+** (r27 is the first that aligns to 16 KB by default) and
picks the newest NDK installed unless `ANDROID_NDK_HOME` says otherwise. On
Windows the script forces the Ninja generator — cmake defaults to Visual Studio
there, which cannot cross-compile with the NDK toolchain and fails during
configure with a misleading `BaseOutputPath property is not set` MSBuild error.

whisper.cpp and llama.cpp each vendor their own copy of ggml, so they cannot be
configured in one cmake project — the `ggml` targets would be defined twice.
That is why the voice engine has its own `app/src/main/cpp/voice/CMakeLists.txt`
and its own build directory.

## Known build-config issues (not blockers)

- There are **no test source sets** (`app/src/test`, `app/src/androidTest`).
  `./gradlew test` therefore does nothing.

## Distributions and Pro

Two product flavours:

| | `full` | `play` |
|---|---|---|
| package | `io.ioservices.iokey.full` | `io.ioservices.iokey` |
| `READ_SMS` + AccessibilityService | yes | **removed** |
| Play Billing | not compiled in | yes |
| Pro features | unlocked outright | one-time purchase |

```bash
./gradlew assembleFullRelease     # sideload build
./gradlew assemblePlayRelease     # store build
```

### The Pro split

The free keyboard is a complete, private keyboard. Everything protective is
free permanently — kill switch, egress ledger, incognito, per-app rules,
encryption, backup, and every accessibility affordance. Paywalling privacy
would make it a luxury good and undermine the only thing the app is selling.

Pro is the expensive-to-build, nice-to-have layer: on-device AI drafting,
Theme Studio, per-app themes, animated backgrounds and custom fonts. The line
lives in one place — `billing/Pro.kt`, `ProFeature` — so it cannot drift
across call sites.

Gates sit at chokepoints, not at buttons. AI is gated inside
`runGeneration()`, which every drafting path funnels through, so there is no
second entry point to forget.

### Play Console setup

Create a **one-time in-app product** with id `localboard_pro` (see
`PRODUCT_ID` in `src/play/.../ProProviderImpl.kt`). It must be *active* before
the store returns a price, and the app shows the buy button disabled until it
does.

#### Listing requirements

`PRIVACY.md` and `TERMS.md` are the source of truth; `docs/` is the same
content as a self-contained static site (no external requests — it would be a
poor look for a privacy app's policy page to load a tracker). Point GitHub
Pages at the `docs/` folder and the policy URL is
`https://<user>.github.io/<repo>/privacy.html`.

#### Data safety form

The honest answers, and the code that backs each one:

| Question | Answer | Why |
|---|---|---|
| Does your app collect or share user data? | **No** | There is no backend. The only outbound requests are user-initiated model downloads (`ModelRegistry`, `VoiceModelManager`), which carry no user data. |
| Is data encrypted in transit? | n/a — no data is sent | Model downloads are plain HTTPS GETs |
| Data deletion mechanism | In-app | *Settings → Data controls* erases; uninstall destroys the Keystore key |

Two things will still be asked about, so have the answer ready:

- **`RECORD_AUDIO`** — declared for offline voice typing. Transcription is
  local (`VoiceBridge` → whisper.cpp); audio is never written to disk or sent.
- **`READ_CONTACTS`** — off by default, opt-in, names only, tokenised into the
  local encrypted dictionary.

The Play build has no `READ_SMS` and no AccessibilityService at all, so neither
appears on the form. `build.yml` fails the build if either ever leaks in.

The entitlement is cached locally and never re-verified over the network: a
privacy keyboard that stops working because it could not phone home would be
contradicting itself, and the keyboard has to work in aeroplane mode. The
store is consulted to *grant*, never to *revoke*.
