package ca.lampandlight

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.lampandlight.Epub.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * A book on the page.
 *
 * It is the Bible's page with a book on it: the same paper and type, the same bars that
 * step aside while the reader is moving and come back when they stop, the same player and
 * the same voice. What a book adds is its own furniture — chapter heads, points, quotations
 * with the reference under them — and every Scripture reference is a way into the passage:
 * a tap shows it over the book, so the place in the book is never lost to look one up.
 */
@Composable
fun BookReader(
    session: BookSession,
    bible: Bible,
    narrator: Narrator,
    bed: Bed,
    prefs: Prefs,
    onShelf: () -> Unit,
    onBible: () -> Unit,
    /** Leave for the passage itself; the second verse is the end of a range. */
    onOpenBible: (VerseId, VerseId?) -> Unit,
) {
    val p = LocalPalette.current
    // Opened part-way down a chapter, the list starts there: scrolling to the place after
    // the first frame would show the chapter's head for an instant first.
    val list = rememberLazyListState(initialFirstVisibleItemIndex = session.pending.coerceAtLeast(0))
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val ctx = LocalContext.current
    var chromeVisible by remember { mutableStateOf(true) }
    var topBarPx by remember { mutableIntStateOf(0) }
    var playerPx by remember { mutableIntStateOf(0) }
    val topBarHeight = with(density) { topBarPx.toDp() }.coerceAtLeast(64.dp)
    val playerHeight = with(density) { playerPx.toDp() }.coerceAtLeast(96.dp)

    var peek by remember { mutableStateOf<Citations.Passage?>(null) }
    var acting by remember { mutableStateOf<Int?>(null) }
    var contents by remember { mutableStateOf(false) }
    var landed by remember { mutableStateOf(false) }

    val pieces = session.pieces
    val speaking = narrator.speaking
    val sounding = narrator.verseIndex

    // The bars behave exactly as they do over the Bible; see Reader.
    val dragging by remember { derivedStateOf { list.isScrollInProgress } }
    LaunchedEffect(dragging, speaking) { if (dragging && !speaking) chromeVisible = false }
    LaunchedEffect(list) {
        var last = 0
        snapshotFlow { list.firstVisibleItemIndex * 10_000 + list.firstVisibleItemScrollOffset }
            .collect { now ->
                if (now < last - 24) chromeVisible = true
                last = now
            }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { moving -> if (!moving) chromeVisible = true }
    }
    LaunchedEffect(speaking) { if (speaking) chromeVisible = true }

    // The block the reader is on: the first one with something of it still below the bar.
    // The list's own "first visible" counts a paragraph with one line left under the bar.
    val topBlock by remember(session) {
        derivedStateOf {
            val i = list.layoutInfo.visibleItemsInfo.firstOrNull { it.offset + it.size > 48 }?.index
                ?: list.firstVisibleItemIndex
            i.coerceIn(0, (session.pieces.size - 1).coerceAtLeast(0))
        }
    }
    // The foot of the last chapter is the end of the book, wherever the top of the screen is.
    val ended = landed && !session.hasNext && !list.canScrollForward
    val progress = if (ended) 1f else session.progressAt(topBlock)

    // Land where the session says: the saved place on opening, the top on turning a chapter.
    LaunchedEffect(session) {
        snapshotFlow { session.index to session.pending }.collect { (_, at) ->
            if (at >= 0) {
                session.consumePending()
                list.scrollToItem(at.coerceAtMost((session.pieces.size - 1).coerceAtLeast(0)), 0)
            }
            landed = true
        }
    }

    // Keep the place as the page moves — once it has come to rest for a moment, so a fling
    // through a chapter is one write and not forty.
    fun keepPlace() {
        if (!landed || session.loading || session.pieces.isEmpty()) return
        session.save(if (!session.hasNext && !list.canScrollForward) session.pieces.lastIndex else topBlock)
    }
    LaunchedEffect(session) {
        snapshotFlow { Triple(session.index, topBlock, list.canScrollForward) }.collectLatest {
            delay(350)
            keepPlace()
        }
    }
    DisposableEffect(session) { onDispose { keepPlace() } }

    /** Turn to another chapter. A reading under way carries on there, as it would on its own. */
    fun turn(to: Int) {
        if (to !in session.sections.indices || session.loading) return
        val was = narrator.speaking
        narrator.reset()
        scope.launch {
            session.go(to)
            if (was) narrator.speak(session.lines(), 0)
        }
    }

    // Where the page sits in the window, to compare with what a block reports about itself.
    var pageTop by remember { mutableFloatStateOf(0f) }
    var pageHeight by remember { mutableIntStateOf(0) }

    // A paragraph in a book can run past the foot of the screen, so the page follows the
    // clause being read and not the block: when the lit words leave the clear part of the
    // page, they are brought back up near the top of it.
    val follow: (Float, Float) -> Unit = { top, bottom ->
        val lo = pageTop + topBarPx + with(density) { 18.dp.toPx() }
        val hi = pageTop + pageHeight - playerPx - with(density) { 30.dp.toPx() }
        if (hi > lo && (bottom > hi || top < lo)) {
            scope.launch { list.animateScrollBy(top - (lo + (hi - lo) * 0.16f)) }
        }
    }
    // And when the block being read is not on the screen at all, go and find it.
    LaunchedEffect(sounding, session.index) {
        if (sounding < 0 || sounding >= session.pieces.size) return@LaunchedEffect
        if (list.layoutInfo.visibleItemsInfo.none { it.index == sounding }) {
            list.animateScrollToItem(sounding, -200)
        }
    }

    Box(
        Modifier.fillMaxSize().background(p.paper)
            .onGloballyPositioned { pageTop = it.positionInWindow().y; pageHeight = it.size.height },
    ) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 13.dp, end = 13.dp,
                top = topBarHeight + 14.dp, bottom = playerHeight + 22.dp,
            ),
        ) {
            itemsIndexed(pieces, key = { i, _ -> "${session.index}:$i" }) { i, piece ->
                PieceBlock(
                    piece = piece,
                    first = i == 0,
                    afterLabel = pieces.getOrNull(i - 1)?.kind == Kind.Label,
                    lit = if (sounding == i) narrator.wordSpan else null,
                    reading = sounding == i,
                    session = session,
                    onCite = { chromeVisible = true; peek = it },
                    onReadFrom = { chromeVisible = true; narrator.speak(session.lines(), i) },
                    onLongPress = { acting = i },
                    onFollow = follow,
                )
            }
            if (pieces.isEmpty() && !session.loading) item("empty:${session.index}") {
                Text(
                    "There is nothing to read on this page of the book.",
                    fontFamily = UiFont, fontSize = 13.sp, color = p.inkSoft, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 60.dp, horizontal = 24.dp),
                )
            }
            item("foot:${session.index}") {
                ChapterFoot(
                    next = session.sections.getOrNull(session.index + 1),
                    onNext = { turn(session.index + 1) },
                    onShelf = onShelf,
                )
            }
        }

        val here = session.section
        val barTitle = here?.title?.replace('\n', ' ')?.ifBlank { null } ?: here?.label ?: session.title
        val barDetail = when {
            ended -> "Finished"
            else -> "${(progress * 100).toInt()}% · ${readingTime(session.wordsLeft(topBlock).second, narrator.rate)} left"
        }
        // An invisible copy gives the bar's resting height; see Reader for why the visible
        // one cannot be measured.
        Box(
            Modifier.align(Alignment.TopCenter)
                .onSizeChanged { topBarPx = it.height }
                .drawWithContent { }
                .pointerInput(Unit) { },
        ) {
            BookBar(barTitle, barDetail, progress, false, false, {}, {}, {}, {}, {})
        }
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            BookBar(
                title = barTitle, detail = barDetail, progress = progress,
                hasPrev = session.hasPrev, hasNext = session.hasNext,
                onPrev = { turn(session.index - 1) },
                onNext = { turn(session.index + 1) },
                onContents = { contents = true },
                onShelf = onShelf,
                onBible = onBible,
            )
        }

        AnimatedVisibility(
            visible = chromeVisible || speaking,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
                .onSizeChanged { if (it.height > playerPx) playerPx = it.height },
        ) {
            PlayerBar(
                narrator, bed, prefs,
                reading = readingTime(
                    session.wordsLeft(if (sounding >= 0) sounding else topBlock).first, narrator.rate,
                ) + " left in this chapter",
                resume = "Continue reading",
                onPlay = {
                    // Carry on from where the voice stopped if that is still on the screen;
                    // if the reader has moved on since, they mean the page they are on.
                    val shown = list.layoutInfo.visibleItemsInfo.map { it.index }
                    val from = narrator.resumeAt.takeIf { it > 0 && it in shown } ?: topBlock
                    narrator.speak(session.lines(), from)
                },
            )
        }
    }

    peek?.let { passage ->
        PassageSheet(
            passage = passage,
            bible = bible,
            onOpen = {
                peek = null
                val r = passage.ranges.first()
                val to = r.last.takeIf {
                    !passage.wholeChapter && it > r.first && it.book == r.first.book && it.chapter == r.first.chapter
                }
                onOpenBible(r.first, to)
            },
            onDismiss = { peek = null },
        )
    }

    acting?.let { i ->
        val piece = pieces.getOrNull(i)
        if (piece == null) acting = null
        else BlockSheet(
            text = piece.block.text,
            onRead = { acting = null; narrator.speak(session.lines(), i) },
            onCopy = {
                val from = if (session.author.isBlank()) session.title else "${session.title}, ${session.author}"
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(
                    android.content.ClipData.newPlainText(session.title, "${piece.block.text}\n\n— $from")
                )
                acting = null
            },
            onDismiss = { acting = null },
        )
    }

    if (contents) ContentsSheet(
        session = session,
        progress = progress,
        left = if (ended) 0 else session.wordsLeft(topBlock).second,
        rate = narrator.rate,
        onGo = { i -> contents = false; if (i != session.index) turn(i) else scope.launch { list.animateScrollToItem(0) } },
        onDismiss = { contents = false },
    )
}

