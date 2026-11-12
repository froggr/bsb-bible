package ca.lampandlight

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hearing a line said, on the phone and nowhere else.
 *
 * The same two parts Joe's herdvoice hears with, through the same library: Silero's voice
 * detector decides where something said begins and ends, and an NVIDIA Parakeet recogniser
 * turns that stretch into words. A phone with no Google services has no recogniser of its
 * own to ask, so the app brings one. The recogniser is the small Parakeet, a fifth the size
 * of the one herdvoice carries: it was measured against it on verse phrases and heard as
 * many of them, because [Heard] only has to tell whether a known line was said, not take
 * dictation.
 *
 * Nothing is kept and nothing is sent. The sound goes from the microphone to the two models
 * and is dropped; the words go to whoever asked, and they drop them when the turn ends.
 *
 * Everything heavy is on one worker thread. The microphone has a thread of its own for as
 * long as a turn lasts.
 */
class Ears(private val ctx: Context) {
    /** The models are in memory and a turn can be listened to. */
    var loaded by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    /** The microphone is open. */
    var listening by mutableStateOf(false); private set
    /** Somebody is speaking, as far as the detector can tell. */
    var voiced by mutableStateOf(false); private set

    private val work = Executors.newSingleThreadExecutor { Thread(it, "ears").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private var vad: Vad? = null
    /** Only ever touched on the worker thread. */
    private var recognizer: OfflineRecognizer? = null
    /** Held by whichever thread has the detector: one turn's capture, or its release. */
    private val mic = Any()
    /** Which turn is being listened to. Anything that arrives for an earlier one is dropped. */
    @Volatile private var turn = 0
    /** Turns begun since the models were loaded. Only the stand-in for a microphone counts them. */
    private var turns = 0
    /** Which load is wanted. One that finishes after [release] is not to say it is loaded. */
    private var loads = 0

    /** Bring the models into memory, if they are on the phone. Returns at once. */
    fun load() {
        if (loaded || loading || !present(ctx)) return
        loading = true
        val wanted = ++loads
        val dir = dir(ctx)
        work.execute {
            val began = SystemClock.elapsedRealtime()
            val ok = runCatching {
                val v = Vad(null, VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = File(dir, "silero_vad.onnx").path,
                        threshold = 0.5f,
                        minSilenceDuration = QUIET,
                        minSpeechDuration = 0.25f,
                        windowSize = WINDOW,
                        maxSpeechDuration = 18f,
                    ),
                    sampleRate = RATE, numThreads = 1, provider = "cpu",
                ))
                val r = OfflineRecognizer(null, OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = File(dir, "encoder.int8.onnx").path,
                            decoder = File(dir, "decoder.int8.onnx").path,
                            joiner = File(dir, "joiner.int8.onnx").path,
                        ),
                        tokens = File(dir, "tokens.txt").path,
                        numThreads = 2,
                        modelType = "nemo_transducer",
                    ),
                    decodingMethod = "greedy_search",
                ))
                synchronized(mic) { vad = v }
                recognizer = r
            }.onFailure { Log.w(TAG, "could not load: $it") }.isSuccess
            Log.d(TAG, "loaded $ok in ${SystemClock.elapsedRealtime() - began} ms")
            main.post { if (wanted == loads) { loading = false; loaded = ok } }
        }
    }

    /**
     * Open the microphone and report what is said, a stretch at a time, until [stop]. Each
     * stretch ends at a pause, so a line said in two breaths arrives as two. [onHeard] is
     * called on the main thread. Does nothing without the models or without leave to listen.
     *
     * With [onPartial], what a stretch has come to so far is reported while it is still
     * being said, a little under once a second, for a page that fills in as it listens. It
     * is the whole stretch so far each time, not what is new, and less its last word, which
     * is as likely as not half a word. When the stretch ends [onHeard] has the last say and
     * what [onPartial] gave for it is to be thrown away.
     *
     * Returns this turn's number, for [stop] to be told which turn it means, or 0 if
     * nothing was opened.
     */
    fun listen(onPartial: ((String) -> Unit)? = null, onHeard: (String) -> Unit): Int {
        if (!loaded || !allowed(ctx)) return 0
        val mine = ++turn
        val count = ++turns
        listening = true; voiced = false
        Thread({ capture(mine, count, onPartial, onHeard) }, "ears-mic").apply { isDaemon = true }.start()
        return mine
    }

    /** Close the microphone. Whatever was still being worked out is thrown away. */
    fun stop() {
        turn++
        listening = false; voiced = false
    }

    /**
     * Close the microphone if it is still open for [mine]. Two pages can be on screen for a
     * moment as one gives way to the other, and the one leaving must not close what the
     * one arriving has just opened.
     */
    fun stop(mine: Int) {
        if (mine != 0 && turn == mine) stop()
    }

    /** Let the models go. They are a few hundred megabytes of memory nobody else can use. */
    fun release() {
        stop()
        loads++
        loaded = false; loading = false; turns = 0
        work.execute {
            // The detector belongs to a turn while there is one; wait for it to be let go.
            synchronized(mic) { runCatching { vad?.release() }; vad = null }
            runCatching { recognizer?.release() }; recognizer = null
        }
    }

    private fun capture(
        mine: Int, count: Int, onPartial: ((String) -> Unit)?, onHeard: (String) -> Unit,
    ) = synchronized(mic) {
        val v = vad ?: return@synchronized
        if (turn != mine) return@synchronized
        val source = planted(count) ?: runCatching { Microphone() }
            .onFailure { Log.w(TAG, "no microphone: $it") }.getOrNull()
        if (source == null) {
            main.post { if (turn == mine) { listening = false } }
            return@synchronized
        }
        try {
            v.clear(); v.reset()
            val window = FloatArray(WINDOW)
            // The end of the app's own voice is still in the room, and in the speaker's
            // pipe, when the turn begins. It is not the listener.
            var skip = RATE * SKIP_MS / 1000
            var speaking = false
            // The stretch being said, kept so that it can be worked out before it ends. The
            // detector is slow to say somebody has begun, so the moment before is kept too.
            val lead = ArrayDeque<FloatArray>()
            var live = FloatArray(RATE * 4)
            var have = 0
            var told = 0
            val busy = AtomicBoolean(false)
            while (turn == mine && source.read(window)) {
                if (skip > 0) { skip -= WINDOW; continue }
                v.acceptWaveform(window)
                val now = v.isSpeechDetected()
                if (onPartial != null) {
                    if (now) {
                        if (!speaking) {
                            have = 0; told = 0
                            for (w in lead) { w.copyInto(live, have); have += WINDOW }
                            lead.clear()
                        }
                        if (have + WINDOW > live.size) live = live.copyOf(live.size * 2)
                        window.copyInto(live, have); have += WINDOW
                        // One at a time: on a slow phone they come less often, and never
                        // queue up in front of the stretch's last word.
                        if (have - told >= PARTIAL && have <= PARTIAL_MOST && busy.compareAndSet(false, true)) {
                            told = have
                            val part = live.copyOf(have)
                            work.execute {
                                try { decode(mine, part, onPartial, whole = false) } finally { busy.set(false) }
                            }
                        }
                    } else {
                        lead.addLast(window.copyOf())
                        if (lead.size > LEAD) lead.removeFirst()
                    }
                }
                if (now != speaking) {
                    speaking = now
                    main.post { if (turn == mine) voiced = now }
                }
                while (!v.empty()) {
                    val samples = v.front().samples
                    v.pop()
                    have = 0; told = 0
                    work.execute { decode(mine, samples, onHeard) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "listening failed: $e")
        } finally {
            source.close()
            main.post { if (turn == mine) { listening = false; voiced = false } }
        }
    }

    /** [whole] is a stretch that has ended; otherwise it is one still being said. */
    private fun decode(mine: Int, samples: FloatArray, onHeard: (String) -> Unit, whole: Boolean = true) {
        if (turn != mine) return
        val began = SystemClock.elapsedRealtime()
        val r = recognizer ?: return
        val s = r.createStream()
        var text = try {
            s.acceptWaveform(samples, RATE)
            r.decode(s)
            r.getResult(s).text.trim()
        } finally { s.release() }
        // How long it took is worth knowing on a phone. What was said is nobody's business:
        // it is written down only by a build made for testing.
        Log.d(TAG, "%.1f s of speech %s in %d ms%s".format(
            samples.size / RATE.toFloat(), if (whole) "worked out" else "so far",
            SystemClock.elapsedRealtime() - began, if (testing()) ": $text" else ""))
        // The last word of a stretch still being said is as likely as not half a word.
        if (!whole) text = text.substringBeforeLast(' ', "")
        if (text.isNotEmpty()) main.post { if (turn == mine) onHeard(text) }
    }

    /** Where the sound comes from: [WINDOW] samples at a time, until there is no more. */
    private interface Source {
        fun read(into: FloatArray): Boolean
        fun close()
    }

    private inner class Microphone : Source {
        private val shorts = ShortArray(WINDOW)
        private val rec: AudioRecord

        init {
            val min = AudioRecord.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            // The phone's own microphone, tuned for a recogniser and not for a call. A
            // headset's is not asked for: that needs a call's audio path, which would take
            // the music and the voice down to telephone quality with it.
            @Suppress("MissingPermission")
            rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE * 2),
            )
            check(rec.state == AudioRecord.STATE_INITIALIZED) { "the microphone would not open" }
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                rec.release()
                error("the microphone would not start")
            }
        }

        override fun read(into: FloatArray): Boolean {
            var at = 0
            while (at < WINDOW) {
                val n = rec.read(shorts, at, WINDOW - at)
                if (n <= 0) return false
                at += n
            }
            for (i in 0 until WINDOW) into[i] = shorts[i] / 32768f
            return true
        }

        override fun close() {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    /**
     * A recording standing in for the microphone, in a build made for testing only.
     *
     * The emulators this is tried on have no microphone, and a recogniser that has only
     * ever been handed silence has not been tried. So when `ears/3.wav` is in the app's
     * external files, the third turn since the models were loaded hears that file, at the
     * pace it was spoken, and silence after it. 16 kHz, one channel, 16 bit.
     */
    private fun planted(count: Int): Source? {
        if (!testing()) return null
        val file = File(ctx.getExternalFilesDir(null), "ears/$count.wav")
        if (!file.exists()) return null
        return runCatching {
            val raw = RandomAccessFile(file, "r")
            // Find where the sound starts: the chunk called "data".
            var at = 12L
            val head = ByteArray(8)
            while (true) {
                raw.seek(at); raw.readFully(head)
                val size = (head[4].toLong() and 0xff) or ((head[5].toLong() and 0xff) shl 8) or
                    ((head[6].toLong() and 0xff) shl 16) or ((head[7].toLong() and 0xff) shl 24)
                if (String(head, 0, 4, Charsets.US_ASCII) == "data") break
                at += 8 + size + (size and 1)
            }
            Log.d(TAG, "turn $count hears ${file.name}")
            object : Source {
                val bytes = ByteArray(WINDOW * 2)
                var next = SystemClock.elapsedRealtime()
                override fun read(into: FloatArray): Boolean {
                    val n = raw.read(bytes).coerceAtLeast(0)
                    for (i in 0 until WINDOW) {
                        into[i] = if (i * 2 + 1 < n)
                            ((bytes[i * 2].toInt() and 0xff) or (bytes[i * 2 + 1].toInt() shl 8)).toShort() / 32768f
                        else 0f
                    }
                    next += WINDOW * 1000L / RATE
                    val wait = next - SystemClock.elapsedRealtime()
                    if (wait > 0) Thread.sleep(wait)
                    return true
                }
                override fun close() = raw.close()
            }
        }.onFailure { Log.w(TAG, "could not read ${file.name}: $it") }.getOrNull()
    }

    private fun testing() = ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** One of the files hearing needs: its name on the phone, its length and its SHA-256. */
    class Part(val name: String, val bytes: Long, val sha: String)

    companion object {
        private const val TAG = "Ears"
        const val RATE = 16000
        /** What the voice detector takes at a time: 32 ms. */
        private const val WINDOW = 512
        /** How long a pause ends a stretch of speech, in seconds. */
        private const val QUIET = 0.45f
        /** How much of the start of a turn is not listened to. */
        private const val SKIP_MS = 350
        /** How much more has to have been said before a stretch is worked out again: 0.8 s. */
        private const val PARTIAL = RATE * 4 / 5
        /** Past this a stretch is left until it ends: working it out again costs too much. */
        private const val PARTIAL_MOST = RATE * 15
        /** How many windows from before the detector spoke up are kept: about 0.4 s. */
        private const val LEAD = 12

        const val DIR = "ears"

        /**
         * Where the files are fetched from: one release, named, and not whichever is latest.
         * They are 136 MB that do not change from one build to the next, and a release that
         * had to carry them again each time would be one upload away from a phone that
         * cannot find them. **Do not delete that release.**
         */
        const val BASE = "https://github.com/froggr/bsb-bible/releases/download/v0.52"

        /**
         * sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8, which is NVIDIA's
         * parakeet-tdt_ctc-110m (CC BY 4.0) as k2-fsa exported it, and Silero VAD (MIT).
         */
        val PARTS = listOf(
            Part("encoder.int8.onnx", 131_113_202, "0f35509ddeb9b39002fb077d979a9fe74f06eb0bc4dd5c34f512f82e5111d657"),
            Part("decoder.int8.onnx", 3_955_863, "f7c331c5504c2e593c76ed22b728e3f554af6c4a383dde862e719ced08b1da19"),
            Part("joiner.int8.onnx", 1_411_403, "bf7dff69e9f2cdbe9943d70da358f38b361c115ba0105bae7e908e0d6ec782f6"),
            Part("tokens.txt", 9_953, "450e56bd2f036fe5b6aa821865838cc5aa9d8b0106134ce9a9ba0664abe6cd10"),
            Part("silero_vad.onnx", 643_854, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"),
        )

        /** The download, in megabytes, for saying so before it is asked for. */
        val MEGABYTES: Int get() = ((PARTS.sumOf { it.bytes } + 500_000) / 1_000_000).toInt()

        fun dir(ctx: Context) = File(ctx.filesDir, DIR)

        /** Every file is there and whole. By length: they were checked as they came. */
        fun present(ctx: Context): Boolean =
            dir(ctx).let { d -> PARTS.all { File(d, it.name).length() == it.bytes } }

        fun allowed(ctx: Context): Boolean =
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /**
         * Is the app's sound coming out into the room, where the microphone hears it? Not
         * with headphones in. Music from the phone's own speaker, a hand's width from its
         * microphone, is all a recogniser hears; music in the ears it does not hear at all,
         * and stopping it there would be the bed vanishing at every line for nothing.
         */
        fun aloud(ctx: Context): Boolean {
            val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).none {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_HEARING_AID
            }
        }
    }
}
