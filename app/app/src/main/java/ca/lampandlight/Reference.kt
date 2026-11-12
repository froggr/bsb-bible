package ca.lampandlight

/**
 * Typing a reference instead of searching for words.
 *
 * "1cor12", "1 co 12", "ps23", "jn3:16", "rev", "song 2:1" — the fast path to a passage. A
 * reference is recognised without a space between the book and the chapter, because that is
 * how a reference gets typed when the point is speed.
 *
 * Matching is by prefix on a squashed form of the name, so "phil" reaches Philippians and
 * "1th" reaches 1 Thessalonians. Where a prefix is genuinely ambiguous ("j" is Joshua,
 * Judges, Job, Joel, Jonah, John, James, Jude; "phi" is Philippians and Philemon) every
 * candidate is offered rather than one guessed at.
 */
object Reference {

    /** A recognised reference: where it points, and how sure we are of the book. */
    data class Hit(
        val book: Book,
        val chapter: Int,
        val verse: Int?,
        /** True when the typed name matched this book's full name, not just a prefix. */
        val exact: Boolean,
        /** The last verse of a range ("2:3-4"), when one was given and it is past [verse]. */
        val verseEnd: Int? = null,
    ) {
        val id: VerseId get() = book.id * 1_000_000 + chapter * 1_000 + (verse ?: 1)
        /** The far end of the range, or null for a single verse or a whole chapter. */
        val endId: VerseId? get() = verseEnd?.let { book.id * 1_000_000 + chapter * 1_000 + it }
        val label: String get() =
            "${book.name} $chapter" + (if (verse != null) ":$verse" else "") +
                (if (verseEnd != null) "\u2013$verseEnd" else "")
    }

    /**
     * Abbreviations people actually type that a prefix match would miss or send elsewhere.
     * Keyed by squashed input, valued by the book's squashed name.
     */
    private val ALIASES = mapOf(
        "ge" to "genesis", "gn" to "genesis",
        "ex" to "exodus", "exo" to "exodus",
        "lv" to "leviticus", "nm" to "numbers", "nb" to "numbers",
        "dt" to "deuteronomy", "dn" to "daniel",
        "js" to "joshua", "jsh" to "joshua", "jdg" to "judges", "jg" to "judges",
        "rt" to "ruth",
        "1sm" to "1samuel", "2sm" to "2samuel",
        "1kg" to "1kings", "2kg" to "2kings", "1ki" to "1kings", "2ki" to "2kings",
        "1ch" to "1chronicles", "2ch" to "2chronicles",
        "ne" to "nehemiah", "es" to "esther", "est" to "esther",
        "jb" to "job", "psa" to "psalm", "pss" to "psalm", "pslm" to "psalm",
        "pr" to "proverbs", "prv" to "proverbs", "pv" to "proverbs",
        "ec" to "ecclesiastes", "qoh" to "ecclesiastes",
        "ss" to "songofsolomon", "sos" to "songofsolomon", "sng" to "songofsolomon",
        "cant" to "songofsolomon", "song" to "songofsolomon",
        "is" to "isaiah", "isa" to "isaiah", "jr" to "jeremiah", "jer" to "jeremiah",
        "lam" to "lamentations", "lm" to "lamentations",
        "ez" to "ezekiel", "ezk" to "ezekiel", "eze" to "ezekiel", "ezr" to "ezra",
        "ho" to "hosea", "hos" to "hosea", "jl" to "joel", "am" to "amos",
        "ob" to "obadiah", "oba" to "obadiah", "jon" to "jonah", "jnh" to "jonah",
        "mic" to "micah", "mi" to "micah", "na" to "nahum", "nah" to "nahum",
        "hab" to "habakkuk", "zep" to "zephaniah", "zph" to "zephaniah",
        "hag" to "haggai", "hg" to "haggai", "zec" to "zechariah", "zch" to "zechariah",
        "mal" to "malachi", "ml" to "malachi",
        "mt" to "matthew", "matt" to "matthew",
        "mk" to "mark", "mr" to "mark", "mrk" to "mark",
        "lk" to "luke", "luk" to "luke",
        "jn" to "john", "joh" to "john", "jhn" to "john",
        "ac" to "acts", "act" to "acts",
        "ro" to "romans", "rm" to "romans", "rom" to "romans",
        "1co" to "1corinthians", "2co" to "2corinthians",
        "1cor" to "1corinthians", "2cor" to "2corinthians",
        "ga" to "galatians", "gal" to "galatians",
        "ep" to "ephesians", "eph" to "ephesians",
        "php" to "philippians", "phil" to "philippians", "pp" to "philippians",
        "phm" to "philemon", "phlm" to "philemon", "pm" to "philemon",
        "co" to "colossians", "col" to "colossians",
        "1th" to "1thessalonians", "2th" to "2thessalonians",
        "1ts" to "1thessalonians", "2ts" to "2thessalonians",
        "1ti" to "1timothy", "2ti" to "2timothy", "1tm" to "1timothy", "2tm" to "2timothy",
        "ti" to "titus", "tit" to "titus",
        "he" to "hebrews", "heb" to "hebrews",
        "jas" to "james", "jm" to "james",
        "1pe" to "1peter", "2pe" to "2peter", "1pt" to "1peter", "2pt" to "2peter",
        "1jn" to "1john", "2jn" to "2john", "3jn" to "3john",
        "1jo" to "1john", "2jo" to "2john", "3jo" to "3john",
        "jud" to "jude", "jde" to "jude",
        "re" to "revelation", "rev" to "revelation", "rv" to "revelation",
        // What the books are called by people who are not looking at the contents page,
        // and how Philippians is actually spelled when it is typed in a hurry.
        "psalms" to "psalm", "revelations" to "revelation",
        "songofsongs" to "songofsolomon", "songs" to "songofsolomon", "canticles" to "songofsolomon",
        "phillipians" to "philippians", "philipians" to "philippians", "phillippians" to "philippians",
    )

