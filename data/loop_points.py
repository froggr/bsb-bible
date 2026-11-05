#!/usr/bin/env python3
"""Re-derive the music beds' loop points: bar-exact, then nudged for the cleanest join.

    cd /Users/frog/projects/bible && python3 data/loop_points.py
    # needs: pip install librosa   (and ffmpeg on PATH)

Reads data/beds/*.opus and bars.json (written by the beat pass below), prints each bed's
loopStartMs/loopEndMs and bar count, and writes combined.json. Paste into Beds.ALL in Bed.kt.

Why bars and not just a smooth join: a loop can measure beautifully and still lurch,
because the ear is counting. Release 0.34 shipped loops of 4.11, 5.62 and 16.42 bars —
each one landed mid-bar. Musical length comes first; the seam is optimised within it.

Run this whenever a bed's audio is re-rendered: loop points belong to one exact file.
"""

import numpy as np, json, subprocess
SR=24000
def pcm(p): return np.frombuffer(subprocess.run(["ffmpeg","-v","error","-i",p,"-f","f32le",
    "-ac","1","-ar",str(SR),"-"],capture_output=True).stdout,dtype=np.float32)
def cost(x,A,B,win=7200):
    A,B=int(A),int(B)
    if B>len(x) or A+win>len(x) or B-win<0 or B<=A: return None
    pre,post=x[B-win:B],x[A:A+win]
    P=np.abs(np.fft.rfft(pre*np.hanning(win))); Q=np.abs(np.fft.rfft(post*np.hanning(win)))
    P,Q=P/(P.sum()+1e-9),Q/(Q.sum()+1e-9)
    spec=float(np.abs(P-Q).sum())
    amp=float(abs(np.sqrt((pre[-720:]**2).mean())-np.sqrt((post[:720]**2).mean())))
    return spec+0.5*amp
def beat_grid(name):
    """Tempo and beat times for one bed."""
    import librosa
    y, sr = librosa.load(f"data/beds/{name}.opus", sr=22050, mono=True)
    tempo, beats = librosa.beat.beat_track(y=y, sr=sr, trim=False)
    bt = librosa.frames_to_time(beats, sr=sr)
    per = float(np.median(np.diff(bt))) if len(bt) > 2 else 60.0 / max(float(np.atleast_1d(tempo)[0]), 1)
    return dict(tempo=float(np.atleast_1d(tempo)[0]), period=per, beats=bt.tolist(), dur=len(y) / sr)

BEDS = ["stillness", "morning", "evening", "psalter", "vigil", "upper-room", "wilderness", "shepherd"]
g = {n: beat_grid(n) for n in BEDS}
out={}
print(f"{'bed':<12} {'bars':>5} {'loop s':>8} {'start':>8} {'end':>8} {'seam':>7}")
for n,v in g.items():
    x=pcm(f"data/beds/{n}.opus")
    beats=np.array(v["beats"]); per=v["period"]; dur=v["dur"]
    bar=4*per
    pool=[]
    for i in range(len(beats)):
        for j in range(i+16, len(beats)):           # >= 4 bars
            nb=(j-i)/4.0
            if abs(nb-round(nb))>1e-9: continue
            L=beats[j]-beats[i]
            if L<8 or L>0.8*dur: continue
            # slide the WHOLE window, so the length stays an exact bar multiple
            for sh in np.arange(-0.5,0.51,0.05)*per:
                A,B=(beats[i]+sh)*SR,(beats[j]+sh)*SR
                if A<0: continue
                c=cost(x,A,B)
                if c is None: continue
                pool.append((c-0.0022*min(L,90), c, A, B, round(nb)))
    if not pool: print(f"{n}: none"); continue
    pool.sort()
    _,c,A,B,nb=pool[0]
    out[n]=dict(start=int(round(A/SR*1000)), end=int(round(B/SR*1000)), bars=nb)
    print(f"{n:<12} {nb:5d} {(B-A)/SR:8.2f} {A/SR:8.2f} {B/SR:8.2f} {c:7.3f}")
json.dump(out, open("combined.json", "w"), indent=1)
print("\nwritten to combined.json")
