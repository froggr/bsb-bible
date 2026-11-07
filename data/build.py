#!/usr/bin/env python3
"""Build bible.db: the whole study Bible the app ships with.

    python3 build.py [outfile]

Reads from src/:
  tables.tsv            BSB translation tables (word-level Hebrew/Greek, Strong's, headings)
  cross_references.txt  OpenBible.info, CC-BY
  x_MHC/, x_JFB/        SWORD commentary modules, public domain

Design notes:
  - `verses` holds the assembled reading text, so drawing a chapter is one query.
  - `words` holds the study layer (original, transliteration, Strong's, parsing) and is only
    read when someone taps a word.
  - Commentary bodies are stored once in `comm_text` and pointed at by `comm`, because
    Matthew Henry comments on passages and would otherwise repeat for every verse in one.
  - `verses_fts` is an external-content FTS5 index: search without a second copy of the text.
"""
import csv, html, json, pathlib, re, sqlite3, sys, hashlib, struct, zlib

# Bump when the verse text itself changes, so phones replace the copy they unpacked.
#   4 — footnotes carry the display-word range they annotate (w_start, w_end), and keep
#       the translators' italics as *asterisks*.
#   3 — HTML entities decoded: the Psalm 119 acrostic headings carried their Hebrew
#       letter as "&#1488;" and showed it raw on the page.
#   2 — text taken from bsb.txt: dropped the vvv/-/'. . .' alignment placeholders, restored
#       the closing quotes, stopped inventing ellipses, whole psalm superscriptions.
TEXT_VERSION = 4

HERE = pathlib.Path(__file__).parent
SRC = HERE / "src"
csv.field_size_limit(10**9)

# Canonical order, with the OSIS abbreviations the commentaries and cross-references use.
BOOKS = [
    ("Genesis","Gen",50),("Exodus","Exod",40),("Leviticus","Lev",27),("Numbers","Num",36),
    ("Deuteronomy","Deut",34),("Joshua","Josh",24),("Judges","Judg",21),("Ruth","Ruth",4),
    ("1 Samuel","1Sam",31),("2 Samuel","2Sam",24),("1 Kings","1Kgs",22),("2 Kings","2Kgs",25),
    ("1 Chronicles","1Chr",29),("2 Chronicles","2Chr",36),("Ezra","Ezra",10),("Nehemiah","Neh",13),
    ("Esther","Esth",10),("Job","Job",42),("Psalm","Ps",150),("Proverbs","Prov",31),
    ("Ecclesiastes","Eccl",12),("Song of Solomon","Song",8),("Isaiah","Isa",66),("Jeremiah","Jer",52),
    ("Lamentations","Lam",5),("Ezekiel","Ezek",48),("Daniel","Dan",12),("Hosea","Hos",14),
    ("Joel","Joel",3),("Amos","Amos",9),("Obadiah","Obad",1),("Jonah","Jonah",4),
    ("Micah","Mic",7),("Nahum","Nah",3),("Habakkuk","Hab",3),("Zephaniah","Zeph",3),
    ("Haggai","Hag",2),("Zechariah","Zech",14),("Malachi","Mal",4),
    ("Matthew","Matt",28),("Mark","Mark",16),("Luke","Luke",24),("John","John",21),
    ("Acts","Acts",28),("Romans","Rom",16),("1 Corinthians","1Cor",16),("2 Corinthians","2Cor",13),
    ("Galatians","Gal",6),("Ephesians","Eph",6),("Philippians","Phil",4),("Colossians","Col",4),
    ("1 Thessalonians","1Thess",5),("2 Thessalonians","2Thess",3),("1 Timothy","1Tim",6),
    ("2 Timothy","2Tim",4),("Titus","Titus",3),("Philemon","Phlm",1),("Hebrews","Heb",13),
    ("James","Jas",5),("1 Peter","1Pet",5),("2 Peter","2Pet",3),("1 John","1John",5),
    ("2 John","2John",1),("3 John","3John",1),("Jude","Jude",1),("Revelation","Rev",22),
]
OSIS = {o: n for n, o, _ in BOOKS}          # Gen  -> Genesis
NUM = {n: i + 1 for i, (n, _, _) in enumerate(BOOKS)}
OT_BOOKS = {n for n, _, _ in BOOKS[:39]}

