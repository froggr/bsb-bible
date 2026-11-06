package ca.lampandlight

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Quiet music under the reading.
 *
 * Three things make a bed a bed rather than a song playing:
 *
 *  - It never stops and never restarts. The file's run-in plays once, then a region whose end
 *    genuinely continues into its start repeats for as long as you read.
 *  - It gets out of the way of the voice. Speech and music in the same band fight, so the
 *    bed drops while a verse is sounding and comes back up between them — the ducking a
 *    radio desk does, slow enough not to pump.
 *  - It is quiet by default. The bed is meant to be noticed only if you listen for it.
 *
 * AudioTrack over decoded PCM, not a media player, and the reason is specific.
 *
 * ExoPlayer cannot loop a REGION of an Opus file without a gap. ClippingMediaPeriod signals an
 * initial discontinuity whenever the clip starts anywhere but zero, unless the format is one
 * where every sample is a sync sample — and ExoPlayer's MimeTypes.allSamplesAreSyncSamples()
 * lists MP3, FLAC, AC3 and raw PCM but NOT Opus, even though every Opus packet is in fact
 * independently decodable. That discontinuity resets the renderers, which flushes the audio
 * sink, which releases and rebuilds the AudioTrack. Measured on device: the playback position
 * froze for 100-150 ms at every wrap, with both REPEAT_MODE_ONE and a playlist of identical
 * clipped items. That is the gap, and it is not reachable from the MediaItem API.
 *
 * Writing the loop's own samples round and round cannot gap: no seek, no second decode, no
 * renderer to reset. Measured here: two consecutive 22.304 s passes took 22.175 s and 22.441 s
 * of wall clock and the playback head never stalled. It is also what the narrator already does.
 * The cost is memory — about 2 MB per 22 s of loop at 24 kHz mono.
 */
class Bed(private val ctx: Context) {

    /** The chosen bed, [ROTATE] to move through them all, or null for silence. */
    var track by mutableStateOf<String?>(null); private set

    /**
     * Which bed is sounding right now.
     *
     * The same as [track] for a fixed choice; under [ROTATE] it is whichever one the rotation
     * has reached, so the player can say what is playing rather than just "Rotating".
     */
    var sounding by mutableStateOf<String?>(null); private set

    /** Beds already played this round, so rotation covers them all before repeating. */
    private val played = mutableSetOf<String>()

    /** How loud the bed sits when nothing is being read, 0f to 1f. */
    var level by mutableFloatStateOf(0.5f); private set

    var playing by mutableStateOf(false); private set

    /** True while narration is sounding, so the bed holds itself down. */
    private var ducked = false

    @Volatile private var live: AudioTrack? = null
    private var pump: Job? = null
    private var fade: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    /**
     * Decoded loops, kept so switching back to a bed does not decode it again.
     *
     * Every bed, not a few: rotation visits all of them, and a cache of three meant almost
     * every turn paid for a fresh decode, which is the pause that was heard between beds.
     *
     * Only the LOOPS are kept. All eight loops are 40 MB at 24 kHz mono; keeping the run-ins
     * too would be 61 MB, and after the first play a run-in is never wanted again — rotation
     * enters at the loop deliberately. Dropping them costs nothing and saves a third.
     */
    private val cache = HashMap<String, Pair<FloatArray, FloatArray>>()

    /** The gain actually applied, before the duck. */
    private val target: Float get() = level * CEILING

    /** Beds present on disk, in catalogue order. */
    fun available(): List<String> =
        Beds.ALL.map { it.name }.filter { File(dir(), "$it.opus").let { f -> f.exists() && f.length() > 0 } }

    fun ready(): Boolean = available().isNotEmpty()

    private fun dir(): File = File(ctx.filesDir, Beds.DIR)

    /**
     * The next bed to play.
     *
     * A fixed choice always returns itself. Rotation shuffles through every downloaded bed
     * before any repeats, so an hour of reading is not one loop forty times over. The bed just
     * finished is excluded even when the round resets, so nothing plays twice back to back.
     */
    private fun nextBed(after: String? = null): String? {
        val have = available()
        if (have.isEmpty()) return null
        val choice = track
        if (choice != null && choice != ROTATE) return choice.takeIf { it in have }
        val fresh = have.filter { it !in played && it != after }
        val pool = if (fresh.isNotEmpty()) fresh else {
            played.clear()
            have.filter { it != after }.ifEmpty { have }
        }
        return pool.random()
    }

