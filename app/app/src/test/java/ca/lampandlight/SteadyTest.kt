package ca.lampandlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

class SteadyTest {
    /** A second of a 200 Hz tone at [peak], with [air] samples of silence after it. */
    private fun tone(peak: Float, air: Int = 0, hz: Double = 200.0) =
        FloatArray(Steady.RATE + air) { if (it < Steady.RATE) (peak * sin(2 * PI * hz * it / Steady.RATE)).toFloat() else 0f }

    private fun db(x: Float) = 20f * log10(x)

    @Test fun `a quiet clause and a loud one come out close together`() {
        val quiet = tone(0.03f); val loud = tone(0.35f)
        val apart = db(Steady.level(loud)) - db(Steady.level(quiet))
        Steady.even(quiet); Steady.even(loud)
        val now = db(Steady.level(loud)) - db(Steady.level(quiet))
        assertTrue("was $apart dB apart, now $now", apart > 20f && now < 9f)
    }

    @Test fun `nothing leaves above the ceiling`() {
        // Quiet speech with one great peak in it: turned up, the peak would pass full scale.
        val x = tone(0.05f)
        for (i in 12_000 until 12_060) x[i] = if (i % 2 == 0) 0.9f else -0.9f
        val told = Steady.even(x)
        assertTrue(told.held < 0f)
        assertTrue("peak ${x.maxOf { abs(it) }}", x.all { abs(it) <= Steady.CEILING + 1e-4f })
    }

    @Test fun `the limiter turns down and does not clip`() {
        // A tone far over the ceiling comes out a tone: no run of samples flattened at the top.
        val x = tone(0.6f, hz = 150.0)
        Steady.even(x)
        var flat = 0; var longest = 0
        for (i in 1 until x.size) {
            if (abs(x[i]) > Steady.CEILING * 0.999f && abs(x[i] - x[i - 1]) < 1e-5f) { flat++; if (flat > longest) longest = flat } else flat = 0
        }
        assertTrue("a flat top of $longest samples", longest < 3)
    }

    @Test fun `silence stays silence and the length does not change`() {
        val x = tone(0.2f, air = 9600)
        val n = x.size
        Steady.even(x)
        assertEquals(n, x.size)
        // 100 ms into the pause there is nothing left of the filter's tail.
        assertTrue(x.drop(Steady.RATE + 2400).all { abs(it) < 1e-3f })
        val none = FloatArray(4800)
        assertEquals(0f, Steady.even(none).gain, 0f)
        assertTrue(none.all { it == 0f })
    }

    @Test fun `a clause is never moved by more than it may be`() {
        val x = tone(0.001f)
        val told = Steady.even(x)
        assertTrue(told.gain <= Steady.MOST * Steady.SHARE + 1e-3f)
    }

    @Test fun `the pause after a clause is not counted as its level`() {
        val bare = Steady.level(tone(0.2f))
        val aired = Steady.level(tone(0.2f, air = 24_000))
        assertEquals(db(bare), db(aired), 0.2f)
    }
}
