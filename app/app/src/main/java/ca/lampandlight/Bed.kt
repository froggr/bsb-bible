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
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * Music under the reading.
 *
 * Three things make a bed a bed rather than a song playing:
 *
 *  - It never stops. Whole tracks, one after another, each fading in over the end of the
 *    one before, for as long as you read.
 *  - It gets out of the way of the voice. Speech and music in the same band fight, so the
 *    bed drops while a verse is sounding and comes back up between them — the ducking a
 *    radio desk does, slow enough not to pump.
 *  - It is quiet by default. The bed is meant to be noticed only if you listen for it.
 *
 * There are two kinds, [Beds.BEATS] and [Beds.PADS], and what is chosen is the kind, not
 * a track: the player moves through every track of that kind before any comes round again.
 *
 * Until 0.53 a bed was one track cut down to a loop of under a minute and played round and
 * round. Three releases went on making that join exact, and it was the wrong thing made
 * well: a minute of music forty times over is what wore, not the join. Whole tracks have no
 * join to get wrong. The only seam left is between two different pieces of music, and that
 * one is meant to be heard as a change: an equal-power crossfade, mixed here in the samples
 * themselves so both tracks are sounding through it.
 *
 * AudioTrack fed from a decoder, not a media player: the narrator already works that way,
 * the gain is ours to move under the voice, and a crossfade needs two tracks decoded at
 * once into one stream. Nothing is held in memory but the half second being played.
 */
class Bed(private val ctx: Context) {

    /** The kind of bed chosen, [Beds.BEATS] or [Beds.PADS], or null for silence. */
    var track by mutableStateOf<String?>(null); private set

    /** The track sounding right now, so the player can say what is playing. */
    var sounding by mutableStateOf<String?>(null); private set

    /** Tracks already played this round, so every one is heard before any repeats. */
    private val played = mutableSetOf<String>()

    /** How loud the bed sits when nothing is being read, 0f to 1f. */
    var level by mutableFloatStateOf(0.5f); private set

    var playing by mutableStateOf(false); private set

    /** True while narration is sounding, so the bed holds itself down. */
    private var ducked = false
    private var hushed = false
    /** Where the gain belongs when nothing is moving it. */
    private val resting: Float
        get() = when {
            hushed -> 0f
            ducked -> target * duckFloor(level)
            else -> target
        }

    @Volatile private var live: AudioTrack? = null
    /** [track] again, for the thread that plays: it must not read what the screen draws from. */
    @Volatile private var kind: String? = null
    /** Asks the playing thread to leave this track now, for a change of kind. */
    @Volatile private var skip = false
    /** Where [pause] left the music, to go on from: the track, and the frame within it. */
    @Volatile private var left: Pair<String, Long>? = null
    private var pump: Job? = null
    private var fade: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    /** The gain actually applied, before the duck. */
    private val target: Float get() = level * CEILING

    private fun dir(): File = File(ctx.filesDir, Beds.DIR)

    private fun has(name: String) = File(dir(), "$name.opus").let { it.exists() && it.length() > 0 }

    /** The kinds of bed with at least one track on disk, in catalogue order. */
    fun kinds(): List<Beds.Kind> = Beds.KINDS.filter { k -> Beds.ALL.any { it.kind == k.name && has(it.name) } }

    fun ready(): Boolean = kinds().isNotEmpty()

    /**
     * The next track to play: any of the chosen kind not yet heard this round. The one just
     * finished is left out even when the round starts again, so nothing plays twice running.
     */
    private fun nextBed(after: String? = null): String? {
        val of = kind ?: return null
        val have = Beds.ALL.filter { it.kind == of && has(it.name) }.map { it.name }
        if (have.isEmpty()) return null
        synchronized(played) {
            val fresh = have.filter { it !in played && it != after }
            val pool = if (fresh.isNotEmpty()) fresh else {
                played.clear()
                have.filter { it != after }.ifEmpty { have }
            }
            return pool.random().also { played += it }
        }
    }

    private fun open(name: String?, from: Long = 0L): BedStream? {
        name ?: return null
        return runCatching {
            BedStream(name, File(dir(), "$name.opus"), Beds.of(name)?.secs ?: 0).also {
                check(it.rate == RATE) { "$name is at ${it.rate} Hz" }
                it.seek(from)
            }
        }.onFailure { Log.w(TAG, "bed $name could not be opened: ${it.message}") }.getOrNull()
    }

