package ca.lampandlight

import ai.gradium.phonon.PhononTTS
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    var speaking by mutableStateOf(false); private set
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

    private var tts: PhononTTS? = null
    private var job: Job? = null
    // Held so stop() can silence what is already queued inside AudioTrack; cancelling the
    // coroutine only stops generating, and a bufferful would keep playing without it.
    @Volatile private var live: AudioTrack? = null
    // Samples written to the track so far, across the whole reading. A verse begins at this
    // mark, not at the playback head — the previous verse is usually still draining, so the
    // head is already past where this verse starts.
    private var written = 0L
    private val scope = CoroutineScope(Dispatchers.Default)

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
    )

    fun modelReady(): Boolean = File(ctx.filesDir, MODEL_DIR).let { d ->
        d.isDirectory && File(d, "config.json").exists() && File(d, WEIGHTS).exists()
    }

    private fun open(): PhononTTS {
        tts?.let { return it }
        val cores = Runtime.getRuntime().availableProcessors()
        Os.setenv("RAYON_NUM_THREADS", (cores / 2).coerceIn(2, 6).toString(), true)
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
        voice = name
        runCatching { tts?.setVoice(name) }
        // Each voice has its own temperature, and its own tempo.
        runCatching { tts?.setTemperature(warmth) }
        retime()
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
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { open() } }
    }

    fun reloadVoices() {
        stop()
        runCatching { tts?.close() }
        tts = null
        voices = emptyList()
        // Reopen at once rather than waiting for the next read, so the picker can show what
        // just arrived. Loading is ~100 ms and this runs off the main thread.
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { open() } }
    }

    fun stop() {
        // Remember the verse being read, so the button offers to carry on from there.
        // The LINE, not the verse: with study material interleaved the two are different
        // numbers, and speak() starts from a line.
        if (speaking && lineIndex >= 0) resumeAt = lineIndex
        job?.cancel(); job = null
        // pause() then flush() drops the frames AudioTrack has not played yet, which is what
        // makes the button feel like a stop rather than a request to stop soon.
        live?.let { t -> runCatching { t.pause(); t.flush() } }
        speaking = false; preparing = false
        verseIndex = -1; wordIndex = -1; wordSpan = null
    }

    /** Read [lines] in order, from [startAt]. */
    fun speak(lines: List<Line>, startAt: Int = 0) {
        stop()
        if (lines.isEmpty()) return
        resumeAt = startAt
        error = null
        speaking = true
        preparing = true
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
                val model = open()
                preparing = false
                // Every clause of the chapter, flattened, so generation can run ahead of
                // playback across verse boundaries as well as inside them.
                val script = buildList {
                    for (i in startAt until lines.size) {
                        val line = lines[i]
                        // "Matthew Henry writes." before the commentary, so the reader knows
                        // whose voice this is without looking at the screen. It is a clause
                        // of its own, over no words: nothing on the page lights for it.
                        line.preamble?.let { add(i to Clause(IntRange.EMPTY, it, PAUSE_STOP)) }
                        clausesOf(line.words).forEach { add(i to it) }
                    }
                }
                // Generation runs ahead of playback through a short queue: one coroutine,
                // in order — the model is a single native handle and is not shared — filling
                // a channel that holds LOOKAHEAD finished clauses. One clause of lookahead
                // was not enough: a three-word sentence followed by a thirty-word one
                // stalled, because the long one took longer to make than the short one took
                // to say. Phonon is ~4x faster than speech, so the queue is full nearly
                // all the time and a long sentence has been made two clauses before it is
                // needed.
                val feed = Channel<Pair<Pair<Int, Clause>, FloatArray>>(LOOKAHEAD)
                maker = scope.launch(Dispatchers.Default) {
                    try {
                        for (step in script) {
                            if (!isActive) break
                            feed.send(step to render(model, step.second))
                        }
                        feed.close()
                    } catch (e: Throwable) {
                        feed.close(e)
                    }
                }
                for ((step, pcm) in feed) {
                    if (!isActive) break
                    val (i, clause) = step
                    val line = lines[i]
                    // An aside is read against the verse it belongs to, so the page keeps
                    // that verse marked while its commentary is spoken instead of going
                    // blank. Only scripture gets the moving highlight within the verse.
                    lineIndex = i
                    verseIndex = line.verse
                    wordSpan = if (line.aside) null else clause.range
                    sayOne(track, line, clause, pcm)
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
                live = null
                lineIndex = -1
                runCatching { track.stop(); track.flush(); track.release() }
                speaking = false; preparing = false
                verseIndex = -1; wordIndex = -1; wordSpan = null
            }
            if (finished) {
                // Read to the end, so the next press starts over — and whoever is listening
                // may want the next chapter.
                resumeAt = 0
                Handler(Looper.getMainLooper()).post { onChapterEnd?.invoke() }
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
    private fun render(model: PhononTTS, clause: Clause): FloatArray {
        // Debug-level, so the script can be read back from logcat when a device is the
        // only ear available: which clauses were made, and what split them.
        Log.d(TAG, "say [${clause.range}] ${clause.text}")
        val parts = mutableListOf<FloatArray>()
        model.speak(speakable(clause.text)) { pcm -> parts += pcm.copyOf(); true }
        val raw = FloatArray(parts.sumOf { it.size })
        var at = 0
        parts.forEach { System.arraycopy(it, 0, raw, at, it.size); at += it.size }
        return trimmed(raw, clause.pause)
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
        val began = android.os.SystemClock.elapsedRealtime()

        // Where speech actually starts. render() has trimmed the model's lead-in, so this
        // is the short run-in it kept — found rather than assumed all the same.
        val lead = pcm.indexOfFirst { kotlin.math.abs(it) > SILENCE }.coerceAtLeast(0).toLong()

        // How long each word holds the voice. Character count is a poor guide: "I" and
        // "through" differ by a factor of seven in letters but nothing like that in time.
        // Syllables plus a floor per word track speech far better, and the floor is what
        // keeps a run of short words ("Even though I walk through the") from being skipped.
        val spanWords = line.words.subList(clause.range.first, clause.range.last + 1)
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
                if (abs >= lead) {
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
            val took = (android.os.SystemClock.elapsedRealtime() - began).coerceAtLeast(1L)
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
        runCatching { tts?.close() }
        tts = null
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
        /** Finished clauses held ahead of playback. */
        private const val LOOKAHEAD = 2
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
        const val MODEL_DIR = "phonon-31m-en"
        const val WEIGHTS = "model.q8.gguf"
        val MODEL_FILES = listOf("config.json", "tokenizer.json", WEIGHTS, "default-voice.safetensors")
        const val MODEL_BASE = "https://huggingface.co/gradium/phonon-31m-en/resolve/main/"
    }
}
