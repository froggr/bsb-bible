package ca.lampandlight

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/**
 * Scripture cited inside commentary, made into links.
 *
 * The commentaries cite in different hands. Tyndale: `Exod 28:41-43; 29:9`, `1 Sam
 * 17:12-58`, and a bare `1:3–2:3` meaning this book. Henry: `Lam. iv. 10` with the chapter
 * in roman numerals, `ch. xii. 3`, and `v. 57` meaning this chapter. Jamieson-Fausset-Brown:
 * `Lu 13:6`, `Ge 3:1-5`, `Is 5:2`, in the shortest abbreviations anyone has used. Wesley:
 * `Ge 5:4`, `Tit 1:6-9`, `chap.25:8`, much as JFB. All of them run continuations off one
 * book name with semicolons.
 *
 * A citation is recognised by shape — a capitalised word (or `ch.`/`v.`), a chapter, a
 * verse — and then the book is resolved through [Reference], the same parser the search box
 * uses, so `Lu`, `Is` and `Ge` land where a reader typing them would land. Anything that
 * does not resolve to exactly one book is left as plain text rather than linked wrongly:
 * a wrong link is worse than none.
 *
 * Only the first verse of a range is linked to, because that is where you want to land.
 */
object Citations {

    /** A link found in the text: where it sits, and where it goes. */
    data class Link(val start: Int, val end: Int, val target: VerseId)

    // A book name with an optional ordinal, then chapter:verse. The chapter may be roman
    // (Henry). The name is one capitalised word of two or more letters, optionally dotted,
    // so "Lu 13:6", "Lam. iv. 10", "1 Sam 17:12" and "Exod 28:41" all match. "ch." and "v."
    // are handled by their own patterns below.
    private val BOOKED = Regex(
        """(?<![A-Za-z])([1-3]\s?)?([A-Z][a-z]{1,10})\.?\s+([1-9]\d{0,2}|[ivxlc]{1,7})\s*[:.]\s*([1-9]\d{0,2})(?:\s*[-–]\s*\d{1,3}(?::\d{1,3})?)?"""
    )
    // "; 29:9" or "; 18:17-30" directly after a citation: same book.
    private val CONTINUED = Regex("""\G\s*;\s*([1-9]\d{0,2}):([1-9]\d{0,2})(?:\s*[-–]\s*\d{1,3}(?::\d{1,3})?)?""")
    // Henry: "ch. xii. 3", "chap. 12. 3" — this book. The verse follows its own separator,
    // so "ch. xii. The" is chapter twelve and a full stop.
    private val CHAPTER = Regex("""(?<![A-Za-z])ch(?:ap)?\.\s*([1-9]\d{0,2}|[ivxlc]{1,7})(?:\s*[:.]\s*([1-9]\d{0,2})(?!\d))?""")
    // Henry: "v. 57", "ver. 21" — this chapter.
    private val VERSE = Regex("""(?<![A-Za-z])v(?:er)?\.\s*([1-9]\d{0,2})(?![:.]?\d)""")
    // Tyndale: a bare "1:3–2:3" or "(2:7)" — this book. Chapter and verse start at 1, which
    // keeps "3:00 pm" out. Not preceded by a digit or letter, so
    // the second half of "28:41-43" and the tail of a booked citation are not re-matched.
    private val BARE = Regex("""(?<![A-Za-z0-9:\-–])([1-9]\d{0,2}):([1-9]\d{0,2})(?:\s*[-–]\s*\d{1,3}(?::\d{1,3})?)?""")