    /**
     * Start a kind of bed, or stop when it is null.
     *
     * Choosing the kind already playing is taken as "turn it off", so the same row in the
     * player is both the on and the off switch. Choosing the other kind while one is
     * sounding crosses to it rather than cutting.
     */
    fun choose(name: String?) {
        if (name == null || name == track) { stop(); return }
        val was = track
        track = name; kind = name
        synchronized(played) { played.clear() }
        left = null
        if (was != null && playing && live != null) skip = true else start()
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
        gain(resting)
    }

    private fun gain(g: Float) {
        lastGain = g
        runCatching { live?.setVolume(g) }
    }

    /** Begin playing the chosen kind, from where [pause] left it if it did. */
    private fun start() {
        release()
        val from = left
        left = null
        skip = false
        val t = buildTrack()
        live = t
        playing = true
        t.setVolume(0f)
        t.play()
        fadeTo(resting, FADE_IN)
        pump = scope.launch(Dispatchers.IO) {
            val first = from?.takeIf { Beds.of(it.first)?.kind == kind && has(it.first) }
                ?.let { open(it.first, it.second) }
                ?: open(nextBed())
            if (first == null) {
                scope.launch { if (live === t) { release() } }
                return@launch
            }
            run(t, first)
        }
    }

    /**
     * Feed [t] for as long as it is the live track: [first], then whatever follows.
     *
     * The order at a change is the whole point. The next track is opened and decoding
     * BEFORE the first sample of the fade, while the one before is still sounding in full;
     * then both are mixed, the old one down a quarter-turn of cosine and the new one up
     * the matching sine, which holds the loudness level across two unrelated pieces of
     * music. Fading out first and opening the next afterwards is the silence between beds
     * that 0.35 had.
     */
    private fun run(t: AudioTrack, first: BedStream) {
        var cur = first
        var next: BedStream? = null
        var crossed = 0
        var span = 1
        val a = FloatArray(CHUNK * 2)
        val b = FloatArray(CHUNK * 2)
        say(cur.name)
        try {
            while (live === t) {
                val jump = skip
                if (next == null && (jump || (cur.frames > 0 && cur.frames - cur.at <= CROSS))) {
                    skip = false
                    next = open(nextBed(after = cur.name))
                    crossed = 0
                    span = if (jump) SWITCH
                           else (cur.frames - cur.at).toInt().coerceIn(CHUNK, CROSS)
                }
                var n = cur.read(a, CHUNK)
                val coming = next
                if (coming == null) {
                    if (n == 0) {
                        // Over with no warning: a file that did not say how long it was.
                        val after = cur.name
                        cur.close()
                        cur = open(nextBed(after = after)) ?: break
                        say(cur.name)
                        continue
                    }
                } else {
                    java.util.Arrays.fill(a, n * 2, CHUNK * 2, 0f)
                    val m = coming.read(b, CHUNK)
                    java.util.Arrays.fill(b, m * 2, CHUNK * 2, 0f)
                    for (i in 0 until CHUNK) {
                        val k = ((crossed + i).toFloat() / span).coerceAtMost(1f) * HALF_PI
                        val down = cos(k)
                        val up = sin(k)
                        a[i * 2] = a[i * 2] * down + b[i * 2] * up
                        a[i * 2 + 1] = a[i * 2 + 1] * down + b[i * 2 + 1] * up
                    }
                    crossed += CHUNK
                    n = CHUNK
                    if (crossed >= span) {
                        cur.close()
                        cur = coming
                        next = null
                        say(cur.name)
                    }
                }
                left = cur.name to cur.at
                if (!write(t, a, n * 2)) break
            }
        } catch (e: Exception) {
            Log.w(TAG, "bed stopped: ${e.message}")
        } finally {
            cur.close()
            next?.close()
        }
    }

    /** Tell the screen which track is sounding. */
    private fun say(name: String) {
        Log.d(TAG, "bed: $name")
        scope.launch { if (playing) sounding = name }
    }

    /**
     * Write [count] floats of [pcm] to [t], blocking until they have been taken.
     *
     * WRITE_BLOCKING is what paces the player: the call returns as the speaker consumes the
     * samples, so the thread is not spinning and the track never runs dry.
     */
    private fun write(t: AudioTrack, pcm: FloatArray, count: Int): Boolean {
        var at = 0
        while (at < count) {
            if (live !== t) return false
            val wrote = runCatching { t.write(pcm, at, count - at, AudioTrack.WRITE_BLOCKING) }
                .getOrDefault(-1)
            if (wrote <= 0) return false
            at += wrote
        }
        return true
    }