SCHEMA = """
PRAGMA journal_mode=OFF;
PRAGMA synchronous=OFF;

CREATE TABLE books (
  id INTEGER PRIMARY KEY, name TEXT NOT NULL, osis TEXT NOT NULL,
  chapters INTEGER NOT NULL, testament TEXT NOT NULL);

CREATE TABLE verses (
  id INTEGER PRIMARY KEY,          -- book*1000000 + chapter*1000 + verse
  book INTEGER NOT NULL, chapter INTEGER NOT NULL, verse INTEGER NOT NULL,
  text TEXT NOT NULL,              -- assembled reading text
  heading TEXT,                    -- section heading that opens here
  subheading TEXT,
  psalm_title TEXT,                -- superscription, kept out of verse 1's text
  xref_note TEXT,                  -- the BSB's own parenthetical section reference
  para INTEGER NOT NULL DEFAULT 0);

CREATE TABLE words (
  verse INTEGER NOT NULL, pos INTEGER NOT NULL,
  text TEXT NOT NULL,              -- the English, as the BSB groups it
  orig TEXT, translit TEXT, strongs TEXT, parsing TEXT, lang TEXT,
  punct TEXT,                      -- trailing punctuation
  open_q TEXT,                     -- opening quote mark
  untranslated INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (verse, pos)) WITHOUT ROWID;

CREATE TABLE footnotes (
  verse INTEGER NOT NULL, idx INTEGER NOT NULL, text TEXT NOT NULL,
  -- The words of the verse the note is about, as indices into text.split(' '), inclusive;
  -- -1 when the note could not be placed (a psalm superscription, say).
  w_start INTEGER NOT NULL DEFAULT -1, w_end INTEGER NOT NULL DEFAULT -1,
  PRIMARY KEY (verse, idx)) WITHOUT ROWID;

CREATE TABLE xrefs (
  src INTEGER NOT NULL, dst INTEGER NOT NULL,
  dst_end INTEGER,                 -- for a range, its last verse
  votes INTEGER NOT NULL,
  PRIMARY KEY (src, dst)) WITHOUT ROWID;

CREATE TABLE comm_text (id INTEGER PRIMARY KEY, body TEXT NOT NULL);
CREATE TABLE comm (
  verse INTEGER NOT NULL, source TEXT NOT NULL, text_id INTEGER NOT NULL,
  PRIMARY KEY (verse, source)) WITHOUT ROWID;

-- The user's own layer. Ranges are (start, end) verse ids with a word offset in each.
CREATE TABLE marks (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  v_start INTEGER NOT NULL, w_start INTEGER NOT NULL,
  v_end INTEGER NOT NULL, w_end INTEGER NOT NULL,
  color TEXT NOT NULL DEFAULT 'gold',
  note TEXT,
  excerpt TEXT,
  created INTEGER NOT NULL, updated INTEGER NOT NULL);
CREATE INDEX marks_start ON marks(v_start);

-- The 16 verses the BSB omits (absent from the earliest manuscripts). Kept so a
-- reference lands somewhere instead of nowhere; the note comes from the BSB itself.
CREATE TABLE omitted (
  id INTEGER PRIMARY KEY, book INTEGER NOT NULL, chapter INTEGER NOT NULL,
  verse INTEGER NOT NULL, note TEXT);

CREATE INDEX verses_ref ON verses(book, chapter, verse);
CREATE INDEX xrefs_votes ON xrefs(src, votes DESC);
-- Tapping a Strong's number asks for every other verse using the same root; without this
-- that is a full scan of 441k words.
CREATE INDEX words_strongs ON words(strongs, lang, verse);
"""


def txt(s):
    """Strip the table's HTML-ish markup. Its attributes use | where " belongs."""
    s = (s or "").replace("|", '"')
    s = re.sub(r"<[^>]*>", "", s)
    # The Psalm 119 acrostic headings carry their Hebrew letter as a numeric entity
    # (&#1488; ALEPH); undecoded they reach the page as literal punctuation.
    s = html.unescape(s)
    # Psalm 119:1 carries both the psalm's heading and the first acrostic letter in one
    # cell, with nothing between them ("…Lamp to My Feet\u05d0 ALEPH"). Give the letter
    # its own line rather than letting it run into the sentence before it.
    s = re.sub(r"(?<=[a-z])(?=[\u0590-\u05ff])", " \u2014 ", s)
    return re.sub(r"\s+", " ", s).strip()


