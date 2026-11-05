#!/usr/bin/env python3
"""Re-derive the music beds' loop points, and show whether they improved.

    cd /Users/frog/projects/bible && python3 data/loop_points.py

Reads data/beds/*.opus, prints the chosen loopStartMs/loopEndMs for each, and writes
them to final.json. Paste the numbers into Beds.ALL in Bed.kt.

Run this whenever a bed's audio is re-rendered: loop points belong to one exact file,
and a re-render silently invalidates them. That is how seven of the eight came to loop
back far too early before 0.34.
"""

import subprocess, numpy as np, json
SR=24000
OLD={"stillness":(22018,44322),"morning":(2198,13344),"evening":(6763,51935),
     "psalter":(20535,55828),"vigil":(46592,156299),"upper-room":(54763,141771),
     "wilderness":(39831,118656),"shepherd":(88266,154474)}
MIN={"stillness":10,"morning":9,"evening":10,"psalter":12,
     "vigil":25,"upper-room":25,"wilderness":25,"shepherd":25}

def pcm(p):
    return np.frombuffer(subprocess.run(["ffmpeg","-v","error","-i",p,"-f","f32le","-ac","1","-ar",str(SR),"-"],
                         capture_output=True).stdout,dtype=np.float32)

def cost(x,A,B,win=7200):
    """A = loop start sample, B = loop end sample. Lower is a better join."""
    A,B=int(A),int(B)
    if B>len(x) or A+win>len(x) or B-win<0 or B<=A: return None
    pre,post=x[B-win:B],x[A:A+win]
    P=np.abs(np.fft.rfft(pre*np.hanning(win))); Q=np.abs(np.fft.rfft(post*np.hanning(win)))
    P,Q=P/(P.sum()+1e-9),Q/(Q.sum()+1e-9)
    spec=float(np.abs(P-Q).sum())
    amp=float(abs(np.sqrt((pre[-720:]**2).mean())-np.sqrt((post[:720]**2).mean())))
    return spec+0.5*amp

def seam300(x,A,B):
    """The reported figure: same shape, 300 ms, comparable to the old audit."""
    n=7200; A,B=int(A),int(B)
    if B>len(x) or A+n>len(x) or B-n<0: return None
    P=np.abs(np.fft.rfft(x[B-n:B]*np.hanning(n))); Q=np.abs(np.fft.rfft(x[A:A+n]*np.hanning(n)))
    P,Q=P/(P.sum()+1e-9),Q/(Q.sum()+1e-9)
    return float(np.abs(P-Q).sum())

def cands(path,ml,top=20):
    out=subprocess.run(["pymusiclooper","export-points","--path",path,"--fmt","seconds",
        "--alt-export-top",str(top),"--min-loop-duration",str(ml)],capture_output=True,text=True).stdout
    r=[]
    for l in out.splitlines():
        p=l.split()
        if len(p)>=3:
            try: r.append((float(p[0]),float(p[1])))
            except ValueError: pass
    return r

out={}
print(f"{'bed':<12} {'old':>7} {'new':>7} {'loop s':>8}  source")
for name,(oa,ob) in OLD.items():
    path=f"data/beds/{name}.opus"; x=pcm(path); dur=len(x)*1000/SR
    pool=[]
    for a,b in cands(path,MIN[name]):
        for da in range(-12000,12001,240):
            for db in range(-12000,12001,240):
                A,B=a*SR+da,b*SR+db
                if (B-A)/SR < MIN[name]: continue
                c=cost(x,A,B)
                if c is None: continue
                pool.append((c+0.0015*max(0,60-(B-A)/SR), A, B))
    incumbent=None
    if ob<=dur:
        c=cost(x,oa/1000*SR,ob/1000*SR)
        if c is not None: incumbent=(c,oa/1000*SR,ob/1000*SR)
    if incumbent: pool.append((incumbent[0], incumbent[1], incumbent[2]))
    pool.sort()
    _,A,B=pool[0]
    kept = incumbent is not None and abs(A-incumbent[1])<1 and abs(B-incumbent[2])<1
    s_old = seam300(x,oa/1000*SR,ob/1000*SR) if ob<=dur else None
    s_new = seam300(x,A,B)
    out[name]=dict(start=int(round(A/SR*1000)), end=int(round(B/SR*1000)))
    o = f"{s_old:7.3f}" if s_old is not None else "   n/a "
    print(f"{name:<12} {o} {s_new:7.3f} {(B-A)/SR:8.1f}  {'kept old' if kept else 'new'}")
json.dump(out, open("final.json","w"), indent=1)
print("\nwritten to final.json")
