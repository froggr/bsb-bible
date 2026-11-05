# BSB Bible

A BSB study Bible for Android: read it, mark it up, and have it read aloud on the phone
with no network and no subscription.

Shipped publicly at **<https://github.com/froggr/bsb-bible/releases/latest>** — that link is
what to give someone who wants to install it. The app updates itself from there.

Current release: **0.31**. See `STATUS.md` for what works, what is half-built and what is
known broken.

## Where things are

| Path | What |
|---|---|
| `app/` | The Android app (Kotlin + Phonon). `./gradlew assembleDebug` |
| `proto/` | Design prototype: the reading/listening/study experience as a web page |
| `proto/extract.py` | Parses the BSB translation table into verses with word-level data |
| `proto/sword.py` | Reads SWORD `zCom`/`zCom4` commentary modules |
| `vendor/xn-ptts/` | Phonon TTS (Gradium), built for Android by `android/build.sh` |
| `data/build.py` | Builds `bible.db` and splits it into core + commentary |
| `data/bible-reading.db` | 8.8 MB: verse text, footnotes, marks, search. **This is what ships in the APK** |
| `data/bible-core.db` | 77 MB: text plus the word layer and cross-references. Build input, not shipped |
| `data/bible-study.db` | 68 MB: Strong's and cross-references. Downloaded on request (20 MB gzipped) |
| `data/bible-commentary.db` | 34 MB: Matthew Henry + JFB. Downloaded on request (11 MB gzipped) |
| `data/beds/` | The music beds that SHIP. Not a source directory — see below |
| `data/beds/_withvocals/` | Untouched originals for stillness, psalter, evening |
| `public/beds/` | Untouched originals for morning, shepherd, vigil, upper-room, wilderness |
| `app/keystore/` | The release signing key. Irreplaceable — read `app/KEYSTORE-README.md` |

## Building

Needs JDK 17 (not the default JDK 26 on this machine) and the Android SDK/NDK:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/29.0.14206865

