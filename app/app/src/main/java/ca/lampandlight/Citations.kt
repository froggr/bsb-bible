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
    // What follows a citation is matched with matchAt, at the place it ended — never with
    // \G and find(text, from). \G means "where the last match ended", and Android's regex
    // engine (ICU) forgets that when find is given a starting index, where the desktop JVM
    // does not: every one of these passed its unit test and matched nothing on a phone, so
    // "Genesis 3:8-9" opened verse 8 alone.
    // "; 29:9" or "; 18:17-30" directly after a citation: same book.
    private val CONTINUED = Regex("""\s*;\s*([1-9]\d{0,2}):([1-9]\d{0,2})(?:\s*[-–]\s*\d{1,3}(?::\d{1,3})?)?""")
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
                val c = CONTINUED.matchAt(text, at) ?: break
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

    // ------------------------------------------------------------------ in a book

    /**
     * Scripture cited in a book's prose: where it sits in the text and every verse it names.
     *
     * [ranges] are spans of verse ids, in the order cited — `Numbers 4:6-7, 9, 11-12` is
     * three of them. [mute] runs past a translation's initials after the reference, "(ESV)",
     * which belong to the eye: read aloud they are noise after every quotation.
     */
    data class Passage(val start: Int, val end: Int, val ranges: List<IntRange>, val mute: Int = end) {
        val target: VerseId get() = ranges.first().first
        /** True when a whole chapter was cited, not verses of one. */
        val wholeChapter: Boolean get() = ranges.size == 1 && ranges[0].first.verse == 1 && ranges[0].last.verse == 999
    }

    private const val ORDINAL = """(?:[1-3]|III|II|I|First|Second|Third|1st|2nd|3rd)"""
    // A book's name with its ordinal, a chapter, and usually a verse. Unlike the commentary
    // pattern this one reads ordinary writing, where "He 3:16" would be a pronoun and a
    // time, so the name is checked harder below and only a colon — or a dot with no space
    // round it — may part chapter from verse.
    private val PROSE = Regex(
        """(?<![A-Za-z0-9])(?:(""" + ORDINAL + """)\s?)?(Song of (?:Solomon|Songs)|[A-Z][a-z]{1,14})\.?\s+([1-9]\d{0,2})(?!\d)(?:\s?:\s?([1-9]\d{0,2})|\.([1-9]\d{0,2}))?(?!\d)"""
    )
    private val TO = Regex("""\s?[-–—]\s?(\d{1,3})(?:\s?:\s?(\d{1,3}))?(?![\d:])""")
    private val ALSO = Regex("""\s?,\s?(\d{1,3})(?!\s?[:\d])(?!\s[A-Z][a-z]+\.?\s\d)(?:\s?[-–—]\s?(\d{1,3})(?![\d:]))?""")
    private val NEXT = Regex("""\s?;\s?([1-9]\d{0,2})\s?:\s?([1-9]\d{0,2})(?!\d)""")
    private val VERSION = Regex(
        """[\s,]*[(\[]?\s?(?:KJV|NKJV|AKJV|ESV|NIV|NLT|NASB|AMPC?|MSG|TLB|RSV|NRSV|CSB|HCSB|NET|CEV|GNT|GNB|ASV|YLT|TPT|BSB|NCV|ERV|WEB|ISV|NIRV|TEV|NEB|REB|JB|NJB|NAB)\b\s?[)\]]?"""
    )
    /** Words that are a book's abbreviation to a commentator and an ordinary word to anyone else. */
    private val WORDS = setOf(
        "he", "is", "am", "so", "no", "do", "go", "in", "on", "at", "to", "as", "an", "or", "of",
        "if", "it", "we", "me", "my", "be", "by", "up", "us", "son", "the",
    )
    private val COUNTED = Regex("""\s?(?:%|percent|years?|months?|weeks?|days?|hours?|minutes?|times?|people|men|women|things?|of\b|or\b)""")

    /**
     * Every passage cited in [text], read as ordinary prose.
     *
     * `John 3:16`, `1 Cor. 13:4-7`, `First Peter 2:9`, `Mark 16:16,17`, `Hebrews 8:4-7; 9:23`,
     * and a whole chapter when the book is named in full: `Psalm 23`. Nothing is relative to
     * a current book — a book has none — so the bare `3:16` a commentary uses is left alone.
     */
    fun inProse(text: String, books: List<Book>): List<Passage> {
        if (text.none { it.isDigit() }) return emptyList()
        val out = mutableListOf<Passage>()
        var from = 0
        while (true) {
            val m = PROSE.find(text, from) ?: break
            from = m.range.last + 1
            val ordinal = when (m.groupValues[1].lowercase()) {
                "" -> ""
                "1", "i", "first", "1st" -> "1"
                "2", "ii", "second", "2nd" -> "2"
                else -> "3"
            }
            val name = m.groupValues[2]
            val chapter = m.groupValues[3].toInt()
            val vText = m.groupValues[4].ifEmpty { m.groupValues[5] }
            val plain = name.lowercase()
            val typed = if (name.startsWith("Song of")) "song" else name
            val common = plain in WORDS
            // "I John 1:9" is First John; "I Mark 3:5" is a pronoun and then Mark.
            val numbered = if (ordinal.isEmpty()) null else bookOf(ordinal + typed, chapter, books)
            val book = numbered ?: (if (common) null else bookOf(typed, chapter, books)) ?: continue
            val start = if (numbered == null && ordinal.isNotEmpty()) m.groups[2]!!.range.first else m.range.first
            var end = m.range.last + 1

            if (vText.isEmpty()) {
                // A chapter with no verse is only taken on a book's whole name: "Psalm 23",
                // not "Ps 23" — and not "Numbers 3 times".
                val full = ((if (numbered != null) ordinal else "") + typed).filter { it.isLetterOrDigit() }.lowercase()
                val whole = book.name.filter { it.isLetterOrDigit() }.lowercase()
                val named = full == whole || full == whole + "s" || (whole == "songofsolomon" && full == "song" && typed != name)
                if (!named) continue
                if (COUNTED.matchAt(text, end) != null) continue
                out += Passage(start, end, listOf(verseId(book.id, chapter, 1)..verseId(book.id, chapter, 999)))
                continue
            }

            // One "chapter:verse", then "-9" or "-4:2" after it, at [end]. Moves [end] on.
            var verse = 0
            var chapterNow = chapter
            fun range(ch: Int, v: Int): IntRange {
                var last = verseId(book.id, ch, v)
                verse = v; chapterNow = ch
                TO.matchAt(text, end)?.let { t ->
                    val a = t.groupValues[1].toInt()
                    val b = t.groupValues[2].toIntOrNull()
                    if (b != null && a > ch && a <= book.chapters) {
                        last = verseId(book.id, a, b); end = t.range.last + 1; verse = b; chapterNow = a
                    } else if (b == null && a > v) {
                        last = verseId(book.id, ch, a); end = t.range.last + 1; verse = a
                    }
                }
                return verseId(book.id, ch, v)..last
            }
            val ranges = mutableListOf(range(chapter, vText.toInt()))
            // ", 9" and ", 11-12": more verses of the same chapter, always climbing — which
            // is what keeps "John 3:16, 2 Corinthians 5:17" from citing John 3:2.
            while (true) {
                val c = ALSO.matchAt(text, end) ?: break
                val a = c.groupValues[1].toInt()
                if (a <= verse) break
                val b = c.groupValues[2].toIntOrNull()?.takeIf { it > a }
                ranges += verseId(book.id, chapterNow, a)..verseId(book.id, chapterNow, b ?: a)
                verse = b ?: a
                end = if (b == null) c.groups[1]!!.range.last + 1 else c.range.last + 1
            }
            out += Passage(start, end, ranges)

            // "; 9:23" straight after: the same book again, a passage of its own.
            while (true) {
                val n = NEXT.matchAt(text, end) ?: break
                val ch = n.groupValues[1].toInt()
                if (ch > book.chapters) break
                end = n.range.last + 1
                // The link is the numbers, not the semicolon before them.
                val at = n.groups[1]!!.range.first
                val r = range(ch, n.groupValues[2].toInt())
                out += Passage(at, end, listOf(r))
            }
            from = maxOf(from, end)
            VERSION.matchAt(text, end)?.takeIf { it.value.isNotBlank() }?.let { v ->
                out[out.lastIndex] = out.last().copy(mute = v.range.last + 1)
            }
        }
        return out
    }

    /**
     * A passage as it is said aloud: "Genesis 3, verses 8 to 9", "First John 1, verse 9".
     * A colon and a dash are how a reference is written, not how anyone reads one out.
     */
    fun spoken(p: Passage, books: List<Book>): String {
        val book = books[p.target.book - 1]
        val name = when {
            book.name.startsWith("1 ") -> "First " + book.name.drop(2)
            book.name.startsWith("2 ") -> "Second " + book.name.drop(2)
            book.name.startsWith("3 ") -> "Third " + book.name.drop(2)
            else -> book.name
        }
        val first = p.ranges.first()
        if (p.wholeChapter) return "$name ${first.first.chapter}"
        if (first.last.chapter != first.first.chapter)
            return "$name ${first.first.chapter}, verse ${first.first.verse}, to chapter ${first.last.chapter}, verse ${first.last.verse}"
        val parts = p.ranges.map { r ->
            if (r.first == r.last) "${r.first.verse}" else "${r.first.verse} to ${r.last.verse}"
        }
        val many = p.ranges.size > 1 || first.first != first.last
        val list = when (parts.size) {
            1 -> parts[0]
            2 -> "${parts[0]} and ${parts[1]}"
            else -> parts.dropLast(1).joinToString(", ") + ", and " + parts.last()
        }
        return "$name ${first.first.chapter}, ${if (many) "verses" else "verse"} $list"
    }

    /** A passage as a title: "Numbers 4:6–7, 9, 11–12", "Psalm 23". */
    fun title(p: Passage, books: List<Book>): String {
        val book = books[p.target.book - 1]
        val first = p.ranges.first()
        if (p.wholeChapter) return "${book.name} ${first.first.chapter}"
        return "${book.name} ${first.first.chapter}:" + p.ranges.joinToString(", ") { r ->
            when {
                r.first == r.last -> "${r.first.verse}"
                r.last.chapter != r.first.chapter -> "${r.first.verse}–${r.last.chapter}:${r.last.verse}"
                else -> "${r.first.verse}–${r.last.verse}"
            }
        }
    }

    /** [Passage.ranges] as an annotation's text, and back. */
    fun pack(ranges: List<IntRange>): String = ranges.joinToString(",") { "${it.first}-${it.last}" }
    fun unpack(s: String): List<IntRange> = s.split(',').mapNotNull { part ->
        val a = part.substringBefore('-').toIntOrNull() ?: return@mapNotNull null
        val b = part.substringAfter('-').toIntOrNull() ?: return@mapNotNull null
        a..b
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
