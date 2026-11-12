package ca.lampandlight

/**
 * What is kept of one chapter: when it was last read through, and how saying what is in it
 * has gone since. [chapter] is the book and the chapter in one number, see [Places.key].
 */
data class Place(val chapter: Int, val readAt: Long, val history: List<Past> = emptyList()) {
    val book: Int get() = chapter / 1000
    val number: Int get() = chapter % 1000
    /** The last time it was asked for, it was there. */
    val known: Boolean get() = history.lastOrNull()?.outcome == Quiz.RIGHT
    val due: Long get() = Places.dueAt(readAt, history)
}

/**
 * Knowing where things are.
 *
 * A reader who has been through John can still be lost in it: the wedding, the temple,
 * Nicodemus, the well, and no idea which comes first or in which chapter. What fixes the
 * order is being asked for it. So before going on from a chapter that has been read, the
 * page asks what was in it and holds the answer back, and the answer is the chapter's own
 * section headings. Nothing is written for this: the headings are the translation's.
 *
 * Nobody is marked. The reader says it to themselves, looks, and says whether they had it,
 * the same as a verse learned by heart, and a chapter they had is asked for again after
 * longer and longer. The map in the chapter picker is this record and nothing more: a
 * chapter read through, and a chapter known.
 *
 * Kept free of the phone so it can be tested on its own.
 */
object Places {
    /** A chapter as one number: John 2 is 43002. */
    fun key(book: Int, chapter: Int): Int = book * 1000 + chapter

    /**
     * A heading as it is asked for, or null when it names nothing to remember.
     *
     * Some things on a heading line are not the name of a section. The Psalms open their
     * five books there ("BOOK II Psalms 42–72 As the Deer Pants for the Water"), Psalm 119
     * names each stanza by its Hebrew letter, the first of them on the end of the psalm's
     * own heading, and the Song of Songs says who is speaking, four times run on to the
     * end of the heading before. Done with plain string work: a pattern that
     * passes on the desktop has failed on the phone before.
     */
    fun title(raw: String): String? {
        var t = raw.trim()
        if (t.startsWith("BOOK ")) {
            val at = t.indexOf("Psalms ")
            if (at > 0) t = t.substring(at + 7).substringAfter(' ', "")
        }
        val letter = t.indexOfFirst { it in '\u05D0'..'\u05EA' }
        if (letter >= 0) t = t.substring(0, letter)
        t = t.trim(' ', '\u2014', '\u2013', '-')
        if (t in SPEAKERS) return null
        for (s in SPEAKERS) {
            // "Solomon Admires His BrideThe Bridegroom": run on, with no space between.
            if (t.endsWith(s) && t.length > s.length && t[t.length - s.length - 1].isLowerCase()) {
                t = t.dropLast(s.length)
            }
        }
        return t.ifBlank { null }
    }

    /** Who is speaking in the Song of Songs, which the text sets as headings. */
    private val SPEAKERS = listOf("The Bridegroom", "The Bride", "The Friends")

    /** What a chapter is asked for: its headings in order, each once. */
    fun outline(headings: List<String?>): List<String> =
        headings.mapNotNull { it?.let(::title) }.distinct()

    /**
     * How long a chapter of [verses] has to have been open before reaching its end counts
     * as reading it, in milliseconds. Well under the time it takes to read, and well over
     * the time it takes to flick to the foot of the page for the next chapter.
     */
    fun enough(verses: Int): Long = (verses * 1_500L).coerceIn(10_000L, 60_000L)

    /**
     * When a chapter is next to be asked for. One never asked for is due from the moment it
     * was read; after that it follows the same lengthening rests as a question.
     */
    fun dueAt(readAt: Long, history: List<Past>): Long = Quiz.dueAt(history) ?: readAt

    /**
     * For how long a chapter that has been read, and never asked for, is put in going over.
     * It is always asked for on the page, on going on to the next chapter. Away from the
     * page it is only worth asking while the reading is recent: someone who listens to ten
     * chapters a day should not find three hundred waiting at the end of the month.
     */
    const val FRESH = 3 * 24 * 60 * 60_000L

    /**
     * The chapters to go over at [now]: those asked for before and due again, the longest
     * due first, then those just read and not yet asked for, the latest read first.
     */
    fun waiting(places: Collection<Place>, now: Long): List<Place> {
        val due = places.filter { it.due <= now }
        return due.filter { it.history.isNotEmpty() }.sortedBy { it.due } +
            due.filter { it.history.isEmpty() && now - it.readAt <= FRESH }.sortedByDescending { it.readAt }
    }

    /**
     * The chapter before [book] and [chapter], where [chapters] is how many each book has.
     * Across books, and round from Genesis to the end of Revelation, so that the last
     * chapter of a book is asked for somewhere too.
     */
    fun before(book: Int, chapter: Int, chapters: List<Int>): Pair<Int, Int> = when {
        chapter > 1 -> book to chapter - 1
        book > 1 -> (book - 1) to chapters[book - 2]
        else -> chapters.size to chapters.last()
    }

    /** "What was First John 4 about?" as the voice says it; the page writes the figure. */
    fun asks(book: String, chapter: Int, spoken: Boolean = false): String =
        "What was ${if (spoken) ByHeart.named(book) else book} $chapter about?"

    /** In [ReaderState.replied] and the narrator: the key of the asking, which is one a page. */
    const val KEY = "place"
}
