package ca.lampandlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A passage said from memory with nobody leading, followed line by line. */
class FollowTest {
    private val psalm = listOf(
        "The LORD is my shepherd;",
        "I shall not want.",
        "He makes me lie down in green pastures;",
        "He leads me beside quiet waters.",
        "He restores my soul;",
        "He guides me in the paths of righteousness",
        "for the sake of His name.",
    )

    private fun follow(lines: List<String>, heard: String) = Heard.Passage(lines).follow(Heard.words(heard))

    @Test fun `said straight through, every line is said and every word heard`() {
        val f = follow(psalm, psalm.joinToString(" "))
        assertEquals(psalm.indices.toSet(), f.said)
        psalm.forEachIndexed { i, line -> assertEquals(line, line.split(' ').indices.toSet(), f.words[i]) }
    }

    @Test fun `nothing heard is nothing said`() {
        val f = follow(psalm, "")
        assertTrue(f.said.isEmpty())
        assertEquals(psalm.size, f.words.size)
        assertTrue(f.words.all { it.isEmpty() })
    }

    @Test fun `a line stopped short of its last word is filled so far and not given away`() {
        val f = follow(psalm, "the lord is my")
        assertTrue(f.said.isEmpty())
        assertEquals(setOf(0, 1, 2, 3), f.words[0])
        // And then it comes.
        assertEquals(setOf(0), follow(psalm, "the lord is my shepherd").said)
    }

    @Test fun `it fills in as far as the speaker has got`() {
        val f = follow(psalm, "the lord is my shepherd i shall not want he makes me lie")
        assertEquals(setOf(0, 1), f.said)
        assertEquals(setOf(0, 1, 2, 3), f.words[2])
        assertTrue(f.words[3].isEmpty())
    }

    // What the recogniser really made of that line, in the middle of the others.
    @Test fun `a line misheard is still a line said`() {
        val f = follow(
            psalm,
            "The Lord is my shepherd. I shall not want. He makes me lie down in green pastures. " +
                "He leaves me the sides wild waters. He restores my soul.",
        )
        assertEquals(setOf(0, 1, 2, 3, 4), f.said)
    }

    @Test fun `its last word misheard, the line is still said`() {
        assertEquals(setOf(0), follow(psalm, "the lord is my chef heard").said)
        assertEquals(setOf(0, 1), follow(psalm, "the lord is my shepard i shall not wand").said)
    }

    @Test fun `a line left out is not said, and the ones round it are`() {
        val f = follow(psalm, "the lord is my shepherd he makes me lie down in green pastures")
        assertEquals(setOf(0, 2), f.said)
        assertTrue(f.words[1].isEmpty())
    }

    @Test fun `begun again, it is followed from where it was begun again`() {
        val f = follow(psalm, "the lord is my shepherd i shall the lord is my shepherd i shall not want")
        assertEquals(setOf(0, 1), f.said)
    }

    @Test fun `something else is not the passage`() {
        assertTrue(follow(psalm, "what was it again").said.isEmpty())
        assertTrue(follow(psalm, "I do not know this one, can you tell me how it goes").said.isEmpty())
        assertTrue(follow(psalm, "for God so loved the world that he gave his one and only son").said.isEmpty())
    }

    @Test fun `a stray word is not taken for the same word far on`() {
        val f = follow(psalm, "the lord is my shepherd of")
        assertEquals(setOf(0), f.said)
        // "of" is in the sixth line and the seventh, and nobody has got there.
        assertTrue(f.words.drop(1).all { it.isEmpty() })
    }

    @Test fun `a word the page joins and a figure are one word of the page`() {
        val lines = listOf("He was forty-two years old", "and reigned 3 months in Jerusalem.")
        val f = follow(lines, "he was forty two years old and reigned three months in jerusalem")
        assertEquals(setOf(0, 1), f.said)
        assertEquals(setOf(0, 1, 2, 3, 4), f.words[0])
        assertEquals(setOf(0, 1, 2, 3, 4, 5), f.words[1])
    }

    @Test fun `the same line twice in a passage is two lines`() {
        val lines = listOf("Holy, holy, holy", "is the LORD of Hosts;", "Holy, holy, holy", "is His name.")
        assertEquals(setOf(0, 1), follow(lines, "holy holy holy is the lord of hosts").said)
        assertEquals(setOf(0, 1, 2, 3), follow(lines, "holy holy holy is the lord of hosts holy holy holy is his name").said)
    }

    @Test fun `a long passage and a great deal heard are still followed`() {
        val long = List(4) { psalm }.flatten()
        val heard = List(6) { psalm.joinToString(" ") }.joinToString(" ")
        assertEquals(long.indices.toSet(), follow(long, heard).said)
    }

    @Test fun `only its end heard, a long line is not said`() {
        val line = listOf("He guides me in the paths of righteousness for the sake of His name.")
        assertFalse(0 in follow(line, "for the sake of his name").said)
    }
}
