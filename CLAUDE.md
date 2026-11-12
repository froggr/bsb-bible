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

## Source on GitHub

Since 0.38 the source lives on `origin/main` at `https://github.com/froggr/bsb-bible` (before
that the repo held only a README and the releases, and this clone had no remote). Push over
HTTPS with gh's credential helper — `gh auth setup-git` once, then `git push` — because a bare
`git push` asks for a username and SSH has failed with `commit_refs_rules` before. The README's
opening is Joe's own words about how the app was made; keep it as the front of the file.

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
what is on the release. `data/beds/_v1/` holds the beds 0.35 replaced and `_withvocals/` the
pre-vocal-strip originals; neither ships, both are gitignored.

**The beds come from `~/Music/Scripture Radio`** (Lo-Fi, Piano, Prayer Room — 78 tracks of
2-6 minutes, written as beds). Not from `~/projects/yue2/outputs`: that holds song generations,
and its only instrumentals are Tron-pastiche and french house, wrong for reading under.

**No lyrics is a measured claim, not an assumption.** Every candidate goes through
`demucs --two-stems=vocals` on a 40 s slice and must show its vocal stem at least 40 dB below
the accompaniment. This matters: 23 of the 78 did NOT measure clean and two separated with the
vocal stem LOUDER than the music. They may be sustained pads misread as held voices — two
attempts to distinguish them automatically both failed, ranking the library's cleanest track as
its most vocal-like — so anything that does not measure clean is simply excluded.

**serve.py stages the DEBUG apk.** For a release, copy
`app/app/build/outputs/apk/release/app-release.apk` yourself; `prepare()` will otherwise leave
a stale APK in `public/` and the manifest will claim the wrong version.

**A release must carry the data layers even when they have not changed.** The app fetches
everything from `/latest/download/`, so re-upload `data-bible-study.db.gz` and
`data-bible-commentary.db.gz` (download them from the previous tag, verify their SHA-256
against the manifest first) or a fresh install finds no study or commentary layer.

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

**Timing is necessary but not sufficient — measure LEVEL and BANDS too.** A loop can be
bar-exact with a perfect seam and still lurch because it ends loud and restarts soft. Morning
shipped at -4.28 dB across the join and the spectral metric ranked it BEST of all candidates,
because timbre matched and level is invisible to it. One averaged number also hides a single
band: an 8-bar candidate read -2.67 dB overall and **-33.7 dB in the bass alone**. `report()`
returns level and per-band change over two windows — 300 ms for the splice instant, 1.5 s for
the phrase either side. Read both; a note decaying into the downbeat looks like a huge bass dip
at 300 ms and is ordinary music.

**`onset_ratio()` is the transient check, and BELOW 1.0 IS GOOD.** It divides the seam's
disturbance by an ordinary bar line's, so 0.67 means the join is calmer than the music's own
bars. Only above ~1.3 does a join stand out. A gate rejecting low values rejects the best
loops — mine did, and called a good bed a failure at 0.67. Treat it as a soft ranking signal,
not a pass/fail: it moves by a few tenths with loop position.

**Encode first, then derive, then verify on the same bytes.** A loop point belongs to one exact
file, and three separate failures in 0.35 were all this one mistake: choosing on the source mp3
then shipping a re-encoded trim; cutting a file flush to its loop so the finder had nothing left
to search (zero candidates); and carrying a measurement from a whole-file encode onto a re-cut
file, which read as -9.5 dB and onsetR 84 on good audio. **Never translate points across a cut.**

**Ducking is proportional to the volume slider** (`Bed.duckFloor`). A fixed ratio is wrong at
the quiet end: someone who already set the bed low has said what they want, and ducking that
by a further 4.4 dB leaves nothing, so the music vanishes and returns at every verse. Full
duck at the top of the slider, none at the bottom.

**Rotation must decode the next bed BEFORE fading the current one,** and must enter the new bed
at its loop rather than its run-in. Fading first and decoding afterwards is why rotation
stopped dead and then started again. The cache holds all eight loops (~40 MB) for the same
reason; at three entries nearly every turn paid for a fresh decode. Run-ins are not cached —
after the first play they are never wanted again.

## Compose text links are a trap

