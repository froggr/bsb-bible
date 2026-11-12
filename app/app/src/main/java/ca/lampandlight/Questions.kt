package ca.lampandlight

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/** A question about the chapter, and the verse or two that answer it. */
data class Question(val key: String, val start: VerseId, val end: VerseId, val text: String)

/** How a question went the last time it was put, and when. See [Quiz.RIGHT]. */
data class Past(val outcome: Int, val at: Long)

/**
 * Which questions a chapter asks, where, and what it offers as answers.
 *
 * The questions are written by people (unfoldingWord's translation checkers) and the answers
 * are verses of the page: nothing here is composed. All this decides is the asking. One
 * question closes each stretch of reading, at the end of a section, where a printed study
 * guide would put it, and the choices are the verse that answers it beside two others from
 * the same reading. So what is being practised is knowing what the passage said, and the
 * right answer is always scripture in its own words.
 *
 * Kept free of the phone so it can be tested on its own.
 */
object Quiz {
    /** The reader chose the verse that answers it. */
    const val RIGHT = 1
    /** The reader chose another. */
    const val WRONG = 0
    /** Nobody answered, and the voice read the answer out. */
    const val HEARD = -1

    /**
     * How many verses of reading earn a question. Sections in the BSB run from three verses
     * (the days of Genesis 1) to seventy; a question after every three is an interruption,
     * so short sections are read on through until there is this much to ask about.
     */
    const val STRETCH = 6

    /** One asking: the question, the verse it follows, the passages offered, and the right one. */
    data class Asked(
        val question: Question,
        /** Position in the chapter of the verse it is put after: the last of its section. */
        val after: Int,
        /** Each choice as a first and last verse id. */
        val options: List<Pair<VerseId, VerseId>>,
        val correct: Int,
    )

    /**
     * The questions for a chapter of [verses], where [opens] holds the positions at which a
     * section begins. [past] is what has happened to each question before.
     */
    fun plan(
        verses: List<VerseId>,
        opens: Set<Int>,
        questions: List<Question>,
        past: Map<String, Past> = emptyMap(),
    ): List<Asked> {
        if (verses.isEmpty() || questions.isEmpty()) return emptyList()
        val out = mutableListOf<Asked>()
        var from = 0
        for (e in verses.indices) {
            val last = e == verses.lastIndex
            if (!last && (e + 1) !in opens) continue        // not the end of a section
            val length = e - from + 1
            if (length < STRETCH && !last) continue
            // A few verses left over at the end of a chapter that has already been asked
            // about are let go, rather than asked about for the sake of it.
            if (last && length < STRETCH / 2 && out.isNotEmpty()) break
            val lo = verses[from]; val hi = verses[e]
            val q = choose(questions.filter { it.start >= lo && it.end <= hi }, past)
            // Nothing to ask about this stretch: read on, and ask about the longer one.
            if (q == null) continue
            out += offer(q, e, lo, hi, verses, questions, past[q.key]?.at ?: 0L)
            from = e + 1
        }
        return out
    }

    /**
     * Which question to put, of those a stretch has.
     *
     * One never put before, then one that was got wrong, then one that was only heard, and
     * last one already got right, the longest ago first. So going back over a chapter asks
     * something new until there is nothing new, and then what was missed.
     */
    fun choose(candidates: List<Question>, past: Map<String, Past>): Question? =
        candidates.minWithOrNull(
            compareBy<Question> {
                when (past[it.key]?.outcome) {
                    null -> 0
                    WRONG -> 1
                    HEARD -> 2
                    else -> 3
                }
            }.thenBy { past[it.key]?.at ?: 0L }
                // Not always the first question of the stretch: a fixed scatter of the key.
                .thenBy { scatter(it.key) }
        )

    /** The choices for [q]: its own verses, and two other passages from the same reading. */
    private fun offer(
        q: Question, after: Int, lo: VerseId, hi: VerseId,
        verses: List<VerseId>, questions: List<Question>, turn: Long,
    ): Asked {
        val random = kotlin.random.Random(scatter(q.key).toLong() xor turn)
        val taken = mutableListOf(q.start to q.end)
        fun free(p: Pair<VerseId, VerseId>) = taken.none { p.first <= it.second && p.second >= it.first }
        // Passages that some question is about are the ones worth mistaking for the answer.
        // Those in the stretch just read come first, then the rest of the chapter, nearest
        // first; a chapter with too few of either falls back to its other verses.
        val others = questions.map { it.start to it.end }.distinct()
        val near = others.filter { it.first >= lo && it.second <= hi }.shuffled(random)
        val far = others.filter { it.first < lo || it.second > hi }
            .sortedBy { kotlin.math.abs(it.first - q.start) }
        val plain = verses.map { it to it }.sortedBy { kotlin.math.abs(it.first - q.start) }
        for (p in near + far + plain) {
            if (taken.size >= CHOICES) break
            if (free(p)) taken += p
        }
        val options = taken.shuffled(random)
        return Asked(q, after, options, options.indexOf(q.start to q.end))
    }

