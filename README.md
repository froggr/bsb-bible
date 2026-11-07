# BSB Bible

An offline study Bible for Android: the Berean Standard Bible, read aloud in a natural voice,
with highlights, notes and study material that sits inline with the text.

I vibe this mainly with fable 5.1 and opus 5.5. played architect,PM, and QA. This is a personal app and felt to share of it helps others. if you don't like the implementation or code then complain to anthropic. 

The Bible text is the Berean Standard Bible, unmodified.

This readme may be slop 🤷 oh well. 😁

## Install

**[Download the latest version](https://github.com/froggr/bsb-bible/releases/latest)** — open
`app.apk` on your phone and tap it.

Android will ask whether to allow installing from your browser or files app. That prompt is
normal for an app that does not come from the Play Store; you grant it once.

Needs Android 10 or newer, and about 120 MB free once the voice is downloaded.

## What it does

- **The whole Bible, offline.** No account, no network needed to read.
- **Read aloud.** A small neural voice runs on the phone — not a robotic system voice, and not
  a cloud service. It highlights each phrase as it is spoken, reads the section headings, keeps
  reading with the screen off, and carries on into the next chapter unless you tell it to stop.
  Fourteen voices to choose from. Optional quiet instrumental music can play underneath.
- **Marking is fast.** Tap a verse number to select the verse; tap another and everything
  between is selected. Then pick a colour or write a note, and it applies to the whole span.
  Press and drag works too. Built to be usable during a sermon.
- **Study mode.** Commentary, cross-references and footnotes appear between the verses rather
  than behind a tap, so you can study while you read. Scripture stays the dominant text. A
  commentary paragraph on a run of verses shows once, under the last of them, with a bracket
  down the margin from the first — the way a comment brackets a passage in a word processor.
  A translator's note marks the words it is about with a small letter and a dotted underline,
  and quotes them.
- **Scripture cited in the commentary is a link.** Tap `Rom 5:6-8` and you are there.
- **It remembers where you were**, down to the verse.
- **Other apps can open it at a passage.** A `bsb://open?ref=John 3:16` link, or a BibleGateway,
  YouVersion or BibleHub link, opens the app on that verse (details under *Opening the app at a
  passage* below).

## Updates

The app checks for new versions itself and offers to install them. You can also check by hand:
**Marks & settings → Settings → About**.

Updates are published here as new releases.

## Optional downloads

The app works fully offline on first install. From Settings you can add:

- the **voice** for reading aloud (about 60 MB, plus about 4 MB for the fourteen voices)
- **study material** — Strong's numbers, cross-references (about 20 MB)
- **commentary** (about 11 MB)
- **music beds** — eight instrumental tracks (about 12 MB)

## Why it is not on the Play Store

It is a personal project, shared with friends. The Play Store also does not allow an app to
update itself, which is how this one delivers new versions.

## Text and credits

The verse text is the **Berean Standard Bible**, chosen because it is freely available for
anyone to use and share. Read-aloud uses the open-source **Phonon** speech model.

This repository holds the source and the built releases. Questions or problems: open an issue.

The read-aloud voice is a neural model running on your phone; it mispronounces things
occasionally, and proper nouns are its weak spot.

---

# Working on it

Everything below is for building and changing the app. `CLAUDE.md` holds the things that
waste a session if rediscovered; `STATUS.md` what works, what is half-built and what is
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
| `data/bible-commentary.db` | 39 MB: Tyndale + Matthew Henry + JFB. Downloaded on request (13 MB gzipped) |
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
| `tyndaleopenresources.com/…/tyndale_open-studynotes.zip` | 2.9 MB | Tyndale Open Study Notes (2022), whole Bible |

BSB text is CC0. Cross-references need the line
`Cross-references from OpenBible.info, CC-BY`. Henry and JFB declare
`DistributionLicense=Public Domain` in their own module config.

**Tyndale is CC BY-SA 4.0, not public domain** — the only source here with a condition
attached. It needs the attribution the Settings About block carries:
`Tyndale Open Study Notes © 2022 Tyndale House Publishers, CC BY-SA 4.0, available free at
tyndaleopenresources.com.` Modify the notes and the licence also wants the change stated and
the wording "Adapted from Tyndale Open Study Notes…" — see `data/src/x_TYN/_README.txt`,
which ships with the download. Keep that file.

Why a third commentary at all: Henry and JFB explain the text in the prose of their own
centuries — Roman-numeral verse references, nested `I. / 1. / (1.)` outlines, providence as
"physic works upon the body". Tyndale is the open release of the NLT Study Bible apparatus and
explains the same verse in today's English, so it reads first. All three are kept, because
Tyndale is silent where the older two are not — Romans 8:28 has no Tyndale note.

Notes on the data, learned the hard way:

- The BSB's own footnotes are **translator's notes** (variants, literal readings, alternate
  renderings), not commentary. Commentary comes from the SWORD modules.
- A footnote sits on the **word slot it annotates** in `tables.tsv`, but the page splits the
  verse on spaces and a slot is a phrase ("an expanse"). `build.py` aligns each slot to the
  reading text in order, at a word boundary, and stores the display-word range as
  `footnotes.w_start`/`w_end`; 4,815 of 4,854 place, and the rest are psalm superscriptions.
  A note on an alignment placeholder (`. . .`) takes the phrase before it.
- In `bsb_tables.tsv`, punctuation lives in `pnc`/`begQ`/`endQ`, not in the word cell, and
  `begQ` can carry verse-number markup that must be stripped.
- A word cell of `-` marks an original-language word with no English equivalent (e.g. the
  Greek article). Worth showing in study view, worth hiding in reading view.
- Matthew Henry comments on *passages*, so several verses share one entry, and each entry
  opens by re-quoting the KJV text — strip that, the reader already has the text on screen.
- Tyndale's `<item name=…>` is **not** a usable key: it spells books in Roman numerals
  (`IJn`, `IISam`, `IKgs`). The `<refs>` child carries the canonical form (`1Jn.1.1-4`) — key
  off that. Its own abbreviations still need mapping (`Pr`→`Prov`, `Hagg`→`Hag`).
- 37% of Tyndale's notes cover a *range*, and a range can cross a chapter (`Ps.23.1-28.9`).
  Each note is attached to every verse in its range and the **narrowest note wins**, so
  Psalm 23:1 gets its own note rather than a 265-verse overview. Keying only the first verse
  of each range leaves coverage at 48%; expanding gets it to 93%.
- Tyndale marks quoted scripture with `class="sn-excerpt"`. Convert those to `*emphasis*`
  using sentinel characters, *then* collapse whitespace — do it the other way round and the
  whitespace pass strands the `*` away from the word it belongs to.
- CCEL's XML downloads are zero bytes; CrossWire's `ftpmirror` is the working source.

## The app

| Screen | |
|---|---|
| Reader | chapter text, narration with the word lit as it is spoken, tap a number to select, long-press for the sheet |
| Study sheet | word (Hebrew/Greek, Strong's, parsing), cross-references, translators' notes, commentary |
| Note sheet | writing on a verse, with the verse in front of you |
| Search | full text, debounced, matches emboldened |
| Marks and notes | everything coloured or written on |
| Settings | text size, theme, voice, study downloads, music, manual update check |
| Player | read aloud, speed, voice, music bed, volume |

**Study mode** interleaves the apparatus between verses — notes, footnotes, commentary,
cross-references — as its own `Row` types, so the LazyColumn still only composes what is on
screen. Scripture stays the dominant text: nothing else uses the reading face at reading size.
A note or a commentary paragraph covering several verses is one `Row`, under the last verse
of its span, and every row from the span's first verse down to it carries a `Margin` — the
bracket lanes `rebuild()` works out row by row, because only the layout knows what sits
between a span's first verse and its last (a heading, a footnote, another commentator).

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

## Opening the app at a passage

Anything outside the app — a verse-of-the-day widget, a launcher shortcut, Tasker, another
app — can open it on a verse:

```
bsb://open?ref=John+3:16
```

`ref` is whatever the search box accepts: `jn3:16`, `1 cor 13`, `ps 23:4`, `rev`. The same
reference can be sent as a string extra named `ref` on an explicit intent to
`ca.lampandlight/.MainActivity`. From a shell:

```sh
adb shell am start -a android.intent.action.VIEW -d "bsb://open?ref=John%203:16"
adb shell am start -n ca.lampandlight/.MainActivity --es ref "Psalm 23:4"
```

The app also registers for the links a verse-of-the-day widget usually carries, so such a
widget needs no change — Android offers BSB Bible next to the browser and remembers the
choice:

| | |
|---|---|
| `https://www.biblegateway.com/passage/?search=John+3:16` | the `search` query |
| `https://www.bible.com/bible/59/JHN.3.16` | YouVersion's three-letter book codes |
| `https://biblehub.com/john/3-16.htm` | book, chapter-verse |

If the app is already open it turns to the passage rather than starting a second copy
(`launchMode="singleTask"`). Parsing lives in `Deeplink.kt`.

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
