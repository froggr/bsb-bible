package ca.lampandlight

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList

/**
 * A drawn line of the chapter.
 *
 * Study material is rows of its own rather than something a verse draws inside itself, so
 * the LazyColumn goes on virtualising it: a chapter with commentary against all 176 verses
 * composes only what is on screen. It also means a note and a commentary paragraph scroll
 * as siblings of the verse rather than as part of it.
 */
sealed interface Row {
    val key: String

    /** What runs down the margin beside this row. See [Margin]. */
    val margin: Margin

    data class Heading(
        val text: String, override val key: String,
        override val margin: Margin = Margin(),
    ) : Row
    data class Title(
        val text: String, override val key: String,
        override val margin: Margin = Margin(),
    ) : Row
    data class Verse(
        val verse: ca.lampandlight.Verse,
        val index: Int,              // position within the chapter, for the narrator
        val words: List<String>,
        override val key: String,
        override val margin: Margin = Margin(),
        /**
         * For each translator's note on the verse, the words it is about — or null when it
         * has no place in the text. Empty unless the notes are on the page, so reading mode
         * draws the verse clean.
         */
        val noteAnchors: List<IntRange?> = emptyList(),
        /**
         * The words a commentator on the page is glossing — Wesley's lemmas, carried from
         * the King James by Strong's number. Empty unless his notes are shown.
         */
        val glossAnchors: List<IntRange> = emptyList(),
    ) : Row

    /**
     * Something the reader wrote, drawn once, after the last verse it covers.
     *
     * A note belongs to a span, not to each verse in it: the same words under verse 13 and
     * again under 14 read as two notes. [verse] is where it sits on the page — the last
     * verse of the span on this page — and [start] the first, so the bracket down the
     * margin and the note's label both know how far it reaches.
     */
    data class Note(
        val verse: VerseId,
        val start: VerseId,
        val text: String,
        val colour: MarkColor,
        override val key: String,
        override val margin: Margin = Margin(),
    ) : Row

    /**
     * A paragraph of commentary, drawn once, after the last verse it covers.
     *
     * The same shape as a note: [verse] is the last verse of the paragraph's span on this
     * page and [start] the first. A paragraph on one verse has the two equal.
     * [source] is the short key: "tyn", "wes", "mhc", "jfb".
     */
    data class Comment(
        val verse: VerseId,
        val start: VerseId,
        val source: String,
        val body: String,
        override val key: String,
        override val margin: Margin = Margin(),
        /** Which of the body's `*lemmas*` (by index) are underlined in the verse above. */
        val anchored: Set<Int> = emptySet(),
    ) : Row

    /** The cross-references for one verse, drawn as a single line of links. */
    data class Refs(
        val verse: VerseId,
        val refs: List<CrossRef>,
        override val key: String,
        override val margin: Margin = Margin(),
    ) : Row

    /**
     * A translator's footnote: the apparatus a study Bible sets at the foot of the page.
     *
     * [letter] is its index among the verse's notes, and the same letter marks the place
     * in the verse; [phrase] is the words it is about, quoted so the note reads without
     * looking back up.
     */
    data class Footnote(
        val verse: VerseId,
        val text: String,
        val letter: Int,
        val phrase: String?,
        override val key: String,
        override val margin: Margin = Margin(),
    ) : Row
}

/**
 * What passes down the margin beside a row and carries on below it.
 *
 * A note and a commentary paragraph each cover a span of verses, and each is drawn once,
 * at the end of its span; the bracket is what joins the words written on to the writing.
 * So every row the span runs through — the verses, a heading between them, a footnote
 * under one of them — draws the bracket through its own full height, and the row the
 * span ends on draws the last stretch itself, down to the foot of the note. Two lanes at
 * most: the reader's note, in its mark's colour, and commentary, thinner and quieter.
 */
data class Margin(val note: MarkColor? = null, val comment: Boolean = false)

/** What study mode puts on the page. Notes are not here: those are never hidden. */
data class StudyLayers(
    val commentary: Boolean = true,
    val refs: Boolean = true,
    val footnotes: Boolean = true,
    /** Commentary source keys to leave out, e.g. the 1710 and 1871 prose. */
    val hiddenCommentaries: Set<String> = emptySet(),
) {
    val any: Boolean get() = commentary || refs || footnotes

    fun shows(source: String) = commentary && source !in hiddenCommentaries
}

