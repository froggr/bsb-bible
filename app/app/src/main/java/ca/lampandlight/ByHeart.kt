package ca.lampandlight

/**
 * A verse being learned by heart, or a few that run on from it: when it was taken up, and
 * each time it has been said as it now stands. [upto] is the last verse where there is more
 * than one; it is in the same chapter.
 */
data class Heart(val verse: VerseId, val added: Long, val history: List<Past>, val upto: VerseId? = null) {
    val last: VerseId get() = upto ?: verse
    val span: Int get() = last - verse + 1
    val level: Int get() = ByHeart.level(history)
    val due: Long get() = ByHeart.dueAt(added, history)
}

/**
 * Learning a verse by heart, by ear.
 *
 * The method is the old one: hear it, say it after the voice a phrase at a time, then say
 * each phrase before the voice does, then say all of it from the reference alone. What the
 * app adds is a voice that will do this at any hour, with the screen off, and that leaves
 * the right length of silence. No microphone: nobody is marked. The listener hears the
 * phrase a moment after saying it and knows.
 *
 * It works in phrases, not words, because a phrase is the smallest thing the voice can be
 * silent for exactly. The model reports no timing, so where one word falls inside a
 * sentence is only ever an estimate, while what was handed to it as a sentence is known.
 */
object ByHeart {
    /** Not yet said: hear it, then say it after the voice. */
    const val NEW = 0
    /** The first letter of each word shows, and the voice waits before each phrase. */
    const val LETTERS = 1
    /** Nothing shows but the reference, and the voice waits for the whole verse. */
    const val BLANK = 2

    /** Twice right running and the letters come away. A miss puts them back. */
    fun level(history: List<Past>): Int = when {
        history.isEmpty() -> NEW
        history.takeLastWhile { it.outcome == Quiz.RIGHT }.size >= 2 -> BLANK
        else -> LETTERS
    }

    /**
     * When the verse is next to be said. New, it is due at once; missed, in ten minutes;
     * then a day, three, a week and so on as it is got right. The questions' own ladder,
     * without their head start: a verse is not known until it has been said.
     */
    fun dueAt(added: Long, history: List<Past>): Long = Quiz.dueAt(history, headStart = 0) ?: added