Never put a `LinkAnnotation` in a long text. Compose's link layout computes a path for every
link on every measure pass, and a commentary paragraph with hundreds of citations held a
Pixel 9's main thread past the ANR limit (0.41, the first thing a fresh phone hit). Colour the
range with a `SpanStyle`, tag it with `addStringAnnotation`, and resolve taps from the
`TextLayoutResult` — `Citations.linked` / `Citations.targetAt`. The emulator never showed it.

## A regex that passes on the desktop can fail on the phone

**Never use `\G` with `Regex.find(text, from)`.** On the desktop JVM it anchors at `from`; on
Android (ICU) it never matches, because a `find` given a start index forgets the last match
end. `CitationsTest` passed while the phone dropped every range, list and translation suffix
after a citation in a book, and the "; 15:3" continuation in commentary had been dead
since 0.38. Use `Regex.matchAt(text, index)` with
no `\G`. More generally: a green JVM test of anything regex-shaped is not evidence about the
phone.

## Books

**No epub ever goes into the repository, the APK or a release**, the samples Joe sent
included. They are his own copies. Tests read theirs from the `EPUB_SAMPLES` environment
variable; keep it that way.

**Android's folder picker refuses `Download` itself and the storage root** ("Can't use this
folder. To protect your privacy, choose another folder"). Push test books to a subfolder such
as `/sdcard/Download/Books`.

**`adb shell am start` cannot hand the app an epub**: the shell cannot grant read access to a
document URI, so the import fails with a `SecurityException` that is the test's fault. Tap the
file in the emulator's Files app instead.

**A full-screen overlay needs `.pointerInput(Unit) {}` on its root**, or touches fall through
to the Bible reader, which stays composed underneath every other screen.

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

**The emulator cannot tell you whether the voice keeps up.** It makes speech about fifteen
times faster than it is heard, with the app open, at home or with the screen off, because it
runs on the Mac's cores. A Pixel 9 measured 4.5x in the foreground and nobody has measured it
in the background. When the model falls behind, the speech itself is unchanged and the gap
*between* clauses grows, which is reported as "long pauses". Three log lines say what happened
(`made` is the model's speed, `heard` the pace at the speaker, `stalled` a wait for a clause
that was not ready), and `Pulse` keeps the same tally on the phone, split by whether the app
was on screen, shown at the foot of Settings — ask for those two lines before guessing. To
imitate a slowed phone on the emulator, starve the app as root:

    adb shell 'su 0 sh -c "for i in 1 2 3 4; do nice -n -14 sh -c \"while :; do :; done\" & done; sleep 70; kill \$(jobs -p)"'

There is no CFS quota in the emulator's kernel, so a cgroup limit is not available.

**The model does its work on threads it starts itself, two per sentence, not on the thread
that calls it.** The caller only waits and takes the audio callbacks. So the caller's CPU time
says nothing (it read 0%), and the measure is `/proc/self/task/<tid>/schedstat` of the threads
named `voice` other than the caller, read inside the callback: nanoseconds on a core, then
nanoseconds waiting for one. Those threads inherit the caller's name, priority and cores, which
is why pinning the voice thread before each clause places them. The persistent pool is
`xn-worker-{i}`, its width is fixed at first use from `RAYON_NUM_THREADS`, and every step
waits for every worker, so **the voice runs at the pace of its slowest thread**.

**A Pixel 9 off screen has three fast cores, not four.** From `init.zumapro.soc.rc`:
`top-app` is cores 0-7, `foreground` 0-6, `background` 0-3; cores 0-3 are small, 4-6 middle,
7 large. A pool four wide then has one thread on a small core. `Cores` sizes the pool to the
fast cores left off screen and re-pins before every clause, because a move between those sets
resets where a thread may run. 0.47 shipped on that reading of the files; **Joe's Pixel 9
then measured it** (2026-11-11): off screen, 3 threads on cores 4-6 of 0-6, made at 2.9x and
never below 1.6x over 39 min, waiting for a core 4% of the time, cores at 76% of full speed;
on screen 3.9x, slowest 2.8x. So the voice is not behind any more, and yet it was still left
waiting 8 times off screen, 7.6 s in all, longest 2.2 s. A model that never drops below 1.6x
cannot empty a thirty-second buffer, so those waits are where the buffer is empty: **every
`speak()` starts with nothing made ahead**, and a short first clause followed by a long one
leaves the voice waiting. That is an inference from the code, not a measurement, and the
silence at the chapter change itself is not in the tally at all (`idle` starts at -1). The two
"Reading aloud" lines at the foot of Settings are the measurement: ask for them.

