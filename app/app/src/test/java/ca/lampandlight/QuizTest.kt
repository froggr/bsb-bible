package ca.lampandlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuizTest {

    /** A chapter of [n] verses of John 3, and a question on each verse in [on]. */
    private fun verses(n: Int) = (1..n).map { verseId(43, 3, it) }
    private fun q(verse: Int, to: Int = verse) =
        Question("JHN/v$verse-$to", verseId(43, 3, verse), verseId(43, 3, to), "What about verse $verse?")

    @Test fun `a question closes each section long enough to be asked about`() {
        // Sections of 8, 8 and 5 verses.
        val plan = Quiz.plan(verses(21), setOf(0, 8, 16), (1..21).map { q(it) })
        assertEquals(listOf(7, 15, 20), plan.map { it.after })
        // And each is about the section it closes, not one further on.
        plan.forEachIndexed { i, a ->
            val section = listOf(1..8, 9..16, 17..21)[i]
            assertTrue(a.question.start.verse in section)
        }
    }

    @Test fun `short sections are read through until there is enough to ask about`() {
        // Genesis 1 in miniature: sections of three and four verses.
        val plan = Quiz.plan(verses(14), setOf(0, 3, 7, 10), (1..14).map { q(it) })
        // After 3 verses, nothing. After 7, a question on any of them. After 10, nothing
        // (three since the last). At the end, four more: asked, being the chapter's last.
        assertEquals(listOf(6, 13), plan.map { it.after })
        assertTrue(plan[0].question.start.verse in 1..7)
        assertTrue(plan[1].question.start.verse in 8..14)
    }

    @Test fun `a chapter with no headings is asked about once, at its end`() {
        val plan = Quiz.plan(verses(30), emptySet(), (1..30).map { q(it) })
        assertEquals(listOf(29), plan.map { it.after })
    }

    @Test fun `two verses left at the end of a chapter already asked about are let go`() {
        val plan = Quiz.plan(verses(10), setOf(0, 8), (1..10).map { q(it) })
        assertEquals(listOf(7), plan.map { it.after })
    }

    @Test fun `a short chapter is still asked about`() {
        // Psalm 117: two verses, a question on each.
        val plan = Quiz.plan(verses(2), setOf(0), listOf(q(1), q(2)))
        assertEquals(1, plan.size)
        assertEquals(1, plan[0].after)
        // Only two passages exist, so only two are offered.
        assertEquals(2, plan[0].options.size)
    }

    @Test fun `a stretch with no question on it is carried into the next`() {
        // Questions only on verses 12 to 16; the first section has none.
        val plan = Quiz.plan(verses(16), setOf(0, 8), (12..16).map { q(it) })
        assertEquals(listOf(15), plan.map { it.after })
    }

    @Test fun `the choices are three different passages and one of them is the answer`() {
        val questions = (1..21).map { q(it) } + q(3, 4)
        for (a in Quiz.plan(verses(21), setOf(0, 8, 16), questions)) {
            assertEquals(3, a.options.size)
            assertEquals(a.question.start to a.question.end, a.options[a.correct])
            // No two choices share a verse: "verses 3-4" beside "verse 4" has two answers.
            for (x in a.options) for (y in a.options) if (x !== y) {
                assertTrue("$x overlaps $y", x.second < y.first || y.second < x.first)
            }
        }
    }

    @Test fun `the other choices come from the reading just finished when it has them`() {
        val plan = Quiz.plan(verses(24), setOf(0, 8, 16), (1..24).map { q(it) })
        val second = plan[1]
        assertTrue(second.options.all { it.first.verse in 9..16 })
    }

    @Test fun `a chapter with one question is filled out with its other verses`() {
        val plan = Quiz.plan(verses(9), setOf(0), listOf(q(5)))
        assertEquals(3, plan[0].options.size)
        assertEquals(1, plan[0].options.count { it.first.verse == 5 })
    }

    @Test fun `what has not been asked comes before what was missed, and that before what was right`() {
        val all = listOf(q(1), q(2), q(3), q(4))
        val past = mapOf(
            all[0].key to Past(Quiz.RIGHT, 100),
            all[1].key to Past(Quiz.WRONG, 200),
            all[2].key to Past(Quiz.HEARD, 300),
        )
        assertEquals(all[3], Quiz.choose(all, past))
        assertEquals(all[1], Quiz.choose(all.take(3), past))
        assertEquals(all[2], Quiz.choose(listOf(all[0], all[2]), past))
        // All right before: the one answered longest ago.
        val right = mapOf(all[0].key to Past(Quiz.RIGHT, 500), all[1].key to Past(Quiz.RIGHT, 400))
        assertEquals(all[1], Quiz.choose(all.take(2), right))
    }

    @Test fun `the same chapter asks the same thing until something has been answered`() {
        val questions = (1..21).map { q(it) }
        val once = Quiz.plan(verses(21), setOf(0, 8, 16), questions)
        assertEquals(once, Quiz.plan(verses(21), setOf(0, 8, 16), questions))
        // Answer the first one, and the first section asks something else.
        val past = mapOf(once[0].question.key to Past(Quiz.RIGHT, 1_000))
        val again = Quiz.plan(verses(21), setOf(0, 8, 16), questions, past)
        assertNotEquals(once[0].question, again[0].question)
        assertEquals(once[1].question, again[1].question)
    }

    @Test fun `the first question is not always the first verse`() {
        // Over many chapters' worth of keys, the chosen verse should move about.
        val firsts = (1..40).map { c ->
            val qs = (1..8).map { Question("GEN/$c-$it", verseId(1, c, it), verseId(1, c, it), "?") }
            Quiz.choose(qs, emptyMap())!!.start.verse
        }.toSet()
        assertTrue("only $firsts", firsts.size >= 5)
    }

    // ------------------------------------------------------------------ going over

    private val minute = 60_000L
    private val day = 24 * 60 * minute

    @Test fun `a question never put is never due`() {
        assertEquals(null, Quiz.dueAt(emptyList()))
    }

    @Test fun `a missed question comes back within the sitting, a heard one too`() {
        assertEquals(1_000 + 10 * minute, Quiz.dueAt(listOf(Past(Quiz.WRONG, 1_000))))
        assertEquals(1_000 + 10 * minute, Quiz.dueAt(listOf(Past(Quiz.HEARD, 1_000))))
    }

    @Test fun `each time a missed question is then known it rests for longer`() {
        val h = mutableListOf(Past(Quiz.WRONG, 0))
        val rests = (1..7).map { n ->
            h += Past(Quiz.RIGHT, n * 1_000L)
            Quiz.dueAt(h)!! - n * 1_000L
        }
        assertEquals(listOf(1 * day, 3 * day, 7 * day, 21 * day, 60 * day, 180 * day, 180 * day), rests)
        // Missing it again starts it over.
        h += Past(Quiz.WRONG, 9_000)
        assertEquals(9_000 + 10 * minute, Quiz.dueAt(h))
    }

    @Test fun `a question known the first time is left for a week`() {
        assertEquals(500 + 7 * day, Quiz.dueAt(listOf(Past(Quiz.RIGHT, 500))))
        assertEquals(900 + 21 * day, Quiz.dueAt(listOf(Past(Quiz.RIGHT, 500), Past(Quiz.RIGHT, 900))))
    }

    @Test fun `going over offers the answer and two others from the verses around it`() {
        val questions = (1..40).map { q(it) }
        val a = Quiz.again(q(20), verses(40), questions, turn = 0)
        assertEquals(3, a.options.size)
        assertEquals(20, a.options[a.correct].first.verse)
        assertTrue(a.options.all { it.first.verse in 14..26 })
        // Laid out afresh the next time, and still right.
        val later = (1..12L).map { Quiz.again(q(20), verses(40), questions, turn = it * 977) }
        assertTrue(later.all { it.options[it.correct].first.verse == 20 })
        assertTrue(later.map { it.options }.toSet().size > 1)
    }
}
