# Working on this project

`README.md` documents the app and the data. This file is the stuff that wastes a session
if you have to rediscover it.

## Tools that are not where you expect

**adb is not on `PATH`.** It comes from the Homebrew command-line tools, not a cask, so
`which adb`, `/opt/homebrew/bin/adb` and `~/Library/Android/sdk/` all come up empty:

    ADB=/opt/homebrew/share/android-commandlinetools/platform-tools/adb

**`aapt2` and `apksigner` are not installed at all.** Do not try to read an APK's version or
certificate with them. Instead:

- version → `app/build/outputs/apk/release/output-metadata.json`
- signing key → `keytool -list -v -keystore app/keystore/release.jks -alias bsb-bible`
  (the password is in `app/keystore.properties`). `keytool -printcert -jarfile` answers
  "Not a signed jar file" because the APK is v2/v3-signed only, and there is no `META-INF/*.RSA`
  to extract — that is expected, not a problem with the APK.

**`timeout(1)` does not exist** on this machine. Use the Bash tool's own timeout.

## Releasing

The full procedure is in `README.md` under *Shipping a build* — read it, it records real
failure modes (`gh release create` 404s mid-upload with a dozen assets; `git push` over SSH
has failed with `commit_refs_rules` while the REST API worked).

Two things that procedure assumes you know:

- The app updates from **GitHub Releases**, not the local server:
  `https://github.com/froggr/bsb-bible/releases/latest/download`. Copying a rebuilt layer into
  `public/data/` only feeds `serve.py` for development — it ships nothing.
- `gh release create --draft` leaves it invisible. It is not released until
  `gh release edit <tag> --draft=false --latest`. Verify by fetching the live endpoint:

      curl -sL https://github.com/froggr/bsb-bible/releases/latest/download/manifest.json

**`data/beds/` is the real bed set, `public/beds/` is a copy.** They drifted apart before
0.34 — `public/` still held pre-vocal-removal files, twice the length of the shipped ones,
which is how the loop points came to be measured against the wrong audio. `data/beds/` matches
what is on the release. `data/beds/_withvocals/` holds the originals of the three that were
re-rendered to strip vocals; do not ship those.

**A bed's loop points belong to one exact file.** Re-render the audio and the points are
scrap. Never crossfade a loop join.

**Loop in global musical time, and verify by beat timing.** Two approaches failed here, both
for reasons that look like success at the time:

- **0.34 optimised the spectral distance across the seam.** That measures timbre, not time.
  It returned loops of 4.11, 5.62 and 16.42 bars — all ending mid-bar. They scored well and
  lurched, because the ear is counting bars and the measurement was not.
- **0.35 snapped the loop to librosa's detected beat times**, a whole number of detected beats
  apart, and reported them as bar-exact. But that beat list *drifts* — successive intervals
  vary by tens of ms — so sixteen detected beats is not sixteen beats of musical time. Loops
  shipped at 24.386 and 35.641 bars while the commit message claimed exactness.

What works: fit **one tempo and one downbeat for the whole track**, then derive every position
by arithmetic as `downbeat + k * bar`. A candidate is then an integer bar count by
construction and cannot drift. The seam is a **tie-break among bar-exact candidates only** —
never a reason to leave the grid. `data/loop_points.py` does this.

**The test is timing, not timbre:** `python3 -I data/loop_points.py --verify` splices each loop
three times, re-runs beat tracking over the splice, and reports how late the beat crossing the
join arrives. Under ~15 ms is inaudible, ~30 ms is acceptable, 100 ms is the audible skip.
Morning measured **116 ms** when it was reported as fixed. Also: **do not let a re-fit regress
a bed that already measures well** — take the better of old and new *per bed*; a global re-fit
moved `stillness` from 0 ms to 23 ms.

**Ducking is proportional to the volume slider** (`Bed.duckFloor`). A fixed ratio is wrong at
the quiet end: someone who already set the bed low has said what they want, and ducking that
by a further 4.4 dB leaves nothing, so the music vanishes and returns at every verse. Full
duck at the top of the slider, none at the bottom.

**Rotation must decode the next bed BEFORE fading the current one,** and must enter the new bed
at its loop rather than its run-in. Fading first and decoding afterwards is why rotation
stopped dead and then started again. The cache holds all eight loops (~40 MB) for the same
reason; at three entries nearly every turn paid for a fresh decode. Run-ins are not cached —
after the first play they are never wanted again.

## Verify on a device before claiming it works

Compiling is not working, and `BUILD SUCCESSFUL` is not a test. Two defects shipped in 0.32
that a single install would have caught — a data layer that cannot be re-downloaded once a
stale copy is present, and per-source toggles nobody had seen render.

`STATUS.md` has a *Works, and verified* table. Only put something there with the evidence
that verified it, and say what was **not** covered: that table once claimed the music beds
loop correctly on the strength of a measurement of `stillness` alone, while the other seven
loop back far too early.

## Narration

**Phonon's vocabulary is 4000 Unigram pieces, and a word missing from it is mangled, not
spelled.** `LORD` has no piece and comes out letter by letter — that is what `speakable()`
folds. But a lot of biblical names fail the same way more quietly: `Pyrrhus` segments to
`P|y|r|r|hu|s` and is read as fragments. `Narrator.RESPELL` maps those to spellings the model
reads, and every entry is **verified against the real tokenizer**, not guessed — a Viterbi
pass over the Unigram lattice in

    ~/.cache/huggingface/hub/models--gradium--phonon-31m-en/snapshots/*/tokenizer.json

counting single-character pieces before and after. Three of my first nine entries gave no
improvement and had to be re-chosen; check, do not assume.

**Fragment count is a proxy, not proof.** `Psalm` is the most-heard fragmenting word in the
Bible (2,522 times) and the model says it perfectly, as it does `Egypt`, `Hebrews` and
`Pharaoh` — ordinary English it has clearly learned. Fragmentation earns a word a listen, not
an entry. Render candidates with the CLI and judge by ear:

    ~/.local/bin/ptts -l en -t 0.3 -s 1 -o out.wav "Pyrrhus. Binnui. Issachar."

`-l en` is mandatory, not optional — normalization has no default, and it is what enables
number reading and abbreviations. Temperature is 0.3; the checkpoint's `config.json` says 0.8
and is stale.

## Data

`data/build.py` rebuilds everything from `data/src/`. It writes `data/bible.db` and splits it
into the reading / study / commentary layers, so run it with no argument — passing an output
path writes only the monolith and leaves the shipped layers untouched.

`data/src/` is gitignored (85 MB of BSB tables). Keep `data/src/x_TYN/_README.txt`: it is the
licence text for the Tyndale notes, which are CC BY-SA 4.0 and need attribution, unlike the
two public-domain commentaries.
