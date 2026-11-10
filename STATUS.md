# Status

Last updated 2026-11-10, **0.46** released.

Install / share: <https://github.com/froggr/bsb-bible/releases/latest>

## Works, and verified

| Thing | How it was verified |
|---|---|
| Reading, marks, notes, study mode | In use daily |
| Opening at a passage from outside | Verified on the emulator 2026-11-06: `bsb://open?ref=John%203:16` cold-starts on John 3:16; BibleGateway, YouVersion and BibleHub URLs and the `ref` extra each turn the running app to the verse (singleTask, no second copy). Cold-start landings were flaky — three distinct causes fixed: verse NUMBER vs INDEX off-by-one (every jump landed a verse late, breadcrumb a verse early), the landing effect being restarted by the study relayout, and a stale study fetch clearing `studyLoading`. 5 of 5 cold starts landed right afterwards, 2 of 4 before. Not covered: a phone with Chrome set as default for those sites (Android will ask) |
| Scripture cited in commentary is a link | Verified: Tyndale's `Rom 5:6-8; 15:3`, `2 Cor 8:9`, bare `8:1–10:33`, `1 Jn 4:9-10` render in the cross-reference colour and open the passage with a breadcrumb back. Henry (`Lam. iv. 10`, `v. 57`, `ch. xii. 3`) and JFB (`Lu 13:6`, `Ge 3:1-5`) shapes verified against the patterns only, not on a device — those commentaries were switched off on the emulator |
| Narration: whole sentences, trimmed pauses, lookahead queue | Verified on the emulator 2026-11-06 from the clause log: Genesis 1 and Psalm 118 are read sentence by sentence (a 22-word sentence stays one utterance; before, anything past 7 words split at the first comma and every comma became a full stop). Clauses are made 2–3 ahead of playback. Not covered: listening — the emulator has no ear here; the pause lengths (200/400/550 ms) are chosen, not judged |
| Section headings read aloud | Verified from the clause log: "The Creation", "The First Day", "Extol Him, All You Peoples" are generated and sounded. The report that they were not came from "Read from here", which skipped them as asides; `lineOfVerse` now starts on the heading. Local render: a two-word heading is 1.6 s at −9 dB peak, with or without a full stop |
| Carrying on into the next chapter | Verified: Psalm 117 read to its end rolled into Psalm 118 heading-first, page and breadcrumb followed. Off switch is Then → Stop in the player. Not covered: study mode (the voice waits up to 4 s for the commentary to load before starting) |
| Fourteen voices, each at its own warmth | Verified on the emulator 2026-11-06: Spencer, Hadley and the twelve renamed characters download under their new names, old files are pruned, and each reads at its own sampling temperature (Methuselah 0.9, the rest 0.6). No voice has a baked-in tempo: 0.36 played Methuselah at 1.25× and Joe found it "way too much", so speed is the Pace control alone (0.37). Not covered: the warmth figures for the characters other than Methuselah are the same 0.6, not chosen by ear |
| A note on a range shows once, bracketed | Verified on the emulator 2026-11-06: a note on Genesis 1:1–2 draws once under verse 2 with one unbroken bar from verse 1 to the foot of the note, labelled "verses 1–2"; tapping it edits the span; editing from verse 2's own sheet keeps the mark at 1–2 (it used to shrink it to one verse). Not covered: dark theme, study mode interleaving, narration of the note |
| Read-aloud with phrase highlight | In use; gapless after the one-clause lookahead fix |
| Music beds, intro-then-loop | PCM loop via AudioTrack. 45 s of unbroken playback on an 11.5 s loop — four joins crossed, the track never stopped |
| Bed rotation | All 8 played once, reshuffled, no back-to-back repeat |
| Self-update from GitHub | Full chain tested with the local server **stopped**: APK downloaded, installed, Check reported "Up to date", beds downloaded and played |
| Release signing | `CN=BSB Bible`, valid to 2056; every build (debug included) uses it |
| Beds vocal-free | Vocal stem separated with demucs: all 8 at −62 dB or quieter |
| Bed loop joins | Re-derived in 0.34 and measured: spectral distance across the join is 0.21–0.35 for all 8, against 0.49–1.32 before. Every loop region verified to fit the file actually on the device |
| Commentary in modern English | Tyndale Open Study Notes (2022) built in: 16,729 notes over 28,851 of 31,086 verses, all 66 books. Shipped in 0.32 |
| Per-commentary switches | Verified on the emulator in 0.33: the three switches render under *Commentary*, Henry and JFB off leaves only Tyndale on the page, and the choice survives a reinstall. Reachable from Settings → *What shows under each verse*, not only by long-pressing Study |
| Updating a data layer | Verified: the commentary row offers **Update** when already installed, and the 39 MB layer with Tyndale replaced the old one |
| Tap a number, tap another: a range | Verified on the emulator 2026-11-07: tapping 10 selects it (bar says "1 verse"), tapping 11 makes 10–11 ("2 verses"), Note on that writes one note labelled "verses 10–11". The anchor is held explicitly, so a third tap moves the far end. Not covered: hardware, tapping the lone selected verse to let it go (code path only), the drag (unchanged, still untested) |
| Commentary on a range shows once | Verified on the emulator 2026-11-07, Romans 8 with all three commentaries on: JFB on 10–11 draws once under 11 as "Jamieson, Fausset & Brown · verses 10–11" with a hairline bracket from 10; Henry's 1–9 runs unbroken past footnotes, JFB rows and verse 7–8; a note bracket on 10–11 draws over the commentary lane and the lane carries on below the note. Not covered: dark theme, Tyndale ranges that cross a chapter (the span is clamped to the page) |
| A link or a typed reference can carry a verse range | Verified on the emulator 2026-11-10: `bsb://open?ref=Philippians%202:3-4` lands on verse 3 with 3–4 selected and the bar reading "2 verses"; a BibleGateway `search=John+3:16-17` URL does the same; typing `phil 2:3-4` in Search offers "Philippians 2:3–4" and opens it selected. Before, the parser's regex ended at the verse, so any range failed to parse at all. Not covered: a range across chapters (`2:30-3:2` is not read), YouVersion's `PHP.2.3-4` form (code only), a range whose end is past the chapter (selection just runs to the last verse) |
| Copy selected verses with the reference | Verified on the emulator 2026-11-10 by pasting into the search box: Copy on Philippians 2:3–4 put "Philippians 2:3–4 (BSB)" on the first line and "3 Do nothing… 4 Each of you…" on the next; a single verse copies without its number. Android 13+ shows the system's own "Copied" overlay, older versions get a toast (not seen). The bar is now two lines, count and colours above the actions, because Copy did not fit on one. Not covered: dark theme, a phone |
| Citations in commentary no longer freeze the page | 0.41 froze a Pixel 9 (Android 17) in study mode: four ANRs, main thread in Compose's `TextLinkScope` computing a path per `LinkAnnotation` on every layout pass, with 580 MB resident and the GC holding it — a Matthew Henry paragraph carries hundreds of citations. 0.42 colours the citations with plain spans and resolves the tap from the laid-out text instead. Verified on the emulator 2026-11-08: tapping "Rom 7:7" in Wesley on Romans 8:1 lands on Romans 7:7 with the breadcrumb; tapping the body of a paragraph still opens it. Not covered: the Pixel itself — only Joe can confirm the freeze is gone; scroll timing was not measured |
| Wesley's lemmas underlined in the BSB verse | Verified on the emulator 2026-11-08, Romans 8:28 in study mode: gold dotted lines under "And we know", "that God works all things together for the good" and "called according to His purpose", and those three lemmas in gold italics in his note; the fourth ("To them that are called…") also placed. Isa 53:5 "Wounded" lands on "was pierced" and "The chastisement" on "the punishment" (checked in the build, not on screen). Not covered: dark theme, a verse where a footnote and a lemma underline the same word (both draw at the same height, gold on top), an old commentary layer on the phone (no table, so no underlines — code path only) |
| John Wesley's Notes as a fourth commentary | Verified on the emulator 2026-11-07: Romans 8:1 in study mode shows "John Wesley" between Tyndale and JFB, the lemma in italics ("*There is therefore now no condemnation* — Either for things present or past"), and "Rom 7:7" in it is a link. The import was checked against Gen 1:1, Ps 23:2, Isa 53:5, Mal 4:6, Matt 1:1, John 3:16, Rom 8:28 and Rev 22:21 after two wrong decodes that looked right (see CLAUDE.md). Not covered: the switch on a phone, narration over Wesley's dashes, the 668 one-line glosses on the page, a verse where Wesley's note is only a parallel reference ("Luke 6:31.") |
| Translator's notes anchored to their words | Verified on the emulator 2026-11-07: "Jesus" in Rom 8:1 carries a superscript *a* and a dotted underline ending before the full stop; the note under the verse reads "a Jesus  BYZ and TR *in Christ Jesus…*" with the italics kept. Study off: the verse is clean. Not covered: a phrase that wraps across two lines (drawn per line, not seen), narration over a verse with markers |
| Books: bringing epubs in | Verified on the emulator 2026-11-10 through the real system folder picker: a folder of 33 files came in with a progress card ("Adding 3 of 33"), a damaged file was left out and named, a duplicate was reported as already on the shelf, and tapping an epub in the Files app opened the app on the shelf and read the file. Not covered: the "Files" picker button; a folder of 200 real books (the test shelf was 2 real books and 31 two-chapter stand-ins); any phone |
| Books: the shelf | Verified on the emulator 2026-11-10, light and dark: real covers and generated cloth covers, the continue-reading card, sort by Recent / Title / Author, the filter with its count, the long-press sheet and two-tap removal (the file count on disk dropped). Not covered: clearing the filter and seeing the continue-reading card return was fixed but not re-tested |
| Books: reading | Verified on the emulator 2026-11-10 with both sample books: chapters, quotations set apart with their reference, the contents sheet with minutes per chapter, the chapter foot and next chapter, "The end" and "Finished" on the last page, and the place and the open book surviving a force-stop, on the debug and the 0.44 release build. For 0.45, sixteen full-length Project Gutenberg books (1.8 million words) went through the parser without an error, and Calvin's *Institutes* volume 1 (328,000 words) opened at once on the emulator and scrolled a 48-minute chapter. Not covered: tables or images in the text, double-tap to read from a block, a long chapter read aloud |
| Books: footnote marks and page numbers stay out of the text | Shipped in 0.45. 0.44, which was never published, printed and read aloud "[41]" and "[pg 196]" where a scanned book links a footnote or marks a page. Verified 2026-11-10: across the sixteen Gutenberg books the digest shows no page mark and four footnote marks left in 1.8 million words, and on the emulator the *Institutes* sentence that read "And [pg 196] certainly" reads "And certainly". The note's own number in a list of notes is kept. Not covered: Joe's own books; narration of a book with footnotes was heard for nine seconds only, on the 0.45 release build |
| Books: Scripture references open their passage | Verified on the emulator 2026-11-10: tapping "Genesis 3:8-9" shows both verses in a sheet over the book; "Open in the Bible" lands with the range selected and the book's title in the top bar as the way back, and going back restores the Bible to John 3 |
| Books: read aloud | Verified on the emulator 2026-11-10 from the clause log: a chapter is read with the clause lit and the page following, citations are spoken as "Romans 8, verse 28, and again in John 3, verses 16 to 17", and the end of chapter 1 rolled into chapter 2 without a touch. Not covered: Joe's ear has not judged it; a long chapter; the screen off |
| What follows a citation parses on the phone | Since 0.38 the continuation in "Rom 5:6-8; 15:3" was never linked on Android, because `\G` with `find(text, from)` never matches under ICU while it does on the desktop JVM, so the unit tests passed. The patterns added for books (ranges, lists, a translation in brackets) failed the same way until every site was moved to `matchAt`. Verified on the emulator 2026-11-10 by the passage sheet and the clause log above, and `bsb://open?ref=phillipians2:3-4` still selects both verses. Not covered: the "; 15:3" continuation in commentary was not looked at on the page after the fix |
| Pace changes while the voice is reading | Shipped in 0.46. Until then the pace was set only when an audio track was built, so a change made mid-read did nothing until the reading was stopped and started. Verified on the emulator 2026-11-10 from the new `heard` log line, which is audio seconds over wall seconds per clause: in a book 1.00x became 1.30x inside the clause that was sounding when 1.3× was tapped, then 0.90x the same way, and 0.90x held across pause and play; in Philippians 2 0.90x became 1.30x mid-read. **Not covered:** a real phone; whether the highlight stays on the right word across a change, which was reasoned from the playback head counting source frames and not watched; a voice change mid-read, which now also applies the new voice's tempo at once. |
| Long pauses with the app in the background | **Reported by Joe on 0.45, not reproduced, not fixed.** On the emulator 2026-11-10 the voice was made at about 15x listening speed and heard at 1.00x with no stall, with the app open, at home and with the screen off, so the emulator shows nothing either way. Starving the app of CPU as root reproduced the symptom exactly: speech still at 1.00x, one clause made at 0.3x, and a 25 s silence before the next. That makes a slowed model the likely cause on the phone but does not prove it. Shipped in 0.46 to find out: a tally of model speed and waits, split by app open and app away, at the foot of Settings (seen on the emulator reporting the 25 s wait, and a second line after reading at home); and a partial wake lock held while the reading service runs (seen held while reading and released when reading and music stop). **Not covered:** any measurement on a phone, which is the only thing that can settle the cause; whether the wake lock changes anything. |

