package ca.lampandlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class HeardTest {
    @Test fun `words are bare, a figure is said, and an apostrophe does not split`() {
        assertEquals(listOf("the", "lords", "day"), Heard.words("The LORD’s day,"))
        assertEquals(listOf("forty", "days", "and", "forty", "nights"), Heard.words("40 days and 40 nights."))
        assertEquals(listOf("one", "hundred", "forty", "four", "thousand"), Heard.words("144000"))
        assertEquals(listOf("i", "shall", "not", "want"), Heard.words("  I shall not — want! "))
        assertEquals(emptyList<String>(), Heard.words(" … "))
    }

    // What the recogniser really returned for these lines, said by synthetic voices.
    @Test fun `a line misheard is still a line said`() {
        assertTrue(Heard.said("He leads me beside quiet waters.", "He leaves me the sides wild waters."))
        assertTrue(Heard.said("I will fear no evil,", "I will say no eagle."))
        assertTrue(Heard.said("For God so loved the", "The God so loved it."))
        assertTrue(Heard.said("The LORD is my shepherd;", "the lord is my shepherd"))
        assertTrue(Heard.said("I shall not want.", "Um. I shall not want"))
        assertTrue(Heard.said("to the glory of God", "two the glory of god"))
    }

    @Test fun `something else, or part of it, is not the line`() {
        assertFalse(Heard.said("Shadrach, Meshach,", "Should drop Meshack."))
        assertFalse(Heard.said("The LORD is my shepherd;", ""))
        assertFalse(Heard.said("The LORD is my shepherd;", "my shepherd"))
        assertFalse(Heard.said("The LORD is my shepherd;", "I shall not want"))
        assertFalse(Heard.said("He leads me beside quiet waters.", "what was it again"))
        assertFalse(Heard.said("In the beginning God created", "in the end"))
    }

    @Test fun `said in two goes, it is said`() {
        assertTrue(Heard.said("that He gave His one and only Son,", "that he gave his. One and only son."))
    }

    private val psalm =
        "The LORD is my shepherd; I shall not want. He makes me lie down in green pastures; " +
            "He leads me beside quiet waters. He restores my soul; He guides me in the paths " +
            "of righteousness for the sake of His name."

    @Test fun `a long passage is not ended at a pause`() {
        val most = "the lord is my shepherd i shall not want he makes me lie down in green pastures " +
            "he leads me beside quiet waters he restores my soul he guides me in the paths"
        assertTrue(Heard.score(Heard.words(psalm), Heard.words(most)) >= Heard.ENOUGH)
        assertFalse(Heard.said(psalm, most))
        assertTrue(Heard.said(psalm, "$most of righteousness for the sake of his name"))
        // Its end misheard, as its end will be.
        assertTrue(Heard.said(psalm, "$most of righteousness for the sake of is name."))
    }

    @Test fun `near words are the word, short ones only when they are it`() {
        assertTrue(Heard.alike("shepherd", "shepard"))
        assertTrue(Heard.alike("righteousness", "righteousnes"))
        assertTrue(Heard.alike("for", "four"))
        assertFalse(Heard.alike("not", "now"))
        assertFalse(Heard.alike("want", "water"))
    }

    /**
     * The scores this was tuned at, worked out by the script it was tried in: a line, what
     * was heard, and the score, tab between, one to a row. They are `data/heard_pairs.tsv`;
     * HEARD_PAIRS names another file.
     */
    @Test fun `it scores as it was measured`() {
        val file = File(System.getenv("HEARD_PAIRS") ?: "../../data/heard_pairs.tsv")
        assumeTrue(file.exists())
        var n = 0
        file.forEachLine { row ->
            val (want, got, score) = row.split('\t').takeIf { it.size == 3 } ?: return@forEachLine
            assertEquals("$want | $got", score.toFloat(), Heard.score(Heard.words(want), Heard.words(got)), 0.0005f)
            n++
        }
        assertTrue("no rows", n > 100)
    }
}
