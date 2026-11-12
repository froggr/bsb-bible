package ca.lampandlight

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Makes one clause of the voice as loud as the last, and keeps its peaks under a ceiling.
 *
 * The model sets its own level clause by clause, and a voice with character sets it
 * wildly: forty verses of Methuselah measured 13 dB apart, quietest clause to loudest,
 * where Spencer's lay within 3, and five of the forty peaked above 0.7 of full scale with
 * the bed still to be added on top. Nothing between the model and the speaker evened that
 * out, so a loud clause arrived as a jump and its peaks met the top of the mix. Scripture
 * Radio plays the same voice through a compressor and a limiter; this is the same two
 * things, done once on the finished clause, where the whole of it can be seen.
 *
 * Three steps, all plain arithmetic on the samples:
 *  - a high-pass at [FLOOR_HZ], below any voice, for what a small speaker can only rattle at;
 *  - one gain for the whole clause, most of the way to [TARGET] and never more than [MOST]
 *    either way, so a whisper is not made a shout and a single short word is not trusted;
 *  - a limiter that looks [AHEAD] samples forward and eases the gain down before a peak
 *    and back up after it. It never clips: no sample is changed in shape, only in size.
 */
object Steady {
    const val RATE = 24_000
    /** Where a clause's speech is brought to: -21 dB, where Spencer already sits. */
    val TARGET = 10f.pow(-21f / 20f)
    /** No sample leaves above this. The bed, at the top of its slider, has the rest. */
    const val CEILING = 0.7f
    /** The most a clause is turned up or down, in dB, and how much of the way it is taken. */
    const val MOST = 8f
    const val SHARE = 0.8f
    const val FLOOR_HZ = 60.0
    /** 4 ms: how far ahead the limiter sees, and how long it takes to ease down. */
    const val AHEAD = 96
    private const val RELEASE_S = 0.060
    private const val FRAME = 480

    /** What was done to a clause, for the log: dB of gain, and the limiter's deepest dB. */
    class Told(val gain: Float, val held: Float)

    /** The level of the speech in [pcm], leaving out its pauses: frames within 30 dB of the loudest. */
    fun level(pcm: FloatArray, from: Int = 0, to: Int = pcm.size): Float {
        val n = (to - from) / FRAME
        if (n <= 0) return 0f
        val e = DoubleArray(n)
        var top = 0.0
        for (f in 0 until n) {
            var s = 0.0
            val at = from + f * FRAME
            for (i in at until at + FRAME) s += pcm[i].toDouble() * pcm[i]
            e[f] = s / FRAME
            if (e[f] > top) top = e[f]
        }
        if (top <= 0.0) return 0f
        val gate = top / 1000.0
        var sum = 0.0
        var kept = 0
        for (v in e) if (v > gate) { sum += v; kept++ }
        return sqrt(sum / kept).toFloat()
    }

    /** Even out [pcm] where it lies and say what was done. Its length does not change. */
    fun even(pcm: FloatArray): Told {
        if (pcm.isEmpty()) return Told(0f, 0f)
        highPass(pcm)
        val now = level(pcm)
        if (now < 1e-4f) return Told(0f, 0f)
        val db = (20f * log10(TARGET / now)).coerceIn(-MOST, MOST) * SHARE
        val gain = 10f.pow(db / 20f)
        for (i in pcm.indices) pcm[i] *= gain
        return Told(db, limit(pcm))
    }

    private fun highPass(x: FloatArray) {
        val w = 2.0 * PI * FLOOR_HZ / RATE
        val alpha = sin(w) / (2.0 * 0.7071)
        val c = cos(w)
        val a0 = 1.0 + alpha
        val b0 = (1.0 + c) / 2.0 / a0
        val b1 = -(1.0 + c) / a0
        val a1 = -2.0 * c / a0
        val a2 = (1.0 - alpha) / a0
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val v = x[i].toDouble()
            val y = b0 * v + b1 * x1 + b0 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = v; y2 = y1; y1 = y
            x[i] = y.toFloat()
        }
    }

    /**
     * Hold every sample of [x] under [CEILING]; returns the deepest cut in dB (0 for none).
     *
     * For each sample the gain it would need is worked out; each takes the least needed by
     * any of the [AHEAD] after it, so the cut is in place before the peak arrives; the gain
     * recovers slowly; and the whole is averaged over the [AHEAD] before, which turns the
     * step down into a slope. An average of values none of which is too high for this
     * sample is not too high for it either, so the ceiling holds exactly.
     */
    private fun limit(x: FloatArray): Float {
        val n = x.size
        var over = false
        for (v in x) if (abs(v) > CEILING) { over = true; break }
        if (!over) return 0f
        val need = FloatArray(n) { val a = abs(x[it]); if (a > CEILING) CEILING / a else 1f }
        // The least of need[i..i+AHEAD], by a queue of the places that could still be it.
        val least = FloatArray(n)
        val queue = IntArray(n)
        var head = 0; var tail = 0
        var next = 0
        for (i in 0 until n) {
            val end = minOf(n - 1, i + AHEAD)
            while (next <= end) {
                while (tail > head && need[queue[tail - 1]] >= need[next]) tail--
                queue[tail++] = next++
            }
            while (queue[head] < i) head++
            least[i] = need[queue[head]]
        }
        val rise = (1.0 - exp(-1.0 / (RELEASE_S * RATE))).toFloat()
        var g = 1f
        for (i in 0 until n) { g = minOf(least[i], g + (1f - g) * rise); least[i] = g }
        // The average of the AHEAD before each sample, itself included; before the start
        // the gain was 1.
        var sum = AHEAD.toDouble()
        var deepest = 1f
        for (i in 0 until n) {
            sum += least[i] - (if (i >= AHEAD) need[i - AHEAD] else 1f)
            need[i] = least[i]          // kept for when it leaves the window
            val smooth = (sum / AHEAD).toFloat().coerceAtMost(1f)
            if (smooth < deepest) deepest = smooth
            x[i] *= smooth
        }
        return 20f * log10(deepest)
    }
}