**Joe has said the pause between chapters is fine** (2026-11-11: "Pauses between chapters are
fine tho"). Do not build the make-ahead for the next chapter unless he asks again.

**Never stop and restart the reading service across a chapter.** From the background,
`startForegroundService()` throws `ForegroundServiceStartNotAllowedException` on Android 12 and
later unless the service is already up. 0.46 let `speaking` go false for a moment at every
chapter end, the service stopped, and the restart crashed the app with the screen locked. A
playing bed hid it by keeping the service up. `Narrator.carrying` covers the gap, and
`ReadingService.start()` only replaces the notification when the service is running. **Test a
chapter end with a real book, music None and the screen locked, on Android 12 or later**; a
two-chapter sample had not shown the crash.

**Android replays the intent a task was first started with** when the app has been cleared
from memory, or has crashed, and is reopened from Recents. A passage link, the widget or an
epub tap then arrives a second time. `onCreate` ignores the intent when
`savedInstanceState != null` or `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` is set. To test: start
with `am start -a android.intent.action.VIEW -d "bsb://open?ref=John+3:16"`, open a book, go
home, `am kill ca.lampandlight`, return through Recents.

## Questions

**The questions are people's, the answers are verses, and neither is to be generated.** Joe
asked for an existing human-written curriculum instead of AI-written questions. The text is
unfoldingWord's Translation Questions; their written answers are deliberately left out (they
quote another translation and have slips), and the choices on the page are BSB verses. Do not
"improve" a question or write distractors.

**`app/app/src/main/assets/questions.db` is a hand copy of `data/questions.db`,** like the
reading text. After `python3 -I data/questions.py`, `cp` it, and raise `VERSION` in the script
and `Questions.VERSION` together or the phone keeps its old unpacked copy.

**The chapter's questions are planned once, when it loads, not in `rebuild()`.** `rebuild()`
runs on every study toggle and layer change; planning there would swap the question under the
reader's finger the moment an answer was recorded.

**The voice decides once whether to say an answer, at the wait.** `heard()` settles the
question the moment the answer starts, so asking `replied` again per clause cuts the spoken
verse off after its first clause. And a stop during the wait is not "unanswered":
`awaitAnswer` calls `ensureActive()` before it reports.

**The wait is 30 s on screen and 8 s off it** (`ASK_MS`, `ASK_AWAY_MS`). Joe asked for thirty
seconds; with the phone locked nobody can tap, so thirty seconds of silence after every section
is only dead air. Eight is long enough to answer in your head. The wait is not a stall and must
not be counted as one in `Pulse`.

**Going over is worked out from the log, not stored.** `learning.db` has one table, `asked`,
one row per answer. `Quiz.dueAt` turns a question's rows into the time it is next due: ten
minutes after a miss, then 1, 3, 7, 21, 60, 180 days as it is got right; a question known the
first time starts at 7 days. Only questions already met while reading ever come due.

## By heart

**It works in phrases because the voice reports no word timing.** The only thing known exactly
is the clause handed to the model, so a verse is cut into phrases (`ByHeart.phrases`) and each
is its own `Narrator.Line`. Do not try to light or wait on single words.

**A `Line` with `silence` is the listener's turn,** sent as empty audio and waited out with
`narrator.hushed` true. On a verse's card it also has `hold`, and is waited out until
`narrator.goOn()` with `narrator.held` true: Joe asked for a tap in place of a timed pause
(0.51). Off screen nobody can tap, so a hold falls back to its `silence`, and the hands-free
pass builds its script with no holds at all. Keep both: do not make the hands-free pass wait
for a tap. The short breath between two things the voice says is a silence against
`ByHeart.NAME`, not a phrase, so the page does not call it a turn. `speak(lines, alone = true)` is a reading apart from the page: it
leaves `resumeAt` alone and does not carry into the next chapter. The line verse indexes are
`ByHeart.base(item) + phrase`, far above any real verse, so the reader ignores them.