    /**
     * Start [name], or stop when it is null.
     *
     * Choosing the bed already playing is taken as "turn it off", so the same row in the
     * settings list is both the on and the off switch.
     */
    fun choose(name: String?) {
        if (name == null || name == track) { stop(); return }
        track = name
        played.clear()
        start(nextBed() ?: return)
    }

    /**
     * Set how loud the bed sits, now.
     *
     * The slider wins over anything in flight. A fade writes a new gain every 40 ms, so
     * without cancelling it the dragged value is overwritten twenty-five times a second and
     * the control does nothing. Narration ducks between every clause, so there is nearly
     * always a fade running.
     */
    fun setVolume(v: Float) {
        level = v.coerceIn(0f, 1f)
        fade?.cancel(); fade = null
        gain(if (ducked) target * duckFloor(level) else target)
    }

    private fun gain(g: Float) {
        lastGain = g
        runCatching { live?.setVolume(g) }
    }

    /** Decode a bed's run-in and loop, or pull them from the cache. */
    private suspend fun samples(name: String): Pair<FloatArray, FloatArray>? {
        cache[name]?.let { return it }
        val f = File(dir(), "$name.opus")
        if (!f.exists()) return null
        val item = Beds.of(name)
        val a = item?.loopStartMs?.toLong() ?: 0L
        val b = item?.loopEndMs?.toLong() ?: 0L
        return withContext(Dispatchers.IO) {
            val loop = BedAudio.decode(f, if (b > a) a else 0L,
                if (b > a) b else Long.MAX_VALUE / 2) ?: return@withContext null
            val intro = if (a > 0) BedAudio.decode(f, 0L, a) ?: FloatArray(0) else FloatArray(0)
            (intro to loop).also { cache[name] = FloatArray(0) to loop }
        }
    }