def vid(book, ch, v):
    return NUM[book] * 1000000 + ch * 1000 + v


def parse_ref(ref):
    """'Psalm 23:4' -> verse id, or None."""
    m = re.match(r"^(.+) (\d+):(\d+)$", ref)
    if not m: return None
    book, ch, v = m.group(1), int(m.group(2)), int(m.group(3))
    return vid(book, ch, v) if book in NUM else None


# The tables align every original-language word to an English slot. Where a Hebrew or Greek
# word is carried by an adjacent English phrase rather than a word of its own, the slot holds
# a placeholder instead: '-', 'vvv', or '. . .'. They are alignment scaffolding and must never
# reach the page. (The BSB itself contains no ellipses, so '. . .' is never literal.)
PLACEHOLDERS = {"-", "vvv", ". . ."}


def is_placeholder(text):
    """True when this word slot carries no English of its own."""
    return text.strip().strip("()").strip() in PLACEHOLDERS


def read_text():
    """The BSB's own verse text, which is the authority for what a verse reads.

    The word tables are authoritative for the *word* layer but not for the sentence: their
    endQ column is incomplete (6,673 opening quotes against ~4,100 closing), and assembling
    words loses the spacing around dashes and sentence-ending quotes. So the reading text is
    taken verbatim from bsb.txt and the tables supply only the study data around it.
    """
    out = {}
    for line in (SRC / "bsb.txt").open(encoding="utf-8-sig"):
        if "\t" not in line: continue
        ref, body = line.rstrip("\n").split("\t", 1)
        i = parse_ref(ref.strip())
        if i: out[i] = body.strip()
    return out


# ---------------------------------------------------------------- BSB tables

def read_bsb():
    """Yield one dict per verse, with its words, from the translation tables."""
    f = open(SRC / "tables.tsv", encoding="utf-8-sig")
    r = csv.reader(f, delimiter="\t")
    head = next(r)
    I = {n.strip(): i for i, n in enumerate(head)}
    G = lambda row, k: (row[I[k]] if len(row) > I[k] else "") or ""

    cur = None; words = []; meta = {}
    for row in r:
        if len(row) <= I["BSB version"]: continue
        ref = G(row, "VerseId").strip()
        if ref and ref != cur:
            if cur and words: yield cur, meta, words
            cur, words = ref, []
            par = G(row, "Par")
            hd = txt(G(row, "Hdg"))
            meta = {
                "heading": hd if 'class="hdg"' in G(row, "Hdg").replace("|", '"') or
                                 "subhdg" not in G(row, "Hdg") else None,
                "subheading": hd if "subhdg" in G(row, "Hdg").replace("|", '"') else None,
                "xref": txt(G(row, "Crossref")),
                "psalm_title": "pshdg" in par,
                "para": 1 if ("reg" in par or "pshdg" in par) else 0,
                "footnotes": [],
            }
            if meta["subheading"]: meta["heading"] = None
        # A footnote sits on the word slot it annotates. Keep the slot, so the page can
        # mark the word; keep the translators' italics as *asterisks*, the way the Tyndale
        # notes carry theirs, so "Or *a canopy*" reads as an alternative rendering.
        fn = txt(G(row, "footnotes").replace("<i>", "*").replace("</i>", "*"))
        if fn and meta is not None and fn not in [t for t, _ in meta["footnotes"]]:
            w_here = txt(G(row, "BSB version"))
            meta["footnotes"].append((fn, len(words) if w_here else len(words) - 1))
        w = txt(G(row, "BSB version"))
        if w:
            words.append({
                "text": w,
                "orig": G(row, "WLC / Nestle Base TR RP WH NE NA SBL").strip(),
                "translit": G(row, "Translit").strip(),
                "strongs": G(row, "Str Heb").strip() or G(row, "Str Grk").strip(),
                "parsing": G(row, "Parsing").strip(),
                "lang": G(row, "Language").strip(),
                "par": G(row, "Par"),      # a new <p> here ends the psalm superscription
                "punct": txt(G(row, "pnc")) + txt(G(row, "endQ")),
                # begQ also carries the verse-number markup; drop a leading number.
                "open_q": re.sub(r"^\d+", "", txt(G(row, "begQ"))),
            })
    if cur and words: yield cur, meta, words