    /** Lower-case, strip everything but letters and digits: "1 Cor." -> "1cor". */
    private fun squash(s: String) = buildString {
        for (c in s) if (c.isLetterOrDigit()) append(c.lowercaseChar())
    }

    /**
     * Split what was typed into a book part and the numbers after it.
     *
     * The leading digit of "1cor" belongs to the book, so the book part is taken as an
     * optional leading digit plus the letters that follow; numbers start at the first digit
     * after a letter. A range of verses may follow the verse: "2:3-4", "2:3–4".
     */
    private val SHAPE = Regex(
        """^\s*([1-3])?\s*([a-z]+)\s*\.?\s*(\d{1,3})?\s*[:.\s]?\s*(\d{1,3})?(?:\s*[-\u2013]\s*(\d{1,3}))?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Roman numerals, as in "II Tim" — but only tried after the plain reading fails, because
     * splitting a leading "i" off eagerly turns "isa40" into 1 Samuel 40.
     */
    private val ROMAN = Regex(
        """^\s*(i{1,3})\s*([a-z]+)\s*\.?\s*(\d{1,3})?\s*[:.\s]?\s*(\d{1,3})?(?:\s*[-\u2013]\s*(\d{1,3}))?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Read [input] as a reference. Returns the books it could mean, best first, or empty when
     * it does not look like a reference at all.
     *
     * [books] is the canon in order, as the database lists it.
     */
    fun parse(input: String, books: List<Book>): List<Hit> {
        resolve(input, books, roman = false).let { if (it.isNotEmpty()) return it }
        return resolve(input, books, roman = true)
    }

    private fun resolve(input: String, books: List<Book>, roman: Boolean): List<Hit> {
        val m = (if (roman) ROMAN else SHAPE).find(input.trim()) ?: return emptyList()
        val (prefix, letters, chText, vText, endText) = m.destructured
        if (letters.isEmpty()) return emptyList()

        val ordinal = if (!roman) prefix else when (prefix.lowercase()) {
            "i" -> "1"
            "ii" -> "2"
            "iii" -> "3"
            else -> return emptyList()
        }
        val typed = ordinal + squash(letters)

        val chapter = chText.toIntOrNull()
        val verse = vText.toIntOrNull()
        // "3-4" is a range; "4-3" and "3-3" are a verse with a slip after it.
        val verseEnd = endText.toIntOrNull()?.takeIf { verse != null && it > verse }

        // An alias is a deliberate spelling, so it wins over a prefix match.
        val aliased = ALIASES[typed]
        val candidates = if (aliased != null) {
            books.filter { squash(it.name) == aliased }
        } else {
            val exact = books.filter { squash(it.name) == typed }
            if (exact.isNotEmpty()) exact
            else books.filter { squash(it.name).startsWith(typed) || squash(it.osis).startsWith(typed) }
        }
        if (candidates.isEmpty()) return emptyList()

        return candidates.mapNotNull { b ->
            // A chapter beyond the book is not this book; "1co20" should not offer 1 Cor.
            val ch = (chapter ?: 1).coerceAtLeast(1)
            if (ch > b.chapters) null
            else Hit(b, ch, verse, exact = squash(b.name) == typed || aliased != null, verseEnd = verseEnd)
        }.sortedWith(compareByDescending<Hit> { it.exact }.thenBy { it.book.id })
    }

    // ------------------------------------------------------------------ said aloud

    /**
     * Read [input] as a reference said aloud: "John three sixteen", "First Corinthians
     * thirteen", "Romans chapter eight, verse twenty eight", "Revelation twenty one, verses
     * one through four". Empty when it is anything else, so "John the Baptist" and "the
     * LORD is my shepherd" stay searches for words.
     *
     * This is how the recogniser writes what it hears: numbers as words, a comma where the
     * voice paused. Measured on twenty references in two voices, it also wrote "Psalm" as
     * "Some", "Jude" as "June" and "three sixteen" as "three hundred and sixteen", and all
     * three are read here as what was meant. Figures are read too, so "song of solomon 2:1"
     * and "john chapter 3 verse 16" work when typed.
     *
     * Plain loops, no patterns: a regular expression that passes on the desk has failed on
     * the phone before.
     */
    fun spoken(input: String, books: List<Book>): List<Hit> {
        val t = tokens(input)
        if (t.isEmpty() || t.size > 16) return emptyList()
        var at = 0
        // "the book of", "the gospel of": said, and no part of the name.
        if (at < t.size && t[at] == "the") at++
        if (at + 1 < t.size && t[at] in KINDS && t[at + 1] == "of") at += 2
        // "First", of a book that has a second.
        var ordinal = ""
        if (at + 1 < t.size) FIRSTS[t[at]]?.let { ordinal = it; at++ }
        // The name: every word up to the first number, and there may be three of them.
        val begins = at
        while (at < t.size && named(t[at])) at++
        if (at == begins || at - begins > 3) return emptyList()
        val typed = ordinal + t.subList(begins, at).joinToString("")
        val said = numbers(t.subList(at, t.size)) ?: return emptyList()
        val found = called(typed, books, sure = said.isEmpty())
        return found.mapNotNull { (b, exact) -> place(b, said, exact) }
            .sortedWith(compareByDescending<Hit> { it.exact }.thenBy { it.book.id })
    }

    /** One number that was said, and what was said in front of it: 'c', 'v', 'e' for an end, or nothing. */
    private class Said(val value: Int, val role: Char, val one: Boolean, val hundreds: Boolean)

    private val KINDS = setOf("book", "gospel", "letter", "epistle", "books")
    private val FIRSTS = mapOf(
        "first" to "1", "1st" to "1", "1" to "1", "second" to "2", "2nd" to "2", "2" to "2",
        "third" to "3", "3rd" to "3", "3" to "3",
    )
    private val UNITS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18,
        "nineteen" to 19,
        // What a number is written as when it is misheard for a word that sounds the same.
        "won" to 1, "too" to 2, "for" to 4, "fore" to 4, "ate" to 8,
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fourty" to 40, "fifty" to 50, "sixty" to 60,
        "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )
    private val CHAPTER = setOf("chapter", "chapters")
    private val VERSE = setOf("verse", "verses")
    private val ON = setOf("through", "thru", "till", "until", "-")
    private val OTHER = setOf("to", "and", "hundred", "oh", "o", ",")

    /** What the recogniser writes for a book it did not catch, where nothing nearer would find it. */
    private val MISHEARD = mapOf("some" to "psalm", "sum" to "psalm", "sums" to "psalm")

    private fun figures(w: String) = w.isNotEmpty() && w.all { it in '0'..'9' }

    /** Whether [w] can be part of a book's name: a word, and not one that belongs to the numbers. */
    private fun named(w: String) =
        w.all { it in 'a'..'z' } && w !in UNITS && w !in TENS && w !in CHAPTER && w !in VERSE && w !in ON &&
            w !in OTHER

    /**
     * [input] as small words and figures. A comma is kept, as a word of its own: it is where
     * one number ends. So is a dash between two figures, which is a range.
     */
    private fun tokens(input: String): List<String> {
        val out = ArrayList<String>()
        val word = StringBuilder()
        fun end() { if (word.isNotEmpty()) { out += word.toString(); word.setLength(0) } }
        for ((i, raw) in input.withIndex()) {
            val c = raw.lowercaseChar()
            when {
                c in 'a'..'z' || c in '0'..'9' -> word.append(c)
                c == '\'' || c == '’' -> Unit
                c == ',' -> { end(); out += "," }
                c == '-' || c == '–' -> {
                    end()
                    val next = input.drop(i + 1).firstOrNull { it != ' ' }
                    if (out.isNotEmpty() && figures(out.last()) && next != null && next in '0'..'9') out += "-"
                }
                else -> end()
            }
        }
        end()
        return out
    }

    /**
     * The numbers in [rest], each with what was said in front of it. Null when something in
     * it is neither a number nor a word that goes with one.
     *
     * "twenty eight" is one number and "three sixteen" two. "one hundred and nineteen" is
     * one, and so is "one oh five".
     */
    private fun numbers(rest: List<String>): List<Said>? {
        val out = ArrayList<Said>()
        var cur = -1
        // What may still be added to the number being said.
        var tens = false; var one = false; var hundred = false; var oh = false
        var hundreds = false
        var role = ' '
        fun close() {
            if (cur >= 0) { out += Said(cur, role, one, hundreds); role = ' ' }
            cur = -1; tens = false; one = false; hundred = false; oh = false; hundreds = false
        }
        fun begin(v: Int) { close(); cur = v }
        for ((i, w) in rest.withIndex()) {
            val next = rest.getOrNull(i + 1)
            val unit = UNITS[w]
            val ten = TENS[w]
            when {
                w == "," -> close()
                w in CHAPTER -> { close(); role = 'c' }
                w in VERSE -> { close(); role = 'v' }
                w in ON -> { close(); role = 'e' }
                // "three to five" is a range, and "John to" is John two.
                w == "to" -> if (cur >= 0 && next != null && (next in UNITS || next in TENS || figures(next))) {
                    close(); role = 'e'
                } else { begin(2); one = true }
                w == "and" -> if (!hundred) { close(); role = 'e' }
                w == "hundred" -> when {
                    one -> { cur *= 100; one = false; hundred = true; hundreds = true }
                    cur < 0 -> { cur = 100; hundred = true }
                    else -> return null
                }
                w == "oh" || w == "o" -> if (one) { cur *= 100; one = false; oh = true } else return null
                figures(w) -> { if (w.length > 3) return null; begin(w.toInt()) }
                ten != null -> if (hundred) { cur += ten; hundred = false; tens = true } else { begin(ten); tens = true }
                unit != null && unit >= 10 -> if (hundred) { cur += unit; hundred = false } else begin(unit)
                unit != null -> when {
                    tens || hundred || oh -> { cur += unit; tens = false; hundred = false; oh = false }
                    else -> { begin(unit); one = true }
                }
                else -> return null
            }
        }
        close()
        return out
    }

    /**
     * The books [typed] could be, each with whether it was named outright. A name that is
     * only near one ("June", "Ecklesiest") is taken when a number follows it; with nothing
     * after it, [sure], it is a word and not a reference.
     */
    private fun called(typed: String, books: List<Book>, sure: Boolean): List<Pair<Book, Boolean>> {
        val alias = ALIASES[typed]
        if (alias != null) return books.filter { squash(it.name) == alias }.map { it to true }
        val exact = books.filter { squash(it.name) == typed }
        if (exact.isNotEmpty()) return exact.map { it to true }
        if (sure || typed.length < 3) return emptyList()
        MISHEARD[typed]?.let { meant -> return books.filter { squash(it.name) == meant }.map { it to false } }
        val begun = books.filter { squash(it.name).startsWith(typed) }
        if (begun.isNotEmpty()) return begun.map { it to false }
        // "Kings" is both books of them, and a first that was said has to be the book's own.
        val numbered = typed[0] in '1'..'3'
        var best = 0f
        val near = ArrayList<Pair<Book, Float>>()
        for (b in books) {
            val name = squash(b.name)
            val theirs = name[0] in '1'..'3'
            if (numbered && (!theirs || name[0] != typed[0])) continue
            val a = if (numbered) typed.drop(1) else typed
            val n = if (theirs) name.drop(1) else name
            val like = 1f - Heard.apart(a, n).toFloat() / maxOf(a.length, n.length)
            if (like >= NEAR) { near += b to like; if (like > best) best = like }
        }
        return near.filter { it.second >= best - 0.05f }.map { it.first to false }
    }

    /** How much of a name has to be right for a book to be meant by it. */
    private const val NEAR = 0.65f

    /** Where in [b] the numbers [said] point, or null when they do not fit it. */
    private fun place(b: Book, said: List<Said>, exact: Boolean): Hit? {
        val s = ArrayList(said)
        if (s.isNotEmpty() && s[0].role == ' ') {
            val first = s[0]
            val then = s.getOrNull(1)
            when {
                // "three hundred and sixteen" is three sixteen said carefully: no book has
                // that many chapters.
                first.hundreds && first.value > b.chapters && first.value % 100 != 0 -> {
                    s[0] = Said(first.value / 100, ' ', true, false)
                    s.add(1, Said(first.value % 100, ' ', false, false))
                }
                // "one nineteen" is the hundred and nineteenth psalm.
                first.one && then != null && then.role == ' ' && then.value in 10..99 &&
                    first.value * 100 + then.value <= b.chapters -> {
                    s[0] = Said(first.value * 100 + then.value, ' ', false, false)
                    s.removeAt(1)
                }
            }
        }
        // A book of one chapter is cited by verse alone: "Jude twenty four".
        var chapter: Int? = null
        if (b.chapters == 1 && s.none { it.role == 'c' } &&
            !(s.size >= 2 && s[0].value == 1 && s[0].role == ' ' && s[1].role != 'e')) chapter = 1
        val alone = chapter != null
        var verse: Int? = null
        var end: Int? = null
        for (n in s) when (n.role) {
            'c' -> if (chapter == null || alone) chapter = n.value else return null
            'v' -> if (verse == null) verse = n.value else return null
            // A range of chapters opens at its first.
            'e' -> if (verse != null && end == null) end = n.value else if (verse != null) return null
            else -> when {
                chapter == null -> chapter = n.value
                verse == null -> verse = n.value
                end == null -> end = n.value
                else -> return null
            }
        }
        val ch = chapter ?: 1
        if (ch < 1 || ch > b.chapters) return null
        if (verse != null && verse !in 1..176) return null
        return Hit(b, ch, verse, exact = exact, verseEnd = end?.takeIf { verse != null && it > verse && it <= 176 })
    }

    /** Whether [input] is worth trying as a reference at all: has letters, and is short. */
    fun looksLikeReference(input: String): Boolean {
        val t = input.trim()
        if (t.isEmpty() || t.length > 24) return false
        if (!t.any { it.isLetter() }) return false
        // More than three words is prose, not a reference.
        return t.split(Regex("\\s+")).size <= 3
    }
}