    /**
     * Find every citation in [text], read against [verse] — the verse the commentary hangs
     * from, which is what "this book" and "this chapter" mean.
     */
    fun find(text: String, verse: VerseId, books: List<Book>): List<Link> {
        val out = mutableListOf<Link>()
        val taken = BooleanArray(text.length)
        fun claim(start: Int, end: Int, target: VerseId?) {
            if (target == null || end <= start) return
            if ((start until end).any { taken[it] }) return
            for (i in start until end) taken[i] = true
            out += Link(start, end, target)
        }

        // Booked citations first, with their continuations, so that "29:9" after "Exod
        // 28:41-43;" is Exodus and not the current book.
        for (m in BOOKED.findAll(text)) {
            val (ordinal, name, chText, vText) = m.destructured
            val chapter = number(chText) ?: continue
            val book = bookOf(ordinal.trim() + name, chapter, books) ?: continue
            claim(m.range.first, m.range.last + 1, verseId(book.id, chapter, vText.toInt()))
            var at = m.range.last + 1
            while (true) {
                val c = CONTINUED.find(text, at) ?: break
                if (c.range.first != at) break
                val ch = c.groupValues[1].toInt()
                if (ch > book.chapters) break
                claim(c.range.first, c.range.last + 1, verseId(book.id, ch, c.groupValues[2].toInt()))
                at = c.range.last + 1
            }
        }
        val here = books.getOrNull(verse.book - 1)
        for (m in CHAPTER.findAll(text)) {
            val ch = number(m.groupValues[1]) ?: continue
            if (here == null || ch > here.chapters) continue
            val v = m.groupValues[2].toIntOrNull() ?: 1
            claim(m.range.first, m.range.last + 1, verseId(verse.book, ch, v))
        }
        for (m in VERSE.findAll(text)) {
            claim(m.range.first, m.range.last + 1,
                verseId(verse.book, verse.chapter, m.groupValues[1].toInt()))
        }
        for (m in BARE.findAll(text)) {
            val ch = m.groupValues[1].toInt()
            if (here == null || ch > here.chapters) continue
            claim(m.range.first, m.range.last + 1, verseId(verse.book, ch, m.groupValues[2].toInt()))
        }
        return out.sortedBy { it.start }
    }

    /** The one book an abbreviation means, or null when it means none or several. */
    private fun bookOf(name: String, chapter: Int, books: List<Book>): Book? {
        val hits = Reference.parse("$name $chapter", books)
        val exact = hits.filter { it.exact }
        val pick = when {
            exact.size == 1 -> exact[0]
            hits.size == 1 -> hits[0]
            else -> return null
        }
        return pick.book
    }

    /** "12" or "xii" as a number. */
    private fun number(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        var total = 0; var prev = 0
        for (c in s.reversed()) {
            val v = when (c) { 'i' -> 1; 'v' -> 5; 'x' -> 10; 'l' -> 50; 'c' -> 100; else -> return null }
            total += if (v < prev) -v else v
            prev = v
        }
        return total.takeIf { it > 0 }
    }

    /** The annotation tag carrying a citation's target verse id, for [targetAt]. */
    const val TAG = "cite"

    /**
     * [text] with its citations coloured [colour] and annotated with their targets.
     *
     * Not Compose's `LinkAnnotation`: that makes the text's layout compute a path for
     * every link on every measure pass, and a Matthew Henry paragraph carries hundreds of
     * citations — on a Pixel 9 that held the main thread past the five-second ANR limit
     * (0.41, the first phone it was seen on). A coloured span costs nothing at layout; the
     * tap is resolved afterwards from the laid-out text with [targetAt].
     */
    fun linked(text: AnnotatedString, verse: VerseId, books: List<Book>, colour: Color): AnnotatedString {
        val links = find(text.text, verse, books)
        if (links.isEmpty()) return text
        return buildAnnotatedString {
            append(text)
            for (l in links) {
                addStyle(SpanStyle(color = colour), l.start, l.end)
                addStringAnnotation(TAG, l.target.toString(), l.start, l.end)
            }
        }
    }

    /** The verse a tap at character [offset] of a [linked] text goes to, if it is on one. */
    fun targetAt(text: AnnotatedString, offset: Int): VerseId? =
        text.getStringAnnotations(TAG, offset, offset).firstOrNull()?.item?.toIntOrNull()
}