**A line that is only a preamble has an empty clause range.** The spoken reference ("Psalm 23,
verse 1.") is a line with no words; `sayOne` took `subList(1, 1)` of an empty list and the
voice died at the second level, where the reference is a line by itself. Guard any new use of
`clause.range`.

**Listening is not logged, only "I had it" and "Not yet".** The hands-free pass cannot know
whether anything was said, so it moves no verse along. Since 0.52 the card can hear a line
(see *Hearing* below), and that is not logged either: being heard ends the turn and marks
nobody.

**Making a passage longer starts it again; making it shorter does not.** `heart.since` is the
time from which `said` rows count. `Questions.stretch` moves it only when the passage grows,
because a longer passage is a different thing to say and a shorter one has already been said.

**The phrase breaks were tuned by eye, and the lists are short on purpose.** "for" is a place
word, not a joining word ("what we hope / for and the certainty"); the helping verbs leave out
is/are/was/were/do/does/did ("the LORD your God is / with you"); a helping verb after a
subject pronoun breaks before the pronoun ("by grace / you have been saved", not "you / have
been"). Print the phrases of thirty verses you know before and after
any change to `JOINS`, `HELPS`, `PLACES` or `LONG`.

**The verse panel scrolls within itself.** Six verses in the largest type are taller than the
screen, and with the page scrolling instead the cue and the buttons left it. The panel is
capped at the screen less 350 dp and keeps the sounding line in view with a margin.

## Hearing

**Joe's phone is GrapheneOS: there is no Google speech service and `SpeechRecognizer` is not
an option.** Hearing is sherpa-onnx with NVIDIA's Parakeet TDT-CTC 110M and Silero VAD, the
way his herdvoice app does it, all on the phone. `Ears` owns the models and the microphone,
`Heard` is the matcher, `Hearing.kt` the mark and the sheet.

**The 136 MB of models are release assets on one named tag, `v0.52`, not on `latest`**
(`Ears.BASE`). **Never delete the v0.52 release**, and a later release does not need to carry
them again. Their names on the release are `ears-<file>`; a host that is not the default
serves them at `<host>/ears/<file>`. `Updates.fetchEars` checks each length and SHA-256
against `Ears.PARTS`, so a replaced file is refused, not used.

**The native libraries are packed compressed (`useLegacyPackaging = true`)** or the APK is
about 22 MB larger, 62 MB against 40. `app/app/libs/sherpa-onnx-1.13.7.jar` is the classes from the stock AAR and
the four `.so` files under `jniLibs/` are its libraries; nothing is built from source.

**The matcher is forgiving on purpose, and it only ever answers "was that the line".**
`Heard.said` wants six tenths of the words, allows near spellings and homophones, and for a
long passage also wants its end. It was tuned on 5,460 measured pairs (`HEARD_PAIRS` re-runs
them): about 0.4% of wrong lines pass. Do not tighten it to stop that. A turn that will not
end because a child said "shepard" costs more than one that ends early, and nothing is marked
either way. "Go on" stays on the card for the same reason.

**Hearing marks nobody and writes nothing.** No row, no score, no recording kept. Only
"I had it" and "Not yet" move a verse, as before.

**The recogniser is touched only on the `ears` worker thread; the `mic` lock guards only the
detector** and is held for a whole turn. Taking that lock in `decode` deadlocks: nothing is
worked out until the turn ends.

**The microphone is closed when the app leaves the screen** (`LifecycleStartEffect` in
`HeartCard`), because composition cannot be relied on to do it there, and opened again on the
way back. The bed is silenced, not ducked, while it is open and nothing is plugged in
(`Bed.hush`, `Ears.aloud`): music from the speaker is all the microphone would hear.

**The emulators have no microphone. A debuggable build reads `ears/<n>.wav` from the app's
external files in its place** for the n-th turn since the models loaded (16 kHz, mono, 16
bit). Files pushed with adb belong to the shell and the app cannot see them: after pushing,

    adb shell 'su 0 chown -R <app uid>:1078 /data/media/0/Android/data/ca.lampandlight/files/ears'

or the app quietly opens the silent microphone and the test looks like a dead recogniser.
A release build ignores the files. **A real voice through a real microphone had never been
tried when 0.52 shipped**: the recordings were the Mac's `say` voices.

**`adb devices` may list a real Pixel 9 beside the two emulators.** Set `ANDROID_SERIAL` on
every call; a bare `adb` then fails with "more than one device", which is the better outcome.

## Where things are

**What is asked for is the text's own section headings, never a summary.** Nothing is written
for this. `Places.title` cleans a heading line with plain string work, no regex, and drops
what is not a section: "BOOK II Psalms 42–72 " in front of five psalms, the Hebrew letters of
Psalm 119 (so that psalm is one heading, not twenty-two), and the Song of Songs' speakers,
which the reading text stores as headings and four times runs on to the heading before
("Solomon Admires His BrideThe Bridegroom"). That last is a defect in `bible-reading.db`
itself and is still on the page. Every one of the 1,189 chapters keeps at least one heading;
re-run that check after any change to `title`.

**The look back is planned once, when the chapter loads (`lookBack()`), like the questions.**
It asks about the chapter BEFORE the one opened, across books, and only when that chapter has
been read and is due. In `rebuild()` it would vanish under the reader's finger the moment it
was rated.

**Hearing is not logged.** When nobody answers, the voice says the headings and the panel
shows them, and no row is written, the same as listening to a verse by heart. Only
"I had it" and "Not yet" move a chapter. Looking at every heading stops the voice saying them.

**The two voice lines have verse index -1.** Given 0 they lit verse 1 and the page scrolled
the panel away to show it. Anything that reads `Line.verse` or `narrator.verseIndex` must
allow for a line at no verse; the player's label and `verseOfLine` already do.

**A chapter is read at its foot, after `Places.enough(verses)`, or when the voice ends it.**
With study on even Psalm 117 is longer than a screen, so a test has to scroll to the foot and
wait. `finished()` writes at once, not in the background, because the next chapter opens
straight after and has to find this one read.

**A chapter opened at its head scrolls to the top (`ReaderState.top`).** Before 0.50 nothing
did, and the next chapter opened as far down as the last was left. `go()` also sets
`topVerse` itself, or the last chapter's top verse is saved as this one's place.

**A row can be in the list's layout and wholly under the top bar.** The reader's list keeps
the bar's height as content padding, and a row scrolled into that space is still "visible" to
`layoutInfo` with a negative offset. Switching Study on at the head of a chapter put the panel
there, because the list holds on to the row it was showing, and the voice then asked about a
panel nobody could see. `rebuild()` raises `top` when the panel arrives above a page at its
head, and anything that brings a row into view must test `offset < 0` as well as `null`.

**The map shows no numbers.** Joe asked for no scores that shame. The rule under a book is
drawn in as many parts as it has chapters; do not add a count or a percentage to it.

## Data

**`app/app/src/main/assets/bible-reading.db` is a hand copy of `data/bible-reading.db`.**
Nothing copies it for you; after `build.py`, `cp` it, and bump `TEXT_VERSION` in BOTH
`data/build.py` and `Bible.kt` whenever the reading schema or text changes — the phone only
replaces its unpacked copy when the stamp goes up (0.39 added `footnotes.w_start/w_end`; an
old copy would fail the query).

`data/build.py` rebuilds everything from `data/src/`. It writes `data/bible.db` and splits it
into the reading / study / commentary layers, so run it with no argument — passing an output
path writes only the monolith and leaves the shipped layers untouched.

`data/src/` is gitignored (85 MB of BSB tables). Keep `data/src/x_TYN/_README.txt`: it is the
licence text for the Tyndale notes, which are CC BY-SA 4.0 and need attribution, unlike the
three public-domain commentaries.

**The Wesley module (`x_WES`) has no chapter milestones and its slots link to stale
entries.** `sword.read()` returns nothing for it — use `read_positional()`, which labels
slots from the KJV table in `sword.py`. Verify any new module against known verses before
trusting a count: Wesley decoded "plausibly" with the wrong record layout (`<HII` for `<IIH`)
and with the slot table one off, and both looked like a working import until Psalm 23:1 came
back as the note on Psalm 22:31. README has the detail under *The database*.
