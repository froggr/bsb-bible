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

    /** Whether [input] is worth trying as a reference at all: has letters, and is short. */
    fun looksLikeReference(input: String): Boolean {
        val t = input.trim()
        if (t.isEmpty() || t.length > 24) return false
        if (!t.any { it.isLetter() }) return false
        // More than three words is prose, not a reference.
        return t.split(Regex("\\s+")).size <= 3
    }
}
