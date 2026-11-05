package ca.lampandlight

import ai.gradium.phonon.PhononTTS
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.system.Os
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import kotlin.system.measureTimeMillis

/**
 * Measures Phonon on this phone, which is the one number the design still needs: can the
 * Bible be narrated as it is read, or must audio be baked ahead of time?
 *
 * Reports, per passage: time to first audio (how long before speech starts) and the realtime
 * factor (seconds of audio produced per second of work). Anything above 1x can stream.
 */
class BenchActivity : AppCompatActivity() {

    private lateinit var out: TextView
    private val tag = "lamplight"

    // Real BSB text, the lengths narration actually meets: a verse, a short psalm, a long passage.
    private val passages = listOf(
        "Psalm 23:1" to
            "The LORD is my shepherd; I shall not want.",
        "Psalm 23 (whole)" to
            "The LORD is my shepherd; I shall not want. He makes me lie down in green pastures; " +
            "He leads me beside quiet waters. He restores my soul; He guides me in the paths of " +
            "righteousness for the sake of His name. Even though I walk through the valley of the " +
            "shadow of death, I will fear no evil, for You are with me; Your rod and Your staff, " +
            "they comfort me. You prepare a table before me in the presence of my enemies. You " +
            "anoint my head with oil; my cup overflows. Surely goodness and mercy will follow me " +
            "all the days of my life, and I will dwell in the house of the LORD forever.",
        "Romans 8:28-39" to
            "And we know that God works all things together for the good of those who love Him, " +
            "who are called according to His purpose. For those God foreknew, He also predestined " +
            "to be conformed to the image of His Son, so that He would be the firstborn among many " +
            "brothers. And those He predestined, He also called; those He called, He also justified; " +
            "those He justified, He also glorified. What then shall we say in response to these " +
            "things? If God is for us, who can be against us? He who did not spare His own Son but " +
            "gave Him up for us all, how will He not also, along with Him, freely give us all things? " +
            "Who will bring any charge against God's elect? It is God who justifies. Who is there to " +
            "condemn us? For Christ Jesus, who died, and more than that was raised to life, is at the " +
            "right hand of God, and He is interceding for us. Who shall separate us from the love of " +
            "Christ? Shall trouble or distress or persecution or famine or nakedness or danger or sword?"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply {
            setPadding(36, 28, 36, 36)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val run = Button(this).apply { text = "Run benchmark"; textSize = 16f }
        val speak = Button(this).apply { text = "Speak Psalm 23 aloud"; textSize = 16f }
        val data = Button(this).apply { text = "Check the Bible database"; textSize = 16f }
        val get = Button(this).apply { text = "Download study + commentary"; textSize = 16f }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(run); addView(speak); addView(data); addView(get)
            addView(ScrollView(this@BenchActivity).apply { addView(out) })
        }
        setContentView(root)
        // Keep the first button out from under the status bar and the gesture bar, on whatever
        // phone this is. Hard-coded padding guesses wrong on a Pixel with a tall cutout.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            v.setPadding(24 + bars.left, 24 + bars.top, 24 + bars.right, 24 + bars.bottom)
            insets
        }

        log("BSB Bible — Phonon bench")
        log("device: ${Build.MODEL}  android ${Build.VERSION.RELEASE}")
        log("abi: ${Build.SUPPORTED_ABIS.joinToString()}")
        log("cores: ${Runtime.getRuntime().availableProcessors()}")
        log("")
        log("Tap 'Run benchmark' to measure.")