# once, to produce libptts_ffi.so for arm64-v8a and x86_64
cd vendor/xn-ptts && ./android/build.sh
cp -R android/jniLibs/* ../../app/app/src/main/jniLibs/

cd app && ./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

`adb` is at `$ANDROID_HOME/platform-tools/adb` and is not on PATH.

A phone-only release build is a third smaller than a debug one, because it drops the x86_64
library the emulator needs:

```sh
ANDROID_ABIS=arm64-v8a ./gradlew assembleRelease
```

Every build — debug included — is signed with the release key in `app/keystore/`. That is
deliberate: the app is installed by sideloading, and Android refuses an update whose signature
differs from the installed one, so a debug-signed build could not update anyone. Mixing the two
would force an uninstall, which destroys the reader's highlights and notes.

## The data

Everything is public domain or CC-BY, and all of it is verified as downloading.

| Source | Size | Gives |
|---|---|---|
| `bereanbible.com/bsb.txt` | 4.3 MB | 31,105 verses |
| `bereanbible.com/bsb_tables.tsv` | 85 MB | Per-word Hebrew/Greek, Strong's, parsing, headings, footnotes |
| `a.openbible.info/data/cross-references.zip` | 8.3 MB | 344,800 cross-references ranked by vote |
| `crosswire.org/ftpmirror/pub/sword/packages/rawzip/MHC.zip` | 15 MB | Matthew Henry (1710), whole Bible |
| `…/rawzip/JFB.zip` | 6.3 MB | Jamieson-Fausset-Brown (1871), whole Bible |

BSB text is CC0. Cross-references need the line
`Cross-references from OpenBible.info, CC-BY`. Both commentaries declare
`DistributionLicense=Public Domain` in their own module config.

Notes on the data, learned the hard way:

- The BSB's own footnotes are **translator's notes** (variants, literal readings, alternate
  renderings), not commentary. Commentary comes from the SWORD modules.
- In `bsb_tables.tsv`, punctuation lives in `pnc`/`begQ`/`endQ`, not in the word cell, and
  `begQ` can carry verse-number markup that must be stripped.
- A word cell of `-` marks an original-language word with no English equivalent (e.g. the
  Greek article). Worth showing in study view, worth hiding in reading view.
- Matthew Henry comments on *passages*, so several verses share one entry, and each entry
  opens by re-quoting the KJV text — strip that, the reader already has the text on screen.
- CCEL's XML downloads are zero bytes; CrossWire's `ftpmirror` is the working source.

## The app

| Screen | |
|---|---|
| Reader | chapter text, narration with the word lit as it is spoken, long-press to mark |
| Study sheet | word (Hebrew/Greek, Strong's, parsing), cross-references, translators' notes, commentary |
| Note sheet | writing on a verse, with the verse in front of you |
| Search | full text, debounced, matches emboldened |
| Marks and notes | everything coloured or written on |
| Settings | text size, theme, voice, study downloads, music, manual update check |
| Player | read aloud, speed, voice, music bed, volume |

**Study mode** interleaves the apparatus between verses — notes, footnotes, commentary,
cross-references — as its own `Row` types, so the LazyColumn still only composes what is on
screen. Scripture stays the dominant text: nothing else uses the reading face at reading size.

**Music beds** play their run-in once and then loop a region of the file forever
(`Beds.Item.loopStartMs`/`loopEndMs`, found by PyMusicLooper). The region is decoded to PCM once
— about 2 MB per 22 s at 24 kHz mono — because no Android media player loops part of an Opus file
without a gap. **Rotate** moves through all
eight, shuffled, each once before any repeats.

Traps worth knowing, all found on device:

- **`rawQuery` binds every argument as text**, and SQLite will not equate text with an
  integer expression: `WHERE verse/1000 = ?` silently matches nothing. Compare against an
  id range instead.
- **Compose's downloadable fonts need Google Play Services.** Without it the text quietly
  falls back to the system sans. The faces are bundled in `res/font` instead.
- **A loop join must never be crossfaded.** Fading a file's end into its start fades the loop
  back into the *intro* — music the track never returns to — and smears seconds over itself.
  ExoPlayer clips to the loop region and repeats it with no fade. A crossfade is correct only
  when changing to a *different* bed.
- **Neither MediaPlayer nor ExoPlayer can loop a REGION of an Opus file gaplessly.** MediaPlayer
  loops only whole files; ExoPlayer's `ClippingMediaSource` forces a renderer reset at every wrap
  because `MimeTypes.allSamplesAreSyncSamples()` does not list Opus, which rebuilds the AudioTrack
  and costs 100-150 ms of silence. The bed decodes its loop to PCM once (`BedAudio`) and writes it
  round and round through `AudioTrack`, the same way the narrator works.
- **Two chained `pointerInput` blocks cannot share a long press.** `detectTapGestures` consumes
  the initial `down` unconditionally, and a consumed change makes `awaitLongPressOrCancellation`
  give up. Tap, double-tap, long-press and drag all live in ONE `awaitEachGesture` in
  `VerseBlock`.
- **`init` must be the last thing in `ReaderState`.** Property initialisers run in declaration
  order, so anything added below `init { load() }` is still null when it runs.
- **Kotlin/JVM signature clashes:** a `var x` plus a `fun setX(v)` collide. Hence `setVolume`,
  `chooseStudy`, `chooseLayers`.
- **Don't record a bar/view height with a high-water mark.** `if (h > topBarPx) topBarPx = h`
  fed `contentPadding`, which then never shrank when the bar did, and every verse under the
  finger read a couple too low.

## Shipping a build

Releases go to GitHub, which costs nothing and has no bandwidth limit. The app's default host
is `https://github.com/froggr/bsb-bible/releases/latest/download`.

```sh
cd app && ANDROID_ABIS=arm64-v8a ./gradlew assembleRelease
```

Then stage the assets with FLAT names — GitHub serves release assets from one directory, so
`/beds/stillness.opus` cannot exist there. `Updates.resolve()` rewrites `/` to `-` when the host
contains `/releases/`, and leaves paths alone otherwise, so one manifest serves both hosts:

| manifest path | GitHub asset |
|---|---|
| `/app.apk` | `app.apk` |
| `/data/bible-study.db.gz` | `data-bible-study.db.gz` |
| `/beds/stillness.opus` | `beds-stillness.opus` |

`gh release create` is flaky with a dozen assets — it has 404'd mid-upload and left a draft.
Upload per-file with `gh release upload --clobber`, then `gh release edit --draft=false --latest`.
`git push` over SSH has also failed with a server-side `commit_refs_rules` error while the REST
API worked; `gh api -X PUT .../contents/<file> --input payload.json` is the fallback.

### The local server, for development

```sh
./serve.sh            # http://<this machine>:8099
```

Open that on the phone and tap **Install the app**. The same server is where the app fetches
its optional data and checks for newer builds:

| | |
|---|---|
| `GET /` | the install page |
| `GET /manifest.json` | build version, layer sizes, SHA-256 of each |
| `GET /app.apk` | the current debug build |
| `GET /data/<layer>.db.gz` | a data layer |

`serve.py` re-copies the APK and re-gzips any changed layer each time the page is loaded, so
`./gradlew assembleDebug` then reload is the whole loop. Downloads are checksummed against
the manifest and only moved into place once they match.

`Updates.setHost()` points the app at this server instead of GitHub. Cleartext HTTP is allowed
to `barf.skunk-dominant.ts.net`, the 192.168 range and the emulator's `10.0.2.2` only — see
`network_security_config.xml`. Anything else needs TLS.

Measured on the emulator over the host bridge: study layer (19 MB) in 2 s, commentary (11 MB)
in 1 s.

## The database

`python3 data/build.py` turns the sources below into `bible.db`, then splits it:

| | Rows |
|---|---|
| verses | 31,086 (the BSB omits 16 disputed verses; `omitted` records them with the BSB's own note) |
| words | 441,123 with original, transliteration, Strong's, parsing |
| footnotes | 4,854 |
| cross-references | 344,799, vote-ranked |
| commentary | 20,726 passages → 54,926 verse links, covering 31,040 verses |

Verse ids are `book*1_000_000 + chapter*1_000 + verse`, so they sort in reading order and a
chapter is one range scan.

Measured on device (arm64, Android 13) after the first-run unpack of 228 ms:

| Query | |
|---|---|
| read Psalm 119 (176 verses) | 541 µs |
| words for a verse | 135 µs |
| cross-references | 78 µs |
| full-text search | 226 µs |
| concordance by Strong's | 91 µs |

Two things that only showed up on device:

- **FTS5 does not exist on Android.** AOSP compiles it out of its SQLite, so `fts5` fails with
  "no such module". The index is FTS4 with `content=""`; it cannot build snippets, so search
  joins `verses` for the text.
- **The concordance needs `words(strongs, lang, verse)`.** Without it, tapping a Strong's
  number scanned 441k rows: 11.7 ms on device against 91 µs with the index.

Gradle strips a `.gz` from an asset name and stores the file Deflate-compressed in the APK
anyway, so ship the plain `.db` and copy it out — do not also gzip it.

## Phonon

Phonon 31M English, Apache 2.0, ~65 MB, CPU. The Android path is a C FFI library
(`libptts_ffi.so`) called from Kotlin through JNA — not JNI.

- Pin `abiFilters` to `arm64-v8a`/`x86_64`; a 32-bit install fails to load the library.
- The arm64 build needs dotprod + fp16 (ARMv8.2). It crashes rather than degrades on older
  phones; build with `ARM64_FEATURES=` for those.
- Set `RAYON_NUM_THREADS` before constructing `PhononTTS`. One-per-core is usually wrong on
  big.LITTLE.
- Don't ship the model in the APK; download on first run.
- QNN/NPU is Qualcomm-only and irrelevant on a Pixel's Tensor G4 — the CPU path is the
  tested one anyway.

Audio streams back through a callback in 80 ms frames, so narration can start before a
sentence finishes generating.

Following the voice word by word needs three things the callback does not give you:

- **Where this verse starts in the track**, taken on the player thread as its first frame is
  written. Reading it when the verse begins is too early: the previous verse is still
  draining, so the mark is stale and the highlight starts near the end of the verse.
- **Weight words by syllables, not characters.** "I" and "through" differ sevenfold in
  letters and barely at all in time; a per-word floor keeps a run of short words from being
  skipped entirely.
- **Discount the silence.** The model leaves ~0.6 s after the last word (consistent) and
  1–150 ms before the first (variable, so detect it). Measuring across the whole buffer runs
  the highlight ahead of the voice.

Two traps in that callback, both found on the phone:

- **The `FloatArray` is a view of native memory ptts reuses for the next frame.** Writing it
  to AudioTrack inside the callback is fine; keeping it is not. Queue `pcm.copyOf()`, or
  playback is silence.
- **`ptts_speak` does not return until the callback does**, so writing straight to a blocking
  AudioTrack makes generation appear to run at exactly 1x — ptts's own `totalMs` included.
  Hand frames to a player thread through a bounded queue and time generation separately.
  Measured on a Pixel 9 that way: 4.55x realtime, first audio 121 ms. That, plus notes being read in the same voice as the verse, is
why speech is generated on the phone rather than baked ahead of time.
