# IOKey — Privacy Policy

**Effective date:** 31 July 2026
**Applies to:** IOKey for Android (`io.ioservices.iokey`)
**Contact:** support@ioservices.io

---

## The short version

**IOKey does not collect anything. Nothing you type is sent anywhere.**

There is no account, no sign-in, no analytics, no advertising, no crash
reporting service, and no server that belongs to us — we do not operate a
backend at all. Everything the keyboard learns about you stays in encrypted
storage on your own phone and is deleted when you uninstall the app.

A keyboard sees every password, message and search you type. That is why this
app is built so that the data physically cannot leave: not as a promise, but
because there is nowhere for it to go.

The rest of this document is the detail behind that claim.

---

## 1. What we collect

**Nothing.** We operate no servers and receive no data from the app — not
usage statistics, not diagnostics, not your device identifier, not a count of
installs beyond what Google Play reports to every developer.

We cannot identify you, and we could not hand over your typing data if we were
asked for it, because we have never had it.

---

## 2. What the app stores on your device

All of this lives only on your phone, in the app's private storage. Other apps
cannot read it.

| What | Why | Where |
|---|---|---|
| Words and phrases you type | Autocorrect, predictions and glide typing get better the more they match how *you* write | Encrypted database |
| A learned personal dictionary | So names and slang you use stop being autocorrected away | Encrypted database |
| Your settings, theme and layout | To remember how you set the keyboard up | App preferences |
| Optional: contact first names | Only if you turn on "learn contact names", so people's names stop being corrected. Only names are read — never numbers, emails or any other field | Encrypted database, as isolated words |
| Optional: a downloaded AI model | If you choose to download one | App file storage |
| The last few crash reports | Shown to you in Settings so a bug can be described accurately. Never uploaded | Plain text file, capped in size |

**Encryption.** The typing database is encrypted with SQLCipher (AES-256). The
key is generated on your device, stored in the Android Keystore — hardware-backed
on phones that have a secure element — and never leaves it. It is not derived
from anything we know, and we have no copy.

### Deleting it

- **Settings → Data controls** lets you erase the learned dictionary and typing
  history at any time, and inspect exactly what has been learned before you do.
- **Uninstalling the app** destroys all of it, including the encryption key.

There is no server-side copy to request the deletion of, because there is no
server.

---

## 3. When the app uses the network

The app declares the `INTERNET` permission and uses it for exactly two things,
both of which you start deliberately:

1. **Downloading an optional AI language model** that you pick in
   *Settings → Language model*. The download goes to Hugging Face
   (`huggingface.co`). The request contains no personal data — it is an
   anonymous file download, like any other. Once the file is on your phone the
   model runs entirely offline.
2. **Downloading an optional offline voice-typing model**, from the same place
   and on the same terms.

Nothing else ever goes out. Specifically: your keystrokes, your dictionary, your
messages, your clipboard and your settings are never transmitted, not even in
aggregated or anonymised form.

Two controls let you verify this rather than trust it:

- **The network kill switch** (*Settings → Data controls*) blocks the app from
  opening any connection at all. The keyboard is fully functional with it on.
- **The egress ledger** records every network request the app makes, with what
  it was for, so you can audit the claim above for yourself.

---

## 4. Permissions, and why each one exists

| Permission | What it is for | Optional? |
|---|---|---|
| `INTERNET` | The two model downloads in §3 | Can be disabled entirely by the kill switch |
| `VIBRATE` | Vibration when you press a key | Turn haptics off and it is unused |
| `READ_USER_DICTIONARY` | One-tap import of words you already added to Android's personal dictionary | Yes — only on your tap |
| `RECORD_AUDIO` | Offline voice typing. Audio is transcribed **on your device** by a local model and is never recorded, stored or uploaded | Yes — requested only when you tap the microphone |
| `READ_CONTACTS` | Only if you enable "learn contact names". Reads names only; no numbers, no emails, no other field. Names are stored as isolated words in the local dictionary | Yes — off by default |
| Billing | To sell the one-time Pro unlock through Google Play | Only in the Play Store version |

Some permissions are added automatically by Google's own libraries (for example
a wake lock and boot-completed receiver used by the scheduler that improves your
predictions overnight while the phone is charging). They are not used to send
anything.

**IOKey never asks for location, camera, photos, files, call logs, or
device identifiers, because it has no use for them.**

---

## 5. Incognito and per-app rules

When an app asks Android for a private text field — a password box, or a browser
in private-browsing mode — IOKey honours it and learns nothing from what you
type there.

You can also set your own rules per app in *Settings → Data controls*: pick any
app and tell the keyboard never to learn from it.

---

## 6. Payments

The Pro unlock is a **one-time purchase**, not a subscription.

The transaction is handled entirely by **Google Play Billing**. We never see or
receive your card number, billing address, or any other payment detail — Google
tells the app only whether a purchase for this account exists.

Once bought, the unlock is remembered locally on the device. The app does **not**
phone home to re-check it, which means Pro keeps working in aeroplane mode and
keeps working if we ever disappear.

Google's handling of the payment is covered by
[Google's Privacy Policy](https://policies.google.com/privacy).

---

## 7. Children

IOKey is not directed at children under 13 and does not knowingly collect
information from them. Since the app collects no information from anyone, there
is nothing for us to hold or delete.

---

## 8. Your rights (GDPR, CCPA and similar)

Laws like the GDPR and CCPA give you the right to access, correct, export and
delete the personal data a company holds about you, and to know who it has been
sold or shared with.

**We hold no personal data about you, and we sell and share none**, so there is
nothing to request from us. The equivalent controls are in the app itself and
work instantly and offline:

- **Access** — *Settings → My writing style* shows everything the keyboard has
  learned.
- **Export** — *Settings → Data controls* exports it to an encrypted file you
  control.
- **Deletion** — the same screen erases it, or uninstall the app.

If you are in the EEA or UK, the legal basis for the on-device processing
described above is your consent, given by installing and enabling the keyboard
and by turning on each optional feature individually. Withdrawing it is done by
turning the feature off.

---

## 9. The direct-download version

> **This section does not apply to the Google Play version of IOKey.**
> The two capabilities below are *absent* from the Play build — the permission
> is not requested and the code is not included in the app at all.

A separate build of IOKey is distributed directly (outside the Play Store)
with two extra, opt-in capabilities:

- **Writing-style import from SMS.** If you explicitly choose it during setup,
  the app makes one pass over messages *you have sent* to learn your writing
  style. It reads them on the device, records only style statistics, and never
  transmits or stores the message text.
- **On-screen context for AI drafting.** An Android accessibility service that,
  once *you* switch it on in your system Accessibility settings, lets the AI see
  the conversation on screen so a reply it drafts makes sense. The text is held
  in memory only, never written to disk, and never transmitted. It is off until
  you enable it and stops the moment you turn it off.

Both are optional, both are off by default, and neither sends anything anywhere.

---

## 10. Changes to this policy

If this policy changes in a way that affects what the app does with your data,
the effective date above changes and the updated policy is published here before
the change ships. Because the app collects nothing, we do not expect this section
to see much use.

---

## 11. Contact

Questions about this policy, or about anything the app does:

**support@ioservices.io**