/**
 * What the reader is looking at.
 *
 * Verses are split into display words once, here, rather than on every recomposition: the
 * narrator addresses words by index and the reader draws them by index, so they have to
 * agree on the same list.
 */
class ReaderState(private val bible: Bible, private val place: Place? = null) {

    /**
     * Where the reader was last time.
     *
     * A Bible is not an app you start over in: you come back to it where you left off, the
     * way a ribbon holds the page. Genesis 1 is only the first-run default, for someone who
     * has not read anything yet.
     */
    interface Place {
        fun load(): Triple<Int, Int, Int>?
        fun save(book: Int, chapter: Int, verse: Int)
    }

    private val opening = place?.load()

    var book by mutableIntStateOf(opening?.first ?: 1)
        private set
    var chapter by mutableIntStateOf(opening?.second ?: 1)
        private set

    var verses by mutableStateOf<List<Verse>>(emptyList())
        private set
    var rows by mutableStateOf<List<Row>>(emptyList())
        private set

    /** Every mark touching the chapter on screen, in the order they start. */
    var marks by mutableStateOf<List<Mark>>(emptyList())
        private set

    /**
     * The marks covering each verse, by verse id.
     *
     * A mark is a range, so one mark appears against every verse it reaches. Built once per
     * load rather than searched on each recomposition.
     */
    private var byVerse by mutableStateOf<Map<VerseId, List<Mark>>>(emptyMap())

    /** The mark covering [v], if any. The newest wins where two overlap. */
    fun markAt(v: VerseId): Mark? = byVerse[v]?.lastOrNull()

    fun markOf(v: VerseId): MarkColor? = markAt(v)?.let { MarkColor.of(it.color) }
    fun noteOf(v: VerseId): String? = byVerse[v]?.firstNotNullOfOrNull {
        it.note?.takeIf { n -> n.isNotBlank() }
    }
    fun hasNote(v: VerseId): Boolean = noteOf(v) != null

    /**
     * The notes that end on each verse: the mark, and the first verse of it on this page.
     *
     * Built once per load. A mark may start in a verse this page does not show (it never
     * does today — marks are made within a chapter — but a span is clamped to the page
     * rather than trusted).
     */
    private var notesEnding by mutableStateOf<Map<VerseId, List<Pair<Mark, VerseId>>>>(emptyMap())

    /**
     * Which words of [v] are inside a mark, as a half-open range over word indices.
     *
     * A mark that runs through the middle of a verse covers only part of it; one that merely
     * passes through covers all of it. `wordCount` is how many words the verse has, because
     * an end of -1 means "to the end".
     */
    fun markedWords(v: VerseId, wordCount: Int): Pair<IntRange, MarkColor>? {
        val m = markAt(v) ?: return null
        val colour = MarkColor.of(m.color) ?: return null
        val from = if (v == m.start) m.startWord.coerceAtLeast(0) else 0
        val to = if (v == m.end && m.endWord >= 0) m.endWord.coerceAtMost(wordCount - 1)
                 else wordCount - 1
        if (to < from) return null
        return from..to to colour
    }

