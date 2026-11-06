#!/usr/bin/env python3
"""Derive each music bed's loop region in GLOBAL MUSICAL TIME.

    cd /Users/frog/projects/bible && python3 -I data/loop_points.py
    # needs: pip install librosa   (and ffmpeg on PATH)

The method, and why it is this one:

A bed loops in time only if the loop is a whole number of BARS of the track's own tempo.
Two earlier attempts failed for instructive reasons, and both are worth not repeating.

  0.34 picked the loop with the smallest SPECTRAL DISTANCE across the seam. Spectral
  distance measures timbre, not time: it happily returned loops of 4.11, 5.62 and 16.42
  bars. Each ended mid-bar, and the ear — which is counting — heard a lurch.

  0.35 snapped the loop to librosa's DETECTED beat times, a whole number of detected
  beats apart. But that list drifts: successive intervals vary by tens of ms, so sixteen
  detected beats is not sixteen beats of musical time. Loops came out 24.386 and 35.641
  bars while being reported as exact.

So: build ONE grid per track — a single tempo and a single downbeat — and define every
position on it by ARITHMETIC, as `downbeat + k * bar`. That is global musical time. A
candidate loop is then an integer bar count by construction, not by measurement, and it
cannot drift however long the track runs.

The seam still matters, but only as a tie-break AMONG bar-exact candidates — never as a
reason to leave the grid.

Verification is by beat timing, not by timbre: `--verify` splices each loop three times,
re-runs beat tracking over the splice, and reports how late or early the beat that
straddles the join arrives. Under about 15 ms is inaudible; 100 ms is the lurch.

Loop points belong to one exact file. Re-render a bed's audio and they are scrap.
"""

import argparse, json, subprocess, sys
import numpy as np

SR = 24_000
BEDS = ["stillness", "morning", "evening", "psalter",
        "vigil", "upper-room", "wilderness", "shepherd"]
# Loops already in time keep their points unless a candidate is clearly better: a
# re-fit that "improves" a working bed is how stillness got worse in 0.35.
SHIPPED = {
    "stillness": (33901, 45047), "morning": (9961, 26587),
    "evening": (3640, 14925), "psalter": (12823, 55200),
    "vigil": (62198, 130767), "upper-room": (668, 75413),
    "wilderness": (51321, 143458), "shepherd": (50376, 149687),
}


def pcm(name, sr=SR):
    """The bed as mono float PCM, via ffmpeg."""
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", f"data/beds/{name}.opus",
         "-f", "f32le", "-ac", "1", "-ar", str(sr), "-"],
        capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32)


def grid(name):
    """One tempo and one downbeat for the whole track: global musical time.

    The tempo comes from a comb filter over the onset envelope rather than from the
    detected beat list, so it is a single number that holds across the file. The phase
    is the offset of the strongest periodic train at that tempo — the downbeat.
    """
    import librosa
    y, sr = librosa.load(f"data/beds/{name}.opus", sr=22050, mono=True)
    env = librosa.onset.onset_strength(y=y, sr=sr)
    fps = sr / 512
    seed, _ = librosa.beat.beat_track(y=y, sr=sr, trim=False)
    seed = float(np.atleast_1d(seed)[0])

    best = None
    for bpm in np.arange(seed * 0.97, seed * 1.03, 0.01):
        step = (60.0 / bpm) * fps
        for ph in np.arange(0, step, max(step / 64, 1)):
            idx = np.arange(ph, len(env) - 1, step)
            if len(idx) < 8:
                continue
            score = env[np.round(idx).astype(int)].sum() / len(idx)
            if best is None or score > best[0]:
                best = (score, bpm, ph / fps)
    _, bpm, phase = best
    beat = 60.0 / bpm
    # Pick the downbeat among the four beat phases: bars start where onsets are strongest.
    bar = 4 * beat
    pick, pscore = phase, -1.0
    for k in range(4):
        p = phase + k * beat
        idx = np.arange(p * fps, len(env) - 1, bar * fps)
        if len(idx) < 4:
            continue
        s = env[np.round(idx).astype(int)].sum() / len(idx)
        if s > pscore:
            pscore, pick = s, p
    return dict(beat=beat, bar=bar, bpm=bpm, downbeat=pick % bar, dur=len(y) / sr)