def assemble(words, skip=0):
    """Join word slots back into prose.

    Only used where bsb.txt cannot help: the psalm superscriptions, which the tables keep
    inside verse 1 and which have to be split back out word by word.
    """
    out = ""
    for w in words[skip:]:
        if is_placeholder(w["text"]):
            out += w["punct"]              # the slot is invisible; its punctuation is not
            continue
        out += w["open_q"] + w["text"] + w["punct"] + " "
    return re.sub(r"\s+([,.;:!?])", r"\1", re.sub(r"\s+", " ", out)).strip()


def strip_title(body, title):
    """Take the psalm superscription off the front of verse 1.

    The tables mark where the superscription ends; bsb.txt has no such marker and runs it
    straight into the verse. Matching on letters alone, because the two differ in brackets
    and spacing. If the prefix is not there, the verse is left alone rather than cut blind.
    """
    key = lambda t: re.sub(r"[^a-z0-9]", "", t.lower())
    want, seen = key(title), ""
    for n, c in enumerate(body, 1):
        if c.isalnum(): seen += c
        if key(seen) != want: continue
        # The last word matched. The punctuation right after it closes the superscription, so
        # it stays with the title; only the gap before the verse is dropped.
        rest = body[n:]
        keep = len(rest) - len(rest.lstrip(" .,;:\u201c\u2018\u2014-"))
        return rest[keep:]
    return body


def align(words, body, skip):
    """Where each word slot falls in the reading text, as a display-word range.

    The page splits the verse on spaces, so a footnote on the slot "an expanse" has to be
    expressed as "display words 6 to 7". Each slot's phrase is searched for in order, at a
    word boundary, from where the last one ended; a slot that cannot be found (the tables
    and bsb.txt differ in brackets and dashes here and there) is None, and the search goes
    on from the same place rather than guessing.
    """
    out = []
    cursor = 0
    for w in words[skip:]:
        phrase = w["text"].strip()
        if is_placeholder(phrase) or not phrase:
            out.append(None); continue
        # Brackets mark words supplied by the translators; bsb.txt sets them without.
        phrase = phrase.replace("[", "").replace("]", "")
        m = re.compile(r"(?<![\w\u2019'])" + re.escape(phrase) + r"(?![\w\u2019'])").search(body, cursor)
        if not m:
            out.append(None); continue
        out.append((word_index(body, m.start()), word_index(body, m.end() - 1)))
        cursor = m.end()
    return out


def word_index(body, pos):
    """Which display word (index into the page's split on spaces) character [pos] is in."""
    before = body[:pos]
    n = len(before.split())
    return n if (not before or before[-1].isspace()) else n - 1


def anchor(placed, slot, skip):
    """The display-word range a footnote on word slot [slot] annotates, or (-1, -1).

    A note on an alignment placeholder (". . .") is about the phrase before it, so it takes
    that slot's last word; one inside a psalm superscription has no place on the verse.
    """
    k = slot - skip
    if k < 0 or k >= len(placed): return -1, -1
    if placed[k] is not None: return placed[k]
    for j in range(k - 1, -1, -1):
        if placed[j] is not None:
            return placed[j][1], placed[j][1]
    return -1, -1


