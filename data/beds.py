#!/usr/bin/env python3
"""Build the music under the reading: beats and pads, whole tracks.

    python3 -I data/beds.py            # cut, encode, then measure the encoded files
    python3 -I data/beds.py --verify   # measure what is in data/beds/ and change nothing

Until 0.53 the beds were eight tracks, each cut down to a loop of 40 to 66 seconds that
went round for as long as you read. However exact the join, a minute of music on repeat
is a minute of music on repeat. These are whole tracks from ~/Music/Scripture Radio, played
through one after another, so there are no loop points to get wrong and twenty minutes go
by before anything comes round again.

Two sets. BEATS is the Lo-Fi folder: drums all the way through. PADS is the Prayer Room
folder: no drums at all. The app plays one set or the other (Bed.kt).

Every track here was chosen by measurement, and is measured again after encoding, on the
bytes that ship:

  - No voice. demucs separates the track and the vocal stem has to sit at least 40 dB
    under everything else, over the whole track AND in its worst ten seconds. A voice for
    one phrase is still a voice under a reading. 23 of the 54 tracks in the two folders
    fail this, so it is a measurement and not an assumption.
  - A beat, for the beats: the drum stem within a few dB of the whole. And none, for the pads.

The measurement is not steady, which is the reason for making it twice. demucs places its
windows at a random offset, and a held organ or pad can be heard as a held voice on one
pass and not the next: "Resting Psalm" measured -59 dB from its mp3 and -11 dB once
encoded, "Deep Prayer" -59 and then -39, and "Silent Garden" -45 (-33 at its worst) on one
pass over the encoded file and -63 on the next. "Dawn Breath", six minutes of string
drone, had a worst ten seconds of -38 and -36. All four were dropped. Nothing is argued
back in: after a build, run --verify as well, and a track that fails either pass is taken
out of the lists below. What is left passed both, twice over the same bytes.

A beat track that ends with a long tail of ring-out is cut a few seconds after its last
drum, because the next track fades in over the end of this one and a quarter of a minute
of fading piano between two beats is a hole in the bed. Run-ins are left as written.

Needs ffmpeg with libopus, and python with demucs, torch and numpy.
"""
import json, pathlib, subprocess, sys, warnings
import numpy as np
warnings.filterwarnings("ignore")

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE / "beds"
SOURCE = pathlib.Path.home() / "Music/Scripture Radio"
SR = 44100
#: Where the eight beds before these sat, so the slider means what it meant.
LOUDNESS = -18.5
#: Vocal stem against the rest, in dB: the whole track, and its worst ten seconds.
CLEAN = -40.0
#: Kept after the last drum of a beat, for the next track to fade in over.
TAIL = 6.0

BEATS = [
    ("still-light", "Lo-Fi/Still Light (rainy piano).mp3"),
    ("tender-presence", "Lo-Fi/Tender Presence (rainy piano).mp3"),
    ("holy-breath", "Lo-Fi/Holy Breath (jazzy guitar).mp3"),
    ("morning-light", "Lo-Fi/Morning Light (jazzhop).mp3"),
    ("still-refuge", "Lo-Fi/Still Refuge (mellow keys).mp3"),
    ("resting-grace", "Lo-Fi/Resting Grace (dusty soul).mp3"),
    ("morning-shelter", "Lo-Fi/Morning Shelter (study beats).mp3"),
    ("abiding-mercy", "Lo-Fi/Abiding Mercy (study beats).mp3"),
    ("waiting-mercy", "Lo-Fi/Waiting Mercy (late night).mp3"),
]
PADS = [
    ("tender-grace", "Prayer Room/Tender Grace (soft organ drone).mp3"),
    ("abiding-light", "Prayer Room/Abiding Light (pads and far piano).mp3"),
    ("quiet-stillness", "Prayer Room/Quiet Stillness (warm pads).mp3"),
    ("resting-breath", "Prayer Room/Resting Breath (warm pads).mp3"),
    ("gentle-refuge", "Prayer Room/Gentle Refuge (breath pads).mp3"),
]
SETS = {"beats": (BEATS, "64k"), "pads": (PADS, "48k")}

_model = None


def load(path):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(path), "-f", "f32le", "-ac", "2",
                          "-ar", str(SR), "-"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32).reshape(-1, 2).T.copy()


def db(x):
    return 20 * np.log10(np.sqrt(np.mean(x ** 2)) + 1e-9)


def windows(x, sec, hop=None):
    n = int(sec * SR)
    hop = hop or n // 2
    return [x[..., i:i + n] for i in range(0, max(1, x.shape[-1] - n + 1), hop)]


def stems(x):
    """The track as demucs hears it: {'vocals', 'drums', 'bass', 'other'} -> samples."""
    global _model
    import torch
    from demucs.pretrained import get_model
    from demucs.apply import apply_model
    if _model is None:
        _model = get_model("htdemucs")
        _model.eval()
    dev = "mps" if torch.backends.mps.is_available() else "cpu"
    wav = torch.from_numpy(x)[None]
    ref = wav.mean(1, keepdim=True)
    with torch.no_grad():
        src = apply_model(_model, (wav - ref.mean()) / (ref.std() + 1e-8), device=dev,
                          split=True, overlap=0.25, progress=False)[0]
    src = (src * (ref.std() + 1e-8)).cpu().numpy()
    return dict(zip(_model.sources, src))


