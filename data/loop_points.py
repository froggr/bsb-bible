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


BANDS = [(20, 250, "bass"), (250, 2000, "mid"), (2000, 11000, "high")]


def _band_db(pre, post, lo, hi):
    """Level change in one band across the join, in dB."""
    def e(seg):
        S = np.abs(np.fft.rfft(seg * np.hanning(len(seg))))
        f = np.fft.rfftfreq(len(seg), 1 / SR)
        m = (f >= lo) & (f < hi)
        return float(np.sqrt((S[m] ** 2).mean())) if m.any() else 0.0
    return 20 * np.log10(max(e(post), 1e-9) / max(e(pre), 1e-9))


def seam(x, a_s, b_s, win=7200):
    """How ABRUPT the join is, in the terms the ear uses. Tie-break among bar-exact loops.

    A single averaged spectral distance was blind three times over, and each blind spot
    shipped. It ignores LEVEL, so a loud bar wrapping into a soft one scored best of all
    candidates on morning. It averages across FREQUENCY, so an 8-bar candidate whose bass
    had dropped out entirely — -33.7 dB across the join — scored fine on the strength of
    matching mids and highs. And it says nothing about TRANSIENTS.

    So the parts are measured separately and the worst one is not allowed to hide:

      level   RMS either side, in dB.
      bands   bass / mid / high separately, so no band can be averaged away.
      timbre  the old spectral distance, which is still worth something.

    Returns a single cost for ranking, but `report()` prints the parts — read those when a
    bed sounds wrong, because the summary is exactly what kept failing.
    """
    A, B = int(a_s * SR), int(b_s * SR)
    if B > len(x) or A + win > len(x) or B - win < 0 or B <= A:
        return None
    pre, post = x[B - win:B], x[A:A + win]
    P = np.abs(np.fft.rfft(pre * np.hanning(win)))
    Q = np.abs(np.fft.rfft(post * np.hanning(win)))
    Pn, Qn = P / (P.sum() + 1e-9), Q / (Q.sum() + 1e-9)
    timbre = float(np.abs(Pn - Qn).sum())
    level = abs(20 * np.log10(
        max(float(np.sqrt((post ** 2).mean())), 1e-9) /
        max(float(np.sqrt((pre ** 2).mean())), 1e-9)))
    worst_band = max(abs(_band_db(pre, post, lo, hi)) for lo, hi, _ in BANDS)
    # Level and the worst band dominate: those are the faults that were heard.
    return timbre + 0.25 * level + 0.08 * worst_band


def onset_ratio(x, a_s, b_s, bar_s):
    """Does the join disturb the onset flow more than an ordinary bar line does?

    This is the drum/transient question. Splice the loop three times, take the onset
    strength envelope, and compare the disturbance AT the seam with the disturbance at the
    track's own interior bar lines, where nothing is spliced. 1.0 means the seam is
    indistinguishable from a bar line; morning's chosen loop measures 0.98, and the
    candidates that sounded abrupt measure 1.33 to 2.27.
    """
    import librosa
    A, B = int(a_s * SR), int(b_s * SR)
    loop = x[A:B]
    if len(loop) < SR:
        return None
    L = len(loop) / SR
    ys = librosa.resample(np.concatenate([loop, loop, loop]),
                          orig_sr=SR, target_sr=22050)
    env = librosa.onset.onset_strength(y=ys, sr=22050)
    fps = 22050 / 512

    def jump(t):
        i, k = int(t * fps), max(2, int(0.08 * fps))
        if i - k < 0 or i + k >= len(env):
            return None
        return abs(float(env[i:i + k].mean()) - float(env[i - k:i].mean()))

    seams = [j for j in (jump(L), jump(2 * L)) if j is not None]
    base, t = [], bar_s
    while t < 3 * L - bar_s:
        if abs(t - L) > 0.3 and abs(t - 2 * L) > 0.3:
            j = jump(t)
            if j is not None:
                base.append(j)
        t += bar_s
    if not seams or not base:
        return None
    med = float(np.median(base))
    return float(np.mean(seams)) / med if med > 0 else None


def report(x, a_s, b_s, bar_s):
    """The parts, for reading when a bed sounds wrong despite a good summary cost.

    Measured over TWO windows, because they answer different questions and reading only one
    is misleading. At 300 ms the figures describe the instant of the splice, where a note
    decaying into the downbeat shows up as a large bass dip that is ordinary music rather
    than a fault. At 1.5 s they describe the phrase either side, which is the loud-into-soft
    abruptness a listener actually reports.

    Morning's chosen loop reads -2.5 dB at 300 ms and +1.3 dB at 1.5 s; the loop it replaced
    reads -8.2 dB and -4.3 dB. The RANKING is the same at every window length — only the
    absolute numbers move — so compare candidates, and do not read one window as a verdict.
    """
    out = {}
    A = int(a_s * SR)
    B = int(b_s * SR)
    for lab, win in (("short", int(0.3 * SR)), ("phrase", int(1.5 * SR))):
        pre, post = x[max(0, B - win):B], x[A:A + win]
        if len(pre) < 256 or len(post) < 256:
            continue
        out[lab] = dict(
            level=20 * np.log10(max(float(np.sqrt((post ** 2).mean())), 1e-9) /
                                max(float(np.sqrt((pre ** 2).mean())), 1e-9)),
            bands={l: _band_db(pre, post, lo, hi) for lo, hi, l in BANDS},
        )
    out["onset"] = onset_ratio(x, a_s, b_s, bar_s)
    return out


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
        print(f"\n{'bed':12}{'which':9}{'join ms':>8}{'dLevel':>8}"
              f"{'bass':>7}{'mid':>7}{'high':>7}{'onsetR':>8}")
        for n, v in chosen.items():
            g = grid(n)
            x = pcm(n)
            for lab, (a, b) in (("shipped", SHIPPED[n]),
                                ("new", (v["start"], v["end"]))):
                e = join_error_ms(n, a, b)
                r = report(x, a / 1000, b / 1000, g["bar"])
                ph = r.get("phrase", {})
                bd = ph.get("bands", {})
                o = r["onset"]
                print(f"{n:12}{lab:9}"
                      f"{(f'{e:.1f}' if e is not None else '-'):>8}"
                      f"{ph.get('level', 0):+8.2f}{bd.get('bass', 0):+7.1f}"
                      f"{bd.get('mid', 0):+7.1f}{bd.get('high', 0):+7.1f}"
                      f"{(f'{o:.2f}' if o else '-'):>8}")
        print("\njoin ms: beat crossing the seam, <15 inaudible. dLevel/bands: dB across")
        print("the join over the 1.5 s PHRASE window. onsetR: seam vs the track's own bar")
        print("lines, 1.0 = indistinguishable. See report() for the 300 ms window too.")

    out = "/private/tmp/claude-501/-Users-frog-projects/5b35e6fa-36be-485b-b9d7-f4f5889a0eef/scratchpad/loops.json"
    json.dump(chosen, open(out, "w"), indent=1)
    print(f"\nwrote {out}")


if __name__ == "__main__":
    main()