def load_bible(db):
    nv = nw = nf = 0
    global nplaced
    nplaced = 0
    canon = read_text()
    missing = 0
    for ref, meta, words in read_bsb():
        m = re.match(r"^(.+) (\d+):(\d+)$", ref)
        if not m or m.group(1) not in NUM: continue
        book, ch, v = m.group(1), int(m.group(2)), int(m.group(3))
        i = vid(book, ch, v)

        # A psalm superscription sits in verse 1 but is not part of it.
        title = None; skip = 0
        if meta["psalm_title"] and v == 1:
            # The superscription is its own paragraph: it ends where the next <p> opens.
            # (Psalm 4's is three sentences, so stopping at the first full stop cut it short.)
            take = []
            for n, w in enumerate(words):
                if n > 0 and "<p " in w["par"]: break
                take.append(w)
            if take and len(take) < len(words):
                title = assemble(take); skip = len(take)

        # The BSB's own text, with the superscription taken off the front of a psalm's verse 1.
        body = canon.get(i)
        if body is None:
            missing += 1
            body = assemble(words, skip)
        elif title:
            # bsb.txt runs the superscription into verse 1. Split it there rather than keeping
            # the assembled one: the tables drop the odd full stop (Psalm 9:1) and bsb.txt is
            # the authority for punctuation. The assembled title only says where to cut.
            rest = strip_title(body, title)
            if rest != body:
                # Keep bsb.txt's own punctuation at the cut: a few superscriptions run into
                # the verse with a colon ("He said:") or an open quote, not a full stop.
                title = body[: len(body) - len(rest)].rstrip()
            body = rest

        db.execute(
            "INSERT INTO verses(id,book,chapter,verse,text,heading,subheading,psalm_title,xref_note,para)"
            " VALUES(?,?,?,?,?,?,?,?,?,?)",
            (i, NUM[book], ch, v, body, meta["heading"] or None,
             meta["subheading"] or None, title, meta["xref"] or None, meta["para"]))
        nv += 1
        db.executemany(
            "INSERT INTO words(verse,pos,text,orig,translit,strongs,parsing,lang,punct,open_q,untranslated)"
            " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            [(i, p, w["text"], w["orig"] or None, w["translit"] or None, w["strongs"] or None,
              w["parsing"] or None, w["lang"] or None, w["punct"] or None, w["open_q"] or None,
              1 if is_placeholder(w["text"]) else 0) for p, w in enumerate(words)])
        nw += len(words)
        placed = align(words, body, skip)
        rows = []
        for k, (t, slot) in enumerate(meta["footnotes"]):
            ws, we = anchor(placed, slot, skip)
            rows.append((i, k, t, ws, we))
            if ws >= 0: nplaced += 1
        db.executemany("INSERT INTO footnotes(verse,idx,text,w_start,w_end) VALUES(?,?,?,?,?)", rows)
        nf += len(meta["footnotes"])
    if missing:
        print(f"  note: {missing} verses not in bsb.txt; text assembled from the tables")
    print(f"  {nplaced:,} of {nf:,} footnotes placed on their words")
    return nv, nw, nf


# ---------------------------------------------------------------- cross references

def load_xrefs(db):
    def ref(tok):
        m = re.match(r"^([A-Za-z0-9]+)\.(\d+)\.(\d+)(?:-([A-Za-z0-9]+)\.(\d+)\.(\d+))?$", tok)
        if not m: return None, None
        b, c, v, _, c2, v2 = m.groups()
        name = OSIS.get(b)
        if not name: return None, None
        start = vid(name, int(c), int(v))
        end = vid(name, int(c2), int(v2)) if v2 else None
        return start, end

    rows = []
    for line in open(SRC / "cross_references.txt", encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) < 3 or p[0] == "From Verse": continue
        s, _ = ref(p[0].strip())
        d, de = ref(p[1].strip())
        if not s or not d: continue
        try: votes = int(p[2])
        except ValueError: continue
        rows.append((s, d, de, votes))
    db.executemany("INSERT OR IGNORE INTO xrefs(src,dst,dst_end,votes) VALUES(?,?,?,?)", rows)
    return len(rows)


# ---------------------------------------------------------------- commentary

def clean_jfb(s):
    s = re.sub(r"\s+", " ", s).strip()
    s = s.replace("&amp;", "&").replace("&quot;", '"').replace("&lt;", "<").replace("&gt;", ">")
    s = re.sub(r"^\d+\.\s*", "", s)
    s = re.sub(r"\s*--\s*", " — ", s)
    return s.strip()


def clean_mhc(s):
    """Henry opens each entry with a title then re-quotes the KJV; the reader has the text."""
    s = re.sub(r"\s+", " ", s).strip()
    s = s.replace("&amp;", "&").replace("&quot;", '"').replace("&lt;", "<").replace("&gt;", ">")
    s = re.sub(r"^[^.]{0,60}\.\s*\(\s*b\.\s*c\.[^)]*\)\s*", "", s)
    marks = list(re.finditer(r"(?<![\w])(\d{1,3})\s+(?=[A-Z])", s))
    if marks:
        tail = s[marks[-1].end():]
        cut = re.search(r"[.?!]\s+(?=[A-Z])", tail)
        s = tail[cut.end():] if (cut and len(tail) > 200) else tail
    return s.strip()


