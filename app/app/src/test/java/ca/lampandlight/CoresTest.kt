package ca.lampandlight

import org.junit.Assert.assertEquals
import org.junit.Test

/** The two decisions in [Cores], on the phones they were written for. */
class CoresTest {
    // Pixel 9: four small, three middle, one large. Off screen the app is held to 0-6.
    private val pixel9 = intArrayOf(250, 250, 250, 250, 740, 740, 740, 1024)
    // Pixel 8: four small, four middle, one large; off screen 0-7.
    private val pixel8 = intArrayOf(250, 250, 250, 250, 700, 700, 700, 700, 1024)
    // Pixel 6: four small, two middle, two large; off screen 0-5.
    private val pixel6 = intArrayOf(240, 240, 240, 240, 620, 620, 1024, 1024)
    private val alike4 = intArrayOf(1024, 1024, 1024, 1024)
    private val alike8 = IntArray(8) { 1024 }

    @Test fun `threads are counted by the fast cores left off screen`() {
        assertEquals(3, Cores.width(pixel9, null))
        assertEquals(3, Cores.width(pixel9, (0..6).toSet()))
        assertEquals(4, Cores.width(pixel8, null))
        assertEquals(4, Cores.width(pixel8, (0..7).toSet()))
        assertEquals(2, Cores.width(pixel6, null))
        assertEquals(2, Cores.width(pixel6, (0..5).toSet()))
    }

    @Test fun `middle cores are fast whatever number they report`() {
        // A Pixel 6 as its kernel describes it: the middle pair at well under half the large.
        val real6 = intArrayOf(124, 124, 124, 124, 427, 427, 1024, 1024)
        assertEquals(setOf(4, 5, 6, 7), Cores.fast(real6))
        assertEquals(2, Cores.width(real6, null))
        assertEquals(setOf(4, 5), Cores.choose(real6, (0..5).toSet(), 2))
        // No small cores at all: six alike and two a little faster.
        val allBig = intArrayOf(800, 800, 800, 800, 800, 800, 1024, 1024)
        assertEquals((0..7).toSet(), Cores.fast(allBig))
        assertEquals(4, Cores.width(allBig, null))
    }

    @Test fun `cores that are all alike keep the old half`() {
        assertEquals(2, Cores.width(alike4, null))
        assertEquals(4, Cores.width(alike8, null))
    }

    @Test fun `a sighting that leaves no fast core is not believed`() {
        // Held to the small cores for a moment: not a reason to run two threads for ever.
        assertEquals(3, Cores.width(pixel9, (0..3).toSet()))
    }

    @Test fun `on screen the large core is in, off screen the threads keep to the middle`() {
        assertEquals(setOf(4, 5, 6, 7), Cores.choose(pixel9, (0..7).toSet(), 3))
        assertEquals(setOf(4, 5, 6), Cores.choose(pixel9, (0..6).toSet(), 3))
        // Held to the small cores, there is nothing to choose between.
        assertEquals(setOf(0, 1, 2, 3), Cores.choose(pixel9, (0..3).toSet(), 3))
    }

    @Test fun `alike cores are all left in`() {
        assertEquals(setOf(0, 1, 2, 3), Cores.choose(alike4, (0..3).toSet(), 2))
        assertEquals(emptySet<Int>(), Cores.choose(alike4, emptySet(), 2))
    }

    @Test fun `a set of cores reads back as written`() {
        assertEquals("4-6", Cores.span(setOf(4, 5, 6)))
        assertEquals("0-3,7", Cores.span(setOf(7, 0, 1, 2, 3)))
        assertEquals("", Cores.span(emptySet()))
        assertEquals(setOf(0, 1, 2, 3, 7), Cores.parse("0-3,7"))
        assertEquals(emptySet<Int>(), Cores.parse("nonsense"))
    }
}
