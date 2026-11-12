package ca.lampandlight

import ai.gradium.phonon.PhononTTS
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Reads scripture aloud and says which word is sounding.
 *
 * Phonon hands back 24 kHz PCM in ~80 ms frames as it generates, well ahead of playback
 * (about 4.5x realtime on a Pixel 9). That gap is what makes a live highlight possible:
 * the word under the voice is found from AudioTrack's playback head, not from a timer,
 * so it stays true even when generation stalls or the user pauses.
 *
 * Words are timed by weight rather than by count: a long word holds the voice longer than
 * a short one, which is close enough to feel right and needs no alignment model.
 */
class Narrator(private val ctx: Context) {

    init { Pulse.load(ctx) }

    var speaking by mutableStateOf(false); private set

    /**
     * True from the last word of a chapter until what follows it is settled: either the
     * next chapter has begun to be read, or it is known that nothing will be.
     *
     * [speaking] is false for that moment, and anything that takes it to mean the reading is
     * over lets go of what the reading needs. The foreground service did: it was stopped
     * at the end of every chapter and started again for the next, and Android forbids
     * starting one from behind a locked screen, so with no music playing to hold the
     * service up the app was killed at the first chapter end after the phone was pocketed.
     * Whoever reads on calls [settled] when they have decided; [speak] and [stop] settle it
     * themselves.
     */
    var carrying by mutableStateOf(false); private set
    fun settled() { carrying = false }

    /**
     * True while the voice is leaving room for the listener to speak: a [Line.silence] is
     * running. [verseIndex] says against what. Learning a verse by heart is made of these.
     */
    var hushed by mutableStateOf(false); private set

    /**
     * True while what is being read is not the page in front: a verse being learned, read
     * by itself. Such a reading does not run on into a next chapter and leaves alone the
     * place the page's own reading stopped at.
     */
    var alone by mutableStateOf(false); private set
    var verseIndex by mutableIntStateOf(-1); private set
    var wordIndex by mutableIntStateOf(-1); private set

    /**
     * The words being sounded now, as a range over the verse's word list.
     *
     * A clause, not a word. Phonon is an autoregressive codec model: it reports no timing
     * at all, so where one WORD falls can only ever be estimated from syllable counts, and
     * that estimate drifts audibly. What is known exactly is which SENTENCE was handed to
     * the model, because they are spoken one at a time — so that is what gets lit.
     */
    var wordSpan by mutableStateOf<IntRange?>(null); private set
    var preparing by mutableStateOf(false); private set
    var voice by mutableStateOf("default"); private set
    var voices by mutableStateOf<List<String>>(emptyList()); private set
    var error by mutableStateOf<String?>(null); private set

    /** Where a stop left off, so the next play resumes rather than starting the chapter again. */
    var resumeAt by mutableIntStateOf(0); private set

    /** Which line of the script is sounding. Differs from [verseIndex] once study material
     *  is interleaved, because several lines can belong to one verse. */
    private var lineIndex = -1

    /**
     * 0.9 to 1.4; the speed people set once and leave. Setting it reaches the voice at once:
     * until 0.46 it was read only when a track was built, so a change made while listening
     * did nothing until the reading was stopped and started again, which is not what a
     * speed control that appears to do nothing invites anyone to try.
     */
    var rate: Float
        get() = pace
        set(value) { pace = value; retime() }
    private var pace by mutableStateOf(1.0f)

    /**
     * How much the voice is allowed to vary, 0 to 1. Set per voice: see [Voices.warmth].
     *
     * This is the variance of the noise fed to the flow solver, not a logit temperature:
     * the runtime takes its square root as a standard deviation. The runtime's own default
     * is 0.3 — the `"temp": 0.8` sitting in the checkpoint's config.json is stale metadata
     * that nothing ever reads. 0.3 is steady and flat; the voices here read at 0.6, and the
     * deepest at 0.9, which is where a reading gets its rise and fall back.
     */
    val warmth: Float get() = Voices.warmth(voice)

    /** How fast the audio plays, pitch kept: the voice's own tempo under the reader's pace. */
    private fun speed(): Float = rate * Voices.tempo(voice)

    /**
     * Put the current speed on the track that is sounding. Safe mid-clause: the playback
     * head counts source frames whatever the speed, so the highlight keeps its place.
     */
    private fun retime() {
        live?.let { t ->
            runCatching { t.playbackParams = PlaybackParams().setSpeed(speed()).setPitch(1f) }
        }
    }

    /**
     * Called, on the main thread, when a chapter has been read to its end — not when it
     * was stopped — so the reading can carry on into the next one.
     */
    var onChapterEnd: (() -> Unit)? = null

    /**
     * The question the voice has just asked and is waiting on, by its key; null otherwise.
     *
     * A question is two lines: the asking, and the answer. Between them the voice stops and
     * waits. Answered in that time, it goes on with the reading and the answer is never
     * said; left alone, it reads the answer out and goes on. [askedAt] is when the waiting
     * began and [askFor] how long it will last, both on the uptime clock, for the page to
     * show the time running.
     */
    var asking by mutableStateOf<String?>(null); private set

    /** The question being put or answered aloud at this moment, for the page to bring up. */
    var posed by mutableStateOf<String?>(null); private set
    var askedAt by mutableLongStateOf(0L); private set
    var askFor by mutableLongStateOf(ASK_MS); private set

    /**
     * What the listener has done about a question: null while it is open, else whether they
     * were right. Asked from the reading's own thread.
     */
    var replied: ((String) -> Boolean?)? = null