def seam(x, a_s, b_s, win=7200):
    """Spectral distance across the join — a TIE-BREAK only, never a reason to leave the grid."""
    A, B = int(a_s * SR), int(b_s * SR)
    if B > len(x) or A + win > len(x) or B - win < 0 or B <= A:
        return None
    pre, post = x[B - win:B], x[A:A + win]
    P = np.abs(np.fft.rfft(pre * np.hanning(win)))
    Q = np.abs(np.fft.rfft(post * np.hanning(win)))
    P, Q = P / (P.sum() + 1e-9), Q / (Q.sum() + 1e-9)
    spec = float(np.abs(P - Q).sum())
    amp = float(abs(np.sqrt((pre[-720:] ** 2).mean()) - np.sqrt((post[:720] ** 2).mean())))
    return spec + 0.5 * amp


def join_error_ms(name, a_ms, b_ms):
    """How late the beat crossing the loop join arrives. THE test: timing, not timbre."""
    import librosa
    x = pcm(name)
    loop = x[int(a_ms / 1000 * SR):int(b_ms / 1000 * SR)]
    if len(loop) < SR:
        return None
    spliced = np.concatenate([loop, loop, loop])
    y = librosa.resample(spliced, orig_sr=SR, target_sr=22050)
    _, beats = librosa.beat.beat_track(y=y, sr=22050, trim=False)
    bt = librosa.frames_to_time(beats, sr=22050)
    if len(bt) < 8:
        return None
    d = np.diff(bt)
    med = float(np.median(d))
    errs = []
    for j in (len(loop) / SR, 2 * len(loop) / SR):
        k = np.searchsorted(bt, j)
        if 1 <= k < len(bt):
            errs.append(abs(d[k - 1] - med))
    return round(float(np.mean(errs)) * 1000, 1) if errs else None


def search(name, g):
    """Every integer-bar loop on the track's own grid, ranked by seam."""
    x = pcm(name)
    bar, dn, dur = g["bar"], g["downbeat"], g["dur"]
    nbars = int((dur - dn) / bar)
    out = []
    for i in range(nbars):
        for n in range(4, nbars - i + 1):
            a = dn + i * bar
            b = a + n * bar
            if b > dur - 0.05 or n * bar < 8 or n * bar > 0.85 * dur:
                continue
            c = seam(x, a, b)
            if c is None:
                continue
            # A longer loop is worth a slightly worse seam: it comes round less often.
            out.append((c - 0.0022 * min(n * bar, 90), c, a, b, n))
    out.sort()
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--verify", action="store_true",
                    help="beat-timing test on the chosen loops (slow)")
    ap.add_argument("--beds", nargs="*", default=BEDS)
    args = ap.parse_args()

    chosen = {}
    print(f"{'bed':12}{'bpm':>7}{'bar s':>7}{'bars':>5}{'start':>9}{'end':>9}{'seam':>7}")
    for n in args.beds:
        g = grid(n)
        cands = search(n, g)
        if not cands:
            print(f"{n:12}  no bar-exact candidate", file=sys.stderr)
            continue
        _, c, a, b, bars = cands[0]
        chosen[n] = dict(start=int(round(a * 1000)), end=int(round(b * 1000)),
                         bars=bars, bpm=round(g["bpm"], 2), seam=round(c, 3))
        print(f"{n:12}{g['bpm']:7.1f}{g['bar']:7.3f}{bars:5d}"
              f"{a:9.3f}{b:9.3f}{c:7.3f}")

    if args.verify:
        print(f"\n{'bed':12}{'which':9}{'bars':>8}{'join err ms':>12}")
        for n, v in chosen.items():
            for lab, (a, b) in (("shipped", SHIPPED[n]),
                                ("new", (v["start"], v["end"]))):
                e = join_error_ms(n, a, b)
                print(f"{n:12}{lab:9}{v['bars'] if lab=='new' else '':>8}"
                      f"{e if e is not None else '-':>12}")

    out = "/private/tmp/claude-501/-Users-frog-projects/5b35e6fa-36be-485b-b9d7-f4f5889a0eef/scratchpad/loops.json"
    json.dump(chosen, open(out, "w"), indent=1)
    print(f"\nwrote {out}")


if __name__ == "__main__":
    main()