/** How long [words] take to hear, said the way a person would: "12 min", "2 h 40 min". */
internal fun readingTime(words: Int, rate: Float = 1f): String {
    val m = (words / (165f * rate.coerceIn(0.5f, 2.5f))).roundToInt()
    return when {
        m < 1 -> "under a minute"
        m < 60 -> "$m min"
        m % 60 == 0 -> "${m / 60} h"
        else -> "${m / 60} h ${m % 60} min"
    }
}

/**
 * The bar over a book: the chapter and the ways to another, as over the Bible, and under it
 * the ways out. The rule at its foot is also how far through the book this is.
 */
@Composable
private fun BookBar(
    title: String,
    detail: String,
    progress: Float,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onContents: () -> Unit,
    onShelf: () -> Unit,
    onBible: () -> Unit,
) {
    val p = LocalPalette.current
    Surface(color = p.paper, shadowElevation = 0.dp) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.alpha(if (hasPrev) 1f else 0.3f)) { Chip("‹") { if (hasPrev) onPrev() } }
                Spacer(Modifier.width(7.dp))
                Surface(
                    onClick = onContents,
                    color = Color.Transparent,
                    shape = RoundedCornerShape(9.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, p.rule),
                    modifier = Modifier.weight(1f),
                ) {
                    Box(Modifier.heightIn(min = 40.dp).padding(horizontal = 12.dp), Alignment.Center) {
                        Text(
                            title,
                            fontFamily = DisplayFont, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                            color = p.ink, textAlign = TextAlign.Center,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(7.dp))
                Box(Modifier.alpha(if (hasNext) 1f else 0.3f)) { Chip("›") { if (hasNext) onNext() } }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextAction("‹ Books", onShelf)
                Spacer(Modifier.width(16.dp))
                TextAction("Contents", onContents)
                Spacer(Modifier.width(16.dp))
                TextAction("Bible", onBible)
                Spacer(Modifier.weight(1f))
                Text(
                    detail,
                    fontFamily = UiFont, fontSize = 11.5.sp, letterSpacing = 0.3.sp, color = p.inkSoft,
                    maxLines = 1,
                )
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(p.rule)) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(p.gold))
            }
        }
    }
}