    /** Write a note on [verse], or on the whole mark covering it, marking it if need be. */
    /**
     * Write a note across [from]..[to], marking the range if it is not marked yet.
     *
     * A note taken on a selection belongs to the whole selection. The single-verse [note]
     * widens to an existing mark but cannot invent one, so a fresh range noted straight
     * from the selection bar used to collapse onto its first verse.
     *
     * Any mark already overlapping is swallowed, the same rule [markRange] follows, so
     * noting over the top of an older highlight replaces it rather than layering on it.
     */
    fun noteRange(from: VerseId, to: VerseId, text: String) {
        val selLo = minOf(from, to)
        val selHi = maxOf(from, to)
        val overlapping = bible.marks(selLo.book, selLo.chapter)
            .filter { it.start <= selHi && it.end >= selLo }
        // The note is shown as the whole mark's, so editing it must keep the whole mark:
        // opening the note of a 13–16 mark from verse 14 and saving used to cut the mark
        // down to verse 14 alone. The span is the union of the selection and whatever it
        // touched.
        val lo = (overlapping.map { it.start } + selLo).min()
        val hi = (overlapping.map { it.end } + selHi).max()
        // Keep the colour the reader already chose for any of it, rather than resetting
        // a span they had deliberately coloured.
        val colour = overlapping.firstOrNull()?.color ?: MarkColor.Gold.name.lowercase()
        overlapping.forEach { bible.deleteMark(it.id) }
        bible.saveMark(
            Mark(
                id = 0,
                start = lo, startWord = 0,
                end = hi, endWord = -1,
                color = colour,
                note = text.ifBlank { null },
                excerpt = excerptFor(lo, hi),
            )
        )
        loadMarks()
    }

    /** The note already written across [from]..[to], if one is there. */
    fun noteAcross(from: VerseId, to: VerseId): String? {
        val lo = minOf(from, to)
        val hi = maxOf(from, to)
        return bible.marks(lo.book, lo.chapter)
            .filter { it.start <= hi && it.end >= lo }
            .firstNotNullOfOrNull { it.note?.takeIf { n -> n.isNotBlank() } }
    }

    fun note(verse: VerseId, text: String) {
        val existing = markAt(verse)
        bible.saveMark(
            Mark(
                id = existing?.id ?: 0,
                // Writing on a verse inside a range notes the whole range, not a slice of it.
                start = existing?.start ?: verse, startWord = existing?.startWord ?: 0,
                end = existing?.end ?: verse, endWord = existing?.endWord ?: -1,
                color = existing?.color ?: MarkColor.Gold.name.lowercase(),
                note = text.ifBlank { null },
                excerpt = existing?.excerpt ?: excerptFor(verse, verse),
            )
        )
        loadMarks()
    }

    /** The first words of a range, for the library list. */
    private fun excerptFor(from: VerseId, to: VerseId): String =
        verses.filter { it.id in from..to }.joinToString(" ") { it.text }.take(140)

    /** Paint [verses] in [color], or clear them when it is null. */
    /**
     * Bookmark a verse, or take the bookmark off if it is already there.
     *
     * One tap, saved at once: a bookmark is a thing you drop while reading, and asking which
     * colour first turns a reflex into a decision. The colour and the note are second
     * thoughts, offered after the fact on a verse that is already kept.
     *
     * Returns what was there before, so the action can be undone.
     */
    fun toggle(verse: VerseId): MarkColor? {
        val existing = markAt(verse)
        val had = existing?.let { MarkColor.of(it.color) }
        val note = noteOf(verse)
        // Tapping inside a range takes the whole range off, not a hole out of the middle.
        val lo = existing?.start ?: verse
        val hi = existing?.end ?: verse
        if (had != null && note == null) markRange(lo, hi, null)
        else markRange(lo, hi, had ?: MarkColor.Gold)
        return had
    }

    /** Put a bookmark back as it was, for undo. */
    fun restore(verse: VerseId, color: MarkColor?) {
        mark(setOf(verse), color)
    }

    /**
     * Mark a span as one thing, from [from] word [fromWord] to [to] word [toWord].
     *
     * One mark, not one per verse: a thought that runs across three verses is a single thing
     * to colour, to note and to take away again. [toWord] of -1 means "to the end of [to]".
     *
     * Any mark already overlapping the span is swallowed, so marking over the top of an older
     * highlight replaces it rather than layering on it. A note on a swallowed mark is kept.
     */
    fun markRange(
        from: VerseId, to: VerseId, color: MarkColor?,
        fromWord: Int = 0, toWord: Int = -1,
    ) {
        val lo = minOf(from, to)
        val hi = maxOf(from, to)
        val overlapping = bible.marks(lo.book, lo.chapter).filter { it.start <= hi && it.end >= lo }
        val keptNote = overlapping.firstNotNullOfOrNull { it.note?.takeIf { n -> n.isNotBlank() } }

        if (color == null) {
            // Taking a colour off a noted span keeps the note, and with it the mark.
            overlapping.forEach { m ->
                if (!m.note.isNullOrBlank()) bible.saveMark(m.copy(color = MarkColor.Gold.name.lowercase()))
                else bible.deleteMark(m.id)
            }
        } else {
            overlapping.forEach { bible.deleteMark(it.id) }
            bible.saveMark(
                Mark(
                    id = 0,
                    start = lo, startWord = fromWord,
                    end = hi, endWord = toWord,
                    color = color.name.lowercase(),
                    note = keptNote,
                    excerpt = excerptFor(lo, hi),
                )
            )
        }
        loadMarks()
    }