        run.setOnClickListener { CoroutineScope(Dispatchers.Main).launch { bench(false) } }
        speak.setOnClickListener { CoroutineScope(Dispatchers.Main).launch { bench(true) } }
        data.setOnClickListener { CoroutineScope(Dispatchers.Main).launch { checkData() } }
        get.setOnClickListener { CoroutineScope(Dispatchers.Main).launch { download() } }
    }

    private fun log(s: String) {
        Log.i(tag, s)
        out.append(s + "\n")
    }

    /** The model is ~65 MB; fetch once into app storage rather than shipping it in the APK. */
    private suspend fun ensureModel(): File = withContext(Dispatchers.IO) {
        val dir = File(filesDir, "phonon-31m-en")
        val files = listOf("config.json", "tokenizer.json", "model.q8.gguf", "default-voice.safetensors")
        dir.mkdirs()
        val base = "https://huggingface.co/gradium/phonon-31m-en/resolve/main/"
        for (f in files) {
            val dest = File(dir, f)
            if (dest.exists() && dest.length() > 0) continue
            withContext(Dispatchers.Main) { log("downloading $f …") }
            try {
                URL(base + f).openStream().use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                withContext(Dispatchers.Main) { log("  ${dest.length() / 1024} KB") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { log("  skip $f (${e.message})") }
                dest.delete()
            }
        }
        dir
    }

    /** Pulls the optional layers from serve.py, and says when a newer build is waiting. */
    private suspend fun download() = withContext(Dispatchers.IO) {
        try {
            withContext(Dispatchers.Main) { log(""); log("server ${Updates.host(this@BenchActivity)}") }
            val m = Updates.manifest(this@BenchActivity)
            val want = Updates.missing(this@BenchActivity, m)
            if (want.isEmpty()) withContext(Dispatchers.Main) { log("every layer is already here.") }
            for (layer in want) {
                withContext(Dispatchers.Main) { log("${layer.name}  ${layer.bytes / 1_000_000} MB …") }
                var lastPct = -1
                val t = System.currentTimeMillis()
                Updates.fetchLayer(this@BenchActivity, layer) { seen, total ->
                    val pct = (100 * seen / maxOf(1, total)).toInt()
                    if (pct >= lastPct + 25) {
                        lastPct = pct
                        Log.i(tag, "  ${layer.name} $pct%")
                    }
                }
                withContext(Dispatchers.Main) {
                    log("  ready in ${(System.currentTimeMillis() - t) / 1000}s")
                }
            }
            if (Updates.updateAvailable(this@BenchActivity, m)) {
                withContext(Dispatchers.Main) { log("a newer build (${m.app?.version}) is on the server.") }
            }
            withContext(Dispatchers.Main) { log("done — tap 'Check the Bible database' again.") }
        } catch (e: Throwable) {
            Log.e(tag, "download failed", e)
            withContext(Dispatchers.Main) { log(""); log("DOWNLOAD FAILED: ${e::class.simpleName}: ${e.message}") }
        }
    }

    /** Proves the shipped database expands and answers the queries the reader will make. */
    private suspend fun checkData() = withContext(Dispatchers.IO) {
        try {
            val t0 = System.currentTimeMillis()
            val bible = Bible.open(this@BenchActivity)
            val openMs = System.currentTimeMillis() - t0
            val dbFile = java.io.File(filesDir, "bible-core.db")

            fun ms(block: () -> Int): Pair<Long, Int> {
                val s = System.nanoTime(); val n = block()
                return (System.nanoTime() - s) / 1_000 to n
            }

            val ps23 = bible.chapter(19, 23)
            val (tChap, nChap) = ms { bible.chapter(19, 119).size }
            val (tWords, nWords) = ms { bible.words(verseId(43, 1, 1)).size }
            val (tXref, nXref) = ms { bible.crossRefs(verseId(45, 8, 28)).size }
            val (tSearch, nSearch) = ms { bible.search("living water").size }
            val (tConc, nConc) = ms { bible.concordance("7462", "Hebrew").size }
            val w = bible.words(verseId(19, 23, 1)).getOrNull(3)
            val core = java.io.File(filesDir, Bible.ASSET)

            withContext(Dispatchers.Main) {
                log(""); log("── Bible database ──")
                log("open + expand   ${openMs} ms")
                log("on disk         ${dbFile.length() / 1_000_000} MB")
                log("books           ${bible.books.size}")
                log("study layer     ${if (bible.hasStudy) "attached" else "not downloaded"}")
                log("commentary      ${if (bible.hasCommentary) "attached" else "not downloaded"}")
                log("")
                log("Psalm 23 (${ps23.size} verses)")
                ps23.firstOrNull()?.let {
                    log("  heading  ${it.heading}")
                    log("  title    ${it.psalmTitle}")
                    log("  v1       ${it.text.take(52)}")
                }
                log("")
                if (w == null) log("Psalm 23:1 words → study layer not downloaded")
                else {
                    log("Psalm 23:1 word 4 → ${w.text}")
                    log("  ${w.original}  ${w.translit}  ${w.strongsKey}")
                    log("  ${w.parsing?.take(44)}")
                }
                log("")
                log("Psalm 119 read   ${tChap} µs  ($nChap verses)")
                log("words John 1:1   ${tWords} µs  ($nWords)")
                log("xrefs Rom 8:28   ${tXref} µs  ($nXref)")
                log("search           ${tSearch} µs  ($nSearch hits)")
                log("concordance H7462 ${tConc} µs ($nConc verses)")
                log("")
                log("Matt 17:21 omitted: ${bible.omissionNote(verseId(40, 17, 21))?.take(58)}")
                log(""); log("database ok.")
            }
            bible.close()
        } catch (e: Throwable) {
            Log.e(tag, "data check failed", e)
            withContext(Dispatchers.Main) { log(""); log("DATA FAILED: ${e::class.simpleName}: ${e.message}") }
        }
    }

    private suspend fun bench(playAudio: Boolean) = withContext(Dispatchers.Default) {
        try {
            // One autoregressive stream, so more threads is not automatically better: the
            // little cores drag the step time. RAYON_NUM_THREADS is read once, when the pool
            // is first built, so it cannot be changed after the first synth in this process.
            val cores = Runtime.getRuntime().availableProcessors()
            val threads = (getSharedPreferences("bench", MODE_PRIVATE).getInt("threads", 0)
                .takeIf { it > 0 } ?: (cores / 2)).coerceIn(1, cores)
            Os.setenv("RAYON_NUM_THREADS", threads.toString(), true)

            val dir = ensureModel()
            withContext(Dispatchers.Main) { log(""); log("model dir: ${dir.absolutePath}") }
            dir.listFiles()?.forEach { f ->
                withContext(Dispatchers.Main) { log("  ${f.name}  ${f.length() / 1024} KB") }
            }

            var tts: PhononTTS? = null
            val loadMs = measureTimeMillis { tts = PhononTTS(dir.absolutePath, "en") }
            val model = tts ?: return@withContext
            withContext(Dispatchers.Main) {
                log(""); log("load: ${loadMs} ms   threads: $threads of $cores")
                log("voices: ${model.voices.take(8).joinToString()}")
            }
            model.setVoice(model.voices.firstOrNull { it.equals("Micah", true) } ?: model.voices.first())

            // Size the buffer from what this device actually reports; a hand-picked number can
            // be below the minimum, and AudioTrack then sits in STATE_UNINITIALIZED silently.
            val minBuf = AudioTrack.getMinBufferSize(
                24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val bufBytes = maxOf(minBuf, 24000 * 4)
            val track = if (playAudio) AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(24000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufBytes)
                .build() else null
            if (track != null) {
                track.play()
                val am = getSystemService(android.content.Context.AUDIO_SERVICE)
                    as android.media.AudioManager
                withContext(Dispatchers.Main) {
                    log("audio: minBuf $minBuf  using $bufBytes")
                    log("  state ${track.state} (1=ready)  play ${track.playState} (3=playing)")
                    log("  sr ${track.sampleRate}")
                    val vol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                    val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                    log("  media volume $vol / $maxVol${if (vol == 0) "   ← TURN IT UP" else ""}")
                    val routes = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                        .filter { it.isSink }
                        .joinToString(", ") {
                            when (it.type) {
                                android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
                                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth"
                                android.media.AudioDeviceInfo.TYPE_BLE_HEADSET -> "LE audio"
                                android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                                android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
                                android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
                                android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> "usb"
                                else -> "type${it.type}"
                            }
                        }
                    log("  outputs: $routes")
                    if (am.isBluetoothA2dpOn || am.isWiredHeadsetOn)
                        log("  NOTE: audio is routed off the speaker")
                }
            }

            withContext(Dispatchers.Main) {
                log(if (playAudio) "" else "(silent run — tap 'Speak Psalm 23 aloud' to hear it)")
            }
            for ((name, text) in passages) {
                val words = text.split(" ").size
                var firstFrameMs = -1L
                val t0 = System.nanoTime()
                var samples = 0L
                // AudioTrack.write blocks once the buffer is full, and ptts_speak does not
                // return until the callback does — so writing straight from the callback makes
                // both this clock and ptts's own totalMs track playback, not generation.
                // Hand frames to a player thread through a queue and let generation run free.
                // Bounded on purpose. Unbounded, generation "finishes" by filling memory and
                // the realtime figure becomes meaningless; a few seconds of lookahead is what
                // the reader will actually use, and what should be measured.
                val lookaheadFrames = 40          // ~3 s at 80 ms a frame
                val queue = java.util.concurrent.LinkedBlockingQueue<FloatArray>(lookaheadFrames)
                var starvedNanos = 0L
                var playedPeak = 0f
                var wrote = 0L
                var writeErr = 0
                val player = track?.let { t ->
                    Thread {
                        while (true) {
                            val s0 = System.nanoTime()
                            val pcm = queue.take()
                            starvedNanos += System.nanoTime() - s0
                            if (pcm.isEmpty()) break          // poison pill: end of utterance
                            var peak = 0f
                            for (v in pcm) { val a = kotlin.math.abs(v); if (a > peak) peak = a }
                            if (peak > playedPeak) playedPeak = peak
                            val n = t.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                            if (n < 0) writeErr = n else wrote += n
                        }
                    }.apply { isDaemon = true; start() }
                }
                // What matters for streaming is not a ratio but whether the player ever runs
                // dry: time spent blocked putting a frame means generation is ahead (good),
                // and a starved player means it fell behind (a stutter the listener hears).
                var blockedPutNanos = 0L
                val r = model.speak(text) { pcm ->
                    if (firstFrameMs < 0) firstFrameMs = (System.nanoTime() - t0) / 1_000_000
                    samples += pcm.size
                    val b0 = System.nanoTime()
                    // The callback's array is read out of native memory that ptts reuses for
                    // the next frame, so it must be copied before it leaves this call —
                    // queueing the original plays whatever the buffer holds by then.
                    if (player != null) queue.put(pcm.copyOf())
                    blockedPutNanos += System.nanoTime() - b0
                    true
                }
                // Generation's own cost: wall time less the time it was held back by the queue.
                val wallMs = ((System.nanoTime() - t0) - blockedPutNanos) / 1_000_000
                if (player != null && track != null) {
                    queue.put(FloatArray(0))
                    player.join()                                    // every frame handed over
                    // join() only means the last frame was written: AudioTrack still holds up
                    // to a bufferful that has not reached the speaker. Wait for the playback
                    // head to reach what was written, or stop() throws it away and the tail of
                    // each passage is never heard.
                    val target = samples
                    var head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    var spins = 0
                    while (head < target && spins < 600) {
                        Thread.sleep(50); spins++
                        head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    }
                }
                val blockedMs = ((System.nanoTime() - t0) / 1_000_000) - wallMs
                val audioSec = samples / 24000.0
                val rtf = audioSec / (wallMs / 1000.0)
                withContext(Dispatchers.Main) {
                    log("")
                    log("$name  ($words words)")
                    log("  audio      ${"%.1f".format(audioSec)} s")
                    log("  generate   ${wallMs} ms")
                    if (track != null) {
                        val starvedMs = starvedNanos / 1_000_000
                        log("  playback   ${blockedMs} ms more")
                        log("  starved    ${starvedMs} ms  ${if (starvedMs < 400) "(no stutter)" else "AUDIBLE GAP"}")
                        log("  peak       ${"%.3f".format(playedPeak)}  ${if (playedPeak > 0.01f) "audible" else "SILENT"}")
                        log("  written    $wrote samples${if (writeErr < 0) "  WRITE ERROR $writeErr" else ""}")
                        val head = (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
                        log("  played     $head of $samples samples" +
                            if (head < samples - 2400) "   ← TAIL LOST" else "")
                    }
                    log("  firstaud   ${firstFrameMs} ms   (ptts says ${"%.0f".format(r.ttfaMs)})")
                    log("  realtime   ${"%.2f".format(rtf)}x  ${if (rtf >= 1) "streams" else "TOO SLOW"}")
                }
            }
            track?.let { it.stop(); it.flush(); it.release() }
            model.close()
            withContext(Dispatchers.Main) { log(""); log("done.") }
        } catch (e: Throwable) {
            Log.e(tag, "bench failed", e)
            withContext(Dispatchers.Main) { log(""); log("FAILED: ${e::class.simpleName}: ${e.message}") }
        }
    }
}
