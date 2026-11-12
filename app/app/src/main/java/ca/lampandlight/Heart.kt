package ca.lampandlight

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect

/**
 * A verse set out to be learned: a phrase to a line, the way a poem is set, because a line
 * is what the ear holds.
 *
 * [level] is how much of a phrase shows until it has been [seen]: all of it, the first
 * letter of each word, or only where the words go. A hidden word keeps its place and its
 * width, so the verse does not shift as it is uncovered. [at] is the phrase the voice is on,
 * [ByHeart.WHOLE] when it is on all of them, or -1 when it is silent, and [hush] is true
 * while it is leaving room for the listener rather than speaking.
 *
 * Said from memory and listened to, it fills in: [heard] is the words of each line that
 * have been heard, by their place in the line, and they show where they stand though the
 * line is still covered. [next] is the line being listened for then, or -1.
 */
@Composable
fun VersePanel(
    phrases: List<String>,
    level: Int,
    seen: Set<Int>,
    at: Int,
    hush: Boolean,
    heard: Map<Int, Set<Int>> = emptyMap(),
    next: Int = -1,
    onTap: ((Int) -> Unit)?,
) {
    val p = LocalPalette.current
    // A passage of several verses in large type is taller than the screen. It scrolls within
    // itself, so what is under it (whose turn it is, and the buttons) never leaves the page.
    val tall = (LocalConfiguration.current.screenHeightDp - 350).coerceAtLeast(220).dp
    val scroll = rememberScrollState()
    Surface(color = p.sunk, shape = RoundedCornerShape(7.dp), modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.heightIn(max = tall)) {
            Column(
                Modifier.verticalScroll(scroll).padding(start = 4.dp, end = 16.dp, top = 15.dp, bottom = 15.dp),
            ) {
                phrases.forEachIndexed { i, phrase ->
                    val on = at == i || at == ByHeart.WHOLE
                    // Nobody is leading, and this is the line the page is listening for.
                    val mine = at < 0 && next == i
                    Phrase(
                        text = phrase,
                        mode = if (i in seen) ByHeart.NEW else level,
                        heard = heard[i] ?: emptySet(),
                        waiting = (hush && on) || mine,
                        sounding = !hush && on,
                        // The voice is busy elsewhere in the verse: this line stands back.
                        back = at >= 0 && !on,
                        lead = at == i || mine,
                        onTap = onTap?.takeIf { i !in seen && level != ByHeart.NEW }?.let { { it(i) } },
                    )
                }
            }
            // More above or below is said by the edge fading, not by a scroll bar.
            val up by animateFloatAsState(if (scroll.canScrollBackward) 1f else 0f, tween(200), label = "moreUp")
            val down by animateFloatAsState(if (scroll.canScrollForward) 1f else 0f, tween(200), label = "moreDown")
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(30.dp).alpha(up)
                    .background(Brush.verticalGradient(listOf(p.sunk, p.sunk.copy(alpha = 0f)))),
            )
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(30.dp).alpha(down)
                    .background(Brush.verticalGradient(listOf(p.sunk.copy(alpha = 0f), p.sunk))),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Phrase(
    text: String, mode: Int, heard: Set<Int>, waiting: Boolean, sounding: Boolean, back: Boolean,
    lead: Boolean, onTap: (() -> Unit)?,
) {
    val p = LocalPalette.current
    val scale = LocalTextScale.current
    val shown by animateFloatAsState(if (mode == ByHeart.NEW) 1f else 0f, tween(420), label = "shown")
    // A word heard comes up where it stands. The ones before it are already there: only
    // what has just arrived is still arriving.
    var settled by remember(text) { mutableStateOf(heard) }
    val arriving = remember(text) { Animatable(1f) }
    val began = remember(text) { arrayOf(heard) }
    LaunchedEffect(heard) {
        if (heard == began[0]) return@LaunchedEffect
        settled = began[0]; began[0] = heard
        arriving.snapTo(0f)
        arriving.animateTo(1f, tween(260))
        settled = heard
    }
    fun inked(word: Int): Float = when {
        word in settled -> 1f
        word in heard -> arriving.value
        else -> 0f
    }
    val ink by animateColorAsState(if (back) p.inkSoft else p.ink, tween(300), label = "phraseInk")
    val pulse = rememberInfiniteTransition(label = "turn").animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "turnPulse",
    )
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // A passage of several verses is longer than the screen: the line the voice is on, or
    // is waiting for, is kept in sight.
    // With room either side of it, so the line coming next is seen too and this one does
    // not sit under the fading edge.
    val bring = remember { BringIntoViewRequester() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val margin = with(LocalDensity.current) { (40 * scale).dp.toPx() }
    LaunchedEffect(lead) {
        if (lead) bring.bringIntoView(Rect(0f, -margin, size.width.toFloat(), size.height + margin))
    }

    // Each word: where it starts, its first letter, and one past its last letter. What is
    // hidden lies between; the comma after it is not part of the word.
    val words = remember(text) {
        buildList {
            var from = 0
            text.split(' ').forEach { w ->
                val first = w.indexOfFirst { it.isLetterOrDigit() }
                val last = w.indexOfLast { it.isLetterOrDigit() }
                if (first >= 0) add(Triple(from, from + first, from + last + 1))
                from += w.length + 1
            }
        }
    }
    val drawn = buildAnnotatedString {
        var done = 0
        fun plain(to: Int, colour: Color) {
            if (to > done) withStyle(SpanStyle(color = colour)) { append(text.substring(done, to)) }
            done = maxOf(done, to)
        }
        // Punctuation stays, faintly, with the first letters: it is the rhythm of the line.
        val marks = ink.copy(alpha = if (mode == ByHeart.BLANK) shown else maxOf(shown, 0.5f))
        words.forEachIndexed { k, (_, first, end) ->
            plain(first, marks)
            val kept = if (mode == ByHeart.BLANK) first else first + 1
            plain(kept, ink)
            plain(end, ink.copy(alpha = maxOf(shown, inked(k))))
        }
        plain(text.length, marks)
    }
    val line = p.inkSoft.copy(alpha = 0.5f * (1f - shown))

    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).bringIntoViewRequester(bring)
            .onSizeChanged { size = it }
            .then(
                if (onTap != null) Modifier.clickable(
                    indication = null, interactionSource = remember { MutableInteractionSource() },
                ) { onTap() } else Modifier
            ),
    ) {
        // Whose turn it is: steady gold while the voice has this line, breathing while it
        // is waiting for the listener to say it.
        Box(
            Modifier.padding(vertical = 7.dp).width(3.dp).fillMaxHeight()
                .background(
                    when {
                        waiting -> p.gold.copy(alpha = pulse.value)
                        sounding -> p.gold
                        else -> Color.Transparent
                    },
                    RoundedCornerShape(2.dp),
                ),
        )
        Spacer(Modifier.width(13.dp))
        Text(
            drawn,
            fontFamily = ReadingFont, fontWeight = FontWeight.Normal,
            fontSize = (21 * scale).sp, lineHeight = (31 * scale).sp,
            // A phrase too long for the line runs on set in, so the next phrase is still
            // seen to begin where it begins.
            style = TextStyle(textIndent = TextIndent(restLine = (20 * scale).sp)),
            onTextLayout = { layout = it },
            modifier = Modifier.padding(vertical = 5.dp).drawBehind {
                val l = layout ?: return@drawBehind
                if (shown >= 1f) return@drawBehind
                words.forEachIndexed { k, (_, first, end) ->
                    val from = if (mode == ByHeart.BLANK) first else first + 1
                    if (from >= end || end > l.layoutInput.text.length) return@forEachIndexed
                    // The rule under a word goes as the word comes.
                    val left = 1f - inked(k)
                    if (left <= 0f) return@forEachIndexed
                    val a = l.getBoundingBox(from)
                    val b = l.getBoundingBox(end - 1)
                    val y = l.getLineBaseline(l.getLineForOffset(from)) + 3.dp.toPx()
                    drawLine(
                        line.copy(alpha = line.alpha * left), Offset(a.left + 1f, y), Offset(b.right - 1f, y),
                        1.5.dp.toPx(), StrokeCap.Round,
                    )
                }
            },
        )
    }
}

