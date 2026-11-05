"""Read a SWORD zCom4 commentary module into {osisRef: text}.

The zverse4 index is positional over the module's versification: each testament
file holds, per verse slot, a 12-byte record (block, offset, size). Chapter and
book boundaries appear in the stream as osisID milestones, so we walk those to
label every slot rather than hard-coding a versification table.
"""
import struct, zlib, re, pathlib

class ZCom:
    """rec=12 for zCom4 (<III), rec=6 for zCom (<HIH)."""
    def __init__(self, base, rec=12):
        self.base = pathlib.Path(base); self._cache = {}; self.rec = rec

    def _blocks(self, t):
        return (self.base / f"{t}.bzs").read_bytes()

    def _blk(self, t, n):
        k = (t, n)
        if k not in self._cache:
            bzs = self._blocks(t)
            off, cs, us = struct.unpack("<III", bzs[n*12:(n+1)*12])
            bzz = (self.base / f"{t}.bzz").read_bytes()
            self._cache[k] = zlib.decompress(bzz[off:off+cs])
        return self._cache[k]

    def entries(self, t):
        bzv = (self.base / f"{t}.bzv").read_bytes()
        r = self.rec; fmt = {12: "<III", 10: "<HII", 8: "<HIH"}[r]
        for i in range(len(bzv)//r):
            bn, off, sz = struct.unpack(fmt, bzv[i*r:(i+1)*r])
            if sz == 0 or bn > 60:
                yield i, None; continue
            try: yield i, self._blk(t, bn)[off:off+sz].decode("utf-8", "replace")
            except Exception: yield i, None

    def read(self, t):
        """Yield (osisRef, plain_text). Verse slots follow the last chapter seen."""
        out, book, chap, vnum = [], None, None, 0
        for i, raw in self.entries(t):
            if raw is None: continue
            bid = re.search(r'osisID="([A-Za-z0-9]+)" [^>]*type="book"', raw)
            cid = re.search(r'<chapter n="(\d+)" osisID="([A-Za-z0-9]+)\.(\d+)"', raw)
            if bid: book = bid.group(1)
            if cid:
                book, chap, vnum = cid.group(2), int(cid.group(3)), 0
                continue
            if chap is None: continue
            vnum += 1
            txt = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", raw)).strip()
            if len(txt) > 30:
                out.append((f"{book}.{chap}.{vnum}", txt))
        return out

def detect(base):
    """Pick the record size whose entries decode plausibly."""
    base = pathlib.Path(base)
    t = "ot" if (base / "ot.bzv").exists() else "nt"
    if not (base / f"{t}.bzv").exists(): return 12
    best, bestok = 12, -1
    for r in (12, 10, 8):
        bzv = (base / f"{t}.bzv").read_bytes()
        if len(bzv) % r: continue
        fmt = {12: "<III", 10: "<HII", 8: "<HIH"}[r]
        n = len(bzv)//r; ok = 0
        for i in range(min(n, 4000)):
            bn, off, sz = struct.unpack(fmt, bzv[i*r:(i+1)*r])
            if bn < 60 and sz < 200000: ok += 1
        ratio = ok / max(1, min(n, 4000))
        if ratio > bestok: best, bestok = r, ratio
    return best

if __name__ == "__main__":
    import sys
    m = ZCom(sys.argv[1], detect(sys.argv[1]))
    for t in ("ot", "nt"):
        if (m.base / f"{t}.bzv").exists():
            r = m.read(t)
            print(f"{t}: {len(r)} entries")
            for ref, tx in r[:2]: print("  ", ref, "|", tx[:110])
