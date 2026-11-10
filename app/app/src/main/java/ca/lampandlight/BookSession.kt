package ca.lampandlight

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ca.lampandlight.Epub.Kind

/**
 * One book, open: the chapter on the page, ready to be drawn and to be read aloud.
 *
 * [Epub] says what a page is made of. This is where it meets the Bible: every reference in
 * the prose is looked up, so only passages that exist are offered as links; the paragraph
 * standing over a reference is recognised as the quotation it is; and each block gets the
 * words the narrator will say, which are not always the words printed — "Genesis 3:8-9
 * (ESV)" is said "Genesis 3, verses 8 to 9".
 */
class BookSession private constructor(
    val id: String,
    val title: String,
    val author: String,
    val sections: List<Epub.Section>,
    private val epub: Epub,
    private val library: Library,
    private val bible: Bible,
) {
    /**
     * A block as the page shows it.
     *
     * [spans] is where each word sits in the block's text; [spoken] is what is said for
     * that word, index for index, so the narrator's place maps straight back onto the page.
     * A word that is not said — the rest of a reference, a translation's initials — is
     * blank there and still has its place here.
     */
    class Piece(
        val block: Epub.Block,
        val passages: List<Citations.Passage>,
        val spans: List<IntRange>,
        val spoken: List<String>,
        val preamble: String?,
        /** For a quotation: whether it carries on into the block below, or began above. */
        val joinsNext: Boolean = false,
        val joinsPrev: Boolean = false,
    ) {
        val kind: Kind get() = block.kind
        val words: Int get() = spans.size
    }

    var index by mutableIntStateOf(-1); private set
    var pieces by mutableStateOf<List<Piece>>(emptyList()); private set
    var loading by mutableStateOf(false); private set

    /** A block to land on once the section is laid out; -1 when there is none. */
    var pending by mutableIntStateOf(-1); private set
    fun consumePending(): Int = pending.also { pending = -1 }

    private val lock = Mutex()
    private val images = LruCache<String, ImageBitmap>(6)

    /** Words in the book before each section, for saying how far through a place is. */
    private val before = IntArray(sections.size + 1).also { a ->
        sections.forEachIndexed { i, s -> a[i + 1] = a[i] + s.words }
    }
    val totalWords: Int get() = before[sections.size].coerceAtLeast(1)

    val section: Epub.Section? get() = sections.getOrNull(index)
    val hasNext: Boolean get() = index < sections.lastIndex
    val hasPrev: Boolean get() = index > 0

    /** Open section [i], to land on block [at]. */
    suspend fun go(i: Int, at: Int = 0) {
        val want = i.coerceIn(0, sections.lastIndex)
        loading = true
        val made = withContext(Dispatchers.IO) {
            lock.withLock { runCatching { build(epub.blocks(sections[want])) }.getOrElse { emptyList() } }
        }
        pieces = made
        index = want
        pending = at.coerceIn(0, (made.size - 1).coerceAtLeast(0))
        loading = false
        save(pending)
    }

    /** How far through the book the top of block [block] of this section is, 0 to 1. */
    fun progressAt(block: Int): Float {
        if (index < 0) return 0f
        var words = before[index]
        for (k in 0 until block.coerceAtMost(pieces.size)) words += pieces[k].words
        return (words.toFloat() / totalWords).coerceIn(0f, 1f)
    }

    /** Words from block [block] to the end of this section, and to the end of the book. */
    fun wordsLeft(block: Int): Pair<Int, Int> {
        if (index < 0) return 0 to 0
        var here = 0
        for (k in block.coerceAtLeast(0) until pieces.size) here += pieces[k].words
        return here to here + (totalWords - before[index + 1])
    }

    fun save(block: Int) {
        if (index < 0) return
        // The last page of the last chapter is the end of the book, whatever its word count.
        val done = !hasNext && block >= pieces.lastIndex
        library.place(id, index, block, if (done) 1f else progressAt(block))
    }

    /** The section as a script: one line a block, so a line's number is the block's. */
    fun lines(): List<Narrator.Line> = pieces.mapIndexed { i, p ->
        Narrator.Line(verse = i, words = p.spoken, text = p.block.text, preamble = p.preamble)
    }

    /** A picture from the book, sized for a phone. */
    suspend fun image(src: String): ImageBitmap? {
        images.get(src)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = epub.bytes(src, 16 shl 20) ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 1200) sample *= 2
                BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample },
                )?.asImageBitmap()
            }.getOrNull()
        }?.also { images.put(src, it) }
    }

    fun close() { runCatching { epub.close() } }

    // ------------------------------------------------------------------ building a page

    private fun build(raw: List<Epub.Block>): List<Piece> {
        val books = bible.books
        val found = raw.map { b ->
            if (b.text.isEmpty() || b.kind == Kind.Image || b.kind == Kind.Break) emptyList()
            else Citations.inProse(b.text, books).filter { real(it) }
        }
        val kinds = raw.map { it.kind }.toMutableList()

        for (i in raw.indices) {
            // A line that looked like a reference and names no passage that exists is prose.
            if (kinds[i] == Kind.Cite && found[i].isEmpty()) kinds[i] = Kind.Para
            // And one the parser passed over — "Exodus 26:33-35 (NIV-MIT)" — is a reference
            // all the same, when nothing much is left of the line once the passage is out.
            else if ((kinds[i] == Kind.Para || kinds[i] == Kind.Lead) && found[i].isNotEmpty() &&
                raw[i].text.length <= 90 && leftover(raw[i].text, found[i]) <= 8) kinds[i] = Kind.Cite
        }

        // What stands over a reference is the quotation. Most books mark it; the ones that
        // do not — every quotation an ordinary paragraph — are read by position, and where
        // a quotation runs to several paragraphs, by whether the words above are the
        // passage's own.
        for (i in raw.indices) {
            if (kinds[i] != Kind.Cite) continue
            val prev = i - 1
            if (prev >= 0 && kinds[prev] == Kind.Quote) continue
            val passage = found[i].first()
            // "As Paul wrote to them:" above a reference introduces it and is the author's.
            // But a verse can end on a colon itself — Hebrews 9:24 does in the King James —
            // so a paragraph ending that way is still the quotation when its words are the
            // passage's own.
            val eligible = prev >= 0 && (kinds[prev] == Kind.Para || kinds[prev] == Kind.Lead) &&
                (!raw[prev].text.trimEnd().endsWith(":") ||
                    overlap(raw[prev].text, vocabulary(passage).first) >= 0.34f)
            if (eligible) {
                kinds[prev] = Kind.Quote
                val theirs by lazy { vocabulary(passage) }
                var quoted = raw[prev].words
                var j = prev - 1
                while (j >= 0 && (kinds[j] == Kind.Para || kinds[j] == Kind.Lead)) {
                    val (set, size) = theirs
                    if (size == 0 || quoted >= size * 0.62f) break
                    if (overlap(raw[j].text, set) < 0.34f) break
                    kinds[j] = Kind.Quote
                    quoted += raw[j].words
                    j--
                }
            } else if (i + 1 < raw.size && kinds[i + 1] == Kind.Para) {
                // The reference first and the words after it, as some books set them.
                val (set, size) = vocabulary(passage)
                if (size > 0 && overlap(raw[i + 1].text, set) >= 0.4f) kinds[i + 1] = Kind.Quote
            }
        }

        return raw.indices.map { i ->
            val block = if (kinds[i] == raw[i].kind) raw[i] else raw[i].copy(kind = kinds[i])
            val quoteish = kinds[i] == Kind.Quote
            piece(
                block, found[i],
                joinsNext = quoteish && (kinds.getOrNull(i + 1) == Kind.Quote || kinds.getOrNull(i + 1) == Kind.Cite),
                joinsPrev = (quoteish || kinds[i] == Kind.Cite) && kinds.getOrNull(i - 1) == Kind.Quote,
            )
        }
    }

    /** Whether the passage is in the Bible: "Psalm 151:3" and "John 3:99" are not links. */
    private fun real(p: Citations.Passage): Boolean = runCatching {
        val r = p.ranges.first()
        val book = bible.books.getOrNull(r.first.book - 1) ?: return false
        if (r.first.chapter !in 1..book.chapters) return false
        bible.passage(r.first, r.first).isNotEmpty() || p.wholeChapter
    }.getOrDefault(false)

    /** The letters of [text] that are not part of a reference or its translation's name. */
    private fun leftover(text: String, passages: List<Citations.Passage>): Int {
        val covered = BooleanArray(text.length)
        for (p in passages) for (k in p.start until maxOf(p.end, p.mute).coerceAtMost(text.length)) covered[k] = true
        return text.indices.count { !covered[it] && text[it].isLetterOrDigit() }
    }

    /** The weightier words of a passage as this Bible has it, and how many words it runs to. */
    private fun vocabulary(p: Citations.Passage): Pair<Set<String>, Int> {
        val set = HashSet<String>()
        var count = 0
        for (r in p.ranges) {
            val verses = runCatching { bible.passage(r.first, r.last) }.getOrElse { emptyList() }
            for ((_, text) in verses) {
                val words = text.split(' ')
                count += words.size
                for (w in words) stem(w)?.let { set += it }
            }
        }
        return set to count
    }

    /**
     * How much of a paragraph's vocabulary is the passage's. The book quotes the King James
     * or the ESV and the passage here is the BSB, so the words are compared, not the
     * sentences: two translations of one verse share its nouns and its names, and an
     * author's own paragraph about that verse shares far fewer.
     */
    private fun overlap(text: String, passage: Set<String>): Float {
        val mine = text.split(' ').mapNotNull { stem(it) }.toSet()
        if (mine.size < 4) return 0f
        return mine.count { it in passage }.toFloat() / mine.size
    }

    private fun stem(word: String): String? {
        val w = word.lowercase().filter { it in 'a'..'z' }
        if (w.length < 4 || w in COMMON) return null
        // Crude, and enough: "pillars" and "pillar", "sockets" and "socket".
        return if (w.length > 4 && w.endsWith("s")) w.dropLast(1) else w
    }

    private fun piece(
        block: Epub.Block, passages: List<Citations.Passage>, joinsNext: Boolean, joinsPrev: Boolean,
    ): Piece {
        val text = block.text
        val spans = WORD.findAll(text).map { it.range }.toList()
        val spoken = spans.map { text.substring(it.first, it.last + 1) }.toMutableList()
        // A reference line says its references and nothing else: the dash before it, the
        // publisher's name for a translation, are the page's business.
        if (block.kind == Kind.Cite) spoken.indices.forEach { spoken[it] = "" }

        for (p in passages) {
            val end = maxOf(p.end, p.mute)
            val first = spans.indexOfFirst { it.last >= p.start }
            val last = spans.indexOfLast { it.first < end }
            if (first < 0 || last < first) continue
            // Punctuation after the reference still shapes the sentence it sits in.
            val tailFrom = (end - spans[last].first).coerceIn(0, spans[last].last - spans[last].first + 1)
            val tail = text.substring(spans[last].first + tailFrom, spans[last].last + 1)
                .firstOrNull { it in ".,;:!?" }?.toString().orEmpty()
            // Said in the LAST word's place, the others blank: a clause ends on the word
            // that carries its stop, so the lit span closes where the reference does.
            for (k in first..last) spoken[k] = ""
            spoken[last] = Citations.spoken(p, bible.books) + tail
        }

        // A heading has no full stop and needs one aloud, or it runs into the paragraph.
        // The same goes for a point, and for a quotation the author trailed off.
        val closing = spoken.indexOfLast { it.isNotBlank() }
        if (closing >= 0 && spoken[closing].last().isLetterOrDigit()) spoken[closing] = spoken[closing] + "."

        val preamble = when (block.kind) {
            Kind.Point -> block.marker?.let { sayMarker(it) }
            Kind.Title -> block.marker?.let { m -> m.trimEnd('.', ':', ' ') + "." }
            else -> null
        }
        return Piece(block, passages, spans, spoken, preamble?.takeIf { spans.isNotEmpty() }, joinsNext, joinsPrev)
    }

    companion object {
        private val WORD = Regex("""\S+""")
        private val COMMON = setOf(
            "that", "this", "with", "have", "from", "they", "them", "then", "were", "will", "your",
            "shall", "unto", "thou", "thee", "there", "their", "which", "when", "what", "said",
            "into", "upon", "also", "shalt", "hath", "these", "those", "been", "would", "could",
        )
        private val UNITS = listOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
        )
        private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

        fun number(n: Int): String = when {
            n < 20 -> UNITS[n]
            n < 100 -> TENS[n / 10] + if (n % 10 > 0) " " + UNITS[n % 10] else ""
            else -> n.toString()
        }

        /** A point's number as it is said: "3." is "Three.", "iv)" is "Four.", a bullet is nothing. */
        fun sayMarker(marker: String): String? {
            val core = marker.trim().trimEnd('.', ')')
            core.toIntOrNull()?.let { n ->
                return if (n in 0..99) number(n).replaceFirstChar { it.uppercase() } + "." else "$n."
            }
            if (core.isNotEmpty() && core.all { it in "ivx" }) {
                var total = 0
                var prev = 0
                for (c in core.reversed()) {
                    val v = when (c) { 'i' -> 1; 'v' -> 5; else -> 10 }
                    if (v < prev) total -= v else { total += v; prev = v }
                }
                if (total in 1..39) return number(total).replaceFirstChar { it.uppercase() } + "."
            }
            if (core.length == 1 && core[0].isLetter()) return core.uppercase() + "."
            return null
        }

        /** Open the book [id] off the main thread; null when its file has gone or will not read. */
        suspend fun open(id: String, library: Library, bible: Bible): BookSession? =
            withContext(Dispatchers.IO) {
                runCatching {
                    library.refresh(id)
                    val v = library.volume(id) ?: return@runCatching null
                    val sections = library.sections(id)
                    if (sections.isEmpty()) return@runCatching null
                    BookSession(id, v.title, v.author, sections, Epub(library.file(id)), library, bible)
                }.getOrNull()
            }
    }
}