    /** Paint whole verses. The range form underneath does the work. */
    fun mark(verses: Set<VerseId>, color: MarkColor?) {
        if (verses.isEmpty()) return
        markRange(verses.min(), verses.max(), color)
    }

    private fun loadMarks() {
        val found = bible.marks(book, chapter)
        marks = found
        // Spread each range across every verse it touches, so a lookup is one map hit.
        val map = HashMap<VerseId, MutableList<Mark>>()
        for (m in found) {
            for (verse in verses) {
                if (verse.id in m.start..m.end) map.getOrPut(verse.id) { mutableListOf() }.add(m)
            }
        }
        byVerse = map
        // A note is drawn once, after the last verse of its mark that this page shows.
        val ending = HashMap<VerseId, MutableList<Pair<Mark, VerseId>>>()
        for (m in found) {
            if (m.note.isNullOrBlank()) continue
            val covered = verses.filter { it.id in m.start..m.end }
            val first = covered.firstOrNull()?.id ?: continue
            val last = covered.last().id
            ending.getOrPut(last) { mutableListOf() }.add(m to first)
        }
        notesEnding = ending
        // Notes are rows of their own, so writing or clearing one changes the layout of the
        // chapter rather than just the colour of a verse. Relaid out here, after byVerse is
        // in place, because that is what rebuild() reads the notes from.
        rebuild()
    }

    /**
     * Where the reader has been, most recent last.
     *
     * Following a cross-reference or a commentary link takes you somewhere else entirely, and
     * without this the way back is gone. Each entry is a chapter and the verse that was being
     * looked at, so returning lands on the verse rather than the top of the page.
     */
    private val history = ArrayDeque<Triple<Int, Int, Int>>()

    /**
     * The verse NUMBER to scroll to once the page has drawn, consumed by the reader.
     *
     * Starts at the verse that was on screen last time, so a launch lands where reading
     * stopped rather than at the top of the chapter.
     */
    var pending by mutableIntStateOf(opening?.third ?: 0)
        private set

    fun consumePending(): Int = pending.also { pending = 0 }

    /** True when there is somewhere to go back to. */
    val canGoBack: Boolean get() = history.isNotEmpty()

    /** How many steps back there are: a place can be told from the ones reached after it. */
    val depth: Int get() = history.size

    /** A short label for the back control: "Psalm 23:4". */
    fun backLabel(): String? = history.lastOrNull()?.let { (b, c, v) ->
        val name = bible.books.getOrNull(b - 1)?.name ?: return@let null
        if (v > 0) "$name $c:$v" else "$name $c"
    }

    /**
     * Open a chapter.
     *
     * [remember] records where we were, so this can be stepped back out of; the back step
     * itself passes false, or going back would push the place we are leaving.
     * [at] is a verse to land on.
     */
    fun go(book: Int, chapter: Int, at: Int = 0, remember: Boolean = true) {
        if (remember && (book != this.book || chapter != this.chapter)) {
            history.addLast(Triple(this.book, this.chapter, topVerse))
            // A reading life is long; a few dozen steps back is more than anyone walks.
            while (history.size > 50) history.removeFirst()
        }
        this.book = book
        this.chapter = chapter
        load()
        pending = at
        remember()
    }

    /** Return to where the last link was followed from. Returns false when there is nowhere. */
    fun back(): Boolean {
        val (b, c, v) = history.removeLastOrNull() ?: return false
        go(b, c, at = v, remember = false)
        return true
    }