    /**
     * Play [name]: its run-in once, then its loop for as long as it is wanted.
     *
     * [decoded] skips the decode when the caller has already done it (see [crossTo]).
     * [fromLoop] skips the run-in, for a bed arriving part-way through a reading.
     */
    private fun start(
        name: String,
        decoded: Pair<FloatArray, FloatArray>? = null,
        fromLoop: Boolean = false,
    ) {
        release()
        sounding = name
        played += name
        playing = true
        pump = scope.launch {
            val s = decoded ?: samples(name) ?: run {
                Log.w(TAG, "bed $name could not be decoded"); track = null; playing = false
                return@launch
            }
            val (rawIntro, loop) = s
            val intro = if (fromLoop) FloatArray(0) else rawIntro
            if (loop.isEmpty()) { playing = false; return@launch }
            val t = buildTrack()
            live = t
            t.setVolume(0f)
            t.play()
            fadeTo(if (ducked) target * duckFloor(level) else target, FADE_IN)
            val began = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                // The run-in, once.
                write(t, intro)
                // Then the loop, round and round. Each write continues exactly where the last
                // one stopped, so there is no join to hear.
                while (isActive && live === t) {
                    write(t, loop)
                    if (track == ROTATE && System.currentTimeMillis() - began > TURN) {
                        val upcoming = nextBed(after = name)
                        if (upcoming != null && upcoming != name) {
                            withContext(Dispatchers.Main) { crossTo(upcoming) }
                            return@withContext
                        }
                    }
                }
            }
        }
    }

    /**
     * Write [pcm] to [t], blocking until it has been taken.
     *
     * WRITE_BLOCKING is what paces the loop: the call returns as the speaker consumes the
     * samples, so the coroutine is not spinning and the track never runs dry.
     */
    private fun write(t: AudioTrack, pcm: FloatArray) {
        var at = 0
        while (at < pcm.size) {
            if (live !== t) return
            val n = minOf(CHUNK, pcm.size - at)
            val wrote = runCatching { t.write(pcm, at, n, AudioTrack.WRITE_BLOCKING) }
                .getOrDefault(-1)
            if (wrote <= 0) return
            at += wrote
        }
    }

    private fun buildTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(
            BedAudio.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(BedAudio.RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, BedAudio.RATE * 2))
            .build()
    }

    /**
     * Move to a different bed.
     *
     * The only place a fade belongs: two unrelated pieces of music, so the join has to be
     * smoothed. A loop join does not — see the class comment.
     *
     * The order here is the whole point. Decoding the next bed takes long enough to hear, so
     * it is decoded FIRST, while the current bed is still playing at full gain; only then does
     * the outgoing fade start. Fading first and decoding afterwards is what made rotation
     * stop dead and then begin again — the gain reached zero, and the music resumed whenever
     * the decode happened to finish.
     *
     * The incoming bed also starts at its LOOP, not at its run-in. A run-in is written to be
     * heard once from silence at the top of a session; arriving at one mid-reading is the
     * jolt, because it is the one part of the file that does not continue from anything.
     */
    private fun crossTo(name: String) {
        scope.launch {
            // Decode before touching the gain, so the handover has something to hand to.
            val next = samples(name)
            if (next == null) { Log.w(TAG, "bed $name could not be decoded"); return@launch }
            val steps = (CROSSFADE / STEP).coerceAtLeast(1)
            val from = lastGain
            for (i in 1..steps) {
                if (!isActive) return@launch
                val k = i.toFloat() / steps
                gain(from * kotlin.math.cos(k * Math.PI.toFloat() / 2))
                delay(STEP.toLong())
            }
            start(name, decoded = next, fromLoop = true)
        }
    }

    /**
     * Pull the bed down under the voice, or let it back up.
     *
     * The ramp is slow — a bed that snaps up the instant a verse ends draws the ear to itself
     * between every verse.
     */
    fun duck(on: Boolean) {
        if (ducked == on) return
        ducked = on
        if (!playing) return
        fadeTo(if (on) target * duckFloor(level) else target, if (on) DUCK_DOWN else DUCK_UP)
    }

    private fun fadeTo(to: Float, ms: Int) {
        fade?.cancel()
        if (live == null) return
        val from = lastGain
        fade = scope.launch {
            val steps = (ms / STEP).coerceAtLeast(1)
            for (i in 1..steps) {
                if (!isActive) break
                val k = i.toFloat() / steps
                gain(from + (to - from) * k)
                delay(STEP.toLong())
            }
            lastGain = to
        }
    }

    private var lastGain = 0f

    /** Turn the bed off for good: the choice is forgotten. */
    fun stop() {
        track = null
        played.clear()
        release()
    }

    /**
     * Silence the player but keep the choice, for going off screen.
     *
     * Leaving music playing to a phone in a pocket is not what anyone wants, but neither is
     * coming back to find the bed switched itself off, so the choice outlives the sound.
     */
    fun pause() = release()

    /** Start again after [pause], if a bed was chosen. Safe to call when none was. */
    fun resume() {
        if (track == null || playing) return
        start(nextBed() ?: return)
    }

    private fun release() {
        pump?.cancel(); pump = null
        fade?.cancel(); fade = null
        val t = live
        live = null                       // stops the writer before the track goes away
        runCatching { t?.pause(); t?.flush(); t?.release() }
        sounding = null
        playing = false
        lastGain = 0f
    }

    /** Restore the saved choice on launch. Silent when the files are not downloaded. */
    fun restore(name: String?, vol: Float) {
        level = vol.coerceIn(0f, 1f)
        if (name.isNullOrBlank()) return
        track = name
        if (name != ROTATE && !File(dir(), "$name.opus").exists()) { track = null; return }
        val next = nextBed()
        if (next == null) { track = null; return }
        start(next)
    }

    companion object {
        private const val TAG = "lamplight"
        /** The bed's loudest, at level 1.0. Music under speech belongs well down. */
        private const val CEILING = 0.34f
        /**
         * What is left of the bed while a verse is sounding, at the TOP of the slider.
         *
         * 0.3 took 10.5 dB off and buried the bed: at a middling setting the music fell to
         * -26 dBFS under every verse and climbed back between them, which is heard as pumping
         * rather than as music. 0.6 is 4.4 dB — enough to clear the voice, little enough that
         * the bed stays present.
         *
         * See [duckFloor] for why this is the duck at the top of the slider and not a
         * constant: the whole reason there is a volume control is that the listener decides
         * how loud the bed is, and a fixed ratio quietly takes that back at low settings.
         */
        private const val DUCK = 0.6f

        /**
         * The duck, eased off as the slider comes down.
         *
         * A constant ratio is wrong at the quiet end. Someone who has already set the bed low
         * has said what they want; ducking that by a further 4.4 dB leaves nothing, so the
         * music disappears and returns at every verse — the effect is more distracting than
         * the music it was protecting the voice from. At the loud end the duck is needed in
         * full, because that is where the bed genuinely competes.
         *
         * So interpolate: full [DUCK] at the top of the slider, no duck at all at the bottom.
         * The voice is loudest relative to the bed exactly where the bed was quiet anyway.
         */
        fun duckFloor(level: Float): Float = DUCK + (1f - DUCK) * (1f - level.coerceIn(0f, 1f))
        private const val FADE_IN = 1_400
        private const val DUCK_DOWN = 450
        private const val DUCK_UP = 1_800
        /** Only for moving BETWEEN beds; a loop join is never crossfaded. */
        private const val CROSSFADE = 3_000
        /** How long a bed holds the floor before rotation moves on. */
        private const val TURN = 4 * 60 * 1000L
        private const val STEP = 40
        /** Frames per write: small enough to stop promptly, large enough not to churn. */
        private const val CHUNK = 4096
        /** [track] value meaning "move through all the beds" rather than one of them. */
        const val ROTATE = "rotate"
    }
}
/** The beds that exist, and what they sound like. */
object Beds {
    const val DIR = "beds"