## Known broken / unfinished

- **Press-and-drag verse selection.** Rewritten to a single `awaitEachGesture` in 0.24 after
  three failed attempts. Taps and the verse menu are verified; **the drag itself has never been
  tested on hardware** because adb cannot synthesise a long-press-then-drag. Unknown whether it
  works.
- **`playerPx` uses a high-water mark** (`if (it.height > playerPx)`), the same bug that caused
  the selection drift via `topBarPx`. Left alone deliberately: the player's voice panel genuinely
  changes height, it only affects bottom padding, and shrinking it would jump the page.
- **Morning's loop is short** (11.1s). Rotation covers it, but it is the weakest bed.
- **Secondary loops / outro not built.** The metadata is `loopStartMs` + `loopEndMs` only. Joe's
  original ask was intro → main → secondary → outro; only intro → main exists. Adding more
  markers is now a small change rather than a rewrite.

## Not started, decided against, or parked

- **Semantic search** ("find the verse I'm thinking of"). Fully measured, not built. Best
  pipeline is gte-small + HyDE: **76.7% recall@5 on a real 532-query benchmark, versus 28.8% for
  the keyword search that ships today.** Tier 1 (gte-small + 6 MB index, 64%) needs no LLM;
  tier 2 adds a ~900 MB generator for the rest. See the `bible-app-semantic-search` memory note
  for the full table, including the five approaches that did *not* work.