/** How one kind of block is set. */
private class Look(
    val family: FontFamily,
    val weight: FontWeight,
    val size: TextUnit,
    val line: TextUnit,
    val colour: Color,
    val align: TextAlign,
    val top: Dp,
    val bottom: Dp,
    /** What bold is, inside this: a step up from [weight], never a jump. */
    val bold: FontWeight,
    val tracking: TextUnit = 0.sp,
)

@Composable
private fun PieceBlock(
    piece: BookSession.Piece,
    first: Boolean,
    afterLabel: Boolean,
    /** The words being said, when this is the block being read. Empty for its number. */
    lit: IntRange?,
    reading: Boolean,
    session: BookSession,
    onCite: (Citations.Passage) -> Unit,
    onReadFrom: () -> Unit,
    onLongPress: () -> Unit,
    onFollow: (Float, Float) -> Unit,
) {
    val p = LocalPalette.current
    val scale = LocalTextScale.current
    val haptic = LocalHapticFeedback.current
    val block = piece.block

    when (piece.kind) {
        Kind.Break -> {
            Text(
                "·   ·   ·",
                fontFamily = DisplayFont, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = p.gold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 16.dp),
            )
            return
        }
        Kind.Image -> { Picture(block.src, session); return }
        else -> {}
    }
    if (block.text.isEmpty()) return

    val given = when (block.align) {
        Epub.Align.Center -> TextAlign.Center
        Epub.Align.End -> TextAlign.End
        Epub.Align.Start -> TextAlign.Start
    }
    val look = when (piece.kind) {
        Kind.Label -> Look(
            UiFont, FontWeight.Medium, 11.5.sp, 16.sp, p.gold, TextAlign.Center,
            top = if (first) 12.dp else 28.dp, bottom = 0.dp, bold = FontWeight.Medium, tracking = 2.4.sp,
        )
        Kind.Title -> Look(
            DisplayFont, FontWeight.SemiBold, 31.sp * scale, 36.sp * scale, p.ink, TextAlign.Center,
            top = if (afterLabel) 0.dp else if (first) 12.dp else 28.dp, bottom = 12.dp, bold = FontWeight.Bold,
        )
        Kind.Heading -> {
            val size = when { block.level <= 2 -> 25; block.level <= 4 -> 22; else -> 20 }
            Look(
                DisplayFont, FontWeight.SemiBold, size.sp * scale, (size + 5).sp * scale, p.ink, given,
                top = if (first) 4.dp else 14.dp, bottom = 0.dp, bold = FontWeight.Bold,
            )
        }
        Kind.Lead -> Look(
            ReadingFont, FontWeight.Medium, 19.sp * scale, 28.sp * scale, p.ink, given,
            top = 0.dp, bottom = 9.dp, bold = FontWeight.Medium,
        )
        Kind.Point -> Look(
            ReadingFont, if (block.level == 1) FontWeight.Medium else FontWeight.Light,
            19.sp * scale, 28.sp * scale, p.ink, TextAlign.Start,
            top = if (block.level == 1 && !first) 6.dp else 0.dp, bottom = 9.dp, bold = FontWeight.Medium,
        )
        Kind.Quote -> Look(
            ReadingFont, FontWeight.Light, 18.sp * scale, 27.sp * scale, p.ink, TextAlign.Start,
            top = 0.dp, bottom = if (piece.joinsNext) 0.dp else 12.dp, bold = FontWeight.Medium,
        )
        Kind.Cite -> Look(
            UiFont, FontWeight.Medium, 12.5.sp * scale, 18.sp * scale, p.inkSoft, TextAlign.End,
            top = 0.dp, bottom = 14.dp, bold = FontWeight.Medium, tracking = 0.3.sp,
        )
        else -> Look(
            ReadingFont, FontWeight.Light, 19.sp * scale, 28.sp * scale, p.ink, given,
            top = 0.dp, bottom = 9.dp, bold = FontWeight.Medium,
        )
    }

    // A label is set in capitals, which is the same letters: the words keep their places.
    val text = if (piece.kind == Kind.Label) block.text.uppercase().takeIf { it.length == block.text.length } ?: block.text
        else block.text
    val litColour = p.gold.copy(alpha = if (p.dark) 0.22f else 0.17f)
    val body = remember(piece, lit, p, look.bold) {
        buildAnnotatedString {
            append(text)
            // The whole block is already as heavy as its kind makes it; bold inside a
            // paragraph is a word leant on, and in a heading it is nothing new.
            for (r in block.bold) addStyle(SpanStyle(fontWeight = look.bold), r.first, (r.last + 1).coerceAtMost(text.length))
            for (r in block.italic) addStyle(SpanStyle(fontStyle = FontStyle.Italic), r.first, (r.last + 1).coerceAtMost(text.length))
            piece.passages.forEachIndexed { k, ps ->
                val end = ps.end.coerceAtMost(text.length)
                if (ps.start < end) {
                    // Coloured and tagged, never a LinkAnnotation: see Citations.
                    addStyle(SpanStyle(color = p.indigo), ps.start, end)
                    addStringAnnotation("cite", k.toString(), ps.start, end)
                }
            }
            if (lit != null && !lit.isEmpty()) {
                val a = piece.spans.getOrNull(lit.first)?.first
                val b = piece.spans.getOrNull(lit.last)?.last
                if (a != null && b != null && b >= a) addStyle(SpanStyle(background = litColour), a, b + 1)
            }
        }
    }

    val glow by animateFloatAsState(if (reading) 1f else 0f, label = "reading")
    // A quotation's rule runs unbroken down every paragraph of it and the reference below.
    val barred = piece.kind == Kind.Quote || (piece.kind == Kind.Cite && piece.joinsPrev)
    val barColour = p.gold.copy(alpha = if (p.dark) 0.7f else 0.75f)

    // Kept outside the snapshot system on purpose: they are read when a clause changes and
    // must not themselves cause the block to be drawn again.
    val layout = remember(piece) { arrayOfNulls<TextLayoutResult>(1) }
    val place = remember(piece) { arrayOfNulls<LayoutCoordinates>(1) }
    val lastTap = remember(piece) { longArrayOf(0L) }

    // Tell the page where the words being read are, so it can keep them in view.
    LaunchedEffect(lit) {
        if (lit == null) return@LaunchedEffect
        withFrameNanos { }
        val l = layout[0] ?: return@LaunchedEffect
        val c = place[0]?.takeIf { it.isAttached } ?: return@LaunchedEffect
        val a = (if (lit.isEmpty()) 0 else piece.spans.getOrNull(lit.first)?.first ?: 0).coerceIn(0, text.length)
        val b = (if (lit.isEmpty()) 0 else piece.spans.getOrNull(lit.last)?.last ?: a).coerceIn(0, text.length)
        val y = c.positionInWindow().y
        onFollow(y + l.getLineTop(l.getLineForOffset(a)), y + l.getLineBottom(l.getLineForOffset(b)))
    }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = look.top)
            .drawWithContent {
                drawContent()
                if (barred) {
                    val y0 = if (piece.joinsPrev) 0f else 6.dp.toPx()
                    val y1 = if (piece.joinsNext) size.height else size.height - look.bottom.toPx() - 6.dp.toPx()
                    if (y1 > y0) drawRect(barColour, Offset(9.dp.toPx(), y0), Size(2.dp.toPx(), y1 - y0))
                }
            }
            .padding(bottom = look.bottom)
            .background(p.sunk.copy(alpha = glow * 0.5f), RoundedCornerShape(5.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        val words = @Composable { modifier: Modifier ->
            Text(
                text = body,
                fontFamily = look.family, fontWeight = look.weight,
                fontSize = look.size, lineHeight = look.line, letterSpacing = look.tracking,
                color = look.colour, textAlign = look.align,
                onTextLayout = { layout[0] = it },
                modifier = modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { place[0] = it }
                    // One gesture loop, as on a verse: tap, double tap and long press are
                    // told apart from a single stream of events. See VerseBlock.
                    .pointerInput(piece) {
                        val reach = 14.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val press = awaitLongPressOrCancellation(down.id)
                            if (press == null) {
                                val up = currentEvent.changes.firstOrNull { it.changedToUp() }
                                    ?: return@awaitEachGesture
                                val now = System.currentTimeMillis()
                                if (now - lastTap[0] < 280) {
                                    lastTap[0] = 0L
                                    onReadFrom()
                                    return@awaitEachGesture
                                }
                                lastTap[0] = now
                                val l = layout[0] ?: return@awaitEachGesture
                                val off = l.getOffsetForPosition(up.position)
                                val hit = body.getStringAnnotations("cite", off, off).firstOrNull()
                                    ?.item?.toIntOrNull()?.let { piece.passages.getOrNull(it) }
                                when {
                                    // The nearest letter to a touch in the empty end of a
                                    // line is still a letter; only a touch ON the reference
                                    // opens it.
                                    hit != null -> {
                                        val box = l.getBoundingBox(off.coerceIn(0, (text.length - 1).coerceAtLeast(0)))
                                        if (up.position.x in (box.left - reach)..(box.right + reach) &&
                                            up.position.y in (box.top - reach)..(box.bottom + reach)) onCite(hit)
                                    }
                                    // A reference on a line of its own is the whole line.
                                    piece.kind == Kind.Cite -> piece.passages.firstOrNull()?.let(onCite)
                                }
                                return@awaitEachGesture
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress()
                            // The finger is still down: whatever it does next is not a scroll.
                            drag(press.id) { it.consume() }
                        }
                    },
            )
        }

        when (piece.kind) {
            Kind.Title -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                // "Chapter 5: The Veil" written as one line in the book: the number goes
                // over the name here too, as it does where the book set them apart.
                val over = block.marker?.takeIf { !afterLabel && it.isNotBlank() }
                if (over != null) {
                    Text(
                        over.uppercase(),
                        fontFamily = UiFont, fontSize = 11.5.sp, fontWeight = FontWeight.Medium,
                        letterSpacing = 2.4.sp, color = p.gold, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                words(Modifier)
                Spacer(Modifier.height(16.dp))
                Box(Modifier.width(34.dp).height(1.5.dp).background(p.gold.copy(alpha = 0.8f)))
                Spacer(Modifier.height(6.dp))
            }
            Kind.Point -> Row(Modifier.fillMaxWidth()) {
                val marker = block.marker.orEmpty()
                val numbered = marker.any { it.isLetterOrDigit() }
                Text(
                    if (numbered) marker else "•",
                    fontFamily = if (numbered) DisplayFont else ReadingFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (if (numbered) 20.sp else 19.sp) * scale, lineHeight = 28.sp * scale,
                    color = p.gold, maxLines = 1, softWrap = false,
                    modifier = Modifier.widthIn(min = 30.dp).padding(end = 7.dp),
                )
                words(Modifier.weight(1f))
            }
            else -> words(if (barred) Modifier.padding(start = 16.dp) else Modifier)
        }
    }
}

@Composable
private fun Picture(src: String?, session: BookSession) {
    val image by produceState<ImageBitmap?>(null, src) { value = src?.let { session.image(it) } }
    val im = image
    if (im == null) { Spacer(Modifier.height(6.dp)); return }
    Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 16.dp), Alignment.Center) {
        Image(
            bitmap = im,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            // A printer's ornament stays an ornament; only a real plate takes the width.
            modifier = Modifier
                .fillMaxWidth((im.width / 1000f).coerceIn(0.22f, 1f))
                .aspectRatio((im.width.toFloat() / im.height.coerceAtLeast(1)).coerceIn(0.3f, 4f))
                .clip(RoundedCornerShape(3.dp)),
        )
    }
}