def loudness(path, extra=()):
    err = subprocess.run(["ffmpeg", "-nostats", "-i", str(path), *extra, "-af", "ebur128=peak=true",
                          "-f", "null", "-"], capture_output=True, text=True).stderr
    tail = err[err.rindex("Summary:"):]
    i = float([l for l in tail.splitlines() if l.strip().startswith("I:")][0].split()[1])
    peak = float([l for l in tail.splitlines() if l.strip().startswith("Peak:")][0].split()[1])
    return i, peak


def span(x, s, beat):
    """Where the track is cut: (from, to) in seconds."""
    mono = np.abs(x).max(0)
    loud = np.where(mono > 10 ** (-50 / 20))[0]
    a, b = loud[0] / SR, (loud[-1] + 1) / SR
    if beat:
        # The last second with a drum in it: within 20 dB of the drums' usual level.
        d = [db(w) for w in windows(s["drums"], 1.0, hop=SR)]
        usual = np.median([v for v in d if v > max(d) - 20])
        last = max(i for i, v in enumerate(d) if v > usual - 20) + 1
        b = min(b, last + TAIL)
    return round(max(0.0, a - 0.05), 3), round(b, 3)


def measure(x, s):
    acc = sum(v for k, v in s.items() if k != "vocals")
    voc = s["vocals"]
    worst = max((db(v) - db(a), i * 5) for i, (v, a) in enumerate(zip(windows(voc, 10), windows(acc, 10))))
    return dict(vocal_db=round(float(db(voc) - db(acc)), 1), vocal_worst_db=round(float(worst[0]), 1),
                vocal_worst_at=worst[1], drums_db=round(float(db(s["drums"]) - db(acc)), 1))


def build(name, rel, rate, beat):
    src = SOURCE / rel
    x = load(src)
    a, b = span(x, stems(x), beat)
    cut = ["-ss", f"{a}", "-t", f"{b - a}"]
    was, _ = loudness(src, cut)
    gain = LOUDNESS - was
    dest = OUT / f"{name}.opus"
    # A plain gain, not a loudness filter: nothing about the music's own dynamics is touched.
    subprocess.run(["ffmpeg", "-v", "error", "-y", *cut, "-i", str(src), "-af", f"volume={gain:.2f}dB",
                    "-ar", "48000", "-ac", "2", "-c:a", "libopus", "-b:a", rate, "-vbr", "on",
                    "-map_metadata", "-1", str(dest)], check=True)
    return dict(source=rel, cut=[a, b], gain_db=round(gain, 2))


def verify(name, beat):
    """Measure the file that ships. Raises if it is not what the app is told it is."""
    f = OUT / f"{name}.opus"
    x = load(f)
    m = measure(x, stems(x))
    i, peak = loudness(f)
    m.update(secs=round(x.shape[1] / SR, 2), lufs=i, peak_db=peak, bytes=f.stat().st_size)
    ok = m["vocal_db"] <= CLEAN and m["vocal_worst_db"] <= CLEAN
    ok = ok and (m["drums_db"] > -8 if beat else m["drums_db"] < -30)
    m["ok"] = bool(ok)
    return m


def main():
    only = "--verify" in sys.argv
    OUT.mkdir(exist_ok=True)
    record_at = OUT / "beds.json"
    record = json.loads(record_at.read_text()) if record_at.exists() else {}
    bad = []
    for kind, (tracks, rate) in SETS.items():
        for name, rel in tracks:
            beat = kind == "beats"
            row = record.get(name, {})
            if not only:
                row = build(name, rel, rate, beat)
            row.update(set=kind, **verify(name, beat))
            record[name] = row
            print(f"{kind:5s} {name:16s} {row['secs']:6.1f} s  {row['lufs']:6.1f} LUFS  peak {row['peak_db']:5.1f}  "
                  f"voice {row['vocal_db']:6.1f} (worst {row['vocal_worst_db']:6.1f} at {row['vocal_worst_at']} s)  "
                  f"drums {row['drums_db']:6.1f}  {row['bytes'] / 1e6:.2f} MB  {'ok' if row['ok'] else 'FAILS'}",
                  flush=True)
            if not row["ok"]:
                bad.append(name)
    keep = {n for tracks, _ in SETS.values() for n, _ in tracks}
    record = {k: v for k, v in record.items() if k in keep}
    record_at.write_text(json.dumps(record, indent=1) + "\n")
    for kind, (tracks, _) in SETS.items():
        secs = sum(record[n]["secs"] for n, _ in tracks)
        size = sum(record[n]["bytes"] for n, _ in tracks)
        print(f"{kind}: {len(tracks)} tracks, {secs / 60:.1f} min, {size / 1e6:.1f} MB")
    if bad:
        sys.exit("NOT CLEAN, do not ship: " + ", ".join(bad))


if __name__ == "__main__":
    main()
