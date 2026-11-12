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
    internal fun apart(a: String, b: String): Int {
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

    /** What [Passage.follow] makes of what has been heard so far. */
    class Followed(
        /** The lines that have been said, by their place in the passage. */
        val said: Set<Int>,
        /** For each line, which of its words as the page shows them have been heard. */
        val words: List<Set<Int>>,
    )

    /**
     * A passage being said from memory, followed as it goes.
     *
     * A voice's turn waits for one known line, and [said] is all it needs. Saying a whole
     * passage with nobody leading is another thing: nobody knows which line the speaker is
     * on, they stop, they start a line again, they leave one out. So everything heard is
     * laid against the whole passage at once, in order, and each word of it finds its
     * place or is dropped. A line left out costs a little, so one stray "and" is not taken
     * for the "and" thirty words on, and where the speaker has stopped costs nothing.
     *
     * Then each line is asked the same question a turn asks, [said], of the stretch that
     * fell to it. So a line the recogniser mangled still counts, as it does in a turn. One
     * thing is asked here that a turn does not ask: the line's end has to have been
     * reached. Four words of five is the line said, but shown at that moment it would hand
     * over the fifth while it was still being looked for.
     *
     * A word of the page is one stretch between spaces, as the card draws them; what is
     * said for it may be two ("twenty-three") or a figure's words.
     */
    class Passage(lines: List<String>) {
        private val want = ArrayList<String>()
        /** Where each line begins in [want], and one more: where the last one ends. */
        private val from = IntArray(lines.size + 1)
        /** For each line, each word the page shows, as its stretch of [want]. */
        private val shown = ArrayList<List<IntRange>>()
        /** A word heard, against every word wanted. Worked out once: words come round again. */
        private val like = HashMap<String, BooleanArray>()

        init {
            lines.forEachIndexed { l, line ->
                from[l] = want.size
                shown += line.split(' ').filter { w -> w.any { it.isLetterOrDigit() } }.map { w ->
                    val a = want.size
                    want += words(w)
                    a until want.size
                }
            }
            from[lines.size] = want.size
        }

        /** How many lines there are. */
        val size: Int get() = shown.size

        fun follow(got: List<String>): Followed {
            val n = want.size
            val m = got.size
            if (n == 0 || m == 0) return Followed(emptySet(), shown.map { emptySet() })
            val rows = Array(m) { j -> like.getOrPut(got[j]) { BooleanArray(n) { i -> alike(want[i], got[j]) } } }
            // s[i, j]: the best that can be made of the first i words wanted and the first j heard.
            val w = m + 1
            val s = IntArray((n + 1) * w)
            for (j in 1..m) s[j] = -j * DROP
            for (i in 1..n) {
                s[i * w] = -i * SKIP
                val row = i * w
                val above = row - w
                for (j in 1..m) {
                    var best = maxOf(s[above + j] - SKIP, s[row + j - 1] - DROP)
                    if (rows[j - 1][i - 1]) best = maxOf(best, s[above + j - 1] + MATCH)
                    s[row + j] = best
                }
            }
            // It need not have been finished: the earliest place it can be said to have stopped.
            var end = 0
            for (i in 1..n) if (s[i * w + m] > s[end * w + m]) end = i
            /** For each word wanted, the word heard that is it, or -1. */
            val pair = IntArray(n) { -1 }
            var i = end
            var j = m
            while (i > 0 && j > 0) {
                val here = s[i * w + j]
                when {
                    rows[j - 1][i - 1] && here == s[(i - 1) * w + j - 1] + MATCH -> { pair[i - 1] = j - 1; i--; j-- }
                    here == s[i * w + j - 1] - DROP -> j--
                    else -> i--
                }
            }

            val said = HashSet<Int>()
            val words = ArrayList<Set<Int>>(shown.size)
            for (l in shown.indices) {
                val a = from[l]
                val b = from[l + 1]
                val lit = HashSet<Int>()
                shown[l].forEachIndexed { k, r ->
                    val n1 = r.last - r.first + 1
                    if (n1 > 0 && r.count { pair[it] >= 0 } * 2 >= n1) lit += k
                }
                words += lit
                if (b == a) continue
                // What was heard for this line: all that lies between the last word heard
                // of the lines before it and the first heard of the lines after.
                var lo = -1
                for (k in a - 1 downTo 0) if (pair[k] >= 0) { lo = pair[k]; break }
                var hi = m
                for (k in b until n) if (pair[k] >= 0) { hi = pair[k]; break }
                if (hi - lo <= 1) continue
                var last = -1
                for (k in b - 1 downTo a) if (pair[k] >= 0) { last = pair[k]; break }
                // Its end has been reached: its last word was heard, or the speaker has gone
                // on to a later line, or something was said after the last of it that was
                // heard, which is the rest of the line misheard.
                val ended = pair[b - 1] >= 0 || hi < m || last < hi - 1
                if (ended && said(want.subList(a, b), got.subList(lo + 1, hi))) said += l
            }
            return Followed(said, words)
        }

        private companion object {
            const val MATCH = 8
            /** A word wanted and not heard. */
            const val SKIP = 2
            /** A word heard that is none of the passage. */
            const val DROP = 1
        }
    }
}