    private fun buildTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            // Half a second, in bytes: two channels of four-byte floats.
            .setBufferSizeInBytes(maxOf(min, RATE * 4))
            .build()
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
        if (hushed) return
        fadeTo(resting, if (on) DUCK_DOWN else DUCK_UP)
    }

    /**
     * Silence the bed altogether while the microphone is open, and bring it back after.
     *
     * Ducked is not enough here. The duck leaves the music under a voice; this is music
     * from the phone's speaker going straight into the phone's microphone, where it is all
     * the recogniser hears of someone saying a verse across the room. Whoever calls this
     * decides whether the sound is in the room at all: see [Ears.aloud].
     */
    fun hush(on: Boolean) {
        if (hushed == on) return
        hushed = on
        if (!playing) return
        fadeTo(resting, if (on) HUSH_DOWN else DUCK_UP)
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
        track = null; kind = null
        synchronized(played) { played.clear() }
        release()
        left = null
    }

    /**
     * Silence the player but keep the choice and the place in the track.
     *
     * Leaving music playing to a phone in a pocket is not what anyone wants, but neither is
     * coming back to find the bed switched itself off, so the choice outlives the sound.
     */
    fun pause() = release()

    /** Start again after [pause], if a bed was chosen. Safe to call when none was. */
    fun resume() {
        if (track == null || playing) return
        if (nextBedExists()) start()
    }

    private fun nextBedExists(): Boolean = kind?.let { k -> Beds.ALL.any { it.kind == k && has(it.name) } } == true

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
        val k = Beds.kind(name) ?: return
        track = k; kind = k
        if (!nextBedExists()) { track = null; kind = null; return }
        start()
    }

    companion object {
        private const val TAG = "lamplight"
        /** What every bed file is encoded at, and so what the player runs at. */
        const val RATE = 48_000
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
        /** Quicker than a duck: the listener is about to speak. */
        private const val HUSH_DOWN = 200
        /** One track into the next, in frames: five seconds. */
        private const val CROSS = RATE * 5
        /** From one kind of bed to the other when it is asked for: a second and a half. */
        private const val SWITCH = RATE * 3 / 2
        private const val STEP = 40
        /** Frames per write: small enough to stop promptly, large enough not to churn. */
        private const val CHUNK = 4096
        private const val HALF_PI = (Math.PI / 2).toFloat()
    }
}

/** The beds that exist. `data/beds.py` makes the files and measures every one of them. */
object Beds {
    const val DIR = "beds"
    const val BEATS = "beats"
    const val PADS = "pads"

    /** A kind of bed: what is chosen in the player. */
    data class Kind(val name: String, val label: String, val detail: String)

    /**
     * One track. [secs] is its length, used only when the file does not give its own, to
     * know when to bring the next one in.
     */
    data class Item(val name: String, val label: String, val kind: String, val secs: Int)

    val KINDS = listOf(
        Kind(BEATS, "Beats", "lo-fi, a beat all the way through"),
        Kind(PADS, "Pads", "no beat: warm pads and soft organ"),
    )

    // Every one measured with no voice in it, whole and in its worst ten seconds, on the
    // encoded file: data/beds/beds.json.
    val ALL = listOf(
        Item("still-light", "Still Light", BEATS, 150),
        Item("tender-presence", "Tender Presence", BEATS, 84),
        Item("holy-breath", "Holy Breath", BEATS, 80),
        Item("morning-light", "Morning Light", BEATS, 74),
        Item("still-refuge", "Still Refuge", BEATS, 115),
        Item("resting-grace", "Resting Grace", BEATS, 73),
        Item("morning-shelter", "Morning Shelter", BEATS, 105),
        Item("abiding-mercy", "Abiding Mercy", BEATS, 108),
        Item("waiting-mercy", "Waiting Mercy", BEATS, 101),
        Item("tender-grace", "Tender Grace", PADS, 81),
        Item("abiding-light", "Abiding Light", PADS, 107),
        Item("quiet-stillness", "Quiet Stillness", PADS, 91),
        Item("resting-breath", "Resting Breath", PADS, 109),
        Item("gentle-refuge", "Gentle Refuge", PADS, 159),
    )

    fun of(name: String): Item? = ALL.firstOrNull { it.name == name }

    /** What to call [name], a track or a kind. */
    fun label(name: String): String =
        of(name)?.label ?: KINDS.firstOrNull { it.name == name }?.label ?: name

    /**
     * The kind a saved choice means now. Before 0.53 the choice was one of eight looped
     * beds or "rotate"; all of those were music with a beat or near it, and whoever chose
     * one wanted music, so they become [BEATS] rather than silence.
     */
    fun kind(saved: String?): String? = when {
        saved.isNullOrBlank() -> null
        KINDS.any { it.name == saved } -> saved
        else -> BEATS
    }
}