# Tyndale Open Study Notes: modern-English study notes, CC BY-SA 4.0.
# Henry (1710) and JFB (1871) explain the text in the prose of their own century;
# these explain it in ours. Kept as a third source, not a replacement: Tyndale is
# silent on some verses the older two cover (Rom 8:28, for one).
TYN_BOOK = {"Pr": "Prov", "Hagg": "Hag", "1Thes": "1Thess", "2Thes": "2Thess",
            "Jon": "Jonah", "1Jn": "1John", "2Jn": "2John", "3Jn": "3John"}


def clean_tyn(raw):
    """Plain text, keeping Tyndale's quoted-scripture spans as *emphasis*.

    The leading ref span ("1:1") goes — the reader is looking at the verse already.
    Markers are stashed as control chars so the whitespace passes below cannot
    strand a '*' away from the word it belongs to.
    """
    s = re.sub(r'<span class="sn-ref(?:-sc)?">.*?</span>', " ", raw, flags=re.S)

    def excerpt(m):
        inner = re.sub(r"<[^>]+>", "", m.group(1))
        inner = re.sub(r"\s+", " ", html.unescape(inner)).strip()
        return " \x01" + inner + "\x02 " if inner else " "

    s = re.sub(r'<span class="sn-excerpt[^"]*">(.*?)</span>', excerpt, s, flags=re.S)
    s = re.sub(r"<[^>]+>", " ", s)
    s = html.unescape(s)
    s = re.sub(r"\s+", " ", s)
    s = re.sub(r"\s+([,;:.!?])", r"\1", s)
    s = re.sub(r"\(\s+", "(", s)
    s = re.sub(r"\s+\)", ")", s)
    s = re.sub(r"\u201c\s+", "\u201c", s)
    s = re.sub(r"\s+\u201d", "\u201d", s)
    s = s.replace("\x01", "*").replace("\x02", "*")
    return re.sub(r"\s{2,}", " ", s).strip()


def _chapter_lengths(db):
    """(book number, chapter) -> last verse, for expanding a note's range."""
    return {(b, c): mx for b, c, mx in
            db.execute("SELECT book, chapter, MAX(verse) FROM verses GROUP BY book, chapter")}


def _span(book, c1, v1, c2, v2, lengths):
    """Every verse id from c1:v1 through c2:v2, walking chapter ends."""
    out, c, v = [], c1, v1
    for _ in range(400):
        out.append(vid(book, c, v))
        if c == c2 and v >= v2:
            break
        mx = lengths.get((NUM[book], c))
        if mx is None:
            break
        if v >= mx:
            if c >= c2:
                break
            c, v = c + 1, 1
        else:
            v += 1
    return out


def load_tyndale(db):
    """A note on a range attaches to every verse in it; narrowest note wins."""
    path = SRC / "x_TYN/StudyNotes.xml"
    if not path.exists():
        return 0, 0
    lengths = _chapter_lengths(db)
    xml = path.read_text(encoding="utf-8")
    bodies, claims = {}, []
    for m in re.finditer(r"<item\b[^>]*>(.*?)</item>", xml, re.S):
        blob = m.group(1)
        rm = re.search(r"<refs>([^<]+)</refs>", blob)
        if not rm:
            continue
        ref = re.fullmatch(r"([\w]+)\.(\d+)\.(\d+)(?:-(?:(\d+)\.)?(\d+))?",
                           rm.group(1).split(";")[0].strip())
        if not ref:
            continue
        book = OSIS.get(TYN_BOOK.get(ref.group(1), ref.group(1)))
        if not book:
            continue
        bm = re.search(r"<body>(.*?)</body>", blob, re.S)
        body = clean_tyn(bm.group(1) if bm else blob)
        if len(body) < 40:
            continue
        key = hashlib.md5(body.encode()).hexdigest()
        tid = bodies.setdefault(key, (len(bodies) + 1, body))[0]
        c1, v1 = int(ref.group(2)), int(ref.group(3))
        if ref.group(5) is None:
            verses = [vid(book, c1, v1)]
        else:
            verses = _span(book, c1, v1, int(ref.group(4) or c1), int(ref.group(5)), lengths)
        for q in verses:
            claims.append((q, tid, len(verses)))
    # a verse inside both a tight note and a sweeping one gets the tight one
    claims.sort(key=lambda t: (t[0], t[2]))
    links, seen = [], set()
    for q, tid, _ in claims:
        if q not in seen:
            seen.add(q)
            links.append((q, "tyn", tid))
    base = db.execute("SELECT COALESCE(MAX(id), 0) FROM comm_text").fetchone()[0]
    db.executemany("INSERT INTO comm_text(id,body) VALUES(?,?)",
                   [(i + base, b) for i, b in bodies.values()])
    db.executemany("INSERT OR IGNORE INTO comm(verse,source,text_id) VALUES(?,?,?)",
                   [(q, s, t + base) for q, s, t in links])
    return len(bodies), len(links)