    /**
     * The verse NUMBER at the top of the screen, so going back returns to the place, not
     * the page. A number, like [pending], because it becomes one: through the history and
     * the saved place it comes back as the verse to land on.
     *
     * Also what gets written down: coming back to the chapter but the top of it would still
     * lose your place in a long one.
     */
    var topVerse = 0
        set(v) { field = v; remember() }

    /** Write down where we are, cheaply enough to do on every scroll. */
    private fun remember() = place?.save(book, chapter, topVerse)

    fun nextChapter() {
        val b = bible.books.getOrNull(book - 1) ?: return
        if (chapter < b.chapters) go(book, chapter + 1)
        else if (book < bible.books.size) go(book + 1, 1)
    }

    fun previousChapter() {
        if (chapter > 1) go(book, chapter - 1)
        else if (book > 1) {
            val prev = bible.books[book - 2]
            go(book - 1, prev.chapters)
        }
    }

    private fun load() {
        val vs = bible.chapter(book, chapter)
        verses = vs
        // The chapter changed, so last chapter's commentary must not show against it.
        commentary = emptyList(); refs = emptyMap(); studyFor = null
        loadMarks()          // lays the chapter out; notes are rows, so they must be known
        if (study) fetchStudy()
    }

    /**
     * Lay the chapter out, interleaving whatever study material is switched on.
     *
     * Order under a verse is deliberate: the reader's own note first, because it is theirs
     * and they are looking for it; then the translators' footnote, which is about the text
     * itself; then commentary, which is someone's opinion of it; then the cross-references,
     * which lead away from the page and so belong last.
     *
     * A note or a commentary paragraph that covers several verses is drawn ONCE, under the
     * last of them, and every row from its first verse down to it carries its bracket in
     * the margin — see [Margin]. The bracket is worked out here, row by row, because only
     * the layout knows what sits between a span's first verse and its last.
     */
    private fun rebuild() {
        val out = mutableListOf<Row>()
        // The reader's notes on this page. Overlapping marks swallow each other, so at most
        // one covers any verse.
        val noted = marks.filter { !it.note.isNullOrBlank() }
        // Commentary that reaches past one verse: the only kind that wants a bracket.
        val long = if (study) commentary.filter { layers.shows(it.source) && it.start != it.end }
                   else emptyList()
        val ending = if (study) commentary.filter { layers.shows(it.source) }.groupBy { it.end }
                     else emptyMap()
        // Paragraphs whose quoted words are placed in a verse (Wesley), by that verse.
        val glossing = if (study) commentary.filter { layers.shows(it.source) && it.anchors.isNotEmpty() }
                           .groupBy { it.start }
                       else emptyMap()
        // The note lane at verse [v]: the colour of a note covering it, when the note also
        // reaches past it ([beyond]) or merely reaches it.
        fun noteLane(v: VerseId, beyond: Boolean): MarkColor? = noted
            .lastOrNull { it.start <= v && (if (beyond) it.end > v else it.end >= v) }
            ?.let { MarkColor.of(it.color) }
        fun commentLane(v: VerseId, beyond: Boolean): Boolean = long
            .any { it.start <= v && (if (beyond) it.end > v else it.end >= v) }

        verses.forEachIndexed { i, v ->
            // A heading between two verses carries whatever spans both of them.
            val prev = verses.getOrNull(i - 1)?.id
            val above = if (prev != null) Margin(noteLane(prev, true), commentLane(prev, true))
                        else Margin()
            v.heading?.let { out += Row.Heading(it, "h$i", above) }
            v.subheading?.let { out += Row.Heading(it, "s$i", above) }
            v.psalmTitle?.let { out += Row.Title(it, "t$i", above) }

            val words = splitWords(v.text)
            val showNotes = study && layers.footnotes
            out += Row.Verse(
                v, i, words, "v${v.id}",
                margin = Margin(noteLane(v.id, false), commentLane(v.id, false)),
                noteAnchors = if (showNotes) v.footnotes.map { it.words } else emptyList(),
                glossAnchors = glossing[v.id].orEmpty().flatMap { it.anchors.values },
            )

            // A note is the reader's own writing: it shows whether or not study mode is on.
            // Hiding someone's own words behind a mode would be the wrong way round. It is
            // drawn ONCE, under the last verse of its span — not under each verse in it.
            // It closes its own lane; only commentary passes through it.
            notesEnding[v.id]?.forEach { (m, first) ->
                out += Row.Note(
                    verse = v.id, start = first, text = m.note!!,
                    colour = MarkColor.of(m.color) ?: MarkColor.Gold,
                    key = "n${m.id}",
                    margin = Margin(null, commentLane(v.id, false)),
                )
            }
            if (!study) return@forEachIndexed
            if (showNotes)
                v.footnotes.forEachIndexed { k, f ->
                    out += Row.Footnote(
                        v.id, f.text, k,
                        phrase = f.words?.let { r ->
                            words.slice(r.first.coerceIn(words.indices)..r.last.coerceIn(words.indices))
                                .joinToString(" ").trim { !it.isLetterOrDigit() }
                        },
                        key = "f${v.id}_$k",
                        margin = Margin(noteLane(v.id, true), commentLane(v.id, false)),
                    )
                }
            val here = ending[v.id].orEmpty()
            here.forEachIndexed { k, c ->
                // The lane passes through this paragraph if another span is still open:
                // one reaching past this verse, or one ending here but drawn after it.
                val still = commentLane(v.id, true) ||
                    here.drop(k + 1).any { it.start != it.end }
                out += Row.Comment(
                    v.id, c.start, c.source, c.body, "c${c.start}_${c.source}",
                    margin = Margin(noteLane(v.id, true), still),
                    anchored = c.anchors.keys,
                )
            }
            if (layers.refs)
                refs[v.id]?.takeIf { it.isNotEmpty() }?.let {
                    out += Row.Refs(v.id, it, "x${v.id}",
                        Margin(noteLane(v.id, true), commentLane(v.id, true)))
                }
        }
        rows = out
    }

