package ca.lampandlight

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File


/** A verse id is book*1_000_000 + chapter*1_000 + verse, so it sorts in reading order. */
typealias VerseId = Int

fun verseId(book: Int, chapter: Int, verse: Int): VerseId = book * 1_000_000 + chapter * 1_000 + verse
val VerseId.book get() = this / 1_000_000
val VerseId.chapter get() = this / 1_000 % 1_000
val VerseId.verse get() = this % 1_000

data class Book(val id: Int, val name: String, val osis: String, val chapters: Int, val ot: Boolean)

data class Verse(
    val id: VerseId,
    val text: String,
    val heading: String?,
    val subheading: String?,
    val psalmTitle: String?,
    val xrefNote: String?,
    val footnotes: List<Footnote> = emptyList(),
)

/**
 * A translator's note, and the words of the verse it is about.
 *
 * [wordStart]..[wordEnd] index the verse as the page splits it (on spaces), inclusive; -1
 * when the note could not be placed, which is the psalm superscriptions. The text keeps
 * the translators' italics as `*asterisks*`, like the Tyndale notes.
 */
data class Footnote(val text: String, val wordStart: Int, val wordEnd: Int) {
    val anchored: Boolean get() = wordStart >= 0 && wordEnd >= wordStart
    val words: IntRange? get() = if (anchored) wordStart..wordEnd else null
}

/** One BSB word group, with everything behind it. */
data class Word(
    val pos: Int,
    val text: String,
    val original: String?,
    val translit: String?,
    val strongs: String?,
    val parsing: String?,
    val lang: String?,
    val punct: String?,
    val openQuote: String?,
    val untranslated: Boolean,
) {
    /** "H7462" / "G3056", the form a concordance uses. */
    val strongsKey: String?
        get() = strongs?.let { (if (lang == "Hebrew") "H" else "G") + it }
}

data class CrossRef(val dst: VerseId, val dstEnd: VerseId?, val votes: Int)
data class Commentary(val source: String, val body: String)

/**
 * One commentator's paragraph and the run of verses it covers on a page.
 *
 * Matthew Henry writes on passages and Tyndale on ranges, and the table links the same
 * paragraph to every verse in the range. On the page it is one thing about verses
 * [start] to [end], so it is drawn once with a bracket down the margin, not once per verse.
 */
data class CommentSpan(
    val source: String, val body: String, val start: VerseId, val end: VerseId,
    /**
     * For Wesley: where each `*lemma*` of the paragraph (by its index in the body) falls in
     * the BSB verse, as a display-word range. He quotes the King James; the build carried
     * the words across by Strong's number. Lemmas it could not place are absent.
     */
    val anchors: Map<Int, IntRange> = emptyMap(),
)

/**
 * Reading order for the commentaries: plainest first.
 *
 * Not alphabetical. Tyndale (2022) explains the verse in today's English, so it is what
 * most readers want first. Wesley (1755) is a line or two on each phrase; Henry and JFB
 * are worth the work but are 1710 and 1871 paragraphs.
 */
private const val COMM_ORDER =
    "CASE c.source WHEN 'tyn' THEN 0 WHEN 'wes' THEN 1 WHEN 'mhc' THEN 2 ELSE 3 END"

data class Mark(
    val id: Long,
    val start: VerseId, val startWord: Int,
    val end: VerseId, val endWord: Int,
    val color: String,
    val note: String?,
    val excerpt: String?,
)

/**
 * The study Bible on disk.
 *
 * `bible-core.db` ships gzipped in the APK and is expanded once on first run. The commentary
 * is a third of the data and nobody needs it to read, so it lives in its own file that is
 * attached when present.
 */
class Bible private constructor(private val db: SQLiteDatabase) {

    val books: List<Book> by lazy {
        db.rawQuery("SELECT id,name,osis,chapters,testament FROM books ORDER BY id", null).use { c ->
            buildList {
                while (c.moveToNext())
                    add(Book(c.getInt(0), c.getString(1), c.getString(2), c.getInt(3), c.getString(4) == "OT"))
            }
        }
    }