    /** Called, on the main thread, when the wait ran out and the answer is being read. */
    var onUnanswered: ((String) -> Unit)? = null

    /**
     * Wait for an answer to [key]. True if one came, and the answer need not be said.
     *
     * Half a minute with the page in front of someone. With the screen off or the app put
     * away nobody can answer, and half a minute of silence between sections is not a pause
     * to think in but a reading that seems to have stopped; there it is a few seconds, as a
     * teacher leaves after a question before giving the answer. Coming back to the page
     * part-way through gives the full time from when the question was asked.
     */
    private suspend fun awaitAnswer(key: String): Boolean {
        val began = SystemClock.elapsedRealtime()
        askedAt = began
        asking = key
        try {
            while (currentCoroutineContext().isActive) {
                val right = replied?.invoke(key)
                if (right != null) {
                    // A moment on the page before the voice moves on: longer after a wrong
                    // choice, when the right verse has just been shown and wants reading.
                    asking = null
                    kotlinx.coroutines.delay(if (right) BEAT_RIGHT_MS else BEAT_WRONG_MS)
                    return true
                }
                val allow = if (Pulse.onScreen) ASK_MS else ASK_AWAY_MS
                if (allow != askFor) askFor = allow
                if (SystemClock.elapsedRealtime() - began >= allow) break
                kotlinx.coroutines.delay(100)
            }
        } finally {
            asking = null
        }
        // Stopped while waiting is not the same as nobody answering.
        currentCoroutineContext().ensureActive()
        Handler(Looper.getMainLooper()).post { onUnanswered?.invoke(key) }
        return false
    }

    @Volatile private var tts: PhononTTS? = null
    private var job: Job? = null
    // Held so stop() can silence what is already queued inside AudioTrack; cancelling the
    // coroutine only stops generating, and a bufferful would keep playing without it.
    @Volatile private var live: AudioTrack? = null
    // Samples written to the track so far, across the whole reading. A verse begins at this
    // mark, not at the playback head — the previous verse is usually still draining, so the
    // head is already past where this verse starts.
    private var written = 0L
    private val scope = CoroutineScope(Dispatchers.Default)

    // Counts the readings begun. A reading that was stopped finishes tidying up on another
    // thread a moment later, and by then the next may have started: it must not clear
    // state that is no longer its own. It did — "read from here" pressed during a reading
    // left the page showing nothing being read while the voice carried on.
    @Volatile private var turn = 0
    // What is being read, so a change of voice can start again from the line being heard.
    private var reading: List<Line>? = null
    @Volatile private var making: Job? = null