    private fun scatter(key: String): Int {
        // String.hashCode() of keys that differ in their last letter differ by one, which
        // would put neighbours in file order; multiply it out.
        var h = key.hashCode() * -0x61c88647
        h = h xor (h ushr 15)
        return h and 0x7fffffff
    }

    const val CHOICES = 3

    // ------------------------------------------------------------------ going over

    /**
     * How long a question rests before it is worth putting again, by how many times running
     * it has been got right. Recalling a thing just as it starts to slip is what fixes it,
     * so the rests lengthen: a question that was missed comes back within the sitting, and
     * each time it is then known it is left for longer.
     */
    private val RESTS = longArrayOf(
        10 * MINUTE, 1 * DAY, 3 * DAY, 7 * DAY, 21 * DAY, 60 * DAY, 180 * DAY,
    )

    /** A question known the first time it was met has less to prove, and starts this far along. */
    private const val HEAD_START = 2

    /**
     * When a question is next due, from everything that has happened to it, oldest first.
     * Null for one never put: going over is of what has been read, never of what has not.
     */
    fun dueAt(history: List<Past>, headStart: Int = HEAD_START): Long? {
        val last = history.lastOrNull() ?: return null
        val running = history.takeLastWhile { it.outcome == RIGHT }.size
        val missed = history.any { it.outcome != RIGHT }
        val step = if (missed) running else running + headStart
        return last.at + RESTS[step.coerceAtMost(RESTS.lastIndex)]
    }

    /**
     * [q] put again away from its page: its own verses beside two others from the verses
     * around it. [turn] is when it was last put, so the choices are not laid out as before.
     */
    fun again(q: Question, verses: List<VerseId>, questions: List<Question>, turn: Long): Asked {
        val at = verses.indexOf(q.start).coerceAtLeast(0)
        val lo = verses.getOrNull((at - AROUND).coerceAtLeast(0)) ?: q.start
        val hi = verses.getOrNull((at + AROUND).coerceAtMost(verses.lastIndex)) ?: q.end
        return offer(q, at, lo, hi, verses, questions, turn)
    }

    /** How far either side of the answer the other choices are drawn from. */
    private const val AROUND = 6
    private const val MINUTE = 60_000L
    private const val DAY = 24 * 60 * MINUTE
}

/**
 * The questions, and what the reader has made of them.
 *
 * Two files. The questions come with the app and are replaced when a newer build carries a
 * newer set, the same way the text is. What the reader answered is theirs, lives in a file
 * of its own and is never replaced, so it does not have to be rescued the way the marks are.
 */