    private val attached: Set<String> by lazy {
        db.rawQuery("PRAGMA database_list", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(1)) }
        }
    }

    /** The word-level Hebrew/Greek and the cross-references: a download, not in the APK. */
    val hasStudy: Boolean get() = "study" in attached

    /** Tyndale, Wesley, Matthew Henry and JFB: a download. */
    val hasCommentary: Boolean get() = "comm" in attached

    /** The commentary layer from 0.41 on carries the lemma anchors; an older download does not. */
    private val hasAnchors: Boolean by lazy {
        hasCommentary && db.rawQuery(
            "SELECT 1 FROM comm.sqlite_master WHERE type='table' AND name='comm_anchor'", null
        ).use { it.moveToFirst() }
    }

    /** Every verse of a chapter, in order, with footnotes attached. */
    fun chapter(book: Int, chapter: Int): List<Verse> {
        val notes = HashMap<VerseId, MutableList<Footnote>>()
        db.rawQuery(
            // A bound argument arrives as text, and SQLite will not equate text with the
            // integer `verse/1000`; compare against a range of ids instead.
            "SELECT f.verse, f.text, f.w_start, f.w_end FROM footnotes f" +
                " WHERE f.verse >= ? AND f.verse < ? ORDER BY f.verse, f.idx",
            arrayOf(
                (verseId(book, chapter, 0)).toString(),
                (verseId(book, chapter + 1, 0)).toString(),
            )
        ).use { c ->
            while (c.moveToNext())
                notes.getOrPut(c.getInt(0)) { mutableListOf() }
                    .add(Footnote(c.getString(1), c.getInt(2), c.getInt(3)))
        }
        return db.rawQuery(
            "SELECT id,text,heading,subheading,psalm_title,xref_note FROM verses" +
                " WHERE book=? AND chapter=? ORDER BY verse",
            arrayOf(book.toString(), chapter.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getInt(0)
                    add(Verse(id, c.getString(1), c.getString(2), c.getString(3),
                        c.getString(4), c.getString(5), notes[id] ?: emptyList()))
                }
            }
        }
    }

    /** The section headings of one chapter, in order, as they are written. */
    fun headings(book: Int, chapter: Int): List<String> = db.rawQuery(
        "SELECT heading FROM verses WHERE book=? AND chapter=? AND heading IS NOT NULL ORDER BY verse",
        arrayOf(book.toString(), chapter.toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /** The section headings of every chapter of [book], by chapter. */
    fun headings(book: Int): Map<Int, List<String>> = db.rawQuery(
        "SELECT chapter, heading FROM verses WHERE book=? AND heading IS NOT NULL ORDER BY chapter, verse",
        arrayOf(book.toString()),
    ).use { c ->
        val out = LinkedHashMap<Int, MutableList<String>>()
        while (c.moveToNext()) out.getOrPut(c.getInt(0)) { mutableListOf() }.add(c.getString(1))
        out
    }

    /** The study layer for one verse: tap a word, get this. */
    fun words(verse: VerseId): List<Word> {
        if (!hasStudy) return emptyList()
        return db.rawQuery(
            "SELECT pos,text,orig,translit,strongs,parsing,lang,punct,open_q,untranslated" +
                " FROM study.words WHERE verse=? ORDER BY pos", arrayOf(verse.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext())
                    add(Word(c.getInt(0), c.getString(1), c.getString(2), c.getString(3),
                        c.getString(4), c.getString(5), c.getString(6), c.getString(7),
                        c.getString(8), c.getInt(9) == 1))
            }
        }
    }

    /** Cross-references, strongest connection first. */
    fun crossRefs(verse: VerseId, limit: Int = 12): List<CrossRef> {
        if (!hasStudy) return emptyList()
        return db.rawQuery(
            "SELECT dst,dst_end,votes FROM study.xrefs WHERE src=? ORDER BY votes DESC LIMIT ?",
            arrayOf(verse.toString(), limit.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext())
                    add(CrossRef(c.getInt(0), if (c.isNull(1)) null else c.getInt(1), c.getInt(2)))
            }
        }
    }

    fun commentary(verse: VerseId): List<Commentary> {
        if (!hasCommentary) return emptyList()
        return db.rawQuery(
            "SELECT c.source, t.body FROM comm.comm c JOIN comm.comm_text t ON t.id=c.text_id" +
                " WHERE c.verse=? ORDER BY $COMM_ORDER", arrayOf(verse.toString())
        ).use { cur ->
            buildList { while (cur.moveToNext()) add(Commentary(cur.getString(0), cur.getString(1))) }
        }
    }

    /**
     * Commentary for a whole chapter at once, one entry per paragraph, with the verses it
     * covers on this page.
     *
     * Study mode shows commentary against every verse on screen, and Psalm 119 is 176 of
     * them: asking per verse would be 176 round trips on the scroll thread. One range query
     * instead, run off the main thread by the caller. The table links a paragraph to each
     * verse of its range; those links are folded back into one span here, so Henry on
     * Romans 8:1–9 comes back once, as 1–9, rather than nine times. In the order the
     * commentaries read: plainest first.
     */
    fun commentaryFor(book: Int, chapter: Int): List<CommentSpan> {
        if (!hasCommentary) return emptyList()
        // Keyed by paragraph (source and text id), in order of first appearance, so a
        // paragraph's span is the first to the last verse the table links it to.
        val spans = LinkedHashMap<Pair<String, Int>, CommentSpan>()
        db.rawQuery(
            "SELECT c.verse, c.source, c.text_id, t.body FROM comm.comm c JOIN comm.comm_text t" +
                " ON t.id=c.text_id WHERE c.verse >= ? AND c.verse < ?" +
                " ORDER BY c.verse, $COMM_ORDER",
            arrayOf(verseId(book, chapter, 0).toString(), verseId(book, chapter + 1, 0).toString())
        ).use { cur ->
            while (cur.moveToNext()) {
                val verse = cur.getInt(0)
                val key = cur.getString(1) to cur.getInt(2)
                val had = spans[key]
                spans[key] = had?.copy(end = maxOf(had.end, verse))
                    ?: CommentSpan(key.first, cur.getString(3), verse, verse)
            }
        }
        if (hasAnchors) {
            val anchors = HashMap<Pair<Int, Int>, MutableMap<Int, IntRange>>()
            db.rawQuery(
                "SELECT verse, text_id, n, w_start, w_end FROM comm.comm_anchor" +
                    " WHERE verse >= ? AND verse < ?",
                arrayOf(verseId(book, chapter, 0).toString(), verseId(book, chapter + 1, 0).toString())
            ).use { cur ->
                while (cur.moveToNext())
                    anchors.getOrPut(cur.getInt(0) to cur.getInt(1)) { HashMap() }[cur.getInt(2)] =
                        cur.getInt(3)..cur.getInt(4)
            }
            for ((key, span) in spans)
                anchors[span.start to key.second]?.let { spans[key] = span.copy(anchors = it) }
        }
        return spans.values.toList()
    }

    /**
     * Cross-references for a whole chapter, strongest first, capped per verse.
     *
     * The cap is applied here rather than in SQL: a per-verse LIMIT would need a window
     * function, and the whole chapter's references are a few hundred rows at most.
     */
    fun crossRefsFor(book: Int, chapter: Int, each: Int = 4): Map<VerseId, List<CrossRef>> {
        if (!hasStudy) return emptyMap()
        return db.rawQuery(
            "SELECT src,dst,dst_end,votes FROM study.xrefs WHERE src >= ? AND src < ?" +
                " ORDER BY src, votes DESC",
            arrayOf(verseId(book, chapter, 0).toString(), verseId(book, chapter + 1, 0).toString())
        ).use { c ->
            buildMap<VerseId, MutableList<CrossRef>> {
                while (c.moveToNext()) {
                    val list = getOrPut(c.getInt(0)) { mutableListOf() }
                    if (list.size < each)
                        list += CrossRef(c.getInt(1), if (c.isNull(2)) null else c.getInt(2), c.getInt(3))
                }
            }
        }
    }

    /** Every other verse translated from the same original word. */
    fun concordance(strongs: String, lang: String, limit: Int = 60): List<VerseId> {
        if (!hasStudy) return emptyList()
        return db.rawQuery(
            "SELECT DISTINCT verse FROM study.words WHERE strongs=? AND lang=? ORDER BY verse LIMIT ?",
            arrayOf(strongs, lang, limit.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(c.getInt(0)) } }
    }

    /**
     * Full-text search, returning each hit with its verse text.
     *
     * The index is FTS4 with `content=""` — AOSP ships SQLite without the FTS5 module, and an
     * external-content index keeps the database from carrying the text twice. That means the
     * index cannot produce snippets, so the text comes from `verses` in the same query.
     */
    fun search(query: String, limit: Int = 100): List<Pair<VerseId, String>> =
        db.rawQuery(
            "SELECT v.id, v.text FROM verses_fts f JOIN verses v ON v.id=f.docid" +
                " WHERE verses_fts MATCH ? ORDER BY v.id LIMIT ?",
            arrayOf(query, limit.toString())
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.getInt(0) to c.getString(1)) }
        }

    /**
     * The verses from [from] to [to], inclusive, as plain text — a passage quoted somewhere
     * else and looked up. A whole chapter is asked for as verse 1 to verse 999.
     */
    fun passage(from: VerseId, to: VerseId): List<Pair<VerseId, String>> =
        // Ids are numbers of our own making, so they go in as numbers: a bound argument
        // arrives as text.
        db.rawQuery("SELECT id,text FROM verses WHERE id BETWEEN $from AND $to ORDER BY id LIMIT 200", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getInt(0) to c.getString(1)) }
        }

    /** The BSB leaves 16 disputed verses out; say so rather than showing a gap. */
    fun omissionNote(verse: VerseId): String? =
        db.rawQuery("SELECT note FROM omitted WHERE id=?", arrayOf(verse.toString())).use { c ->
            if (c.moveToNext()) c.getString(0) else null
        }

    fun reference(verse: VerseId): String =
        "${books[verse.book - 1].name} ${verse.chapter}:${verse.verse}"

    // ------------------------------------------------------------------ marks

    /**
     * Every mark touching this chapter.
     *
     * A mark is a range, so one that began in the chapter before and ends in this one still
     * belongs on the page: the test is overlap, not where it started.
     */
    fun marks(book: Int, chapter: Int): List<Mark> =
        db.rawQuery(
            "SELECT id,v_start,w_start,v_end,w_end,color,note,excerpt FROM marks" +
                " WHERE v_start < ? AND v_end >= ? ORDER BY v_start, w_start",
            arrayOf(
                (verseId(book, chapter + 1, 0)).toString(),
                (verseId(book, chapter, 0)).toString(),
            )
        ).use { c ->
            buildList {
                while (c.moveToNext())
                    add(Mark(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4),
                        c.getString(5), c.getString(6), c.getString(7)))
            }
        }

    /** Everything marked anywhere, most recent first. */
    fun allMarks(): List<Mark> =
        db.rawQuery(
            "SELECT id,v_start,w_start,v_end,w_end,color,note,excerpt FROM marks" +
                " ORDER BY updated DESC", null
        ).use { c ->
            buildList {
                while (c.moveToNext())
                    add(Mark(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4),
                        c.getString(5), c.getString(6), c.getString(7)))
            }
        }

    fun saveMark(m: Mark): Long {
        val now = System.currentTimeMillis()
        val v = android.content.ContentValues().apply {
            put("v_start", m.start); put("w_start", m.startWord)
            put("v_end", m.end); put("w_end", m.endWord)
            put("color", m.color); put("note", m.note); put("excerpt", m.excerpt)
            put("updated", now)
        }
        return if (m.id > 0) {
            db.update("marks", v, "id=?", arrayOf(m.id.toString())); m.id
        } else {
            v.put("created", now); db.insert("marks", null, v)
        }
    }

    fun deleteMark(id: Long) = db.delete("marks", "id=?", arrayOf(id.toString()))

    fun close() = db.close()

    companion object {
        private const val TAG = "lamplight"
        const val ASSET = "bible-reading.db"

        /** 0 when the file predates the stamp, which is exactly the case we must replace. */
        private fun textVersionOf(f: File): Int = try {
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
                d.rawQuery("PRAGMA user_version", null).use { c ->
                    if (c.moveToNext()) c.getInt(0) else 0
                }
            }
        } catch (e: Exception) { 0 }

        /** Read the marks out of the copy about to be thrown away. */
        private fun rescueMarks(f: File): List<android.content.ContentValues> = try {
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
                d.rawQuery("SELECT v_start,w_start,v_end,w_end,color,note,excerpt,created,updated" +
                    " FROM marks", null).use { c ->
                    buildList {
                        while (c.moveToNext()) add(android.content.ContentValues().apply {
                            put("v_start", c.getInt(0)); put("w_start", c.getInt(1))
                            put("v_end", c.getInt(2)); put("w_end", c.getInt(3))
                            put("color", c.getString(4)); put("note", c.getString(5))
                            put("excerpt", c.getString(6))
                            put("created", c.getLong(7)); put("updated", c.getLong(8))
                        })
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "could not read the old marks: ${e.message}"); emptyList() }

        private fun restoreMarks(f: File, rows: List<android.content.ContentValues>) {
            try {
                SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
                    d.beginTransaction()
                    try {
                        rows.forEach { d.insert("marks", null, it) }
                        d.setTransactionSuccessful()
                    } finally { d.endTransaction() }
                }
            } catch (e: Exception) { Log.w(TAG, "could not restore the marks: ${e.message}") }
        }

        /** The optional layers, by the schema name they are attached as. */
        val LAYERS = mapOf("study" to "bible-study.db", "comm" to "bible-commentary.db")

        /**
         * Copy the shipped database out of the APK once, then attach commentary if it has been
         * downloaded. The asset is stored Deflate-compressed in the APK (24 MB of the install),
         * and AssetManager decompresses it as it is read, so this is a plain copy.
         */
        /** Bump in build.py's TEXT_VERSION whenever the verse text itself changes. */
        const val TEXT_VERSION = 4

        fun open(ctx: Context): Bible {
            val core = File(ctx.filesDir, ASSET)
            // The database is unpacked once and then kept, so a text fix in a later build
            // would never reach a phone that had already unpacked an older one. Compare the
            // stamp and replace the copy when it is behind, carrying the marks across.
            val stale = core.exists() && core.length() > 0 && textVersionOf(core) < TEXT_VERSION
            if (!core.exists() || core.length() == 0L || stale) {
                if (stale) Log.i(TAG, "$ASSET is text version ${textVersionOf(core)}, replacing")
                val t = System.currentTimeMillis()
                val saved = if (stale) rescueMarks(core) else emptyList()
                val tmp = File(ctx.filesDir, "bible-core.db.part")
                ctx.assets.open(ASSET).use { input ->
                    tmp.outputStream().buffered(1 shl 16).use { input.copyTo(it, 1 shl 16) }
                }
                if (core.exists()) core.delete()
                check(tmp.renameTo(core)) { "could not move the database into place" }
                if (saved.isNotEmpty()) restoreMarks(core, saved)
                Log.i(TAG, "unpacked ${core.length() / 1_000_000} MB in ${System.currentTimeMillis() - t} ms" +
                    if (saved.isNotEmpty()) ", kept ${saved.size} marks" else "")
            }
            val db = SQLiteDatabase.openDatabase(core.path, null, SQLiteDatabase.OPEN_READWRITE)
            for ((schema, file) in LAYERS) {
                val f = File(ctx.filesDir, file)
                if (!f.exists() || f.length() == 0L) continue
                try {
                    db.execSQL("ATTACH DATABASE ? AS $schema", arrayOf(f.path))
                } catch (e: Exception) {
                    Log.w(TAG, "$schema not attached: ${e.message}")
                }
            }
            return Bible(db)
        }
    }
}
