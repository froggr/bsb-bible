# Status

Last updated 2026-11-10, **0.43** released.

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