    // ------------------------------------------------------------------ study mode

    /** Whether the chapter is laid out for study. Persisted by [Prefs]. */
    var study by mutableStateOf(false)
        private set

    /** Which study layers are shown. Notes are not among them: those always show. */
    var layers by mutableStateOf(StudyLayers())
        private set

    /** True while the chapter's commentary is being read off disk. */
    var studyLoading by mutableStateOf(false)
        private set

    private var commentary by mutableStateOf<List<CommentSpan>>(emptyList())
    private var refs by mutableStateOf<Map<VerseId, List<CrossRef>>>(emptyMap())

    /** The chapter the loaded study material belongs to, so it is fetched once. */
    private var studyFor: Pair<Int, Int>? = null

    /** Runs the disk read. Set by the UI, which owns a coroutine scope. */
    var background: ((suspend () -> Unit) -> Unit)? = null

    /** Put study mode back as it was left, on launch. */
    fun restoreStudy(on: Boolean, l: StudyLayers) {
        layers = l
        if (on) { study = true; fetchStudy() }
    }

    fun chooseStudy(on: Boolean) {
        if (study == on) return
        study = on
        if (on && studyFor != (book to chapter)) fetchStudy() else rebuild()
    }

    fun chooseLayers(l: StudyLayers) {
        if (layers == l) return
        layers = l
        rebuild()
    }

    /**
     * Read the chapter's commentary and cross-references.
     *
     * Off the main thread: Matthew Henry on a long chapter is a few hundred KB of text, and
     * the scroll must not stop for it. The page shows without the study layers until it
     * lands, which is better than a blank chapter.
     */
    private fun fetchStudy() {
        val want = book to chapter
        val go = background
        if (go == null) { rebuild(); return }
        studyLoading = true
        go {
            val c = bible.commentaryFor(want.first, want.second)
            val x = bible.crossRefsFor(want.first, want.second)
            // The reader may have turned the page while this was reading from disk. A
            // stale fetch must not clear the flag either: the fetch for the new page is
            // still running, and a landing that waits on the flag would stop waiting.
            if (want == (book to chapter)) {
                commentary = c; refs = x; studyFor = want
                rebuild()
                studyLoading = false
            }
        }
    }