def load_commentary(db):
    sys.path.insert(0, str(HERE))
    import sword
    sources = [
        ("jfb", SRC / "x_JFB/modules/comments/zcom/jfb", clean_jfb),
        ("mhc", SRC / "x_MHC/modules/comments/zcom4/mhc", clean_mhc),
    ]
    bodies = {}; links = []
    for name, path, fn in sources:
        if not path.exists(): continue
        mod = sword.ZCom(str(path), sword.detect(str(path)))
        for t in ("ot", "nt"):
            if not (path / f"{t}.bzv").exists(): continue
            for osis_ref, raw in mod.read(t):
                m = re.match(r"^([A-Za-z0-9]+)\.(\d+)\.(\d+)$", osis_ref)
                if not m: continue
                book = OSIS.get(m.group(1))
                if not book: continue
                body = fn(raw)
                if len(body) < 40: continue
                key = hashlib.md5(body.encode()).hexdigest()
                tid = bodies.setdefault(key, (len(bodies) + 1, body))[0]
                links.append((vid(book, int(m.group(2)), int(m.group(3))), name, tid))
    db.executemany("INSERT INTO comm_text(id,body) VALUES(?,?)",
                   [(i, b) for i, b in bodies.values()])
    db.executemany("INSERT OR IGNORE INTO comm(verse,source,text_id) VALUES(?,?,?)", links)
    return len(bodies), len(links)


def load_omitted(db):
    """Verses in the KJV numbering that the BSB leaves out. The BSB explains each in a
    footnote on the verse before, so carry that across."""
    have = {r[0] for r in db.execute("SELECT id FROM verses")}
    rows = []
    for line in open(SRC / "bsb.txt", encoding="utf-8-sig"):
        ref, _, body = line.partition("\t")
        m = re.match(r"^(.+) (\d+):(\d+)$", ref.strip())
        if not m or m.group(1) not in NUM: continue
        if body.strip(): continue
        book, ch, v = m.group(1), int(m.group(2)), int(m.group(3))
        i = vid(book, ch, v)
        if i in have: continue
        prev = db.execute(
            "SELECT text FROM footnotes WHERE verse=? ORDER BY idx", (vid(book, ch, v - 1),)
        ).fetchall()
        note = next((t.replace("*", "") for (t,) in prev if re.search(r"\binclude\b", t)), None)
        rows.append((i, NUM[book], ch, v, note))
    db.executemany("INSERT INTO omitted(id,book,chapter,verse,note) VALUES(?,?,?,?,?)", rows)
    return len(rows)


# ---------------------------------------------------------------- main