/** The foot of a chapter: the way on, where the eye and the thumb already are. */
@Composable
private fun ChapterFoot(next: Epub.Section?, onNext: () -> Unit, onShelf: () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().padding(top = 30.dp, bottom = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.width(34.dp).height(1.5.dp).background(p.gold.copy(alpha = 0.8f)))
        Spacer(Modifier.height(20.dp))
        if (next != null) {
            Column(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable { onNext() }
                    .padding(horizontal = 26.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val over = when {
                    !next.listed -> "CONTINUE"
                    next.label != null -> "NEXT · ${next.label.uppercase()}"
                    else -> "NEXT"
                }
                Text(
                    over,
                    fontFamily = UiFont, fontSize = 10.5.sp, fontWeight = FontWeight.Medium,
                    letterSpacing = 2.sp, color = p.inkSoft,
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    next.title.replace('\n', ' '),
                    fontFamily = DisplayFont, fontSize = 23.sp, lineHeight = 28.sp,
                    fontWeight = FontWeight.SemiBold, color = p.ink, textAlign = TextAlign.Center,
                )
            }
        } else {
            Text(
                "The end",
                fontFamily = DisplayFont, fontSize = 25.sp, fontWeight = FontWeight.SemiBold, color = p.ink,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Back to your books",
                fontFamily = UiFont, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = p.gold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onShelf() }.padding(10.dp),
            )
        }
    }
}