    /** Which row holds the verse at position [index] in the chapter (0-based, the narrator's unit). */
    fun rowOfVerse(index: Int): Int =
        rows.indexOfFirst { it is Row.Verse && it.index == index }

    /**
     * Which row holds verse NUMBER [n] — "16" in John 3:16.
     *
     * Not the same as [rowOfVerse]: a number is one more than an index, and more than that
     * where the BSB omits a verse. Jumps, links and the saved place all speak in numbers;
     * landing them through the index put every one of them a verse too far down the page.
     */
    fun rowOfVerseNumber(n: Int): Int =
        rows.indexOfFirst { it is Row.Verse && it.verse.id.verse == n }

    /**
     * What the narrator reads, in order.
     *
     * In study mode the commentary and the reader's own notes are read too, in the order
     * they sit on the page — the point of reading a study Bible aloud is to hear the study,
     * not to hear the verses and have the notes silently skipped. Cross-references are left
     * out: "Psalm 27 verse 1" read aloud is a string of numbers, not something to listen to.
     */
    fun narrationLines(): List<Narrator.Line> = buildList {
        // A heading belongs to the verse BELOW it, which has not been seen yet when the
        // heading goes by — hold it and hand it to the next verse as its preamble. Without
        // this the voice read straight past "Paying Taxes to Caesar" into the verse, and the
        // listener lost the one cue that a new passage had started.
        var pendingHeading: String? = null
        rows.forEach { row ->
            when (row) {
                is Row.Heading -> {
                    pendingHeading = pendingHeading?.let { "$it. ${row.text}" } ?: row.text
                }
                is Row.Verse -> {
                    val head = pendingHeading
                    pendingHeading = null
                    if (head != null) {
                        // Its own line, so it is spoken before the verse and the highlight
                        // has not started running yet.
                        add(Narrator.Line(row.index, splitWords(head), head, aside = true))
                    }
                    add(Narrator.Line(row.index, row.words, row.verse.text))
                }
                is Row.Note -> verseIndexOf(row.verse)?.let { v ->
                    add(Narrator.Line(v, splitWords(row.text), row.text,
                        aside = true, preamble = "Your note."))
                }
                is Row.Comment -> verseIndexOf(row.verse)?.let { v ->
                    // Cleaned for the ear here, not at synthesis: the page keeps its
                    // citations, which are useful to look at, and the voice drops them.
                    val said = Narrator.listenable(commentaryBody(row.body))
                    if (said.isNotBlank())
                        add(Narrator.Line(v, splitWords(said), said,
                            aside = true, preamble = commentaryName(row.source) + " writes."))
                }
                else -> Unit
            }
        }
    }

    /** The verse's position in the chapter, for a verse id. */
    private fun verseIndexOf(id: VerseId): Int? =
        rows.filterIsInstance<Row.Verse>().firstOrNull { it.verse.id == id }?.index

    /**
     * Which narration line starts verse [index].
     *
     * Callers know verses, not lines, but study mode puts several lines against one verse,
     * so reading "from here" has to be translated into a position in the script.
     *
     * The FIRST line carrying the verse, not the first scripture line: a section heading is
     * an aside that sits in front of its verse, and reading from the verse that opens a
     * section should open with the heading — "The First Day" before "And God said". Skipping
     * asides here is what made the headings seem never to be read: the chapter is mostly
     * started from a verse, not from the top. A previous verse's commentary cannot be caught
     * by this, because it carries that verse's index.
     */
    fun lineOfVerse(index: Int): Int =
        narrationLines().indexOfFirst { it.verse == index }.coerceAtLeast(0)

    /**
     * Open the chapter.
     *
     * This must stay the LAST thing in the class. Kotlin runs initialisers in declaration
     * order, so an `init` placed above a property sees that property as null — and `load()`
     * reaches most of the state here, the study maps included. Anything added below this
     * line will crash the first chapter load, which is how this was found.
     */
    init { load() }

    companion object {
        /** Display words. Punctuation stays attached so the line breaks read naturally. */
        fun splitWords(text: String): List<String> =
            text.split(' ').filter { it.isNotBlank() }
    }
}