- **Play Store.** Viable, and the 12-tester rule is satisfiable by the church friends, but Play
  forbids self-updating apps so `REQUEST_INSTALL_PACKAGES` and `Updates.kt` would have to come
  out of a Play flavour.
- **F-Droid.** Dead end: FOSS-only, and both the in-app updater and the runtime Hugging Face
  model download violate policy.
- **Google developer verification.** Not required today for direct sideloading in Canada, but the
  2027 global rollout blocks installs *and updates* of unregistered apps. Registration is free
  (no ID, up to 50 devices) and needs the package name plus the key's SHA-256 — in
  `app/KEYSTORE-README.md`. Worth doing before 2027.

## Things that cost a lot of time, so they are written down

- **Measurements lied repeatedly about audio.** A spectral-jerk seam metric, a beat-continuity
  metric, a vocal-flatness detector and whisper transcription all agreed with me and disagreed
  with Joe; Joe was right every time. **Render the audio and listen.**
- **Whisper cannot find non-lexical singing.** It reported "(upbeat music)" over audible gibberish
  vocals and put psalter's vocal onset 57s late. Use
  `python3 -m demucs --two-stems=vocals` and measure the vocal stem.
- **Don't hand-roll beat/loop detection.** `pip install pymusiclooper` solved in one command what
  five of my own detectors got wrong. `beat_this` is the tool if true downbeats are ever needed.
- **ExoPlayer cannot loop a REGION of an Opus file without a gap.** `ClippingMediaPeriod` signals
  an initial discontinuity whenever the clip starts anywhere but zero, unless every sample is a
  sync sample — and `MimeTypes.allSamplesAreSyncSamples()` lists MP3, FLAC, AC3 and raw PCM but
  **not Opus**, though every Opus packet is independently decodable. The discontinuity resets the
  renderers, which flushes the audio sink, which rebuilds the AudioTrack: measured, the position
  froze 100-150 ms at every wrap, with both `REPEAT_MODE_ONE` and a playlist of clipped items.
  Not reachable from the MediaItem API. Decode the region to PCM and write it round and round.
- **A loop join must not be crossfaded.** Fading the end of a file into its start fades the loop
  back into the *intro* — music the track never returns to — and smears several seconds over
  itself. The points from PyMusicLooper match sample-exactly; a butt join is correct, and Joe
  confirmed it by ear.
