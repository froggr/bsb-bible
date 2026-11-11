#!/usr/bin/env python3
"""
Build questions.db: one question, and the verse that answers it, for the reader to be asked.

The questions are people's, not a model's: unfoldingWord Translation Questions, written by
the translators named in that project's manifest to check that a translation says what the
text says. They are CC BY-SA 4.0, the same licence as the Tyndale notes, and the database
built here is a derivative work under the same licence.

Only the QUESTION and the verse it points at are taken. Their written answer is left behind:
it quotes a different translation ("tranquil water" where the page says "still waters"), it
repeats the question's own words, and it has slips in it ("They have eternal light" for John
3:36). The verse is the answer, in the words on the page, so the app offers verses to choose
between and the voice reads the right one.

Changes made to the original, which the licence asks to be stated:

  * the Response column, and every question whose reference is not one or two verses of a
    single chapter, or names a verse the BSB does not have, is left out;
  * "Yahweh" in a question is written "the LORD", as the BSB writes the name;
  * "wo not", left where a contraction was expanded by machine, is written "will not";
  * runs of spaces are closed up.

    python3 -I data/questions.py

reads data/src/x_TQ/ (see _README.txt there for where it came from) and data/bible-reading.db
and writes data/questions.db. Like the reading database, the app's copy is a HAND copy:

    cp data/questions.db app/app/src/main/assets/questions.db

and VERSION here must match Questions.VERSION in the app, or the phone keeps its old copy.
"""
import csv
import os
import re
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
VERSION = 1
RELEASE = "v91"

USFM = ("GEN EXO LEV NUM DEU JOS JDG RUT 1SA 2SA 1KI 2KI 1CH 2CH EZR NEH EST JOB PSA PRO ECC "
        "SNG ISA JER LAM EZK DAN HOS JOL AMO OBA JON MIC NAM HAB ZEP HAG ZEC MAL MAT MRK LUK "
        "JHN ACT ROM 1CO 2CO GAL EPH PHP COL 1TH 2TH 1TI 2TI TIT PHM HEB JAS 1PE 2PE 1JN 2JN "
        "3JN JUD REV").split()

REF = re.compile(r"^(\d+):(\d+)(?:-(\d+))?$")

#: The longest passage a question may point at and still be one of the choices on a phone.
SPAN = 2


def clean(text):
    """The question as it is shown: see the list of changes above."""
    t = re.sub(r"\s+", " ", text).strip()
    t = re.sub(r"\bwo not\b", "will not", t)
    t = re.sub(r"\bWo not\b", "Will not", t)
    # "Yahweh" opens a sentence only at the very start of a question or after a full stop.
    t = re.sub(r"(^|[.?!] )Yahweh\b", r"\1The LORD", t)
    t = re.sub(r"\bYahweh\b", "the LORD", t)
    return t


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "src", "x_TQ")
    reading = os.path.join(HERE, "bible-reading.db")
    out = os.path.join(HERE, "questions.db")

    have = {r[0] for r in sqlite3.connect(reading).execute("SELECT id FROM verses")}

    rows, seen = [], set()
    dropped = {"reference": 0, "span": 0, "verse": 0, "empty": 0, "twice": 0}
    for n, code in enumerate(USFM, start=1):
        path = os.path.join(src, "tq_%s.tsv" % code)
        with open(path, encoding="utf-8", newline="") as f:
            table = list(csv.reader(f, delimiter="\t", quoting=csv.QUOTE_NONE))
        head = table[0]
        at = {name: head.index(name) for name in ("Reference", "ID", "Question")}
        for r in table[1:]:
            if len(r) <= at["Question"]:
                dropped["empty"] += 1
                continue
            m = REF.match(r[at["Reference"]].strip())
            if not m:
                dropped["reference"] += 1
                continue
            chapter, first = int(m.group(1)), int(m.group(2))
            last = int(m.group(3)) if m.group(3) else first
            if not (first <= last < first + SPAN):
                dropped["span"] += 1
                continue
            start = n * 1_000_000 + chapter * 1_000 + first
            end = n * 1_000_000 + chapter * 1_000 + last
            if any(v not in have for v in range(start, end + 1)):
                dropped["verse"] += 1
                continue
            text = clean(r[at["Question"]])
            if len(text) < 8 or not text.endswith("?"):
                dropped["empty"] += 1
                continue
            # The same question asked twice of the same verses is one question.
            if (start, end, text) in seen:
                dropped["twice"] += 1
                continue
            seen.add((start, end, text))
            rows.append(("%s/%s" % (code, r[at["ID"]].strip()), start, end, text))

    keys = [r[0] for r in rows]
    if len(keys) != len(set(keys)):
        sys.exit("question ids are not unique within a book; the key needs more than the id")

    if os.path.exists(out):
        os.remove(out)
    db = sqlite3.connect(out)
    db.executescript("""
        CREATE TABLE questions(
            key TEXT PRIMARY KEY,       -- book code and unfoldingWord's own id, "JHN/abcd"
            v_start INTEGER NOT NULL,   -- the verse that answers it, book*1e6 + chapter*1e3 + verse
            v_end INTEGER NOT NULL,     -- the same, or the verse after
            text TEXT NOT NULL
        ) WITHOUT ROWID;
        CREATE INDEX questions_at ON questions(v_start);
        CREATE TABLE about(name TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID;
    """)
    db.executemany("INSERT INTO questions VALUES (?,?,?,?)", rows)
    db.executemany("INSERT INTO about VALUES (?,?)", [
        ("source", "unfoldingWord Translation Questions, release " + RELEASE),
        ("licence", "CC BY-SA 4.0, https://creativecommons.org/licenses/by-sa/4.0/"),
        ("attribution", "The original work by unfoldingWord is available from unfoldingword.org/utq"),
        ("changes", "Answers left out; questions on more than two verses left out; "
                    "Yahweh written as the LORD; wo not written as will not."),
    ])
    db.execute("PRAGMA user_version = %d" % VERSION)
    db.commit()
    db.execute("VACUUM")
    db.close()

    chapters = {r[1] // 1000 for r in rows}
    print("%d questions over %d chapters, %d bytes" % (len(rows), len(chapters), os.path.getsize(out)))
    print("left out:", ", ".join("%s %d" % kv for kv in dropped.items()))


if __name__ == "__main__":
    main()