def main(out):
    p = pathlib.Path(out)
    if p.exists(): p.unlink()
    db = sqlite3.connect(p)
    db.executescript(SCHEMA)
    db.executemany("INSERT INTO books(id,name,osis,chapters,testament) VALUES(?,?,?,?,?)",
                   [(i + 1, n, o, c, "OT" if n in OT_BOOKS else "NT")
                    for i, (n, o, c) in enumerate(BOOKS)])

    print("bible…", flush=True)
    nv, nw, nf = load_bible(db); db.commit()
    print(f"  {nv:,} verses  {nw:,} words  {nf:,} footnotes")

    print("cross-references…", flush=True)
    nx = load_xrefs(db); db.commit()
    print(f"  {nx:,}")

    print("commentary…", flush=True)
    nb, nl = load_commentary(db); db.commit()
    print(f"  {nb:,} passages  {nl:,} verse links")
    tb, tl = load_tyndale(db); db.commit()
    print(f"  {tb:,} Tyndale notes  {tl:,} verse links")

    print("omitted verses…", flush=True)
    n = load_omitted(db); db.commit()
    print(f"  {n}")

    print("search index…", flush=True)
    # FTS4, not FTS5: AOSP compiles FTS5 out of the SQLite it ships, so fts5 fails on device
    # with "no such module". FTS4 with content='' keeps the index without a second copy of
    # the text; snippets are built from `verses` by the caller.
    db.executescript("""
      CREATE VIRTUAL TABLE verses_fts USING fts4(content="", text, tokenize=unicode61);
    """)
    db.executemany("INSERT INTO verses_fts(docid, text) VALUES(?,?)",
                   db.execute("SELECT id, text FROM verses").fetchall())
    db.commit()

    # Stamp the build. The app unpacks the shipped database only once, so without a version
    # it can never tell that the copy on the phone predates a text fix.
    db.execute(f"PRAGMA user_version = {TEXT_VERSION}")
    db.executescript("PRAGMA journal_mode=DELETE;")
    db.execute("VACUUM"); db.commit(); db.close()
    print(f"\n{p}  {p.stat().st_size/1e6:.1f} MB  (text version {TEXT_VERSION})")
    split(p)


def split(full):
    """Three files, so the app installs small and opens on the text.

    reading   ships in the APK: every verse, headings, footnotes, search
    study     words with Hebrew/Greek and Strong's, plus cross-references
    commentary Matthew Henry and JFB

    The two downloads are ATTACHed when present; nothing in the reader needs them."""
    import shutil
    parts = {
        "bible-reading.db": ["words", "xrefs", "comm", "comm_text"],
        "bible-study.db": None,       # built below, not by subtraction
        "bible-commentary.db": None,
    }
    for name in parts:
        f = full.with_name(name)
        if f.exists(): f.unlink()

    # reading: the whole database minus the heavy study tables
    read = full.with_name("bible-reading.db")
    shutil.copy(full, read)
    d = sqlite3.connect(read)
    d.executescript("DROP TABLE words; DROP TABLE xrefs; DROP TABLE comm; DROP TABLE comm_text;"
                    " PRAGMA journal_mode=DELETE;")
    d.execute("VACUUM"); d.commit(); d.close()

    def extract(name, script, tables):
        out = full.with_name(name)
        d = sqlite3.connect(out)
        d.executescript("PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;" + script)
        d.execute("ATTACH ? AS full", (str(full),))
        for t in tables:
            d.execute(f"INSERT INTO {t} SELECT * FROM full.{t}")
        d.commit(); d.execute("DETACH full")
        d.executescript("PRAGMA journal_mode=DELETE;")
        d.execute("VACUUM"); d.commit(); d.close()
        return out

    extract("bible-study.db", """
      CREATE TABLE words (
        verse INTEGER NOT NULL, pos INTEGER NOT NULL, text TEXT NOT NULL,
        orig TEXT, translit TEXT, strongs TEXT, parsing TEXT, lang TEXT,
        punct TEXT, open_q TEXT, untranslated INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY (verse, pos)) WITHOUT ROWID;
      CREATE TABLE xrefs (
        src INTEGER NOT NULL, dst INTEGER NOT NULL, dst_end INTEGER, votes INTEGER NOT NULL,
        PRIMARY KEY (src, dst)) WITHOUT ROWID;
      CREATE INDEX words_strongs ON words(strongs, lang, verse);
      CREATE INDEX xrefs_votes ON xrefs(src, votes DESC);
    """, ["words", "xrefs"])

    extract("bible-commentary.db", """
      CREATE TABLE comm_text (id INTEGER PRIMARY KEY, body TEXT NOT NULL);
      CREATE TABLE comm (verse INTEGER NOT NULL, source TEXT NOT NULL, text_id INTEGER NOT NULL,
        PRIMARY KEY (verse, source)) WITHOUT ROWID;
    """, ["comm_text", "comm"])

    import gzip
    for name in ("bible-reading.db", "bible-study.db", "bible-commentary.db"):
        f = full.with_name(name)
        gz = f.with_suffix(".db.gz")
        with open(f, "rb") as a, gzip.open(gz, "wb", 9) as b:
            shutil.copyfileobj(a, b)
        print(f"{f.name:24} {f.stat().st_size/1e6:6.1f} MB   {gz.stat().st_size/1e6:5.1f} MB gz")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else HERE / "bible.db")