    /**
     * [text] in phrases short enough to hold in the ear and say back.
     *
     * Broken first where the sentence breaks itself, at its punctuation. A stretch still
     * longer than [LONG] words is broken again before a joining word, as near its middle as
     * one is found: "For God so loved the world / that He gave His one and only Son,". A
     * stretch of one or two words is not left alone; it goes with its neighbour.
     */
    fun phrases(text: String): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val stretches = mutableListOf<List<String>>()
        var from = 0
        words.forEachIndexed { i, w ->
            val bare = w.trimEnd('”', '’', '"', '\'', ')')
            val ends = bare.isNotEmpty() && bare.last() in ",;:.!?—"
            if (ends || i == words.lastIndex) { stretches += words.subList(from, i + 1); from = i + 1 }
        }
        // A stretch too short to be a phrase joins the next one, or the one before at the end.
        val joined = mutableListOf<List<String>>()
        var carry = emptyList<String>()
        for (s in stretches) {
            val now = carry + s
            if (now.size < SHORT) carry = now else { joined += now; carry = emptyList() }
        }
        if (carry.isNotEmpty()) {
            if (joined.isEmpty()) joined += carry else joined[joined.lastIndex] = joined.last() + carry
        }
        return joined.flatMap { halve(it) }.map { it.joinToString(" ") }
    }

    private fun halve(words: List<String>): List<List<String>> {
        if (words.size <= LONG) return listOf(words)
        val middle = words.size / 2.0
        // Where a break may fall: before a joining word, leaving a phrase each side.
        val at = (SHORT..words.size - SHORT).mapNotNull { i ->
            val r = rank(words[i])
            when {
                r == 0 -> null
                // "you / have been saved" is not a break anyone makes: the one who does it
                // goes with the doing, so the break moves to the front of the pair.
                r == 2 && bare(words[i - 1]) in SUBJECTS -> (i - 1).takeIf { it >= SHORT }?.let { it to r }
                else -> i to r
            }
        }.minByOrNull { (i, r) ->
            // Nearest the middle, and a conjunction is worth going a word or two out of the
            // way for. A helping verb comes next, since it is where the subject ends, and a
            // preposition last: "believes / in Him" is the break nobody would make.
            kotlin.math.abs(i - middle) + (3 - r) * 1.5
        }?.first ?: return listOf(words)
        return halve(words.subList(0, at)) + halve(words.subList(at, words.size))
    }

    private fun bare(word: String) = word.lowercase().trim { !it.isLetter() }

    private fun rank(word: String): Int = when (bare(word)) {
        in JOINS -> 3
        in HELPS -> 2
        in PLACES -> 1
        else -> 0
    }

    private val JOINS = setOf(
        "and", "but", "that", "so", "because", "or", "nor", "yet", "when", "while", "if",
        "though", "who", "whom", "whose", "which", "where", "lest", "until", "unless", "since", "then",
        "wherever", "whenever", "whoever",
    )
    // "for" is here and not above: where it means "because" it follows a comma, and the
    // comma has already made the break. In the middle of a stretch it is "hope for".
    private val PLACES = setOf(
        "in", "on", "with", "from", "by", "through", "into", "before", "after", "among", "upon",
        "against", "according", "at", "to", "under", "over", "without", "toward", "like", "as", "for",
    )
    // No "is" or "are": "The LORD / is my shepherd" is not a break.
    private val HELPS = setOf(
        "shall", "will", "has", "have", "had", "may", "might", "must",
        "should", "would", "can", "cannot", "could",
    )
    private val SUBJECTS = setOf("i", "you", "he", "she", "we", "they", "it")

    /** Longer than this and a stretch is broken again. */
    private const val LONG = 10
    /** Shorter than this is not a phrase by itself. */
    private const val SHORT = 3

    /**
     * How long to leave for [phrase] to be said from memory, in milliseconds. Slower than
     * the voice says it: a person recalling speaks in fits, and silence that ends too soon
     * is the voice talking over them.
     */
    fun room(phrase: String): Long =
        900L + phrase.split(' ').sumOf { 330L + 45L * it.count(Char::isLetter).coerceAtMost(9) }

    /** A book's name as a person says it: the figure at the front becomes a word. */
    fun named(book: String): String = when {
        book.startsWith("1 ") -> "First " + book.drop(2)
        book.startsWith("2 ") -> "Second " + book.drop(2)
        book.startsWith("3 ") -> "Third " + book.drop(2)
        book == "Psalms" -> "Psalm"
        else -> book
    }

    /** "First John 4, verse 19." */
    fun spoken(book: String, verse: VerseId, last: VerseId = verse): String {
        val name = named(book)
        return if (last > verse) "$name ${verse.chapter}, verses ${verse.verse} to ${last.verse}."
        else "$name ${verse.chapter}, verse ${verse.verse}."
    }

    /** How many verses may be learned as one. Psalm 23 is six. */
    const val SPAN = 6

    /** The place of everything in a script that belongs to item [item]. See [script]. */
    fun base(item: Int): Int = FIRST + item * EACH
    /** The index the whole verse is read against, as opposed to one phrase of it. */
    const val WHOLE = 90
    /** The index the reference is said against. */
    const val NAME = 99
    private const val FIRST = 100_000
    private const val EACH = 100

    /**
     * What the voice does with one verse at [level], as lines for the narrator.
     *
     * Each line's verse index is [base] plus the phrase it is, or plus [WHOLE], so whoever
     * draws the verse can light the phrase that is sounding and show whose turn it is. The
     * indexes start far above any chapter's, so the page underneath lights nothing.
     *
     * - [NEW]: the reference, the verse, then each phrase with room after it to say it back,
     *   then the verse once more.
     * - [LETTERS]: the reference, then room before each phrase to say it first.
     * - [BLANK]: the reference, room for all of it, then the verse.
     *
     * With [tap] the room is not timed: the voice waits to be told to go on, as it can be
     * on the verse's own card. Without, it leaves [room] and goes on by itself, which is
     * what hands that are busy need.
     */
    fun script(
        item: Int, reference: String, phrases: List<String>, level: Int, tap: Boolean = false,
    ): List<Narrator.Line> = buildList {
        val at = base(item)
        fun say(index: Int, text: String, preamble: String? = null) =
            add(Narrator.Line(at + index, text.split(' '), text, preamble = preamble))
        // The listener's turn: by the clock, or theirs to end where [tap] says they can.
        fun leave(index: Int, ms: Long) =
            add(Narrator.Line(at + index, emptyList(), "", silence = ms, hold = tap))
        // A breath between two things the voice says. Against no phrase: it is nobody's turn.
        fun breath(ms: Long) = add(Narrator.Line(at + NAME, emptyList(), "", silence = ms))
        // The reference alone, against no phrase, so none is lit while it is said.
        fun name() = add(Narrator.Line(at + NAME, emptyList(), "", aside = true, preamble = reference))
        val whole = phrases.joinToString(" ")
        when (level) {
            NEW -> {
                say(WHOLE, whole, preamble = reference)
                breath(1500)
                phrases.forEachIndexed { i, p -> say(i, p); leave(i, room(p)) }
                say(WHOLE, whole)
            }
            LETTERS -> {
                name()
                phrases.forEachIndexed { i, p -> leave(i, room(p)); say(i, p) }
                breath(700)
                say(WHOLE, whole)
            }
            else -> {
                name()
                leave(WHOLE, phrases.sumOf { room(it) } - 900L * (phrases.size - 1))
                say(WHOLE, whole)
            }
        }
    }
}
