package ca.lampandlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesTest {
    private val day = 24 * 60 * 60_000L
    private val now = 1_800_000_000_000L

    @Test fun `a chapter is one number`() {
        assertEquals(43002, Places.key(43, 2))
        val p = Place(43002, readAt = now)
        assertEquals(43, p.book)
        assertEquals(2, p.number)
    }

    @Test fun `the five books of the Psalms are not part of a psalm's heading`() {
        assertEquals("The Two Paths", Places.title("BOOK I Psalms 1–41 The Two Paths"))
        assertEquals(
            "As the Deer Pants for the Water",
            Places.title("BOOK II Psalms 42–72 As the Deer Pants for the Water"),
        )
        assertEquals("Thanksgiving for Deliverance", Places.title("BOOK V Psalms 107–150 Thanksgiving for Deliverance"))
    }

    @Test fun `Psalm 119 is asked for by its heading and not by twenty-two letters`() {
        val text = listOf("Your Word Is a Lamp to My Feet — א ALEPH", "ב BETH", "ג GIMEL", null, "ת TAW")
        assertEquals(listOf("Your Word Is a Lamp to My Feet"), Places.outline(text))
        assertNull(Places.title("ב BETH"))
    }

    @Test fun `who is speaking in the Song of Songs is not a section`() {
        assertEquals(
            listOf("The Bride’s Admiration"),
            Places.outline(listOf("The Bride’s AdmirationThe Bride", "The Bridegroom", "The Bride", "The Friends")),
        )
        assertEquals("Solomon Admires His Bride", Places.title("Solomon Admires His BrideThe Bridegroom"))
        assertEquals("Together in the Garden", Places.title("Together in the GardenThe Friends"))
        // Headings that only mention them are left alone.
        assertEquals("The Bride Confesses Her Love", Places.title("The Bride Confesses Her Love"))
        assertEquals("Admiration by the Bridegroom", Places.title("Admiration by the Bridegroom"))
    }

    @Test fun `an outline keeps the order and says each heading once`() {
        assertEquals(
            listOf("The Wedding at Cana", "Jesus Cleanses the Temple"),
            Places.outline(listOf("The Wedding at Cana", null, "Jesus Cleanses the Temple", "The Wedding at Cana")),
        )
    }

    @Test fun `reading a chapter takes at least ten seconds and at most a minute to count`() {
        assertEquals(10_000L, Places.enough(2))      // Psalm 117
        assertEquals(31_500L, Places.enough(21))
        assertEquals(60_000L, Places.enough(176))    // Psalm 119
    }

    @Test fun `a chapter read and never asked for is due from the moment it was read`() {
        assertEquals(now, Place(43002, readAt = now).due)
        assertFalse(Place(43002, readAt = now).known)
    }

    @Test fun `a chapter is known while the last time it was asked for it was had`() {
        val had = Place(43002, now, listOf(Past(Quiz.WRONG, now), Past(Quiz.RIGHT, now + 600_000)))
        assertTrue(had.known)
        assertTrue(had.due > now + day)
        val lost = Place(43002, now, listOf(Past(Quiz.RIGHT, now), Past(Quiz.WRONG, now + 7 * day)))
        assertFalse(lost.known)
        assertEquals(now + 7 * day + 600_000, lost.due)
    }

    @Test fun `what is waiting is what has come due, then what was read in the last days`() {
        val places = listOf(
            Place(1001, readAt = now - 10 * day),                                        // read long ago, never asked
            Place(1002, readAt = now - 2 * day),                                         // fresh
            Place(1003, readAt = now - 1 * day),                                         // fresher
            Place(1004, readAt = now - 30 * day, history = listOf(Past(Quiz.WRONG, now - 2 * day))),
            Place(1005, readAt = now - 30 * day, history = listOf(Past(Quiz.WRONG, now - 5 * day))),
            Place(1006, readAt = now - 30 * day, history = listOf(Past(Quiz.RIGHT, now - day))),   // not due for a week
        )
        assertEquals(listOf(1005, 1004, 1003, 1002), Places.waiting(places, now).map { it.chapter })
    }

    @Test fun `the chapter before goes back through the books and round from Genesis to Revelation`() {
        val chapters = listOf(50, 40, 27) + List(62) { 1 } + listOf(22)
        assertEquals(1 to 49, Places.before(1, 50, chapters))
        assertEquals(1 to 50, Places.before(2, 1, chapters))
        assertEquals(2 to 40, Places.before(3, 1, chapters))
        assertEquals(66 to 22, Places.before(1, 1, chapters))
    }

    @Test fun `the question is said with the book's name in full`() {
        assertEquals("What was 1 John 2 about?", Places.asks("1 John", 2))
        assertEquals("What was First John 2 about?", Places.asks("1 John", 2, spoken = true))
    }

    @Test fun `the count names what is waiting`() {
        assertNull(GoOver.count(0, 0, 0))
        assertEquals("One verse to say", GoOver.count(1, 0, 0))
        assertEquals("4 questions from what you have read", GoOver.count(0, 4, 0))
        assertEquals("2 chapters to call to mind", GoOver.count(0, 0, 2))
        assertEquals("One verse to say and 4 questions", GoOver.count(1, 4, 0))
        assertEquals("4 questions and one chapter", GoOver.count(0, 4, 1))
        assertEquals("3 verses to say, 4 questions and 2 chapters", GoOver.count(3, 4, 2))
    }
}
