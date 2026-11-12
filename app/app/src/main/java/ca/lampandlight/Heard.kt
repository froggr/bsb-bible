package ca.lampandlight

/**
 * Was the line said? What the recogniser heard, set against the words it was listening for.
 *
 * It is forgiving on purpose. The recogniser is a small one and mishears: "He leads me beside
 * quiet waters" came back from it as "He leaves me the sides wild waters". Nobody is being
 * marked here. The only question is whether the listener has had their turn, so the voice
 * can go on, and a turn wrongly ended costs less than a child saying a line four times at a
 * phone that will not hear it. What decides whether a verse is known is still the listener
 * saying so.
 *
 * Two measures, and the better of them counts: how many of the wanted words turn up in
 * order, a near miss counting as the word, and how much of the wanted letters lie somewhere
 * in what was heard, which catches a word the recogniser split or ran together. Measured
 * over 91 phrases in ten voices, [ENOUGH] passed 98 in 100 of what was said and 1 in 250 of
 * what was not. Plain loops, no patterns: a regular expression that passes on the desk has
 * failed on the phone before.
 */
object Heard {
    /** How much of the line has to be there. */
    const val ENOUGH = 0.6f

    /** Longer than this and the line's end must have been heard too. See [said]. */
    private const val LONG = 8
    private const val TAIL = 5

    /** Has [want] been said, going by [got], everything heard so far this turn? */
    fun said(want: String, got: String): Boolean = said(words(want), words(got))

    fun said(want: List<String>, got: List<String>): Boolean {
        if (want.isEmpty() || got.isEmpty()) return false
        if (score(want, got) < ENOUGH) return false
        // A whole verse is said with pauses in it, and three fifths of it is not the end of
        // it. So a long line is not done until its last words have been heard near the end
        // of what was said.
        if (want.size <= LONG) return true
        return score(want.takeLast(TAIL), got.takeLast(TAIL * 2)) >= ENOUGH
    }

    /** Between nothing and all of it: how far [got] goes to being [want]. */
    fun score(want: List<String>, got: List<String>): Float {
        if (want.isEmpty() || got.isEmpty()) return 0f
        return maxOf(cover(want, got), within(want, got))
    }

    /**
     * [text] as bare words: small letters, no marks, a figure as the words it is said with.
     * An apostrophe is dropped and not split at, so "LORD's" is one word.
     */
    fun words(text: String): List<String> {
        val out = ArrayList<String>()
        val word = StringBuilder()
        var digits = false
        fun end() {
            if (word.isEmpty()) return
            val w = word.toString()
            word.setLength(0)
            if (digits) out += number(w) else out += w
        }
        for (raw in text) {
            val c = raw.lowercaseChar()
            when {
                c == '\'' || c == '’' || c == '‘' -> Unit
                c in 'a'..'z' -> { if (digits) end(); digits = false; word.append(c) }
                c in '0'..'9' -> { if (!digits) end(); digits = true; word.append(c) }
                else -> end()
            }
        }
        end()
        return out
    }

    private val ONES = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
        "nineteen",
    )
    private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    private fun number(figures: String): List<String> {
        val n = figures.toIntOrNull()?.takeIf { figures.length <= 6 } ?: return listOf(figures)
        return number(n)
    }

    private fun number(n: Int): List<String> = when {
        n < 20 -> listOf(ONES[n])
        n < 100 -> listOf(TENS[n / 10]) + (if (n % 10 != 0) listOf(ONES[n % 10]) else emptyList())
        n < 1000 -> listOf(ONES[n / 100], "hundred") + (if (n % 100 != 0) number(n % 100) else emptyList())
        else -> number(n / 1000) + "thousand" + (if (n % 1000 != 0) number(n % 1000) else emptyList())
    }

    /** Words that sound the same, or near enough that the recogniser picks either. */
    private val SAME = listOf(
        setOf("to", "too", "two"), setOf("for", "four"), setOf("no", "know"), setOf("our", "hour", "are"),
        setOf("o", "oh"), setOf("son", "sun"), setOf("by", "buy", "bye"), setOf("i", "eye"),
        setOf("be", "bee"), setOf("in", "inn"), setOf("an", "and"), setOf("their", "there"),
        setOf("your", "youre", "you"),
    )

    /** The same word, as far as a small recogniser can be trusted to tell. */
    internal fun alike(a: String, b: String): Boolean {
        if (a == b) return true
        if (SAME.any { a in it && b in it }) return true
        val n = maxOf(a.length, b.length)
        if (n < 4) return false
        val slack = if (n < 7) 1 else 2
        if (kotlin.math.abs(a.length - b.length) > slack) return false
        return apart(a, b) <= slack
    }

    /** How many letters put in, taken out or changed turn [a] into [b]. */
    private fun apart(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** The share of [want]'s words found in [got], in their order. */
    private fun cover(want: List<String>, got: List<String>): Float {
        var prev = IntArray(got.size + 1)
        var cur = IntArray(got.size + 1)
        for (i in want.indices) {
            cur[0] = 0
            for (j in got.indices) {
                cur[j + 1] = if (alike(want[i], got[j])) prev[j] + 1 else maxOf(prev[j + 1], cur[j])
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[got.size].toFloat() / want.size
    }

    /** The share of [want]'s letters that lie, in one stretch, somewhere inside [got]. */
    private fun within(want: List<String>, got: List<String>): Float {
        val a = want.joinToString("")
        val b = got.joinToString("")
        if (a.isEmpty()) return 0f
        // The first row is all noughts: the stretch may begin anywhere in what was heard.
        var prev = IntArray(b.length + 1)
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            val t = prev; prev = cur; cur = t
        }
        return (1f - prev.min().toFloat() / a.length).coerceAtLeast(0f)
    }
}
