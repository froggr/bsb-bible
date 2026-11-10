package ca.lampandlight

import org.junit.Assert.assertEquals
import org.junit.Test

/** Scripture references as books write them, and what each should resolve to. */
class CitationsTest {
    private val books = CANON.split(';').filter { it.isNotEmpty() }.mapIndexed { i, row ->
        val (name, osis, chapters) = row.split('|')
        Book(i + 1, name, osis, chapters.toInt(), i < 39)
    }

    private fun read(text: String) = Citations.inProse(text, books).joinToString(" / ") {
        Citations.title(it, books) + (if (it.mute != it.end) " [" + text.substring(it.end, it.mute).trim() + "]" else "") +
            " <" + text.substring(it.start, it.end) + ">"
    }

    private fun check(text: String, want: String) = assertEquals(text, want, read(text))

    @Test fun plain() {
        check("Genesis 3:8-9", "Genesis 3:8–9 <Genesis 3:8-9>")
        check("Genesis 5:22 (ESV)", "Genesis 5:22 [(ESV)] <Genesis 5:22>")
        check("as John 3:16 says", "John 3:16 <John 3:16>")
        check("Matthew 3:11", "Matthew 3:11 <Matthew 3:11>")
        check("Psalms 76:1-2", "Psalm 76:1–2 <Psalms 76:1-2>")
        check("Phillipians 2:3-4", "Philippians 2:3–4 <Phillipians 2:3-4>")
        check("(see Rom. 8:28, NIV).", "Romans 8:28 [, NIV)] <Rom. 8:28>")
        check("Song of Solomon 2:1 and Song of Songs 2:4", "Song of Solomon 2:1 <Song of Solomon 2:1> / Song of Solomon 2:4 <Song of Songs 2:4>")
        check("Revelations 3:20", "Revelation 3:20 <Revelations 3:20>")
        check("Jn. 3.16", "John 3:16 <Jn. 3.16>")
    }

    @Test fun ordinals() {
        check("1 John 5:1", "1 John 5:1 <1 John 5:1>")
        check("I John 1:9", "1 John 1:9 <I John 1:9>")
        check("II Timothy 3:16-17", "2 Timothy 3:16–17 <II Timothy 3:16-17>")
        check("First Corinthians 13:4", "1 Corinthians 13:4 <First Corinthians 13:4>")
        check("2 Cor 5:17", "2 Corinthians 5:17 <2 Cor 5:17>")
        check("When I read Mark 3:5", "Mark 3:5 <Mark 3:5>")
        check("as I Mark 3:5", "Mark 3:5 <Mark 3:5>")
    }

    @Test fun lists() {
        check("Mark 16:16,17", "Mark 16:16, 17 <Mark 16:16,17>")
        check("Numbers 4:6-7, 9, 11-12", "Numbers 4:6–7, 9, 11–12 <Numbers 4:6-7, 9, 11-12>")
        check("Hebrews 8:4-7; 9:23-24", "Hebrews 8:4–7 <Hebrews 8:4-7> / Hebrews 9:23–24 <9:23-24>")
        check("John 3:16, 2 Corinthians 5:17", "John 3:16 <John 3:16> / 2 Corinthians 5:17 <2 Corinthians 5:17>")
        check("John 1:1, 2 Corinthians 5:17", "John 1:1 <John 1:1> / 2 Corinthians 5:17 <2 Corinthians 5:17>")
        check("Genesis 1:3-2:3", "Genesis 1:3–2:3 <Genesis 1:3-2:3>")
        check("Genesis 28:15–17", "Genesis 28:15–17 <Genesis 28:15–17>")
        check("In John 3:16, 3 things stand out", "John 3:16 <John 3:16>")
    }

    @Test fun chapters() {
        check("Read Psalm 23 tonight", "Psalm 23 <Psalm 23>")
        check("Romans 8 and Hebrews 11.", "Romans 8 <Romans 8> / Hebrews 11 <Hebrews 11>")
        check("1 Corinthians 13 is the love chapter", "1 Corinthians 13 <1 Corinthians 13>")
        check("Ps 23", "")
        check("Numbers 3 times", "")
        check("Chapter 5", "")
        check("Step 1: Pray", "")
    }

    @Test fun notCitations() {
        check("He 3:16 was late", "")
        check("Is 5:30 too early? At 3:15 we left. In 2:1 odds.", "")
        check("It was 12:30 when the meeting began", "")
        check("The ratio was 3:1", "")
        check("John 99:1", "")
    }

    @Test fun said() {
        fun say(t: String) = Citations.inProse(t, books).joinToString(" / ") { Citations.spoken(it, books) }
        assertEquals("Genesis 3, verses 8 to 9", say("Genesis 3:8-9"))
        assertEquals("First John 5, verse 1", say("1 John 5:1"))
        assertEquals("Numbers 4, verses 6 to 7, 9, and 11 to 12", say("Numbers 4:6-7, 9, 11-12"))
        assertEquals("Mark 16, verses 16 and 17", say("Mark 16:16,17"))
        assertEquals("Psalm 23", say("Psalm 23"))
    }

    private companion object {
        const val CANON = "Genesis|Gen|50;Exodus|Exod|40;Leviticus|Lev|27;Numbers|Num|36;Deuteronomy|Deut|34;Joshua|Josh|24;Judges|Judg|21;Ruth|Ruth|4;1 Samuel|1Sam|31;2 Samuel|2Sam|24;1 Kings|1Kgs|22;2 Kings|2Kgs|25;1 Chronicles|1Chr|29;2 Chronicles|2Chr|36;Ezra|Ezra|10;Nehemiah|Neh|13;Esther|Esth|10;Job|Job|42;Psalm|Ps|150;Proverbs|Prov|31;Ecclesiastes|Eccl|12;Song of Solomon|Song|8;Isaiah|Isa|66;Jeremiah|Jer|52;Lamentations|Lam|5;Ezekiel|Ezek|48;Daniel|Dan|12;Hosea|Hos|14;Joel|Joel|3;Amos|Amos|9;Obadiah|Obad|1;Jonah|Jonah|4;Micah|Mic|7;Nahum|Nah|3;Habakkuk|Hab|3;Zephaniah|Zeph|3;Haggai|Hag|2;Zechariah|Zech|14;Malachi|Mal|4;Matthew|Matt|28;Mark|Mark|16;Luke|Luke|24;John|John|21;Acts|Acts|28;Romans|Rom|16;1 Corinthians|1Cor|16;2 Corinthians|2Cor|13;Galatians|Gal|6;Ephesians|Eph|6;Philippians|Phil|4;Colossians|Col|4;1 Thessalonians|1Thess|5;2 Thessalonians|2Thess|3;1 Timothy|1Tim|6;2 Timothy|2Tim|4;Titus|Titus|3;Philemon|Phlm|1;Hebrews|Heb|13;James|Jas|5;1 Peter|1Pet|5;2 Peter|2Pet|3;1 John|1John|5;2 John|2John|1;3 John|3John|1;Jude|Jude|1;Revelation|Rev|22;"
    }
}
