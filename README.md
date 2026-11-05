# BSB Bible

An offline study Bible for Android: the Berean Standard Bible, read aloud in a natural voice,
with highlights, notes and study material that sits inline with the text.

Built with AI — "vibe coded". I described what I wanted and Claude wrote it, with me testing
on a real phone and sending it back when it was wrong. Worth knowing before you install it:
nobody has audited this code, and the bugs it has are the kind that come from being built fast
rather than carefully. It is a personal project I use daily, shared because friends asked.

The Bible text itself is not AI-generated — it is the Berean Standard Bible, unmodified.

## Install

**[Download the latest version](https://github.com/froggr/bsb-bible/releases/latest)** — open
`app.apk` on your phone and tap it.

Android will ask whether to allow installing from your browser or files app. That prompt is
normal for an app that does not come from the Play Store; you grant it once.

Needs Android 10 or newer, and about 120 MB free once the voice is downloaded.

## What it does

- **The whole Bible, offline.** No account, no network needed to read.
- **Read aloud.** A small neural voice runs on the phone — not a robotic system voice, and not
  a cloud service. It highlights each phrase as it is spoken, and keeps reading with the screen
  off. Optional quiet instrumental music can play underneath.
- **Marking is fast.** Press and drag across verses to highlight the range; a note applies to the
  whole span. Built to be usable during a sermon.
- **Study mode.** Commentary, cross-references and footnotes appear between the verses rather
  than behind a tap, so you can study while you read. Scripture stays the dominant text.
- **It remembers where you were**, down to the verse.

## Updates

The app checks for new versions itself and offers to install them. You can also check by hand:
**Marks & settings → Settings → About**.

Updates are published here as new releases.

## Optional downloads

The app works fully offline on first install. From Settings you can add:

- the **voice** for reading aloud (about 60 MB)
- **study material** — Strong's numbers, cross-references (about 20 MB)
- **commentary** (about 11 MB)
- **music beds** — eight instrumental tracks (about 12 MB)

## Why it is not on the Play Store

It is a personal project, shared with friends. The Play Store also does not allow an app to
update itself, which is how this one delivers new versions.

## Text and credits

The verse text is the **Berean Standard Bible**, chosen because it is freely available for
anyone to use and share. Read-aloud uses the open-source **Phonon** speech model.

This repository holds the built app only. Questions or problems: open an issue.

The read-aloud voice is a neural model running on your phone; it mispronounces things
occasionally, and proper nouns are its weak spot.