/**
 * A passage a book cites, shown over the book.
 *
 * The reader is in the middle of somebody's argument; the verse is wanted for a moment and
 * then the argument again. So it comes up over the page and goes away with a swipe, and
 * only "Open in the Bible" actually leaves — with a way back in the bar when it does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PassageSheet(
    passage: Citations.Passage,
    bible: Bible,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val scale = LocalTextScale.current
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val title = remember(passage) { Citations.title(passage, bible.books) }
    val groups by produceState<List<List<Pair<VerseId, String>>>?>(null, passage) {
        value = withContext(Dispatchers.IO) {
            passage.ranges.map { r -> runCatching { bible.passage(r.first, r.last) }.getOrElse { emptyList() } }
                .filter { it.isNotEmpty() }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = p.sunk,
        dragHandle = { BottomSheetDefaults.DragHandle(color = p.rule) },
    ) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 26.dp)) {
            Text(
                title,
                fontFamily = DisplayFont, fontSize = 25.sp, lineHeight = 30.sp,
                fontWeight = FontWeight.SemiBold, color = p.ink,
            )
            Text(
                "BEREAN STANDARD BIBLE",
                fontFamily = UiFont, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                letterSpacing = 1.8.sp, color = p.inkSoft,
                modifier = Modifier.padding(top = 3.dp),
            )
            Spacer(Modifier.height(14.dp))

            val got = groups
            val body = remember(got, p, scale) {
                buildAnnotatedString {
                    var chapter = passage.target.chapter
                    got?.forEachIndexed { g, verses ->
                        if (g > 0) append("\n\n")
                        verses.forEachIndexed { k, (id, words) ->
                            if (k > 0) append(" ")
                            // A second range in another chapter says so, once.
                            val number = if (k == 0 && id.chapter != chapter) "${id.chapter}:${id.verse}" else "${id.verse}"
                            chapter = id.chapter
                            withStyle(
                                SpanStyle(
                                    color = p.gold, fontSize = 11.sp * scale, fontFamily = UiFont,
                                    fontWeight = FontWeight.SemiBold, baselineShift = BaselineShift(0.42f),
                                    letterSpacing = 0.5.sp,
                                )
                            ) { append("$number ") }
                            append(words)
                        }
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp, max = (LocalConfiguration.current.screenHeightDp * 0.46f).dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (got != null && got.isEmpty()) Text(
                    "This passage is not in the Berean Standard Bible.",
                    fontFamily = UiFont, fontSize = 13.sp, color = p.inkSoft,
                )
                else Text(
                    body,
                    fontFamily = ReadingFont, fontWeight = FontWeight.Light,
                    fontSize = 18.sp * scale, lineHeight = 27.sp * scale, color = p.ink,
                )
            }

            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(onClick = onOpen, color = p.gold, shape = RoundedCornerShape(9.dp)) {
                    Text(
                        "Open in the Bible",
                        fontFamily = UiFont, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = p.paper,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "Copy",
                    fontFamily = UiFont, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = p.gold,
                    modifier = Modifier.clip(RoundedCornerShape(9.dp)).clickable {
                        val verses = got.orEmpty().flatten()
                        if (verses.isNotEmpty()) {
                            // The same form a selection in the Bible is copied in.
                            val text = if (verses.size == 1) verses[0].second
                                else verses.joinToString(" ") { "${it.first.verse} ${it.second}" }
                            ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(
                                android.content.ClipData.newPlainText(title, "$title (BSB)\n$text")
                            )
                            if (android.os.Build.VERSION.SDK_INT < 33) {
                                android.widget.Toast.makeText(ctx, "Copied $title", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                        onDismiss()
                    }.padding(horizontal = 14.dp, vertical = 11.dp),
                )
            }
        }
    }
}

/** What can be done with one paragraph of a book, on a long press. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockSheet(text: String, onRead: () -> Unit, onCopy: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.sunk,
        dragHandle = { BottomSheetDefaults.DragHandle(color = p.rule) },
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(
                text,
                fontFamily = ReadingFont, fontWeight = FontWeight.Light, fontStyle = FontStyle.Italic,
                fontSize = 15.sp, lineHeight = 24.sp, color = p.inkSoft,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.rule))
            SheetAction("Read from here", onRead)
            SheetAction("Copy", onCopy)
        }
    }
}

@Composable
internal fun SheetAction(label: String, onClick: () -> Unit, colour: Color? = null) {
    val p = LocalPalette.current
    Text(
        label,
        fontFamily = UiFont, fontSize = 15.sp, color = colour ?: p.ink,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 15.dp),
    )
}

/**
 * The book's contents: what it is, how much of it is left, and every chapter with how long
 * it takes to hear. Opens on the chapter being read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentsSheet(
    session: BookSession,
    progress: Float,
    /** Words from here to the end of the book. */
    left: Int,
    rate: Float,
    onGo: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    // Each named section, with the unnamed pieces after it counted in: a chapter a
    // converter split across three files is one line here and one length.
    val entries = remember(session) {
        val all = session.sections
        val named = all.indices.filter { all[it].listed }.ifEmpty { all.indices.toList() }
        named.mapIndexed { k, i ->
            val until = named.getOrNull(k + 1) ?: all.size
            Triple(i, all[i], (i until until).sumOf { all[it].words })
        }
    }
    val current = entries.indexOfLast { it.first <= session.index }.coerceAtLeast(0)
    val rows = rememberLazyListState(initialFirstVisibleItemIndex = (current - 1).coerceAtLeast(0))

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.sunk,
        dragHandle = { BottomSheetDefaults.DragHandle(color = p.rule) },
    ) {
        Column(Modifier.padding(horizontal = 22.dp)) {
            Text(
                session.title,
                fontFamily = DisplayFont, fontSize = 25.sp, lineHeight = 30.sp,
                fontWeight = FontWeight.SemiBold, color = p.ink,
            )
            if (session.author.isNotBlank()) Text(
                session.author,
                fontFamily = UiFont, fontSize = 12.5.sp, color = p.inkSoft,
                modifier = Modifier.padding(top = 3.dp),
            )
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(p.rule)) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(p.gold))
            }
            Text(
                if (left <= 0) "Finished · ${readingTime(session.totalWords, rate)} in all"
                else "${(progress * 100).toInt()}% · ${readingTime(left, rate)} left of ${readingTime(session.totalWords, rate)}",
                fontFamily = UiFont, fontSize = 11.5.sp, letterSpacing = 0.3.sp, color = p.inkSoft,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.rule))
        }
        // A long book's contents would otherwise carry the sheet up under the status bar.
        val tallest = (LocalConfiguration.current.screenHeightDp * 0.68f).dp
        LazyColumn(
            state = rows,
            contentPadding = PaddingValues(bottom = 30.dp),
            modifier = Modifier.heightIn(max = tallest),
        ) {
            itemsIndexed(entries) { k, (i, section, words) ->
                val on = k == current
                Row(
                    Modifier.fillMaxWidth().clickable { onGo(i) }
                        .background(if (on) p.gold.copy(alpha = if (p.dark) 0.12f else 0.09f) else Color.Transparent)
                        .padding(horizontal = 22.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        section.label?.let {
                            Text(
                                it.uppercase(),
                                fontFamily = UiFont, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                                letterSpacing = 1.8.sp, color = if (on) p.gold else p.inkSoft,
                                modifier = Modifier.padding(bottom = 2.dp),
                            )
                        }
                        Text(
                            section.title.replace('\n', ' '),
                            fontFamily = DisplayFont, fontSize = 19.sp, lineHeight = 24.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                            color = p.ink,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(
                        if (on) "Reading" else readingTime(words, rate),
                        fontFamily = UiFont, fontSize = 11.5.sp,
                        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                        color = if (on) p.gold else p.inkSoft,
                    )
                }
            }
        }
    }
}
