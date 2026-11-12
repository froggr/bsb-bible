package ca.lampandlight

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

/**
 * A verse set out to be learned: a phrase to a line, the way a poem is set, because a line
 * is what the ear holds.
 *
 * [level] is how much of a phrase shows until it has been [seen]: all of it, the first
 * letter of each word, or only where the words go. A hidden word keeps its place and its
 * width, so the verse does not shift as it is uncovered. [at] is the phrase the voice is on,
 * [ByHeart.WHOLE] when it is on all of them, or -1 when it is silent, and [hush] is true
 * while it is leaving room for the listener rather than speaking.
 */
@Composable
fun VersePanel(
    phrases: List<String>,
    level: Int,
    seen: Set<Int>,
    at: Int,
    hush: Boolean,
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
                    Phrase(
                        text = phrase,
                        mode = if (i in seen) ByHeart.NEW else level,
                        waiting = hush && on,
                        sounding = !hush && on,
                        // The voice is busy elsewhere in the verse: this line stands back.
                        back = at >= 0 && !on,
                        lead = at == i,
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
    text: String, mode: Int, waiting: Boolean, sounding: Boolean, back: Boolean, lead: Boolean,
    onTap: (() -> Unit)?,
) {
    val p = LocalPalette.current
    val scale = LocalTextScale.current
    val shown by animateFloatAsState(if (mode == ByHeart.NEW) 1f else 0f, tween(420), label = "shown")
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
        words.forEach { (_, first, end) ->
            plain(first, marks)
            val kept = if (mode == ByHeart.BLANK) first else first + 1
            plain(kept, ink)
            plain(end, ink.copy(alpha = shown))
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
                words.forEach { (_, first, end) ->
                    val from = if (mode == ByHeart.BLANK) first else first + 1
                    if (from >= end || end > l.layoutInput.text.length) return@forEach
                    val a = l.getBoundingBox(from)
                    val b = l.getBoundingBox(end - 1)
                    val y = l.getLineBaseline(l.getLineForOffset(from)) + 3.dp.toPx()
                    drawLine(line, Offset(a.left + 1f, y), Offset(b.right - 1f, y), 1.5.dp.toPx(), StrokeCap.Round)
                }
            },
        )
    }
}

/**
 * One verse of a round: learned if it is new, said if it is not, and then owned up to.
 *
 * Nothing listens. The reader says the verse aloud, looks, and says whether they had it,
 * and is believed. The voice is offered and never starts by itself: it may be a quiet room.
 *
 * [slot] is the verse's place in whatever the voice is reading, so it can tell its own
 * phrases from another verse's. [said] is the answer given, or null while it is open.
 */
@Composable
fun HeartCard(
    item: GoOver.Item.Verse,
    slot: Int,
    narrator: Narrator,
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
    val hush = mine && narrator.hushed
    // What the voice says stays said: a phrase it has spoken is uncovered and stays so.
    LaunchedEffect(at, hush) {
        if (hush || at < 0) return@LaunchedEffect
        seen = if (at == ByHeart.WHOLE) all else seen + at
    }
    DisposableEffect(item.heart.verse) { onDispose { if (narrator.alone) narrator.stop() } }

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
            seen = seen, at = at, hush = hush,
            onTap = if (said == null) { i -> seen = seen + i } else null,
        )
        Spacer(Modifier.height(12.dp))
        Cue(
            when {
                said == Quiz.RIGHT -> "It comes back $back."
                said != null -> "It will come back in a few minutes."
                hush && !trying -> "Your turn. Say it back."
                hush -> "Your turn. Say it."
                mine -> "Listen."
                !trying -> "Read it through aloud, or listen and say each line after the voice."
                level == ByHeart.BLANK -> "Say it aloud from memory. Tap a line if you are stuck."
                else -> "Say it aloud. The first letters are there to lean on. Tap a line to see it."
            },
            yours = hush && said == null,
        )
        if (said == null) {
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth().padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (narrator.modelReady()) {
                    Listen(playing = mine, preparing = mine && narrator.preparing) {
                        if (mine) narrator.stop()
                        else {
                            seen = emptySet()
                            narrator.speak(ByHeart.script(slot, item.spoken, item.phrases, level), alone = true)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (!trying) Go("Now say it") {
                    if (narrator.alone) narrator.stop()
                    seen = emptySet(); trying = true
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
fun Cue(text: String, yours: Boolean) {
    val p = LocalPalette.current
    val colour by animateColorAsState(if (yours) p.gold else p.inkSoft, tween(220), label = "cue")
    Text(
        text, fontFamily = UiFont, fontSize = 13.sp, lineHeight = 19.sp, color = colour,
        fontWeight = if (yours) FontWeight.SemiBold else FontWeight.Normal,
        // Two lines always, so the buttons under it do not move when the words change.
        minLines = 2,
        modifier = Modifier.padding(horizontal = 2.dp),
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
