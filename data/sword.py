"""Read a SWORD zCom / zCom4 commentary module into [(osisRef, text)].

The index is positional over the module's versification: each testament file holds,
per verse slot, one record (block, offset, size) — 12 bytes `<III` for zCom4, 10 bytes
`<IIH` for the older zCom. OSIS modules (Henry, JFB) carry chapter and book boundaries in
the stream as osisID milestones, so `read()` walks those to label every slot. ThML
modules (Wesley) carry no milestones at all, so `read_positional()` labels the slots from
the KJV versification instead: slot 0 is the module heading, 1 the testament heading,
then for each book a heading slot, and for each chapter a heading slot (verse 0) followed
by its verses.
"""
import struct, zlib, re, pathlib

# The KJV versification SWORD lays every English module out against: OSIS book ids, the
# chapter count of each, and the verse count of every chapter in order. From CrossWire's
# canon.h. 66 books, 1,189 chapters, 31,102 verses.
KJV_BOOKS = ['Gen', 'Exod', 'Lev', 'Num', 'Deut', 'Josh', 'Judg', 'Ruth', '1Sam', '2Sam', '1Kgs',
    '2Kgs', '1Chr', '2Chr', 'Ezra', 'Neh', 'Esth', 'Job', 'Ps', 'Prov', 'Eccl', 'Song', 'Isa',
    'Jer', 'Lam', 'Ezek', 'Dan', 'Hos', 'Joel', 'Amos', 'Obad', 'Jonah', 'Mic', 'Nah', 'Hab',
    'Zeph', 'Hag', 'Zech', 'Mal', 'Matt', 'Mark', 'Luke', 'John', 'Acts', 'Rom', '1Cor', '2Cor',
    'Gal', 'Eph', 'Phil', 'Col', '1Thess', '2Thess', '1Tim', '2Tim', 'Titus', 'Phlm', 'Heb',
    'Jas', '1Pet', '2Pet', '1John', '2John', '3John', 'Jude', 'Rev']
KJV_CHAPTERS = [50, 40, 27, 36, 34, 24, 21, 4, 31, 24, 22, 25, 29, 36, 10, 13, 10, 42, 150, 31,
    12, 8, 66, 52, 5, 48, 12, 14, 3, 9, 1, 4, 7, 3, 3, 3, 2, 14, 4, 28, 16, 24, 21, 28, 16, 16,
    13, 6, 6, 4, 4, 5, 3, 6, 4, 3, 1, 13, 5, 5, 3, 5, 1, 1, 1, 22]
KJV_VERSES = [int(x) for x in """31,25,24,26,32,22,24,22,29,32,32,20,18,24,21,16,27,33,38,18,34,24,20,67,34,35,46,22,35,43,55,32,20,31,29,43,36,30,23,23,57,38,34,34,28,34,31,22,33,26,22,25,22,31,23,30,25,32,35,29,10,51,22,31,27,36,16,27,25,26,36,31,33,18,40,37,21,43,46,38,18,35,23,35,35,38,29,31,43,38,17,16,17,35,19,30,38,36,24,20,47,8,59,57,33,34,16,30,37,27,24,33,44,23,55,46,34,54,34,51,49,31,27,89,26,23,36,35,16,33,45,41,50,13,32,22,29,35,41,30,25,18,65,23,31,40,16,54,42,56,29,34,13,46,37,29,49,33,25,26,20,29,22,32,32,18,29,23,22,20,22,21,20,23,30,25,22,19,19,26,68,29,20,30,52,29,12,18,24,17,24,15,27,26,35,27,43,23,24,33,15,63,10,18,28,51,9,45,34,16,33,36,23,31,24,31,40,25,35,57,18,40,15,25,20,20,31,13,31,30,48,25,22,23,18,22,28,36,21,22,12,21,17,22,27,27,15,25,23,52,35,23,58,30,24,42,15,23,29,22,44,25,12,25,11,31,13,27,32,39,12,25,23,29,18,13,19,27,31,39,33,37,23,29,33,43,26,22,51,39,25,53,46,28,34,18,38,51,66,28,29,43,33,34,31,34,34,24,46,21,43,29,53,18,25,27,44,27,33,20,29,37,36,21,21,25,29,38,20,41,37,37,21,26,20,37,20,30,54,55,24,43,26,81,40,40,44,14,47,40,14,17,29,43,27,17,19,8,30,19,32,31,31,32,34,21,30,17,18,17,22,14,42,22,18,31,19,23,16,22,15,19,14,19,34,11,37,20,12,21,27,28,23,9,27,36,27,21,33,25,33,27,23,11,70,13,24,17,22,28,36,15,44,11,20,32,23,19,19,73,18,38,39,36,47,31,22,23,15,17,14,14,10,17,32,3,22,13,26,21,27,30,21,22,35,22,20,25,28,22,35,22,16,21,29,29,34,30,17,25,6,14,23,28,25,31,40,22,33,37,16,33,24,41,30,24,34,17,6,12,8,8,12,10,17,9,20,18,7,8,6,7,5,11,15,50,14,9,13,31,6,10,22,12,14,9,11,12,24,11,22,22,28,12,40,22,13,17,13,11,5,26,17,11,9,14,20,23,19,9,6,7,23,13,11,11,17,12,8,12,11,10,13,20,7,35,36,5,24,20,28,23,10,12,20,72,13,19,16,8,18,12,13,17,7,18,52,17,16,15,5,23,11,13,12,9,9,5,8,28,22,35,45,48,43,13,31,7,10,10,9,8,18,19,2,29,176,7,8,9,4,8,5,6,5,6,8,8,3,18,3,3,21,26,9,8,24,13,10,7,12,15,21,10,20,14,9,6,33,22,35,27,23,35,27,36,18,32,31,28,25,35,33,33,28,24,29,30,31,29,35,34,28,28,27,28,27,33,31,18,26,22,16,20,12,29,17,18,20,10,14,17,17,11,16,16,13,13,14,31,22,26,6,30,13,25,22,21,34,16,6,22,32,9,14,14,7,25,6,17,25,18,23,12,21,13,29,24,33,9,20,24,17,10,22,38,22,8,31,29,25,28,28,25,13,15,22,26,11,23,15,12,17,13,12,21,14,21,22,11,12,19,12,25,24,19,37,25,31,31,30,34,22,26,25,23,17,27,22,21,21,27,23,15,18,14,30,40,10,38,24,22,17,32,24,40,44,26,22,19,32,21,28,18,16,18,22,13,30,5,28,7,47,39,46,64,34,22,22,66,22,22,28,10,27,17,17,14,27,18,11,22,25,28,23,23,8,63,24,32,14,49,32,31,49,27,17,21,36,26,21,26,18,32,33,31,15,38,28,23,29,49,26,20,27,31,25,24,23,35,21,49,30,37,31,28,28,27,27,21,45,13,11,23,5,19,15,11,16,14,17,15,12,14,16,9,20,32,21,15,16,15,13,27,14,17,14,15,21,17,10,10,11,16,13,12,13,15,16,20,15,13,19,17,20,19,18,15,20,15,23,21,13,10,14,11,15,14,23,17,12,17,14,9,21,14,17,18,6,25,23,17,25,48,34,29,34,38,42,30,50,58,36,39,28,27,35,30,34,46,46,39,51,46,75,66,20,45,28,35,41,43,56,37,38,50,52,33,44,37,72,47,20,80,52,38,44,39,49,50,56,62,42,54,59,35,35,32,31,37,43,48,47,38,71,56,53,51,25,36,54,47,71,53,59,41,42,57,50,38,31,27,33,26,40,42,31,25,26,47,26,37,42,15,60,40,43,48,30,25,52,28,41,40,34,28,41,38,40,30,35,27,27,32,44,31,32,29,31,25,21,23,25,39,33,21,36,21,14,23,33,27,31,16,23,21,13,20,40,13,27,33,34,31,13,40,58,24,24,17,18,18,21,18,16,24,15,18,33,21,14,24,21,29,31,26,18,23,22,21,32,33,24,30,30,21,23,29,23,25,18,10,20,13,18,28,12,17,18,20,15,16,16,25,21,18,26,17,22,16,15,15,25,14,18,19,16,14,20,28,13,28,39,40,29,25,27,26,18,17,20,25,25,22,19,14,21,22,18,10,29,24,21,21,13,14,25,20,29,22,11,14,17,17,13,21,11,19,17,18,20,8,21,18,24,21,15,27,21""".split(",")]