/**
 * One verse of a round: learned if it is new, said if it is not, and then owned up to.
 *
 * Nobody is marked. The reader says the verse aloud, looks, and says whether they had it,
 * and is believed. The voice is offered and never starts by itself: it may be a quiet room.
 * Where it leaves the reader a turn it waits to be told to go on, however long that is: a
 * child sounding a line out is not hurried, and someone who has it is not kept waiting.
 * With hearing switched on the telling can be the line itself: [ears] listens for it during
 * the turn and the voice goes on when it has been said. The button stays, for the line the
 * recogniser will not hear.
 *
 * And with hearing on, a verse said from memory is listened to as it is said. The words
 * come up on the page as they are heard, and a line said is shown whole. Every line said
 * with none of them looked at first is the verse known, and the card says so itself. That
 * is the one thing hearing ever marks. It never marks a verse missed: what it did not
 * hear stays covered for the reader to see, and "Not yet" is still theirs to say, because
 * a recogniser that misses a line is not a child who did not know it.
 *
 * [slot] is the verse's place in whatever the voice is reading, so it can tell its own
 * phrases from another verse's. [said] is the answer given, or null while it is open.
 */
@Composable
fun HeartCard(
    item: GoOver.Item.Verse,
    slot: Int,
    narrator: Narrator,
    ears: Ears,
    prefs: Prefs,
    said: Int?,
    /** "tomorrow", "in 3 days": when it is next due if it was had. */
    back: String,
    onSaid: (Int) -> Unit,
) {
    val p = LocalPalette.current
    var trying by remember(item.heart.verse) { mutableStateOf(item.level != ByHeart.NEW) }
    var seen by remember(item.heart.verse) { mutableStateOf(emptySet<Int>()) }
    val all = remember(item.phrases) { item.phrases.indices.toSet() }
    // How much shows while it is being said: a verse only just heard still has its letters.
    val level = if (!trying) ByHeart.NEW else maxOf(item.level, ByHeart.LETTERS)

    val mine = narrator.alone && narrator.speaking
    val at = if (mine) (narrator.verseIndex - ByHeart.base(slot)).takeIf { it in 0 until ByHeart.NAME } ?: -1 else -1
    // A breath between two things the voice says is against no phrase, and nobody's turn.
    val hush = mine && narrator.hushed && at >= 0
    // The turn is the reader's to end. Not so with the page put away, where it is timed.
    val held = hush && narrator.held
    // What the voice says stays said: a phrase it has spoken is uncovered and stays so.
    LaunchedEffect(at, hush) {
        if (hush || at < 0) return@LaunchedEffect
        seen = if (at == ByHeart.WHOLE) all else seen + at
    }
    DisposableEffect(item.heart.verse) { onDispose { if (narrator.alone) narrator.stop() } }

    // The turn can be heard as well as tapped away, where that has been asked for. The
    // line being waited for is known, so all that is asked of the recogniser is whether
    // that was it. Hearing ends the turn and nothing more: it marks nobody.
    val hear = rememberHearSwitch(prefs)
    LaunchedEffect(hear.on) { if (hear.on) ears.load() }
    val want = when {
        !held || said != null -> null
        at == ByHeart.WHOLE -> item.phrases.joinToString(" ")
        else -> item.phrases.getOrNull(at)
    }
    // Something was said that was not the line, and it has gone quiet since.
    var tries by remember { mutableIntStateOf(0) }
    var again by remember { mutableStateOf(false) }
    // The page is not drawn again while the app is put away, so nothing above would let
    // the microphone go. It is closed here, and the turn is asked for again on the way back.
    var shown by remember { mutableStateOf(true) }
    var visits by remember { mutableIntStateOf(0) }
    // The turn this card opened the microphone for. Another card may have opened it since,
    // while this one was sliding away, and that turn is not this card's to close.
    val mic = remember { intArrayOf(0) }
    LifecycleStartEffect(ears) {
        if (!shown) { shown = true; visits++ }
        onStopOrDispose { shown = false; ears.stop(mic[0]) }
    }
    // Said from memory: the lines heard said, the words heard of each, and the lines that
    // were said while they were still covered.
    var spoke by remember(item.heart.verse) { mutableStateOf(emptySet<Int>()) }
    var filled by remember(item.heart.verse) { mutableStateOf(emptyMap<Int, Set<Int>>()) }
    var clean by remember(item.heart.verse) { mutableStateOf(emptySet<Int>()) }
    var byEar by remember(item.heart.verse) { mutableStateOf(false) }
    DisposableEffect(want, hear.on, ears.loaded, shown, visits) {
        if (want == null || !shown || !hear.on || !ears.loaded) return@DisposableEffect onDispose { }
        tries = 0; again = false
        val line = Heard.words(want)
        val got = ArrayList<String>()
        val turn = at
        val opened = ears.listen { text ->
            got += Heard.words(text)
            // Said in two breaths it comes in two parts. Said five times, only the last
            // few goes are worth weighing.
            while (got.size > line.size * 3 + 12) got.removeAt(0)
            if (Heard.said(line, got)) {
                seen = if (turn == ByHeart.WHOLE) all else seen + turn
                narrator.goOn()
            } else tries++
        }
        mic[0] = opened
        onDispose { ears.stop(opened) }
    }

    // Said from memory with nobody leading, the whole verse is listened for at once. What
    // has been heard is laid against all of it each time more comes, and the page fills in.
    val passage = remember(item.phrases) { Heard.Passage(item.phrases) }
    val most = remember(item.phrases) { item.phrases.sumOf { Heard.words(it).size } * 3 + 12 }
    val covered = all.any { it !in seen && it !in spoke }
    val open = trying && !mine && said == null && covered && shown && hear.on && ears.loaded
    DisposableEffect(open, visits) {
        if (!open) return@DisposableEffect onDispose { }
        tries = 0; again = false
        // The stretches that have ended. One still being said is weighed with them and
        // then let go, for the next telling of it is the whole of it again.
        val done = ArrayList<String>()
        fun weigh(got: List<String>, ended: Boolean) {
            val f = passage.follow(got)
            val fresh = f.said - spoke
            // What is heard stays heard: a later guess at the same stretch takes nothing back.
            filled = all.associateWith { (filled[it] ?: emptySet()) + f.words[it] }
            if (fresh.isNotEmpty()) {
                // A line looked at and then said has been read, not remembered.
                clean = clean + fresh.filter { it !in seen }
                spoke = spoke + fresh
            } else if (ended) tries++
        }
        val opened = ears.listen(
            onPartial = { text -> weigh(done + Heard.words(text), ended = false) },
            onHeard = { text ->
                done += Heard.words(text)
                while (done.size > most) done.removeAt(0)
                weigh(done, ended = true)
            },
        )
        mic[0] = opened
        onDispose { ears.stop(opened) }
    }
    // Every line said and none looked at: that is the verse known, and nobody has to say so.
    // After a moment, so the last line is seen to arrive.
    LaunchedEffect(clean, said, trying) {
        if (said != null || !trying || clean.size < all.size) return@LaunchedEffect
        kotlinx.coroutines.delay(900)
        byEar = true
        onSaid(Quiz.RIGHT)
    }
    LaunchedEffect(tries) {
        if (tries == 0) return@LaunchedEffect
        // Not at once: a line said in two breaths is not a line got wrong.
        kotlinx.coroutines.delay(1300)
        if (!ears.voiced) again = true
    }
    LaunchedEffect(ears.voiced) { if (ears.voiced) again = false }
    val heard = held && ears.listening
    val following = open && ears.listening
    // Listened to the end, but a line was looked at on the way: the reader says how it went.
    val helped = trying && !mine && !covered && spoke.isNotEmpty() && clean.size < all.size

    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 22.dp)) {
        Text(
            when {
                said == Quiz.RIGHT -> "You have it"
                said != null -> "Not yet"
                !trying -> "Learn it"
                else -> "Say it"
            }.uppercase(),
            fontFamily = UiFont, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.1.sp, color = p.gold,
            modifier = Modifier.padding(start = 2.dp, bottom = 9.dp),
        )
        VersePanel(
            phrases = item.phrases,
            level = if (said != null) ByHeart.NEW else level,
            seen = seen + spoke, at = at, hush = hush,
            heard = if (said == null && trying) filled else emptyMap(),
            next = if (following) all.firstOrNull { it !in seen && it !in spoke } ?: -1 else -1,
            onTap = if (said == null) { i -> seen = seen + i } else null,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Top) {
        Cue(
            when {
                said == Quiz.RIGHT && byEar -> "Heard, all of it. It comes back $back."
                said == Quiz.RIGHT -> "It comes back $back."
                said != null -> "It will come back in a few minutes."
                heard && again -> "Say it once more, or go on."
                heard && !trying -> "Your turn. Say it back."
                heard -> "Your turn. Say it."
                held && !trying -> "Your turn. Say it back, then go on."
                held -> "Your turn. Say it, then hear it."
                hush && !trying -> "Your turn. Say it back."
                hush -> "Your turn. Say it."
                mine -> "Listen."
                !trying -> "Read it through aloud, or listen and say each line after the voice."
                // The moment between the last line arriving and its being marked.
                !covered && clean.size >= all.size -> "Heard, all of it."
                following && all.last() in spoke -> "Not all of it was heard. Say what is still covered, or tap it."
                following && again -> "Go on from where it stops, or tap a line to see it."
                helped -> "Said, with a look at the page. Did you have it?"
                following && level == ByHeart.BLANK -> "Say it from memory. It fills in as you are heard."
                following -> "Say it aloud. It fills in as you are heard, and the first letters are there to lean on."
                level == ByHeart.BLANK -> "Say it aloud from memory. Tap a line if you are stuck."
                else -> "Say it aloud. The first letters are there to lean on. Tap a line to see it."
            },
            yours = (hush || following) && said == null,
            modifier = Modifier.weight(1f),
        )
        // Where the eye already is when it is your turn. Where there is something to hear:
        // a turn taken with the voice, or the verse said from memory.
        if (said == null && (trying || narrator.modelReady())) HearMark(
            on = hear.on, listening = heard || following, voiced = ears.voiced,
            modifier = Modifier.offset(x = 9.dp, y = (-10).dp),
        ) { hear.set(!hear.on) }
        }
        if (said == null) {
            Spacer(Modifier.height(20.dp))
            // As tall as its tallest button whatever it holds, so nothing moves as they change.
            Row(
                Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(start = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (narrator.modelReady()) {
                    Listen(playing = mine, preparing = mine && narrator.preparing) {
                        if (mine) narrator.stop()
                        else {
                            seen = emptySet(); spoke = emptySet(); filled = emptyMap(); clean = emptySet()
                            narrator.speak(
                                ByHeart.script(slot, item.spoken, item.phrases, level, tap = true), alone = true,
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                // While the voice has the verse there is one thing to press, and it is not
                // under the thumb that has just pressed "Go on".
                if (held) Go(if (!trying) "Go on" else "Hear it") { narrator.goOn() }
                else if (mine) Unit
                else if (!trying) Go("Now say it") {
                    if (narrator.alone) narrator.stop()
                    seen = emptySet(); spoke = emptySet(); filled = emptyMap(); clean = emptySet()
                    trying = true
                } else {
                    Text(
                        "Not yet", fontFamily = UiFont, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        color = p.inkSoft,
                        modifier = Modifier.clickable {
                            if (narrator.alone) narrator.stop()
                            onSaid(Quiz.WRONG)
                        }.padding(horizontal = 16.dp, vertical = 11.dp),
                    )
                    Go("I had it") {
                        if (narrator.alone) narrator.stop()
                        onSaid(Quiz.RIGHT)
                    }
                }
            }
        }
    }
}

/** The line under a verse that says what to do. It turns gold when it is the listener's turn. */
@Composable
fun Cue(text: String, yours: Boolean, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val colour by animateColorAsState(if (yours) p.gold else p.inkSoft, tween(220), label = "cue")
    Text(
        text, fontFamily = UiFont, fontSize = 13.sp, lineHeight = 19.sp, color = colour,
        fontWeight = if (yours) FontWeight.SemiBold else FontWeight.Normal,
        // Two lines always, so the buttons under it do not move when the words change.
        minLines = 2,
        modifier = modifier.padding(horizontal = 2.dp),
    )
}

/** The voice, offered: the reader's own play button, smaller, with its word beside it. */
@Composable
fun Listen(playing: Boolean, preparing: Boolean = false, label: String = "Listen", onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.clickable(
            indication = null, interactionSource = remember { MutableInteractionSource() },
        ) { onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = p.gold, modifier = Modifier.size(42.dp)) {
            Text(
                if (playing) "❚❚" else "▶",
                color = p.paper, fontSize = if (playing) 12.sp else 15.sp,
                textAlign = TextAlign.Center, modifier = Modifier.wrapContentHeight(),
            )
        }
        Spacer(Modifier.width(11.dp))
        Text(
            when { preparing -> "One moment"; playing -> "Stop"; else -> label },
            fontFamily = UiFont, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = p.ink,
        )
    }
}
