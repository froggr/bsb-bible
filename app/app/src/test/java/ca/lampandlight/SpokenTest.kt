package ca.lampandlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reference said aloud, as the recogniser writes it. The lines marked "heard" are what it
 * made of the Mac's voices saying the reference in front of them, mistakes and all.
 */
class SpokenTest {
    private val books = listOf(
        "Genesis" to 50, "Exodus" to 40, "Leviticus" to 27, "Numbers" to 36, "Deuteronomy" to 34, "Joshua" to 24,
        "Judges" to 21, "Ruth" to 4, "1 Samuel" to 31, "2 Samuel" to 24, "1 Kings" to 22, "2 Kings" to 25,
        "1 Chronicles" to 29, "2 Chronicles" to 36, "Ezra" to 10, "Nehemiah" to 13, "Esther" to 10, "Job" to 42,
        "Psalm" to 150, "Proverbs" to 31, "Ecclesiastes" to 12, "Song of Solomon" to 8, "Isaiah" to 66,
        "Jeremiah" to 52, "Lamentations" to 5, "Ezekiel" to 48, "Daniel" to 12, "Hosea" to 14, "Joel" to 3,
        "Amos" to 9, "Obadiah" to 1, "Jonah" to 4, "Micah" to 7, "Nahum" to 3, "Habakkuk" to 3, "Zephaniah" to 3,
        "Haggai" to 2, "Zechariah" to 14, "Malachi" to 4, "Matthew" to 28, "Mark" to 16, "Luke" to 24, "John" to 21,
        "Acts" to 28, "Romans" to 16, "1 Corinthians" to 16, "2 Corinthians" to 13, "Galatians" to 6,
        "Ephesians" to 6, "Philippians" to 4, "Colossians" to 4, "1 Thessalonians" to 5, "2 Thessalonians" to 3,
        "1 Timothy" to 6, "2 Timothy" to 4, "Titus" to 3, "Philemon" to 1, "Hebrews" to 13, "James" to 5,
        "1 Peter" to 5, "2 Peter" to 3, "1 John" to 5, "2 John" to 1, "3 John" to 1, "Jude" to 1, "Revelation" to 22,
    ).mapIndexed { i, (name, chapters) -> Book(i + 1, name, name.filter { it != ' ' }.take(3), chapters, i < 39) }

    private fun said(text: String) = Reference.spoken(text, books).map { it.label }
    private fun one(text: String, want: String) = assertEquals(text, listOf(want), said(text))
    private fun none(text: String) = assertTrue("$text gave ${said(text)}", said(text).isEmpty())

    @Test fun `as it is heard`() {
        one("John three sixteen.", "John 3:16")
        one("First Corinthians thirteen.", "1 Corinthians 13")
        one("Psalm twenty three", "Psalm 23")
        one("Psalm one hundred nineteen verse one hundred and five.", "Psalm 119:105")
        one("Psalm one hundred nineteen verse one hundred five.", "Psalm 119:105")
        one("Romans chapter eight, verse twenty eight.", "Romans 8:28")
        one("Romans chapter eight verse twenty eight.", "Romans 8:28")
        one("Genesis one.", "Genesis 1")
        one("Song of Solomon Two", "Song of Solomon 2")
        one("Revelation twenty one, verses one through four.", "Revelation 21:1–4")
        one("Philippians four thirteen.", "Philippians 4:13")
        one("Jude twenty four.", "Jude 1:24")
        one("Third John", "3 John 1")
        one("Psalm one hundred and fifty.", "Psalm 150")
        one("Isaiah fifty three five.", "Isaiah 53:5")
        one("Deuteronomy six, four through nine.", "Deuteronomy 6:4–9")
        one("First Thessalonians five seventeen.", "1 Thessalonians 5:17")
        one("Matthew twenty eight nineteen.", "Matthew 28:19")
        one("Acts two", "Acts 2")
    }

    @Test fun `as it is misheard`() {
        one("Some twenty three.", "Psalm 23")
        one("Some one hundred and fifty.", "Psalm 150")
        one("June twenty four.", "Jude 1:24")
        one("Second Timothy three hundred and sixteen to seventeen", "2 Timothy 3:16–17")
        one("First Thessalonians five hundred seventeen", "1 Thessalonians 5:17")
        one("Ecklesiest three", "Ecclesiastes 3")
        one("Act two", "Acts 2")
        one("John to", "John 2")
        one("Psalm for", "Psalm 4")
    }

    @Test fun `other ways of saying it`() {
        one("Psalm one nineteen one oh five", "Psalm 119:105")
        one("Psalm one nineteen", "Psalm 119")
        one("John one nineteen", "John 1:19")
        one("the book of Ruth chapter two", "Ruth 2")
        one("the gospel of John three sixteen", "John 3:16")
        one("Genesis one one", "Genesis 1:1")
        one("John three verse sixteen", "John 3:16")
        one("John three sixteen and seventeen", "John 3:16–17")
        one("Proverbs three five to ten", "Proverbs 3:5–10")
        one("Jude one twenty four", "Jude 1:24")
        one("Jude verse three", "Jude 1:3")
        one("Revelations twenty two", "Revelation 22")
        one("Song of Songs two one", "Song of Solomon 2:1")
        one("Genesis one through three", "Genesis 1")
    }

    @Test fun `with figures`() {
        one("song of solomon 2:1", "Song of Solomon 2:1")
        one("john chapter 3 verse 16", "John 3:16")
        one("1 Corinthians 13:4-7", "1 Corinthians 13:4–7")
        one("2nd Timothy 3 16", "2 Timothy 3:16")
    }

    @Test fun `a book of two or three`() {
        assertEquals(listOf("1 Kings 8", "2 Kings 8"), said("Kings eight"))
        assertEquals(listOf("1 Corinthians 13", "2 Corinthians 13"), said("Corinthians thirteen"))
        one("John three", "John 3")
        one("Second Kings two", "2 Kings 2")
    }

    @Test fun `words are not a reference`() {
        none("The Lord is my shepherd")
        none("John the Baptist")
        none("love one another")
        none("faith hope and love")
        none("in the beginning")
        none("three sixteen")
        none("John three sixteen is my favourite")
        none("June")
        none("some")
        none("John forty")
        none("")
    }

    @Test fun `a name by itself`() {
        one("Philemon", "Philemon 1")
        one("Song of Solomon", "Song of Solomon 1")
    }
}
