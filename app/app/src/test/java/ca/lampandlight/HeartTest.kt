package ca.lampandlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartTest {
    private val day = 24 * 60 * 60_000L

    @Test fun `a verse is broken where it breaks itself, and a long stretch before a joining word`() {
        assertEquals(
            listOf(
                "For God so loved the world",
                "that He gave His one and only Son,",
                "that everyone who believes in Him",
                "shall not perish but have eternal life.",
            ),
            ByHeart.phrases(
                "For God so loved the world that He gave His one and only Son, that everyone " +
                    "who believes in Him shall not perish but have eternal life."
            ),
        )
        assertEquals(
            listOf("The LORD is my shepherd;", "I shall not want."),
            ByHeart.phrases("The LORD is my shepherd; I shall not want."),
        )
    }

    @Test fun `a short verse is one phrase and nothing is lost or reordered`() {
        assertEquals(listOf("Jesus wept."), ByHeart.phrases("Jesus wept."))
        assertEquals(listOf("We love because He first loved us."), ByHeart.phrases("We love because He first loved us."))
        for (text in listOf(
            "Trust in the LORD with all your heart, and lean not on your own understanding;",
            "And we know that God works all things together for the good of those who love Him, who are called according to His purpose.",
            "“Yes, yes,” he said, “go.”",
            "Rejoice in the Lord always. I will say it again: Rejoice!",
        )) {
            val got = ByHeart.phrases(text)
            assertEquals(text, got.joinToString(" "))
            assertTrue("$got", got.all { it.split(" ").size <= 10 })
            assertTrue("$got", got.size == 1 || got.all { it.split(' ').size >= 3 })
        }
    }

    @Test fun `new until said, letters until twice right running, and a miss brings the letters back`() {
        assertEquals(ByHeart.NEW, ByHeart.level(emptyList()))
        assertEquals(ByHeart.LETTERS, ByHeart.level(listOf(Past(Quiz.RIGHT, 1))))
        assertEquals(ByHeart.BLANK, ByHeart.level(listOf(Past(Quiz.RIGHT, 1), Past(Quiz.RIGHT, 2))))
        assertEquals(
            ByHeart.LETTERS,
            ByHeart.level(listOf(Past(Quiz.RIGHT, 1), Past(Quiz.RIGHT, 2), Past(Quiz.WRONG, 3))),
        )
    }

    @Test fun `due at once when new, in ten minutes when missed, then a day, three and a week`() {
        assertEquals(500L, ByHeart.dueAt(500L, emptyList()))
        assertEquals(1000L + 10 * 60_000L, ByHeart.dueAt(0, listOf(Past(Quiz.WRONG, 1000L))))
        assertEquals(1000L + day, ByHeart.dueAt(0, listOf(Past(Quiz.RIGHT, 1000L))))
        assertEquals(2000L + 3 * day, ByHeart.dueAt(0, listOf(Past(Quiz.RIGHT, 1000L), Past(Quiz.RIGHT, 2000L))))
        assertEquals(
            3000L + 7 * day,
            ByHeart.dueAt(0, listOf(Past(Quiz.WRONG, 1L), Past(Quiz.RIGHT, 1000L), Past(Quiz.RIGHT, 2000L), Past(Quiz.RIGHT, 3000L))),
        )
    }

    @Test fun `the voice leaves room before each phrase once the verse has been heard`() {
        val phrases = listOf("The LORD is my shepherd;", "I shall not want.")
        val lines = ByHeart.script(2, "Psalm 23, verse 1.", phrases, ByHeart.LETTERS)
        val at = ByHeart.base(2)
        assertEquals("Psalm 23, verse 1.", lines.first().preamble)
        // Room for the phrase, then the phrase, for each; then the whole verse.
        val shape = lines.drop(1).map { (it.verse - at) to (it.silence > 0) }
        assertEquals(
            listOf(0 to true, 0 to false, 1 to true, 1 to false, ByHeart.NAME to true, ByHeart.WHOLE to false),
            shape,
        )
        // Hands-free, every room is timed. On the card the listener's turns are theirs to
        // end, and the breath before the whole verse is still the voice's own.
        assertTrue(lines.none { it.hold })
        val tapped = ByHeart.script(2, "Psalm 23, verse 1.", phrases, ByHeart.LETTERS, tap = true)
        assertEquals(listOf(0, 1), tapped.filter { it.hold }.map { it.verse - at })
        assertTrue(tapped.all { !it.hold || it.silence > 0 })
        assertEquals("The LORD is my shepherd; I shall not want.", lines.last().text)
        // New: said first, and the room comes after each phrase.
        val fresh = ByHeart.script(0, "Psalm 23, verse 1.", phrases, ByHeart.NEW)
        assertEquals("Psalm 23, verse 1.", fresh.first().preamble)
        assertTrue(fresh.first().silence == 0L && fresh.first().words.isNotEmpty())
        val i = fresh.indexOfFirst { it.text == phrases[0] }
        assertTrue(fresh[i + 1].silence > 0)
        assertEquals(
            listOf(0, 1),
            ByHeart.script(0, "Psalm 23, verse 1.", phrases, ByHeart.NEW, tap = true)
                .filter { it.hold }.map { it.verse - ByHeart.base(0) },
        )
        // From the reference alone: one silence long enough for all of it.
        val blank = ByHeart.script(0, "Psalm 23, verse 1.", phrases, ByHeart.BLANK)
        assertEquals(1, blank.count { it.silence > 0 })
        assertTrue(blank.first { it.silence > 0 }.silence > 4000)
    }

    @Test fun `a book with a number is named as it is said`() {
        assertEquals("First John 4, verse 19.", ByHeart.spoken("1 John", verseId(62, 4, 19)))
        assertEquals("John 3, verse 16.", ByHeart.spoken("John", verseId(43, 3, 16)))
        assertEquals("Psalm 23, verse 1.", ByHeart.spoken("Psalms", verseId(19, 23, 1)))
    }
}
