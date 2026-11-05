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

**Do not upload `public/beds/*.opus` blind.** As of 0.32 the local files differ from the
released ones (`morning.opus` 1.59 MB local vs 226 KB on 0.31) for reasons nobody has
explained. 0.32 deliberately re-uploaded 0.31's beds instead. Settle which set is correct
before publishing any bed.

## Verify on a device before claiming it works

Compiling is not working, and `BUILD SUCCESSFUL` is not a test. Two defects shipped in 0.32
that a single install would have caught — a data layer that cannot be re-downloaded once a
stale copy is present, and per-source toggles nobody had seen render.

`STATUS.md` has a *Works, and verified* table. Only put something there with the evidence
that verified it, and say what was **not** covered: that table once claimed the music beds
loop correctly on the strength of a measurement of `stillness` alone, while the other seven
loop back far too early.

## Data

`data/build.py` rebuilds everything from `data/src/`. It writes `data/bible.db` and splits it
into the reading / study / commentary layers, so run it with no argument — passing an output
path writes only the monolith and leaves the shipped layers untouched.

`data/src/` is gitignored (85 MB of BSB tables). Keep `data/src/x_TYN/_README.txt`: it is the
licence text for the Tyndale notes, which are CC BY-SA 4.0 and need attribution, unlike the
two public-domain commentaries.