    /**
     * The one thread speech is made on. One, because the model is a single handle and must
     * not be entered twice; its own, because it is kept on particular cores (see [Cores])
     * and that cannot be done to a thread borrowed from a pool for a sentence at a time.
     */
    private val voiceThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "voice").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    // How many threads the model was given. Fixed the first time it runs, for the life of
    // the process: its pool cannot be resized.
    private var width = 0
    private var workers = IntArray(0)
    private var scans = 0
    private var raised = 0
    private var placed = ""
    private var named = -1
    @Volatile private var pinned: Set<Int> = emptySet()

    /**
     * One thing to be read aloud.
     *
     * [verse] is the verse's position in the chapter — the same index the reader draws by,
     * so the page can follow the voice. Study material carries the index of the verse it
     * hangs from and sets [aside]: the voice moves on, but the highlight stays on the verse
     * being discussed rather than jumping to nothing.
     */
    data class Line(
        val verse: Int,
        val words: List<String>,
        val text: String,
        /** True for commentary and notes: read aloud, but not the scripture itself. */
        val aside: Boolean = false,
        /** Spoken before the text, to say whose words these are. */
        val preamble: String? = null,
        /** Set on a question put to the listener: its key. The line after it answers it. */
        val question: String? = null,
        /**
         * Set on the answer to the question with this key. The voice waits before it, and
         * leaves it unsaid if the listener has answered. See [asking].
         */
        val answerTo: String? = null,
        /**
         * A line of nothing: the voice says no words and leaves this long, in milliseconds,
         * for the listener to say them. [verse] still says what the silence is against, so
         * the page can show whose turn it is. See [hushed].
         */
        val silence: Long = 0L,
    )

    fun modelReady(): Boolean = File(ctx.filesDir, MODEL_DIR).let { d ->
        d.isDirectory && File(d, "config.json").exists() && File(d, WEIGHTS).exists()
    }

    private fun open(): PhononTTS {
        tts?.let { return it }
        // As many threads as there are fast cores left to the app off screen, not half of
        // all cores as it was: on a Pixel 9 half is four, and off screen the fourth thread
        // has only a small core to run on and holds the other three to its pace.
        if (width == 0) {
            width = Cores.width(Cores.capacity, Cores.away(ctx))
            Os.setenv("RAYON_NUM_THREADS", width.toString(), true)
            Log.d(TAG, "model on $width threads; cores ${Cores.capacity.joinToString(",")}" +
                "; off screen ${Cores.away(ctx)?.let { Cores.span(it) } ?: "not seen yet"}")
        }
        pruneVoices()
        val t = PhononTTS(File(ctx.filesDir, MODEL_DIR).path, "en")
        voices = t.voices
        // The saved choice wins, then Spencer, then whatever the checkpoint ships.
        val saved = ctx.getSharedPreferences("reader", Context.MODE_PRIVATE).getString("voice", "")
        voice = t.voices.firstOrNull { it.equals(saved, true) }
            ?: t.voices.firstOrNull { it.equals(Voices.DEFAULT, true) }
            ?: t.voices.firstOrNull() ?: "default"
        runCatching { t.setVoice(voice) }
        runCatching { t.setTemperature(warmth) }
        tts = t
        return t
    }

    /**
     * Delete voices that are no longer offered.
     *
     * Phonon lists whatever is in the folder, so a voice dropped from [Voices.ALL] would go
     * on appearing in the picker under its old name for anyone who had downloaded it. Done
     * before the model opens, which is when the folder is read.
     */
    private fun pruneVoices() {
        File(File(ctx.filesDir, MODEL_DIR), Voices.DIR).listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".safetensors") &&
                Voices.byName(f.name.removeSuffix(".safetensors")) == null) f.delete()
        }
    }

    /** Stop, and forget where we were: a different chapter starts at its beginning. */
    fun reset() { stop(); resumeAt = 0 }

    fun chooseVoice(name: String) {
        // Speech is made up to half a minute ahead of the ear, so a voice chosen during a
        // reading would not be heard until all of that had been played. Start again from
        // the line that is sounding: the answer to choosing a voice is hearing it.
        val lines = reading
        val at = lineIndex
        val again = speaking && lines != null && at >= 0
        if (again) stop()
        voice = name
        // On the model's own thread, behind whatever it was in the middle of saying.
        scope.launch(voiceThread) {
            runCatching { tts?.setVoice(name) }
            // Each voice has its own temperature, and its own tempo.
            runCatching { tts?.setTemperature(Voices.warmth(name)) }
        }
        retime()
        if (again) speak(lines!!, at)
    }

    /**
     * Drop the loaded model so the next read picks up voices downloaded since.
     *
     * Phonon reads the voices folder when the model is opened, so a model already in memory
     * has the old list however many files have arrived beside it.
     */
    /**
     * Fill [voices] without waiting for the first read.
     *
     * Opening the model is what discovers the voices, and that only happened on playback, so
     * the picker stayed hidden until something had been read aloud at least once. Cheap
     * enough (~100 ms) to do when the settings screen asks.
     */
    fun loadVoices() {
        if (voices.isNotEmpty() || !modelReady()) return
        scope.launch(voiceThread) { runCatching { open() } }
    }

    fun reloadVoices() {
        stop()
        voices = emptyList()
        // On the model's thread, so it is never closed under a sentence it is still making.
        // Reopened at once rather than at the next read, so the picker can show what just
        // arrived. Loading is ~100 ms.
        scope.launch(voiceThread) {
            runCatching { tts?.close() }
            tts = null
            runCatching { open() }
        }
    }

    fun stop() {
        // Remember the verse being read, so the button offers to carry on from there.
        // The LINE, not the verse: with study material interleaved the two are different
        // numbers, and speak() starts from a line.
        if (speaking && lineIndex >= 0 && !alone) resumeAt = lineIndex
        job?.cancel(); job = null
        // The maker too, and now: it is told again when the reading has wound down, but
        // that is a moment later and it would start another sentence in between.
        making?.cancel(); making = null
        // pause() then flush() drops the frames AudioTrack has not played yet, which is what
        // makes the button feel like a stop rather than a request to stop soon.
        live?.let { t -> runCatching { t.pause(); t.flush() } }
        speaking = false; preparing = false; carrying = false
        verseIndex = -1; wordIndex = -1; wordSpan = null
        asking = null; posed = null; hushed = false
    }

    /**
     * Read [lines] in order, from [startAt]. [alone] for a reading that is not the page's:
     * see the property of that name.
     */
    fun speak(lines: List<Line>, from: Int = 0, alone: Boolean = false) {
        stop()
        this.alone = alone
        if (lines.isEmpty()) return
        // Starting on an answer would be an answer to nothing: begin at its question.
        val startAt = if (lines.getOrNull(from)?.answerTo != null &&
            lines.getOrNull(from - 1)?.question == lines[from].answerTo) from - 1 else from
        if (!alone) resumeAt = startAt
        error = null
        reading = lines
        speaking = true
        preparing = true
        carrying = false
        val mine = ++turn
        job = scope.launch {
            val track = buildTrack()
            live = track
            written = 0L
            // The model runs on the narrator's own scope, not as a child of this job: a
            // child's failure would cancel this coroutine with a bare CancellationException
            // and lose the message. Through the channel the real error arrives instead.
            var maker: Job? = null
            var finished = false
            try {
                val model = kotlinx.coroutines.withContext(voiceThread) { open() }
                preparing = false
                // Every clause of the chapter, flattened, so generation can run ahead of
                // playback across verse boundaries as well as inside them.
                val script = buildList {
                    for (i in startAt until lines.size) {
                        val line = lines[i]
                        // Room left for the listener: one step, with nothing to make.
                        if (line.silence > 0) { add(i to Clause(IntRange.EMPTY, "", 0)); continue }
                        // "Matthew Henry writes." before the commentary, so the reader knows
                        // whose voice this is without looking at the screen. It is a clause
                        // of its own, over no words: nothing on the page lights for it.
                        line.preamble?.let { add(i to Clause(IntRange.EMPTY, it, PAUSE_STOP)) }
                        clausesOf(line.words).forEach { add(i to it) }
                    }
                }
                // Generation runs ahead of playback through a queue: one coroutine, in
                // order — the model is a single native handle and is not shared — making
                // clauses until AHEAD of finished speech is waiting, then resting until the
                // voice has caught up. The queue was two clauses until 0.47, which is a few
                // seconds: enough while the model is several times faster than speech, and
                // nothing at all the moment it is not. Half a minute rides out a slow
                // patch, and costs three megabytes.
                val feed = Channel<Pair<Pair<Int, Clause>, FloatArray>>(Channel.UNLIMITED)
                val ahead = AtomicLong(0)
                maker = scope.launch(voiceThread) {
                    try {
                        for (step in script) {
                            while (isActive && ahead.get() > AHEAD) kotlinx.coroutines.delay(100)
                            if (!isActive) break
                            if (lines[step.first].silence > 0) { feed.send(step to FloatArray(0)); continue }
                            tune()
                            val pcm = render(model, step.second) { isActive } ?: break
                            ahead.addAndGet(pcm.size.toLong())
                            feed.send(step to pcm)
                        }
                        feed.close()
                    } catch (e: Throwable) {
                        feed.close(e)
                    }
                }
                making = maker
                // Not counted before the first clause: that wait is the model opening,
                // and the play button already shows it.
                var idle = -1L
                // The answer line last waited before, and the one being left unsaid: decided
                // once, at the wait, so an answer the voice has begun is finished even
                // though the page marks the question settled the moment it begins.
                var waitedOn: String? = null
                var unsaid: String? = null
                for ((step, pcm) in feed) {
                    ahead.addAndGet(-pcm.size.toLong())
                    if (!isActive) break
                    // Time spent with nothing to say: the voice had finished a clause and
                    // the next was not made yet. Silence the script did not ask for.
                    if (idle >= 0) (SystemClock.elapsedRealtime() - idle).let {
                        if (it >= Pulse.STALL_MS) Log.d(TAG, "stalled $it ms waiting for the next clause")
                        Pulse.waited(it)
                    }
                    val (i, clause) = step
                    val line = lines[i]
                    // The listener's turn. Silence the script asked for, so not a stall.
                    if (line.silence > 0) {
                        lineIndex = i
                        verseIndex = line.verse
                        wordSpan = null; wordIndex = -1
                        hushed = true
                        try { kotlinx.coroutines.delay(line.silence) } finally { hushed = false }
                        idle = -1L
                        continue
                    }
                    // A question the listener settled on the page before the voice reached
                    // it is not asked, and its answer is not read.
                    if (line.question != null && replied?.invoke(line.question) != null) {
                        idle = -1L
                        continue
                    }
                    if (line.answerTo != null) {
                        // Before the first clause of an answer, wait for the listener's.
                        if (line.answerTo != waitedOn) {
                            waitedOn = line.answerTo
                            unsaid = if (replied?.invoke(line.answerTo) != null) line.answerTo
                                     else if (awaitAnswer(line.answerTo)) line.answerTo else null
                            // The wait is silence the script asked for, not a stall.
                            idle = -1L
                        }
                        if (unsaid == line.answerTo) continue
                    }
                    // An aside is read against the verse it belongs to, so the page keeps
                    // that verse marked while its commentary is spoken instead of going
                    // blank. Only scripture gets the moving highlight within the verse.
                    lineIndex = i
                    verseIndex = line.verse
                    (line.question ?: line.answerTo).let { if (it != posed) posed = it }
                    wordSpan = if (line.aside) null else clause.range
                    sayOne(track, line, clause, pcm)
                    idle = SystemClock.elapsedRealtime()
                }
                drain(track)
                finished = isActive
            } catch (e: Throwable) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "narration failed", e)
                    error = e.message ?: e::class.simpleName
                }
            } finally {
                maker?.cancel()
                Pulse.save(ctx)
                runCatching { track.stop(); track.flush(); track.release() }
                // Only if no reading has begun since: see [turn].
                if (turn == mine) {
                    live = null
                    lineIndex = -1
                    // Before speaking goes false, so nothing sees a moment with neither.
                    if (finished && !alone && onChapterEnd != null) carrying = true
                    speaking = false; preparing = false
                    verseIndex = -1; wordIndex = -1; wordSpan = null
                    posed = null; hushed = false
                }
            }
            if (finished && turn == mine) {
                // Read to the end, so the next press starts over — and whoever is listening
                // may want the next chapter.
                if (!alone) resumeAt = 0
                Handler(Looper.getMainLooper()).post {
                    val then = onChapterEnd
                    if (then != null && carrying) then() else carrying = false
                }
            }
        }
    }

    private fun buildTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, RATE * 2))
            .build()
            .also {
                // Time-stretched, not resampled: playbackRate would raise the pitch with the
                // speed, and a deep voice played at 1.25x stops being deep. The playback head
                // still counts source frames, which is what sayOne measures in.
                it.playbackParams = PlaybackParams().setSpeed(speed()).setPitch(1f)
                it.play()
            }
    }

    /**
     * Generate one verse and play it, moving [wordIndex] from the playback head.
     *
     * Generation runs ahead of playback, so the frames are queued to a player thread; the
     * callback's FloatArray is a view of native memory ptts reuses, so it is copied first.
     */
    /**
     * A run of words spoken as one utterance, with where it sits in the verse, and the
     * silence in frames that follows it — decided by what ended it, see [clausesOf].
     */
    data class Clause(val range: IntRange, val text: String, val pause: Int = PAUSE_STOP)

    /**
     * What a clause should SOUND like, which is not always what it looks like.
     *
     * The tokenizer is a 4000-piece Unigram vocabulary with `\u2581Lord` and `\u2581God` in it
     * but no `LORD` or `GOD`: an all-capital word has no piece of its own and breaks into
     * single letters — the same shape an acronym gets — so the model spells it out. The BSB
     * sets the divine name in capitals 6,483 times across nearly a fifth of its verses, so
     * this is most of what a chapter sounds like.
     *
     * The capitals are a real distinction on the page (LORD for the divine name against
     * Lord for the title) and worth keeping there, but a reader says "Lord" either way.
     * Only the spoken string is folded; what is drawn is untouched.
     *
     * Em dashes go too: the normaliser deletes them rather than pausing on them, so an aside
     * runs into its sentence. A comma is how this model is asked to breathe.
     */
    private fun speakable(text: String): String =
        text.split(" ").joinToString(" ") { w ->
            // Ignore a trailing possessive, so LORD's folds the same as LORD.
            val letters = w.removeSuffix("'s").removeSuffix("'S").filter { it.isLetter() }
            val folded =
                if (letters.length >= 2 && letters.all { it.isUpperCase() })
                    w.lowercase().replaceFirstChar { it.uppercase() }
                else w
            respell(folded)
        }.replace("\u2014", ",").replace("\u2013", ",").replace(" ,", ",")

    /**
     * Say a name the model cannot read, by spelling it the way it sounds.
     *
     * The same 4000-piece vocabulary that spells out LORD also has no piece for a lot of
     * biblical names, and what it does instead is worse than spelling: it takes the longest
     * pieces it has and runs them together. `Pyrrhus` becomes P|y|r|r|hu|s and `Binnui`
     * becomes B|in|n|u|i — five and four single-character pieces, read as fragments.
     *
     * Each entry below was checked against the real tokenizer (a Viterbi pass over the
     * Unigram lattice in `tokenizer.json`, the same segmentation ptts performs) and is here
     * only because the respelling genuinely segments better: Pyrrhus 5 single-char pieces
     * down to 1, Binnui 4 to 1, Cyprus 4 to 2.
     *
     * Deliberately NOT here: `Psalm`, `Egypt`, `Hebrews`, `Pharaoh`, `Woe`. They fragment too
     * — `Psalm` is the single most-heard fragmenting word in the Bible — but they are ordinary
     * English the model has clearly learned, and it says them correctly. Fragment count is a
     * proxy for trouble, not proof of it, so it earns a word a listen, not an entry.
     *
     * Only the spoken string changes; the page is untouched. Add to this list by ear: say the
     * word, and if the model gets it wrong, find a spelling it reads correctly.
     */
    private fun respell(word: String): String {
        val bare = word.trimStart { !it.isLetter() }.trimEnd { !it.isLetter() }
        if (bare.isEmpty()) return word
        val fix = RESPELL[bare] ?: RESPELL[bare.lowercase()] ?: return word
        return word.replaceFirst(bare, fix)
    }

    /**
     * Split a verse into the pieces that will be spoken separately.
     *
     * Phonon splits on sentence boundaries internally anyway; doing it here means the app
     * KNOWS where each piece starts and ends instead of guessing. Only a long sentence is
     * broken again at a comma or semicolon. It used to happen at seven words, and that was
     * audible: a clause generated on its own is finished by the model as if it were a
     * sentence — the voice falls, and then there is the model's own tail of silence — so
     * every comma became a full stop. Inside one utterance a comma is a breath, which is
     * what it is on the page. The threshold is now high enough that most sentences stay
     * whole; what a split costs in prosody it only buys back in a shorter lit span.
     */
    private fun clausesOf(words: List<String>): List<Clause> {
        val out = mutableListOf<Clause>()
        var start = 0
        // A word may be blank: a book's "Genesis 3:8-9 (ESV)" is said as one phrase standing
        // in the first word's place, and the rest keep their places on the page with nothing
        // to say. Blanks are left out of what is spoken and never end a clause themselves.
        fun said(from: Int, to: Int) =
            words.subList(from, to + 1).filter { it.isNotBlank() }.joinToString(" ")
        words.forEachIndexed { i, w ->
            val hard = w.endsWith(".") || w.endsWith("!") || w.endsWith("?") ||
                w.endsWith(".\u201d") || w.endsWith("!\u201d") || w.endsWith("?\u201d")
            val soft = w.endsWith(";") || w.endsWith(":") || w.endsWith(",")
            val len = i - start + 1
            val last = i == words.lastIndex
            if (last || (hard && len >= MIN_CLAUSE) || (soft && len >= SOFT_CLAUSE)) {
                val pause = when {
                    last -> PAUSE_VERSE
                    hard -> PAUSE_STOP
                    else -> PAUSE_BREATH
                }
                val text = said(start, i)
                if (text.isNotEmpty()) out += Clause(start..i, text, pause)
                // Nothing to say at the very end: the pause that closes the block goes to
                // the clause before, which would otherwise run straight into the next one.
                else if (last && out.isNotEmpty()) out[out.lastIndex] = out.last().copy(pause = PAUSE_VERSE)
                if (text.isNotEmpty() || last) start = i + 1
            }
        }
        return out
    }

    /**
     * Make the audio for one clause. Blocking; called from the generator coroutine.
     *
     * Returned whole rather than streamed. Phonon generates about four times faster than
     * speech, so a clause is ready long before the one before it has finished sounding —
     * and having the complete buffer means the highlight knows the true length of the
     * utterance from its first frame instead of dividing by a total that is still growing.
     */
    private fun render(model: PhononTTS, clause: Clause, wanted: () -> Boolean): FloatArray? {
        // Debug-level, so the script can be read back from logcat when a device is the
        // only ear available: which clauses were made, and what split them.
        Log.d(TAG, "say [${clause.range}] ${clause.text}")
        val began = SystemClock.elapsedRealtime()
        val parts = mutableListOf<FloatArray>()
        // The model hands over a frame at a time and stops when told to, so a reading
        // that has been stopped does not go on making a sentence nobody will hear, with
        // the next reading waiting behind it for the thread.
        var whole = true
        // The model does its work on threads it starts for the sentence, not on the one
        // that asked: this one only waits and is handed the frames. They take this
        // thread's name, its cores and its priority as they are born, so placing this one
        // places them; they are found here to be told so directly as well, and because
        // theirs is the waiting that says whether they got the cores.
        var crew: IntArray? = null
        var first: LongArray? = null
        var last: LongArray? = null
        model.speak(speakable(clause.text)) { pcm ->
            parts += pcm.copyOf()
            val found = crew ?: run {
                val me = Process.myTid()
                Cores.threads("voice").filter { it != me }.toIntArray().also { c ->
                    crew = c
                    val to = pinned
                    if (to.isNotEmpty() && c.isNotEmpty()) Cores.pin(c, to)
                    if (named != c.size) { named = c.size; Log.d(TAG, "speech is made on ${c.size} threads of its own") }
                }
            }
            if (found.isNotEmpty()) {
                val now = LongArray(2)
                var seen = false
                for (t in found) Cores.waited(t)?.let { now[0] += it[0]; now[1] += it[1]; seen = true }
                if (seen) { if (first == null) first = now; last = now }
            }
            wanted().also { if (!it) whole = false }
        }
        if (!whole) return null
        // How far ahead of the voice the model is running. Under 1x the reading stalls
        // between clauses however long the queue is, and that is heard as a long pause.
        run {
            val took = (SystemClock.elapsedRealtime() - began).coerceAtLeast(1L)
            // The kernel's account of the making threads, first frame to last: time on a
            // core, and time ready to run with no core free. The second is the slowing
            // that Android does to an app off the screen, seen directly.
            val a = first; val b = last
            val ran = if (a != null && b != null) (b[0] - a[0]) / 1e9f else 0f
            val queued = if (a != null && b != null) (b[1] - a[1]) / 1e9f else 0f
            val made = parts.sumOf { it.size }
            Log.d(TAG, "made %.2f s in %.2f s, %.1fx, on a core %.2f s, waiting for one %.2f s".format(
                made / RATE.toFloat(), took / 1000f, made * 1000f / RATE / took, ran, queued))
            Pulse.made(made / RATE.toFloat(), took / 1000f, ran, queued, Cores.clock(pinned))
        }
        val raw = FloatArray(parts.sumOf { it.size })
        var at = 0
        parts.forEach { System.arraycopy(it, 0, raw, at, it.size); at += it.size }
        return trimmed(raw, clause.pause)
    }

    /**
     * Keep the model's threads on the fastest cores the app may use at this moment, and
     * note what those were. Called on [voiceThread] before every clause: where the threads
     * may run is reset whenever the app moves on or off the screen. See [Cores].
     */
    private fun tune() {
        runCatching {
            // The model's own threads appear the first time it runs, so not before the
            // second clause; until then they start out wherever this thread has been put.
            if (workers.size < width - 1 && scans < 8) {
                workers = Cores.threads("xn-worker"); scans++
            }
            val allowed = Cores.allowed()
            if (allowed.isEmpty()) return
            val chosen = Cores.choose(Cores.capacity, allowed, width)
            val tids = intArrayOf(Process.myTid()) + workers
            val held = Cores.pin(tids, chosen)
            // A little above the ordinary, so that work nobody is waiting for does not take
            // a core from a thread three others are waiting on. Below the screen's own.
            if (raised != tids.size) {
                tids.forEach { runCatching { Process.setThreadPriority(it, PRIORITY) } }
                raised = tids.size
            }
            pinned = chosen
            if (!Pulse.onScreen) Cores.sawAway(ctx, allowed)
            Pulse.on(width, chosen, allowed, held)
            val now = "$width threads (${tids.size} found) on cores ${Cores.span(chosen)} of " +
                "${Cores.span(allowed)}, ${if (held) "held" else "NOT held"}, " +
                if (Pulse.onScreen) "on screen" else "away"
            if (now != placed) { placed = now; Log.d(TAG, now) }
        }
    }

    /**
     * Cut the model's own silence off both ends of an utterance and put back a pause of
     * our choosing.
     *
     * Phonon leaves anything up to 150 ms before the first word and about 560 ms after the
     * last, whatever the punctuation. Played back to back that is a full stop's worth of
     * air after every comma the chapter was split at — the reading sounded as if it paused
     * to think between clauses. So the silence is found and removed, and the gap that
     * follows is decided by what ended the clause: a breath after a comma, a stop after a
     * sentence, a beat longer at the end of a verse. A short run-in and run-out are kept so
     * an onset or a final consonant is not clipped.
     */
    private fun trimmed(pcm: FloatArray, pause: Int): FloatArray {
        val first = pcm.indexOfFirst { kotlin.math.abs(it) > SILENCE }
        if (first < 0) return FloatArray(pause)
        val last = pcm.indexOfLast { kotlin.math.abs(it) > SILENCE }
        val from = (first - RUN_IN).coerceAtLeast(0)
        val to = (last + RUN_OUT).coerceAtMost(pcm.size)
        val out = FloatArray(to - from + pause)
        System.arraycopy(pcm, from, out, 0, to - from)
        return out
    }

    /**
     * Play one clause and follow the voice through it.
     *
     * The buffer is complete before this is called, so the word under the voice is a
     * fraction of a known length, read from AudioTrack's playback head. The head is the
     * only honest clock here: it keeps up with the voice even if the thread is descheduled.
     */
    private suspend fun sayOne(track: AudioTrack, line: Line, clause: Clause, pcm: FloatArray) {
        if (pcm.isEmpty()) return
        wordIndex = -1      // nothing is lit until this clause is actually being heard

        // Where this clause begins in the track's own timeline. The track is never stopped
        // between clauses — that is what keeps the reading gapless — so the head keeps
        // counting and each clause has to be measured from its own starting mark.
        val base = written
        val began = SystemClock.elapsedRealtime()

        // Where speech actually starts. render() has trimmed the model's lead-in, so this
        // is the short run-in it kept — found rather than assumed all the same.
        val lead = pcm.indexOfFirst { kotlin.math.abs(it) > SILENCE }.coerceAtLeast(0).toLong()

        // How long each word holds the voice. Character count is a poor guide: "I" and
        // "through" differ by a factor of seven in letters but nothing like that in time.
        // Syllables plus a floor per word track speech far better, and the floor is what
        // keeps a run of short words ("Even though I walk through the") from being skipped.
        // A clause of nothing but what is said before the words (a reference, a heading) has
        // an empty range, and on a line with no words of its own that range is out of bounds.
        val spanWords = if (clause.range.isEmpty()) emptyList()
        else line.words.subList(clause.range.first, clause.range.last + 1)
        val weights = spanWords.map { syllables(it) + WORD_FLOOR }
        val total = weights.sum().coerceAtLeast(1f)
        val bounds = FloatArray(weights.size)          // cumulative fraction of the SPOKEN part
        run {
            var acc = 0f
            weights.forEachIndexed { i, w -> acc += w; bounds[i] = acc / total }
        }
        // The buffer ends in the pause render() put there, which is not speech; measuring
        // across it would run the highlight ahead of the voice.
        val spoken = (pcm.size - lead - clause.pause).coerceAtLeast(1L)

        val watcher = scope.launch {
            while (isActive) {
                val abs = (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL) - base
                if (abs >= lead && weights.isNotEmpty()) {
                    val frac = ((abs - lead).toFloat() / spoken).coerceIn(0f, 1f)
                    var idx = bounds.indexOfFirst { frac <= it }
                    if (idx < 0) idx = weights.lastIndex
                    val at = clause.range.first + idx
                    if (at != wordIndex) wordIndex = at
                }
                kotlinx.coroutines.delay(30)
            }
        }

        try {
            // Blocking writes on a worker: this is what paces the reading. The track's
            // buffer is small, so write() returns as the audio drains and the loop below
            // keeps pace with the voice rather than racing ahead of it.
            withContext(Dispatchers.IO) {
                var at = 0
                while (at < pcm.size && isActive) {
                    val n = minOf(CHUNK, pcm.size - at)
                    val put = track.write(pcm, at, n, AudioTrack.WRITE_BLOCKING)
                    if (put <= 0) break
                    at += put
                    written += put
                }
            }
            // write() returning only means the frames were handed over. Wait for them to be
            // heard, or the next clause's mark would be taken while this one still sounds.
            var spins = 0
            while (spins < 600 && currentCoroutineContext().isActive) {
                val head = (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL) - base
                if (head >= pcm.size) break
                kotlinx.coroutines.delay(20); spins++
            }
            // The pace as it was heard, not as it was asked for: audio seconds over wall
            // seconds. This is how a speed change is checked on a device with no ear.
            val took = (SystemClock.elapsedRealtime() - began).coerceAtLeast(1L)
            Log.d(TAG, "heard %.2f s in %.2f s, %.2fx".format(
                pcm.size / RATE.toFloat(), took / 1000f, pcm.size * 1000f / RATE / took))
        } finally {
            watcher.cancel()
        }
    }

    private suspend fun drain(track: AudioTrack) {
        var last = -1L
        repeat(60) {
            val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (head == last) return
            last = head
            kotlinx.coroutines.delay(50)
        }
    }

    fun close() {
        stop()
        // On the model's thread, behind the clause it may be in the middle of: freeing the
        // model under a call still inside it is a crash in native code.
        val t = tts
        tts = null
        runCatching {
            scope.launch(voiceThread) { runCatching { t?.close() } }
            voiceThread.close()
        }
    }

    /** A rough syllable count: vowel groups, with a silent trailing 'e' discounted. */
    private fun syllables(word: String): Float {
        val w = word.lowercase().filter { it in 'a'..'z' }
        if (w.isEmpty()) return 1f
        var groups = 0
        var inVowel = false
        for (c in w) {
            val vowel = c in "aeiouy"
            if (vowel && !inVowel) groups++
            inVowel = vowel
        }
        if (w.endsWith("e") && groups > 1 &&
            !w.endsWith("le") && !w.endsWith("ee") && !w.endsWith("ye")) groups--
        return groups.coerceAtLeast(1).toFloat()
    }

    companion object {
        /**
         * Names the model misreads, and a spelling it reads correctly.
         *
         * Verified against the shipped tokenizer, not guessed: each right-hand side segments
         * into fewer single-character pieces than the name it replaces. See [respell].
         */
        private val RESPELL = mapOf(
            // Proper nouns whose real spelling the vocabulary cannot piece together.
            "Pyrrhus" to "Pirus",
            "Binnui" to "Binooee",
            "Cyprus" to "Siprus",
            "Shammua" to "Shammooah",
            "Phinehas" to "Finehas",
            "Issachar" to "Izzakar",
            "Euphrates" to "Yoofrates",
            "Iscariot" to "Iscarryot",
            // "Acts" is read A-c-t-s, and it is in every cross-reference. "Acks" halves
            // the fragments; "Ax" scores the same but would be said "axe".
            "Acts" to "Acks",
        )
        /**
         * Tidy a commentary paragraph for the ear.
         *
         * Commentators cite constantly, in a shorthand built for the eye: "( Joh 10:14 ;
         * He 13:20 ; 1Pe 2:25 ; 5:4 )". Spoken, that is a minute of letters and numbers in
         * the middle of a sentence. Alongside them are navigational waymarks — "(1.)",
         * "( v. 6 )", "(compare)" — put there to be glanced at, not heard.
         *
         * Both are dropped rather than expanded: the argument reads perfectly well without
         * them, and they stay on the page for the eye. Measured over 3,000 entries this
         * keeps 94% of the prose and leaves a citation artefact in 5% of them.
         */
        fun listenable(text: String): String {
            val cite = """(?:[1-3]?\s?[A-Za-z]{2,4}\.?\s*)?\d+:\d+(?:-\d+)?"""
            var t = text
            // Tyndale's quoted scripture is marked *like this* for the page. The emphasis
            // is carried by the voice, not by saying "asterisk".
            t = t.replace("*", "")
            // A parenthesis holding nothing but citations and their separators.
            t = t.replace(Regex("""\(\s*(?:$cite|[;,.\s])+\)"""), "")
            // A bare citation in running text, with any separator trailing it.
            t = t.replace(Regex("""\b$cite\s*[;,]?"""), "")
            // Waymarks: "(1.)", "( v. 6 )", "( 4 )".
            t = t.replace(
                Regex("""\(\s*(?:(?:v+\.|ver\.?)?\s*\d+[.,]?\s*)+\)""", RegexOption.IGNORE_CASE), "")
            // The stub left when "(compare Jn 1:1)" loses its reference.
            t = t.replace(
                Regex("""\(\s*(?:compare|see(?:\s+on)?|cf\.?)\s*\)""", RegexOption.IGNORE_CASE), "")
            t = t.replace("&", " and ")
            t = t.replace(Regex("""\bv+\.\s*\d+"""), "")
            // A connective left dangling by a removed citation: "and in , is not".
            t = t.replace(
                Regex("""\b(?:and|as|in|see|cf\.?)\s+(?=[,;.])""", RegexOption.IGNORE_CASE), "")
            t = t.replace(Regex("""\s{2,}"""), " ")
            t = t.replace(Regex("""\s+([,.;:])"""), "$1")
            t = t.replace(Regex("""[(\[]\s*[)\]]"""), "")
            t = t.replace(Regex("""(?:[,;]\s*){2,}"""), ", ")
            t = t.replace(Regex("""^[\s.;,]+"""), "")
            return t.replace(Regex("""\s{2,}"""), " ").trim()
        }


        private const val TAG = "lamplight"
        /** Below this a sample is silence, for finding where speech starts and stops. */
        private const val SILENCE = 0.015f
        /** Kept before the first loud sample and after the last, so an onset or a final
         *  consonant is not clipped: 40 ms and 70 ms at 24 kHz. */
        private const val RUN_IN = 960
        private const val RUN_OUT = 1680
        /** The air after a clause, in frames, by what ended it. A comma is a breath, a full
         *  stop a pause, and a verse end a beat more — long enough to hear the number change. */
        private const val PAUSE_BREATH = 4800       // 200 ms
        private const val PAUSE_STOP = 9600         // 400 ms
        private const val PAUSE_VERSE = 13200       // 550 ms
        /** The model's threads: Android's own "display" priority, between ordinary and audio. */
        private const val PRIORITY = -4
        /** Added to every word's syllable count, so short words still hold their turn. */
        private const val WORD_FLOOR = 0.7f
        /** Frames per blocking write. Small enough that write() paces the reading and a
         *  stop takes effect promptly, large enough not to spin. ~85 ms at 24 kHz. */
        private const val CHUNK = 2048
        /** Words a sentence must reach before it is worth lighting on its own. */
        private const val MIN_CLAUSE = 3
        /** A comma only breaks a span once it has run this long. See [clausesOf]. */
        private const val SOFT_CLAUSE = 24
        /** The runtime's own default, for a voice not in [Voices]. The listed voices set their own. */
        const val NARRATION_TEMP = 0.3f
        const val RATE = 24000
        /** Finished speech held ahead of playback, in frames: half a minute. */
        private const val AHEAD = RATE * 30L

        /** How long the voice waits on a question with the page in front of someone. */
        const val ASK_MS = 30_000L
        /** And with the screen off or the app put away, where nobody can answer. */
        const val ASK_AWAY_MS = 8_000L
        private const val BEAT_RIGHT_MS = 1_200L
        private const val BEAT_WRONG_MS = 3_500L
        const val MODEL_DIR = "phonon-31m-en"
        const val WEIGHTS = "model.q8.gguf"
        val MODEL_FILES = listOf("config.json", "tokenizer.json", WEIGHTS, "default-voice.safetensors")
        const val MODEL_BASE = "https://huggingface.co/gradium/phonon-31m-en/resolve/main/"
    }
}
