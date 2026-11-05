# Status

Last updated 2026-11-05, release **0.33**.

Install / share: <https://github.com/froggr/bsb-bible/releases/latest>

## Works, and verified

| Thing | How it was verified |
|---|---|
| Reading, marks, notes, study mode | In use daily |
| Read-aloud with phrase highlight | In use; gapless after the one-clause lookahead fix |
| Music beds, intro-then-loop | PCM loop via AudioTrack, **verified on `stillness` only**. Two consecutive 22.304s passes took 22.175s and 22.441s of wall clock, playback head never stalled. The other seven have bad loop points — see Known broken |
| Bed rotation | All 8 played once, reshuffled, no back-to-back repeat |
| Self-update from GitHub | Full chain tested with the local server **stopped**: APK downloaded, installed, Check reported "Up to date", beds downloaded and played |
| Release signing | `CN=BSB Bible`, valid to 2056; every build (debug included) uses it |
| Beds vocal-free | Vocal stem separated with demucs: all 8 at −62 dB or quieter |
| Commentary in modern English | Tyndale Open Study Notes (2022) built in: 16,729 notes over 28,851 of 31,086 verses, all 66 books. Shipped in 0.32 |
| Per-commentary switches | Verified on the emulator in 0.33: the three switches render under *Commentary*, Henry and JFB off leaves only Tyndale on the page, and the choice survives a reinstall. Reachable from Settings → *What shows under each verse*, not only by long-pressing Study |
| Updating a data layer | Verified: the commentary row offers **Update** when already installed, and the 39 MB layer with Tyndale replaced the old one |

## Known broken / unfinished

- **Bed loop points are wrong on seven of the eight.** Only `stillness` loops cleanly. The
  rest jump back to a point far too early in the track, so the join is audible and the piece
  restarts before it has gone anywhere. The loop machinery itself is sound — `stillness`
  proves that — so this is the per-bed loop points, not the player: they were measured for
  one track and never redone for the others. `pymusiclooper` finds the points; the note in
  `README.md` on never crossfading a loop join still stands. Next session's job.

- **Press-and-drag verse selection.** Rewritten to a single `awaitEachGesture` in 0.24 after
  three failed attempts. Taps and the verse menu are verified; **the drag itself has never been
  tested on hardware** because adb cannot synthesise a long-press-then-drag. Unknown whether it
  works.
- **Tap-to-extend anchors on the wrong end.** `MainActivity.onBookmark` takes its anchor from
  `selection.first()` on an *unordered* `Set`, so after a drag a third tap can pivot from the
  wrong verse. Found, not fixed.
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
