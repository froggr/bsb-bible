# The signing key — read this before anything else

`keystore/release.jks` and `keystore.properties` are **irreplaceable**.

An Android app's update identity IS its signing key. Android installs an update only when the
new APK carries the same signature as the installed one. So if this keystore is lost:

- the app can never be updated again — every reader would have to uninstall and reinstall,
  **destroying their highlights, notes and reading position**; and
- the package name `ca.lampandlight` can never be registered with Google's developer
  verification programme, which from the 2027 global rollout is what keeps sideloaded installs
  and updates working at all. Google: "If you lose your signing key you won't be able to
  register your packages."

## Facts you will need

- Keystore: `app/keystore/release.jks` (4096-bit RSA, SHA384withRSA)
- Certificate subject: `CN=BSB Bible`
- Alias: `bsb-bible`
- Valid: 2026-11-05 → **2056-10-28** (30 years)
- Password: in `app/keystore.properties` (store and key passwords are the same)
- **SHA-256 fingerprint** (needed to register the package with Google):
  `61:0E:AB:46:4E:80:8D:81:B7:B9:31:3B:1D:45:AA:DC:25:A6:5C:0C:4F:85:DC:CD:29:17:EA:08:2F:6C:40:10`

Re-read the fingerprint any time with:

    keytool -list -v -keystore app/keystore/release.jks -alias bsb-bible

## Back it up off this machine

Both files, somewhere that is not this laptop — a password manager, an encrypted archive, a
printed copy in a drawer. Treat it like the deed to the app.

## Why the debug build is signed with this key too

The app is delivered by sideloading, and a debug-signed build cannot update a release-signed
install (or the reverse) — the mismatch forces an uninstall, which destroys the reader's marks.
Signing every build we hand out with one key means that can never happen.

## If you later put this on Google Play

When you enable Play App Signing, **upload this key** rather than letting Google generate one.
Google's own guidance: "If you want to use the same signing key across multiple stores, make
sure to provide your own signing key when you configure Play App Signing, instead of having
Google generate one for you." If Google generates the key, Play builds and self-hosted builds
can never update each other, and your friends' installs become a dead end.