class Questions private constructor(
    private val asked: SQLiteDatabase?,
    private val mine: SQLiteDatabase?,
) {
    fun of(book: Int, chapter: Int): List<Question> {
        val db = asked ?: return emptyList()
        val lo = verseId(book, chapter, 0)
        return runCatching {
            db.rawQuery(
                "SELECT key, v_start, v_end, text FROM questions WHERE v_start BETWEEN ? AND ? ORDER BY v_start, key",
                arrayOf(lo.toString(), (lo + 999).toString()),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) add(Question(c.getString(0), c.getInt(1), c.getInt(2), c.getString(3)))
                }
            }
        }.getOrElse { Log.w(TAG, "questions not read: ${it.message}"); emptyList() }
    }

    /** The last thing that happened to each of [keys]; a key never put is absent. */
    fun past(keys: Collection<String>): Map<String, Past> {
        val db = mine ?: return emptyMap()
        if (keys.isEmpty()) return emptyMap()
        return runCatching {
            val out = HashMap<String, Past>()
            keys.chunked(400).forEach { part ->
                db.rawQuery(
                    "SELECT key, outcome, MAX(at) FROM asked WHERE key IN (" +
                        part.joinToString(",") { "?" } + ") GROUP BY key",
                    part.toTypedArray(),
                ).use { c ->
                    while (c.moveToNext()) out[c.getString(0)] = Past(c.getInt(1), c.getLong(2))
                }
            }
            out
        }.getOrElse { emptyMap() }
    }

    /**
     * The questions due to be gone over at [now], the longest overdue first.
     *
     * Worked out from the whole record each time rather than kept as a date beside each
     * question: the record is a few rows a day, and a date kept would have to be kept right.
     */
    fun due(now: Long = System.currentTimeMillis()): List<String> {
        val db = mine ?: return emptyList()
        return runCatching {
            val history = history(db)
            history.mapNotNull { (key, h) -> Quiz.dueAt(h)?.takeIf { it <= now }?.let { key to it } }
                .sortedBy { it.second }.map { it.first }
        }.getOrElse { emptyList() }
    }

    /** When the next question falls due after [now], or null when none is waiting to. */
    fun nextDue(now: Long = System.currentTimeMillis()): Long? {
        val db = mine ?: return null
        return runCatching {
            val history = history(db)
            history.values.mapNotNull { Quiz.dueAt(it) }.filter { it > now }.minOrNull()
        }.getOrNull()
    }

    private fun history(db: SQLiteDatabase): Map<String, List<Past>> {
        val out = LinkedHashMap<String, MutableList<Past>>()
        db.rawQuery("SELECT key, outcome, at FROM asked ORDER BY at", null).use { c ->
            while (c.moveToNext())
                out.getOrPut(c.getString(0)) { mutableListOf() }.add(Past(c.getInt(1), c.getLong(2)))
        }
        return out
    }

    fun byKey(key: String): Question? {
        val db = asked ?: return null
        return runCatching {
            db.rawQuery("SELECT key, v_start, v_end, text FROM questions WHERE key = ?", arrayOf(key)).use { c ->
                if (c.moveToNext()) Question(c.getString(0), c.getInt(1), c.getInt(2), c.getString(3)) else null
            }
        }.getOrNull()
    }

    /** When [key] was last put, or 0. */
    fun lastAt(key: String): Long = past(listOf(key))[key]?.at ?: 0L

    // ------------------------------------------------------------- by heart

    /** Start learning [verse] by heart. What was said of it before, if anything, still counts. */
    fun learn(verse: VerseId, at: Long = System.currentTimeMillis()) {
        runCatching {
            mine?.execSQL("INSERT OR IGNORE INTO heart(verse, added) VALUES (?,?)", arrayOf<Any>(verse, at))
        }
    }

    /** Stop. The record of saying it is kept, so taking it up again is not starting again. */
    fun unlearn(verse: VerseId) {
        runCatching { mine?.execSQL("DELETE FROM heart WHERE verse = ?", arrayOf<Any>(verse)) }
    }

    /**
     * Learn [verse] through to [upto], or with null only itself again.
     *
     * Longer, it is a different thing to say, so it starts again as new: what was said of the
     * shorter one is kept in the log and no longer counted. Shorter, it is something already
     * said within the longer one, and it keeps its place.
     */
    fun stretch(verse: VerseId, upto: VerseId?, at: Long = System.currentTimeMillis()) {
        val last = upto?.takeIf { it > verse }
        val was = heartOf(verse)?.takeIf { it.verse == verse } ?: return
        runCatching {
            if ((last ?: verse) > was.last) mine?.execSQL(
                "UPDATE heart SET upto = ?, since = ?, added = ? WHERE verse = ?",
                arrayOf<Any?>(last, at, at, verse),
            ) else mine?.execSQL("UPDATE heart SET upto = ? WHERE verse = ?", arrayOf<Any?>(last, verse))
        }
    }

    /** What is being learned that has [verse] in it, by its first verse. */
    fun heartOf(verse: VerseId): Heart? = hearts().firstOrNull { verse in it.verse..it.last }

    /** Every verse being learned, in the order they were taken up. */
    fun hearts(): List<Heart> {
        val db = mine ?: return emptyList()
        return runCatching {
            val said = HashMap<Int, MutableList<Past>>()
            db.rawQuery(
                "SELECT s.verse, s.outcome, s.at FROM said s JOIN heart h ON h.verse = s.verse " +
                    "WHERE s.at >= h.since ORDER BY s.at", null,
            ).use { c ->
                while (c.moveToNext())
                    said.getOrPut(c.getInt(0)) { mutableListOf() }.add(Past(c.getInt(1), c.getLong(2)))
            }
            db.rawQuery("SELECT verse, added, upto FROM heart ORDER BY added, verse", null).use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        Heart(
                            c.getInt(0), c.getLong(1), said[c.getInt(0)].orEmpty(),
                            if (c.isNull(2)) null else c.getInt(2),
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    /** [verse] was said from memory ([Quiz.RIGHT]) or was not ([Quiz.WRONG]). */
    fun said(verse: VerseId, outcome: Int, at: Long = System.currentTimeMillis()) {
        runCatching {
            mine?.execSQL("INSERT INTO said(verse, at, outcome) VALUES (?,?,?)", arrayOf<Any>(verse, at, outcome))
        }
    }

    fun record(key: String, outcome: Int, at: Long = System.currentTimeMillis()) {
        runCatching {
            mine?.execSQL("INSERT INTO asked(key, at, outcome) VALUES (?,?,?)", arrayOf<Any>(key, at, outcome))
        }
    }

    // ------------------------------------------------------------- places

    /**
     * [chapter] (see [Places.key]) was read through. Reaching the foot of the page and the
     * voice finishing can both report the same reading, so one inside ten minutes of the
     * last is the same one.
     */
    fun finished(chapter: Int, at: Long = System.currentTimeMillis()) {
        val db = mine ?: return
        runCatching {
            val last = db.rawQuery("SELECT MAX(at) FROM read WHERE chapter = ?", arrayOf(chapter.toString()))
                .use { c -> if (c.moveToNext() && !c.isNull(0)) c.getLong(0) else 0L }
            if (at - last > 10 * 60_000L)
                db.execSQL("INSERT INTO read(chapter, at) VALUES (?,?)", arrayOf<Any>(chapter, at))
        }
    }

    /** What was in [chapter] was there when asked for ([Quiz.RIGHT]) or was not ([Quiz.WRONG]). */
    fun recalled(chapter: Int, outcome: Int, at: Long = System.currentTimeMillis()) {
        runCatching {
            mine?.execSQL(
                "INSERT INTO recalled(chapter, at, outcome) VALUES (?,?,?)", arrayOf<Any>(chapter, at, outcome),
            )
        }
    }

    /** Every chapter that has been read through, by its key. */
    fun places(): Map<Int, Place> = places(null)

    /** One chapter, or null when it has not been read through. */
    fun place(chapter: Int): Place? = places(chapter)[chapter]

    private fun places(only: Int?): Map<Int, Place> {
        val db = mine ?: return emptyMap()
        val where = if (only != null) " WHERE chapter = $only" else ""
        return runCatching {
            val history = HashMap<Int, MutableList<Past>>()
            db.rawQuery("SELECT chapter, outcome, at FROM recalled$where ORDER BY at", null).use { c ->
                while (c.moveToNext())
                    history.getOrPut(c.getInt(0)) { mutableListOf() }.add(Past(c.getInt(1), c.getLong(2)))
            }
            db.rawQuery("SELECT chapter, MAX(at) FROM read$where GROUP BY chapter", null).use { c ->
                buildMap {
                    while (c.moveToNext())
                        put(c.getInt(0), Place(c.getInt(0), c.getLong(1), history[c.getInt(0)].orEmpty()))
                }
            }
        }.getOrElse { emptyMap() }
    }

    companion object {
        private const val TAG = "Questions"
        private const val ASSET = "questions.db"
        private const val MINE = "learning.db"

        /** Must match VERSION in data/questions.py; raise both when the set is rebuilt. */
        const val VERSION = 1

        fun open(ctx: Context): Questions {
            val asked = runCatching {
                val f = File(ctx.filesDir, ASSET)
                if (!f.exists() || f.length() == 0L || versionOf(f) < VERSION) {
                    val tmp = File(ctx.filesDir, "$ASSET.part")
                    ctx.assets.open(ASSET).use { input ->
                        tmp.outputStream().buffered(1 shl 16).use { input.copyTo(it, 1 shl 16) }
                    }
                    if (f.exists()) f.delete()
                    check(tmp.renameTo(f)) { "could not move the questions into place" }
                }
                SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
            }.getOrElse { Log.w(TAG, "no questions: ${it.message}"); null }
            val mine = runCatching {
                SQLiteDatabase.openOrCreateDatabase(File(ctx.filesDir, MINE), null).also {
                    it.execSQL("CREATE TABLE IF NOT EXISTS asked(key TEXT NOT NULL, at INTEGER NOT NULL, outcome INTEGER NOT NULL)")
                    it.execSQL("CREATE INDEX IF NOT EXISTS asked_key ON asked(key, at)")
                    it.execSQL(
                        "CREATE TABLE IF NOT EXISTS heart(verse INTEGER PRIMARY KEY, added INTEGER NOT NULL, " +
                            "upto INTEGER, since INTEGER NOT NULL DEFAULT 0)"
                    )
                    it.execSQL("CREATE TABLE IF NOT EXISTS said(verse INTEGER NOT NULL, at INTEGER NOT NULL, outcome INTEGER NOT NULL)")
                    it.execSQL("CREATE INDEX IF NOT EXISTS said_verse ON said(verse, at)")
                    it.execSQL("CREATE TABLE IF NOT EXISTS read(chapter INTEGER NOT NULL, at INTEGER NOT NULL)")
                    it.execSQL("CREATE INDEX IF NOT EXISTS read_chapter ON read(chapter, at)")
                    it.execSQL(
                        "CREATE TABLE IF NOT EXISTS recalled(chapter INTEGER NOT NULL, at INTEGER NOT NULL, " +
                            "outcome INTEGER NOT NULL)"
                    )
                    it.execSQL("CREATE INDEX IF NOT EXISTS recalled_chapter ON recalled(chapter, at)")
                }
            }.getOrElse { Log.w(TAG, "answers not kept: ${it.message}"); null }
            return Questions(asked, mine)
        }

        private fun versionOf(f: File): Int = runCatching {
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
        }.getOrDefault(0)
    }
}