def kjv_slots(testament):
    """{slot: (osisBook, chapter, verse)} for "ot" or "nt"; verse 0 is a heading slot."""
    books = range(0, 39) if testament == "ot" else range(39, 66)
    first_chapter = sum(KJV_CHAPTERS[:books[0]])
    table, i, ch_i = {}, 2, first_chapter
    for b in books:
        osis = KJV_BOOKS[b]
        table[i] = (osis, 0, 0); i += 1
        for ch in range(1, KJV_CHAPTERS[b] + 1):
            table[i] = (osis, ch, 0); i += 1
            for v in range(1, KJV_VERSES[ch_i] + 1):
                table[i] = (osis, ch, v); i += 1
            ch_i += 1
    return table

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

    def records(self, t):
        """(slot, block, offset, size) for every slot of a testament."""
        bzv = (self.base / f"{t}.bzv").read_bytes()
        r = self.rec; fmt = {12: "<III", 10: "<IIH", 8: "<HIH"}[r]
        for i in range(len(bzv)//r):
            yield (i,) + struct.unpack(fmt, bzv[i*r:(i+1)*r])

    def entries(self, t):
        for i, bn, off, sz in self.records(t):
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

    def read_positional(self, t):
        """Yield (osisRef, raw_markup) for a module with no milestones, by KJV slot.

        Heading slots (verse 0) are dropped. So is any slot whose record points at bytes an
        earlier slot already owns. In SWORD that link means "same entry", and a run of them
        can be a note on a range (Wesley on Gen 1:9: "the third day's work is related in
        these verses", linked through 1:13) — but in this module it is mostly filler: the
        first verse of many chapters points back at the previous chapter's last note, Gen
        2:19 at the note on "it is not good that man should be alone", and 1 Thess 3:2–12
        all at the note on 3:1. Kept, those would draw a one-verse note as a bracketed span
        ending a dozen verses late. Dropped, a genuine range loses its bracket and the note
        sits under the first verse, which is where Wesley put it.
        """
        table = kjv_slots(t)
        seen, out = set(), []
        for i, bn, off, sz in self.records(t):
            if sz == 0 or bn > 60 or i not in table: continue
            book, chap, verse = table[i]
            if verse == 0: continue
            if (bn, off) in seen: continue
            seen.add((bn, off))
            try: raw = self._blk(t, bn)[off:off+sz].decode("utf-8", "replace")
            except Exception: continue
            if raw.strip():
                out.append((f"{book}.{chap}.{verse}", raw))
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
        fmt = {12: "<III", 10: "<IIH", 8: "<HIH"}[r]
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