    /**
     * A bed, and where its seamless loop lives inside the file.
     *
     * [loopStartMs] to [loopEndMs] is a region whose end genuinely continues into its start.
     * It is a whole number of BARS in the track's own GLOBAL MUSICAL TIME: one tempo and one
     * downbeat per track, with every position derived as `downbeat + k * bar` by arithmetic.
     * A loop that measures well but ends mid-bar still lurches, because the ear is counting.
     *
     * Snapping to DETECTED beat times is not good enough and was the 0.35 bug: that list
     * drifts by tens of ms, so a loop can be an exact number of detected beats and still be
     * 0.39 of a bar long. Only a computed grid cannot drift.
     *
     * `data/loop_points.py` builds the grid and `--verify` measures what matters: splice the
     * loop three times, track beats over the splice, and report how late the beat crossing
     * the join arrives. That number is noted against each entry — it is a TIMING measurement,
     * not a timbre one. Under ~15 ms is inaudible; morning previously measured 116 ms.
     * Where the existing points already measured tighter they were kept, per bed.
     *
     * The audio before [loopStartMs] is the track's run-in: played once, then never again,
     * which is why the loop must NOT be crossfaded back into it.
     */
    data class Item(
        val name: String,
        val label: String,
        val detail: String,
        val loopStartMs: Int = 0,
        val loopEndMs: Int = 0,
    )

    val ALL = listOf(
        Item("stillness", "Stillness", "sampled chords \u00b7 0:11 loop", loopStartMs = 33901, loopEndMs = 45047),   // unchanged, join 0 ms
        Item("morning", "Morning", "guitar \u00b7 0:11 loop", loopStartMs = 7667, loopEndMs = 18820),   // 4 bars, join 12 ms
        Item("evening", "Evening", "guitar \u00b7 0:11 loop", loopStartMs = 3599, loopEndMs = 14893),   // 8 bars, join 0 ms
        Item("psalter", "Psalter", "synth pads \u00b7 0:14 loop", loopStartMs = 36332, loopEndMs = 50456),   // 8 bars, join 12 ms
        Item("vigil", "Vigil", "keys \u00b7 0:14 loop", loopStartMs = 60957, loopEndMs = 74673),   // 4 bars, join 0 ms
        Item("upper-room", "Upper Room", "electric piano \u00b7 1:15 loop", loopStartMs = 668, loopEndMs = 75413),   // unchanged, join 0 ms
        Item("wilderness", "Wilderness", "sparse keys \u00b7 1:32 loop", loopStartMs = 51321, loopEndMs = 143458),   // unchanged, join 23 ms
        Item("shepherd", "Shepherd", "warm chords \u00b7 1:39 loop", loopStartMs = 50376, loopEndMs = 149687),   // unchanged, join 12 ms
    )

    fun of(name: String): Item? = ALL.firstOrNull { it.name == name }
    fun label(name: String): String = of(name)?.label ?: name
}
