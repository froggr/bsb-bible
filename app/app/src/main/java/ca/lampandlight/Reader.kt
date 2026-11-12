package ca.lampandlight

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/* The reading screen. One column, nothing between the reader and the text: the chrome
   fades out while the eye is moving and comes back on a tap. */

@Composable
fun Reader(
    bible: Bible,
    narrator: Narrator,
    state: ReaderState,
    onOpenPicker: () -> Unit,
    onOpenStudy: (VerseId, Int?) -> Unit,
    onSearch: () -> Unit,
    onMenu: () -> Unit,
    selection: Set<VerseId>,
    /**
     * A verse number was tapped: select the verse, or with one already selected, make the
     * two the ends of a range. The colour and the note are then offered on the span.
     */
    onPickVerse: (VerseId) -> Unit,
    onOpenVerse: (VerseId, Int) -> Unit,
    /** Open the note written across a span, to read it in full or change it. */
    onOpenNote: (VerseId, VerseId) -> Unit,
    onReadFrom: (Int) -> Unit,
    onStudyLayers: () -> Unit,
    /** Press-and-drag selection: start on a verse, extend, then mark on release. */
    onDragStart: (VerseId) -> Unit,
    onDragTo: (VerseId) -> Unit,
    onDragEnd: () -> Unit,
    /** The span under the finger right now, so it can be seen as it is dragged out. */
    dragSpan: Pair<VerseId, VerseId>?,
    bed: Bed,
    prefs: Prefs,
    /**
     * Whether the voice is this page's. While a book is open the narrator is reading that,
     * and this screen — still composed underneath — must not follow it down the chapter.
     */
    voiced: Boolean = true,
    onBooks: () -> Unit = {},
    /** The book a passage was opened from, to go back to; null when there is none. */
    crumb: String? = null,
    onCrumb: () -> Unit = {},
) {
    val p = LocalPalette.current
    val speaking = voiced && narrator.speaking
    val sounding = if (voiced) narrator.verseIndex else -1
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var chromeVisible by remember { mutableStateOf(true) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    var topBarPx by remember { mutableIntStateOf(0) }
    var playerPx by remember { mutableIntStateOf(0) }
    // Until the bars have been measured, fall back to a height that is certainly enough;
    // starting at zero clips the first and last verses on the first frame.
    val topBarHeight = with(density) { topBarPx.toDp() }.coerceAtLeast(64.dp)
    val playerHeight = with(density) { playerPx.toDp() }.coerceAtLeast(96.dp)

    val verses = state.verses
    val book = bible.books.getOrNull(state.book - 1)

    // Hide the chrome only when the reader drags the page, never when narration scrolls it
    // for them — and never while it is reading, since that is when the player is wanted.
    val dragging by remember {
        derivedStateOf { list.interactionSource.let { _ -> list.isScrollInProgress } }
    }
    LaunchedEffect(dragging, speaking) {
        if (dragging && !speaking) chromeVisible = false
    }
    // Reading down hides the bars; reaching back up is the gesture for wanting them again,
    // the way a reader lifts their eyes. They also return once the page comes to rest.
    LaunchedEffect(list) {
        var last = 0
        snapshotFlow { list.firstVisibleItemIndex * 10_000 + list.firstVisibleItemScrollOffset }
            .collect { now ->
                if (now < last - 24) chromeVisible = true
                last = now
            }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { moving ->
            if (!moving) chromeVisible = true
        }
    }
    // Reading always brings the player back.
    LaunchedEffect(speaking) {
        if (speaking) chromeVisible = true
    }

    // Remember what is at the top of the screen, so going back returns to the place in the
    // chapter rather than to its first verse.
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex }.collect { i ->
            // The head of the chapter is a place too: left there, it is where to come back
            // to, with whatever stands above the first verse.
            if (i == 0) { if (state.topVerse != 0) state.topVerse = 0 }
            else (state.rows.getOrNull(i) as? Row.Verse)?.let { state.topVerse = it.verse.id.verse }
        }
    }

    // A chapter opened at its head starts at the top of the page, wherever the last one
    // was left.
    LaunchedEffect(state.top) { if (state.top > 0) list.scrollToItem(0) }

    // Reaching the foot of a chapter that has been open long enough to have been read is
    // reading it. The first moments are not looked at: the list still holds the place it
    // had in the chapter before.
    LaunchedEffect(state.book, state.chapter) {
        val began = android.os.SystemClock.elapsedRealtime()
        kotlinx.coroutines.delay(800)
        snapshotFlow {
            val rows = state.rows.size
            rows > 0 && (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= rows
        }.first { it }
        val left = Places.enough(state.verses.size) - (android.os.SystemClock.elapsedRealtime() - began)
        if (left > 0) kotlinx.coroutines.delay(left)
        state.finished()
    }

    // Land on the verse a jump asked for, once the new chapter has been laid out.
    //
    // Keyed on the request alone, and landed again every time the chapter is re-laid out
    // in the two seconds after it. The rows change under a fresh chapter more than once —
    // marks, then the study layer off disk — and a scroll asked for by row index before the
    // first layout was being applied to whichever list existed by then: a cold start at
    // Psalm 119:105 landed on verse 57, which is where row 118 of the plain layout falls
    // once the commentary is interleaved. Landing by verse number on each relayout is
    // right whatever the order the layers arrive in.
    LaunchedEffect(state.pending) {
        val want = state.pending
        if (want <= 0) return@LaunchedEffect
        state.consumePending()
        var landedOn: List<Row>? = null
        var spent = 0
        while (spent < 2000) {
            val rows = state.rows
            if (rows !== landedOn) {
                landedOn = rows
                val target = state.rowOfVerseNumber(want)
                if (target >= 0) list.scrollToItem(target, -120)
            }
            // Settled: not in study mode, so nothing more is coming once a few frames have
            // passed. In study mode the window stays open: relayouts are cheap to follow
            // and the layer's arrival time is not worth second-guessing.
            if (!state.study && spent >= 400) break
            kotlinx.coroutines.delay(100); spent += 100
        }
    }

    // Follow the narration, but only when the verse has actually left the comfortable part
    // of the screen: scrolling on every verse drags the page out from under the reader.
    LaunchedEffect(sounding) {
        val i = sounding
        if (i < 0 || i >= verses.size) return@LaunchedEffect
        val target = state.rowOfVerse(i)
        if (target < 0) return@LaunchedEffect
        val info = list.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == target }
        val top = info.viewportStartOffset
        val bottom = info.viewportEndOffset
        val needs = item == null ||
            item.offset < top + 40 ||
            item.offset + item.size > bottom - 260
        if (needs) list.animateScrollToItem(target, -200)
    }

    // When the voice puts a question, bring its panel up the page: it hangs from the last
    // verse of the section, which is usually in view already while the panel under it is
    // still below the player.
    LaunchedEffect(narrator.posed) {
        val key = narrator.posed ?: return@LaunchedEffect
        if (!voiced) return@LaunchedEffect
        val target = if (key == Places.KEY) state.rows.indexOfFirst { it is Row.Recall }
                     else state.rows.indexOfFirst { it is Row.Ask && it.asked.question.key == key }
        if (target < 0) return@LaunchedEffect
        val info = list.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == target }
        val clear = info.viewportEndOffset - with(density) { (playerHeight + 22.dp).roundToPx() }
        // Above the screen too: a row can be in the list's layout and wholly under the top
        // bar, in the space the content padding keeps for it.
        if (item == null || item.offset < 0 || item.offset + item.size > clear) list.animateScrollToItem(target, -90)
    }

    /**
     * Which verse sits under a point on screen, from the list's own layout.
     *
     * Asking the list rather than each row means the answer is right even when the finger
     * passes over a commentary block, and it costs nothing per verse to draw.
     */
    // Where the list sits in the window, so a position reported by a verse (which knows only
    // its own coordinates) can be compared against the list's layout. Measuring this instead
    // of assuming the two frames agree is the whole fix: the drag detector used to live on the
    // LazyColumn, whose top edge is the OUTER edge of the content padding, while item offsets
    // are measured from the content. The two differ by the top bar's height, which is exactly
    // the one-or-two-verse drift.
    var listTopInWindow by remember { mutableFloatStateOf(0f) }

    fun verseAtContent(y: Float): VerseId? {
        val info = list.layoutInfo
        // [y] is in the list's CONTENT space, the same space as item offsets (measured on
        // device: rows above the viewport read negative, e.g. -582, and the row at the padding
        // edge reads +120). Do not add viewportStartOffset — that double-counts the padding.
        // Test CONTAINMENT. `lastOrNull { offset <= y }` has no notion of being inside a row,
        // and the last visible row satisfies it for any y below it.
        val item = info.visibleItemsInfo.firstOrNull { y < it.offset + it.size }
            // Past the last laid-out row — hold the span at the bottom instead of dropping it,
            // so dragging into the player bar does not snap the selection back.
            ?: info.visibleItemsInfo.lastOrNull()
            ?: return null
        return (state.rows.getOrNull(item.index) as? Row.Verse)?.verse?.id
            // A commentary or note row belongs to the verse above it, so dragging across
            // one goes on extending the span instead of stalling.
            ?: state.rows.take(item.index + 1).filterIsInstance<Row.Verse>().lastOrNull()?.verse?.id
    }

    /** The verse under a point given in WINDOW coordinates. */
    fun verseAtWindow(windowY: Float): VerseId? = verseAtContent(windowY - listTopInWindow)

    Box(Modifier.fillMaxSize().background(p.paper)) {
        LazyColumn(
            state = list,
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { listTopInWindow = it.positionInWindow().y },
            // Reserve the bars' real heights so the text scrolls clear of them instead of
            // sliding underneath. Guessed numbers are wrong on a phone with a cutout.
            contentPadding = PaddingValues(
                start = 13.dp,
                end = 13.dp,
                top = topBarHeight + 14.dp,
                bottom = playerHeight + 22.dp,
            ),
        ) {
            items(state.rows, key = { it.key }) { row ->
                // Every row draws the brackets passing it; see Margin.
                MarginLanes(row.margin) { when (row) {
                    is Row.Heading -> SectionHeading(row.text)
                    is Row.Title -> PsalmTitle(row.text)
                    is Row.Verse -> VerseBlock(
                        row = row,
                        narratingSpan = if (sounding == row.index) narrator.wordSpan else null,
                        reading = sounding == row.index,
                        mark = state.markOf(row.verse.id),
                        markedWords = state.markedWords(row.verse.id, row.words.size)?.first,
                        // The selection is a range: its two ends, and everything between.
                        selected = dragSpan?.let { (a, b) ->
                            row.verse.id >= minOf(a, b) && row.verse.id <= maxOf(a, b)
                        } ?: (selection.isNotEmpty() &&
                            row.verse.id >= selection.min() && row.verse.id <= selection.max()),
                        selecting = selection.isNotEmpty() || dragSpan != null,
                        // Tapping the number selects the verse; a second number makes a
                        // range. The colour row and Note then sit at the foot of the screen.
                        // The sheet, on a long press, has the rest.
                        onTapVerse = { chromeVisible = true; onPickVerse(row.verse.id) },
                        onTapWord = { w ->
                            chromeVisible = true
                            if (selection.isEmpty()) onOpenStudy(row.verse.id, w)
                            else onPickVerse(row.verse.id)
                        },
                        onLongPress = { onOpenVerse(row.verse.id, row.index) },
                        onReadFrom = { chromeVisible = true; onReadFrom(row.index) },
                        // The drag starts on THIS verse — no hit-test needed for the anchor,
                        // so the end the finger started on is never guessed wrong.
                        onMarkStart = { chromeVisible = true; onDragStart(row.verse.id) },
                        onMarkTo = { windowY -> verseAtWindow(windowY)?.let { onDragTo(it) } },
                        onMarkEnd = onDragEnd,
                    )
                    // Tapping a note opens the sheet it was written in, on the whole span
                    // it was written across, so reading one and editing it are the same
                    // gesture. Going through the verse sheet instead would offer to note
                    // one verse of a span.
                    is Row.Note -> NoteRow(
                        text = row.text,
                        colour = row.colour,
                        scope = if (row.start != row.verse)
                            "verses ${row.start.verse}\u2013${row.verse.verse}" else null,
                    ) { onOpenNote(row.start, row.verse) }
                    is Row.Footnote -> FootnoteRow(row.letter, row.phrase, row.text)
                    is Row.Ask -> {
                        val key = row.asked.question.key
                        AskRow(
                            row = row,
                            chosen = state.answers[key],
                            waiting = voiced && narrator.asking == key,
                            waitFrom = narrator.askedAt,
                            waitFor = narrator.askFor,
                            onChoose = { chromeVisible = true; state.answer(key, it) },
                        )
                    }
                    is Row.Recall -> RecallPanel(
                        label = "Before you go on",
                        question = Places.asks(row.name.substringBeforeLast(' '), row.place.number),
                        headings = row.headings,
                        lifted = state.lifted,
                        outcome = state.recalled,
                        after = if (state.recalled == Quiz.RIGHT) "It comes back ${state.recallBack}, in Go over."
                                else "It will come back in a few minutes, in Go over.",
                        waiting = voiced && narrator.asking == Places.KEY,
                        waitFrom = narrator.askedAt,
                        waitFor = narrator.askFor,
                        onLift = { chromeVisible = true; state.lift(it) },
                        onSaid = { chromeVisible = true; state.recall(it) },
                    )
                    // Drawn once under the last verse it covers, labelled with the span
                    // and bracketed from its first verse, the same way as a note.
                    is Row.Comment -> CommentRow(
                        row.source, row.body, row.verse, bible.books,
                        onGo = { target ->
                            narrator.stop()
                            state.go(target.book, target.chapter, at = target.verse)
                        },
                        scope = if (row.start != row.verse)
                            "verses ${row.start.verse}\u2013${row.verse.verse}" else null,
                        anchored = row.anchored,
                    )
                    is Row.Refs -> RefsRow(
                        row.refs.map { r ->
                            val lbl = bible.reference(r.dst).let { base ->
                                if (r.dstEnd != null && r.dstEnd != r.dst)
                                    base + "-" + (r.dstEnd % 1000)
                                else base
                            }
                            lbl to r.dst
                        },
                        // Following a reference leaves a breadcrumb in the top bar, so a
                        // chain of them can be walked back out of.
                        onGo = { target ->
                            narrator.stop()
                            state.go(target.book, target.chapter, at = target.verse)
                        },
                    )
                } }
            }
            item("end") { Spacer(Modifier.height(40.dp)) }
        }

        // The list's top padding has to match the bar's RESTING height. Measuring the
        // visible bar gets this wrong twice over: onSizeChanged keeps firing as it slides
        // away, and taking only the maximum (the old `if (height > topBarPx)`) locks in the
        // tallest state forever — so after the back line disappears the padding stays too
        // tall, the content sits lower than the padding claims, and every verse under the
        // finger reads a couple too far down. That was the selection drift.
        // An invisible copy is never mid-animation, so its height is always the true one.
        Box(
            Modifier.align(Alignment.TopCenter)
                // Measured, laid out, never drawn and never touched: it must not steal the
                // taps or the drag that belong to the first verse underneath it.
                .onSizeChanged { topBarPx = it.height }
                .drawWithContent { }
                .pointerInput(Unit) { },
        ) {
            TopBar(
                label = "${book?.name ?: ""} ${state.chapter}",
                onPick = {}, onPrev = {}, onNext = {}, onSearch = {}, onMenu = {}, onBooks = {},
                backTo = crumb ?: state.backLabel(), onBack = {},
                study = state.study, studyBusy = state.studyLoading,
                onStudy = {}, onStudyLayers = {},
            )
        }

        // ---- top bar: reference and a way out ----
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopBar(
                label = "${book?.name ?: ""} ${state.chapter}",
                onPick = onOpenPicker,
                // Like the picker: the place to carry on from is a line of the chapter being
                // left, and would be read as a line of the one arrived at.
                onPrev = { narrator.reset(); scope.launch { state.previousChapter() } },
                onNext = { narrator.reset(); scope.launch { state.nextChapter() } },
                onSearch = onSearch,
                onMenu = onMenu,
                onBooks = onBooks,
                // Out of a book and into the passage it cited: the way back is to the book.
                backTo = crumb ?: state.backLabel(),
                onBack = { if (crumb != null) onCrumb() else { narrator.reset(); state.back() } },
                study = state.study,
                studyBusy = state.studyLoading,
                onStudy = {
                    state.chooseStudy(!state.study)
                    prefs.study = state.study; prefs.save()
                },
                onStudyLayers = { onStudyLayers() },
            )
        }

        // ---- player ----
        AnimatedVisibility(
            visible = chromeVisible || speaking,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
                .onSizeChanged { if (it.height > playerPx) playerPx = it.height },
        ) {
            PlayerBar(
                narrator, bed, prefs,
                reading = when {
                    narrator.asking != null -> "waiting for your answer"
                    narrator.posed != null -> "a question"
                    else -> "reading verse ${state.verses.getOrNull(sounding)?.id?.verse ?: ""}"
                },
                // resumeAt is a LINE of the script; with study rows and questions between the
                // verses that is not the verse's own position.
                resume = "Continue from verse ${state.verses.getOrNull(
                    remember(narrator.resumeAt, state.rows) { state.verseOfLine(narrator.resumeAt) }
                )?.id?.verse ?: ""}",
                onPlay = { narrator.speak(state.narrationLines(), narrator.resumeAt) },
            )
        }

        // Bringing the chrome back must never cost a scroll. A full-screen catcher used to
        // sit here and swallow the first drag after hiding, so the page refused to move
        // until it had been tapped — the list keeps its own touches now, and the bars come
        // back when the reader stops dragging or pulls back up the page.
    }
}

@Composable
private fun SectionHeading(text: String) {
    val p = LocalPalette.current
    Text(
        text = text,
        fontFamily = DisplayFont,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.6.sp,
        color = p.gold,
        modifier = Modifier.padding(top = 26.dp, bottom = 10.dp),
    )
}

@Composable
private fun PsalmTitle(text: String) {
    val p = LocalPalette.current
    Column {
        Text(
            text = text,
            fontFamily = ReadingFont,
            fontStyle = FontStyle.Italic,
            fontSize = 15.sp,
            color = p.inkSoft,
            modifier = Modifier.padding(bottom = 14.dp),
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.rule))
        Spacer(Modifier.height(20.dp))
    }
}

/**
 * One verse.
 *
 * The verse is drawn as a single paragraph so it wraps and justifies like prose; the word
 * being narrated is marked inside that paragraph rather than by splitting it into separate
 * views, which would break line breaking and kerning.
 */
@Composable
private fun VerseBlock(
    row: Row.Verse,
    narratingSpan: IntRange?,
    reading: Boolean,
    mark: MarkColor?,
    /** Which words of this verse the mark covers, when it does not cover all of them. */
    markedWords: IntRange?,
    selected: Boolean,
    selecting: Boolean,
    onTapVerse: () -> Unit,
    onTapWord: (Int) -> Unit,
    onLongPress: () -> Unit,
    /** Long press that turned into a drag: mark from here. */
    onMarkStart: () -> Unit = {},
    /** Extend the span to whatever verse is at this y, in WINDOW coordinates. */
    onMarkTo: (Float) -> Unit = {},
    onMarkEnd: () -> Unit = {},
    onReadFrom: () -> Unit,
) {
    val p = LocalPalette.current
    val scale = LocalTextScale.current
    val glow by animateFloatAsState(if (reading) 1f else 0f, label = "reading")

    // Does the mark stop short of covering the whole verse? Decides whether the colour goes
    // on the words or on the block behind them.
    val partialMark = markedWords != null &&
        (markedWords.first > 0 || markedWords.last < row.words.lastIndex)

    // The translators' notes, when they are on the page: which words each is about. The
    // place is shown the way a printed Bible shows it — a small letter after the words —
    // and the words themselves are underlined, dotted, so the note can be read against
    // exactly what it annotates. The character ranges are kept for the underline.
    val anchors = row.noteAnchors
    val endsAt = HashMap<Int, MutableList<Int>>()        // word index -> notes ending there
    anchors.forEachIndexed { k, r -> if (r != null) endsAt.getOrPut(r.last) { mutableListOf() } += k }
    val underlines = ArrayList<IntRange>()
    // Where each word's letters start and end in the built string, for the gloss underlines.
    val wordStart = IntArray(row.words.size) { -1 }
    val wordEnd = IntArray(row.words.size) { -1 }

    val body = buildAnnotatedString {
        pushStringAnnotation("num", "1")
        withStyle(SpanStyle(
            color = p.gold, fontSize = 11.sp * scale, fontFamily = UiFont,
            fontWeight = FontWeight.SemiBold, baselineShift = BaselineShift(0.42f),
            letterSpacing = 0.5.sp,
        )) { append("${row.verse.id.verse}  ") }
        pop()

        // A mark that covers only part of the verse is painted on its words. One that covers
        // the whole verse tints the block instead, which reads more calmly down a page.
        val partial = partialMark
        // Lighter than the old per-word band: this covers a whole clause, so the same
        // strength would shout.
        val litColour = p.gold.copy(alpha = if (p.dark) 0.22f else 0.17f)
        val marked = mark?.color(p.dark)?.copy(alpha = if (p.dark) 0.5f else 0.75f)

        val starts = IntArray(anchors.size) { -1 }
        row.words.forEachIndexed { i, w ->
            val lit = narratingSpan?.contains(i) == true
            val inMark = partial && markedWords != null && i in markedWords
            anchors.forEachIndexed { k, r -> if (r != null && r.first == i) starts[k] = length }
            wordStart[i] = length
            wordEnd[i] = length + w.trimEnd { !it.isLetterOrDigit() }.length
            pushStringAnnotation("w", i.toString())
            withStyle(SpanStyle(
                background = when {
                    lit -> litColour
                    inMark && marked != null -> marked
                    else -> Color.Transparent
                },
                color = p.ink,
            )) { append(w) }
            endsAt[i]?.forEach { k ->
                // The line stops at the last letter: "Jesus" is annotated, its full stop
                // is not.
                val tail = w.length - w.trimEnd { !it.isLetterOrDigit() }.length
                if (starts[k] >= 0 && length - tail > starts[k]) underlines += starts[k] until length - tail
                // The letter sits with the word it follows, so a tap on it is a tap on
                // the word, and a line never breaks between the two.
                withStyle(SpanStyle(
                    color = p.indigo, fontSize = 10.5.sp * scale, fontFamily = UiFont,
                    fontWeight = FontWeight.SemiBold, baselineShift = BaselineShift(0.45f),
                )) { append(noteLetter(k)) }
            }
            pop()
            // The space between two marked words is marked too, so the band is unbroken.
            if (i < row.words.lastIndex) {
                val bridge = inMark && markedWords != null && (i + 1) in markedWords
                val litBridge = lit && narratingSpan?.contains(i + 1) == true
                when {
                    litBridge -> withStyle(SpanStyle(background = litColour)) { append(" ") }
                    bridge && marked != null ->
                        withStyle(SpanStyle(background = marked)) { append(" ") }
                    else -> append(" ")
                }
            }
        }
    }

    // The words a commentator is glossing, in the commentary colour, so the eye can go
    // from his italic lemma to the words of this translation it stands for.
    val glosses = row.glossAnchors.mapNotNull { r ->
        val a = wordStart.getOrNull(r.first) ?: return@mapNotNull null
        val b = wordEnd.getOrNull(r.last) ?: return@mapNotNull null
        if (a < 0 || b <= a) null else a until b
    }

    // The padding is constant and the background only fades in: adding padding when a verse
    // starts being read reflowed every verse below it, which is what made the page shift.
    // The bracket of a note down the margin is not drawn here: the row is wrapped in
    // MarginLanes, which draws every bracket passing this verse through its full height.
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 9.dp)
            .background(
                when {
                    selected -> p.gold.copy(alpha = 0.18f)
                    mark != null && !partialMark ->
                        mark.color(p.dark).copy(alpha = if (p.dark) 0.5f else 0.75f)
                    else -> p.sunk.copy(alpha = glow * 0.5f)
                },
                RoundedCornerShape(5.dp),
            )
            .then(
                if (selected) Modifier.border(
                    1.5.dp, p.gold, RoundedCornerShape(5.dp)) else Modifier
            )
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        // Two detectors, deliberately split: taps below, long-press-and-drag last (it has to
        // be last to receive the pointer first). ClickableText would swallow the long press.
        var layout by remember(row.key) {
            mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null)
        }
        // Where this verse's text sits in the window, so a touch inside it can be expressed
        // in a frame the list's layout also understands.
        var layoutTop by remember(row.key) { mutableStateOf<Float?>(null) }
        // Double tap is detected by hand, because the one gesture loop below owns the whole
        // pointer stream: remember when and where the last tap landed.
        val lastTap = remember(row.key) { longArrayOf(0L) }
        Text(
            text = body,
            fontFamily = ReadingFont,
            fontWeight = FontWeight.Light,
            fontSize = 19.sp * scale,
            lineHeight = 27.sp * scale,
            color = p.ink,
            onTextLayout = { layout = it },
            modifier = Modifier
                .onGloballyPositioned { layoutTop = it.positionInWindow().y }
                // The dotted line under the words a translator's note is about. Drawn
                // from the laid-out text, line by line, so a phrase that wraps is
                // underlined on both lines.
                .drawBehind {
                    val l = layout
                    if (l == null || (underlines.isEmpty() && glosses.isEmpty())) return@drawBehind
                    val dots = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                        floatArrayOf(1.5.dp.toPx(), 2.5.dp.toPx()))
                    val width = 1.dp.toPx()
                    val noteColour = p.indigo.copy(alpha = if (p.dark) 0.8f else 0.7f)
                    val glossColour = p.gold.copy(alpha = if (p.dark) 0.85f else 0.8f)
                    (glosses.map { it to glossColour } + underlines.map { it to noteColour })
                        .forEach { (chars, colour) ->
                        val from = l.getLineForOffset(chars.first)
                        val to = l.getLineForOffset(chars.last)
                        for (line in from..to) {
                            val a = maxOf(chars.first, l.getLineStart(line))
                            val b = minOf(chars.last, l.getLineEnd(line, visibleEnd = true) - 1)
                            if (b < a) continue
                            val x0 = l.getBoundingBox(a).left
                            val x1 = l.getBoundingBox(b).right
                            val y = l.getLineBaseline(line) + 2.5.dp.toPx()
                            drawLine(colour, androidx.compose.ui.geometry.Offset(x0, y),
                                androidx.compose.ui.geometry.Offset(x1, y), width,
                                pathEffect = dots)
                        }
                    }
                }
                // ONE gesture loop for everything this verse does: tap, double tap, long
                // press, and long-press-then-drag.
                //
                // It has to be one, not two chained pointerInput blocks. detectTapGestures
                // consumes the initial `down` unconditionally, and a consumed change makes
                // awaitLongPressOrCancellation give up — so a tap detector and a drag
                // detector on the same composable can never share a long press, whichever
                // order they are declared in. (Declaration order does matter, confusingly:
                // on the Main pass the LAST-declared block receives events FIRST. Chasing
                // that ordering was a dead end; both arrangements break something.)
                //
                // This is the shape Compose's own text selection uses: await the down, await
                // the long press, then drag.
                .pointerInput(row.key) {
                    val slop = 12.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // A long press that never moves means the menu; one that travels
                        // marks a range. Deciding here, from one stream of events, is what
                        // stops a single press from doing both — the stray highlight.
                        val press = awaitLongPressOrCancellation(down.id)
                        if (press == null) {
                            // No long press: it was a tap (or a scroll took the gesture).
                            // Resolve the tap against the text, as before.
                            val up = currentEvent.changes.firstOrNull { it.changedToUp() }
                            if (up != null) {
                                val now = System.currentTimeMillis()
                                if (now - lastTap[0] < 280) {
                                    // Second tap in quick succession: read from this verse.
                                    lastTap[0] = 0L
                                    onReadFrom()
                                } else {
                                    lastTap[0] = now
                                    val l = layout
                                    if (l != null) {
                                        val off = l.getOffsetForPosition(up.position)
                                        val num = body.getStringAnnotations("num", off, off).firstOrNull()
                                        val word = body.getStringAnnotations("w", off, off).firstOrNull()
                                        when {
                                            num != null -> onTapVerse()
                                            word != null -> onTapWord(word.item.toInt())
                                        }
                                    }
                                }
                            }
                            return@awaitEachGesture
                        }
                        var marking = false
                        var travelled = 0f
                        drag(press.id) { change ->
                            if (!marking) {
                                travelled += change.positionChange().getDistance()
                                if (travelled < slop) return@drag
                                marking = true
                                onMarkStart()
                            }
                            // Window coordinates: this verse's own frame means nothing to
                            // the list's layout.
                            onMarkTo(change.position.y + (layoutTop ?: 0f))
                            change.consume()
                        }
                        if (marking) onMarkEnd() else onLongPress()
                    }
                },
        )
    }
}

@Composable
private fun TopBar(
    label: String,
    onPick: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSearch: () -> Unit,
    onMenu: () -> Unit,
    onBooks: () -> Unit,
    /** Where following a link would return to, named; null when nothing was followed. */
    backTo: String?,
    onBack: () -> Unit,
    study: Boolean,
    studyBusy: Boolean,
    onStudy: () -> Unit,
    onStudyLayers: () -> Unit,
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
                Chip("\u2039", onPrev)
                Spacer(Modifier.width(7.dp))
                Surface(
                    onClick = onPick,
                    color = Color.Transparent,
                    shape = RoundedCornerShape(9.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, p.rule),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        label,
                        fontFamily = DisplayFont,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = p.ink,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 7.dp),
                    )
                }
                Spacer(Modifier.width(7.dp))
                Chip("\u203a", onNext)
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Named, because "back" alone does not say where: after three links the
                // reader has lost the thread without it. Search stays reachable alongside.
                // The name gives way before anything else does: a book's title can be long,
                // and the row has four other things to hold.
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (backTo != null) {
                        Box(Modifier.weight(1f, fill = false)) { TextAction("‹ $backTo", onBack) }
                        Spacer(Modifier.width(16.dp))
                    }
                    TextAction("Search", onSearch)
                    Spacer(Modifier.width(16.dp))
                    // The reader's own books, read and listened to the same way.
                    TextAction("Books", onBooks)
                    Spacer(Modifier.width(12.dp))
                }
                // Study sits with the reading, not in settings: it is a way of reading the
                // page rather than a preference, and it gets switched on and off mid-chapter.
                StudyToggle(study, studyBusy, onStudy, onStudyLayers)
                Spacer(Modifier.width(16.dp))
                TextAction("Marks & settings", onMenu)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.rule))
        }
    }
}

/**
 * The study switch.
 *
 * Lit when on, so the state is visible without reading the word — the page changes under it
 * and the reader should be able to see why at a glance. A long press opens which layers are
 * shown, keeping the common action (on, off) to a single tap.
 */
@Composable
private fun StudyToggle(
    on: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onLayers: () -> Unit,
) {
    val p = LocalPalette.current
    val bg by animateColorAsState(
        if (on) p.gold.copy(alpha = if (p.dark) 0.22f else 0.17f) else Color.Transparent,
        label = "studyBg",
    )
    val fg by animateColorAsState(if (on) p.gold else p.inkSoft, label = "studyFg")
    Surface(
        color = bg,
        shape = RoundedCornerShape(5.dp),
        modifier = Modifier.pointerInput(on) {
            detectTapGestures(onTap = { onToggle() }, onLongPress = { onLayers() })
        },
    ) {
        Row(
            Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Study",
                fontFamily = UiFont, fontSize = 11.5.sp, fontWeight = FontWeight.Medium,
                letterSpacing = 0.4.sp, color = fg,
            )
            // Only while the commentary is coming off disk, and only then: a spinner that
            // shows on every chapter turn would be noise.
            if (busy) {
                Spacer(Modifier.width(6.dp))
                CircularProgressIndicator(
                    strokeWidth = 1.2.dp,
                    color = fg,
                    modifier = Modifier.size(9.dp),
                )
            }
        }
    }
}

@Composable
internal fun TextAction(label: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Text(
        label,
        fontFamily = UiFont, fontSize = 11.5.sp, fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp, color = p.inkSoft,
        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            ) { onClick() }
            .padding(vertical = 4.dp, horizontal = 2.dp),
    )
}

@Composable
internal fun Chip(label: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, p.rule),
    ) {
        Text(
            label, fontFamily = DisplayFont, fontSize = 20.sp, color = p.inkSoft,
            textAlign = TextAlign.Center,
            modifier = Modifier.size(38.dp).wrapContentHeight(),
        )
    }
}

/**
 * The player, shared by the Bible and by a book. What is being read is the caller's to say:
 * [reading] while the voice is going, [resume] when it was stopped part-way, and [onPlay]
 * starts it — the bar itself only knows the voice, the music and the pace.
 */
@Composable
internal fun PlayerBar(
    narrator: Narrator, bed: Bed, prefs: Prefs,
    reading: String, resume: String?, onPlay: () -> Unit,
    idle: String = "Read aloud",
) {
    val p = LocalPalette.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Which control is unfolded, if any. Only one at a time: the panel is a drawer, not a form.
    var open by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    // Discover the voices so the row can name one without a reading happening first.
    LaunchedEffect(Unit) { narrator.loadVoices() }

    Surface(color = p.paper) {
        Column {
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.rule))

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + androidx.compose.animation.expandVertically(),
                exit = fadeOut() + androidx.compose.animation.shrinkVertically(),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)) {
                    Setting(
                        label = "Voice",
                        value = if (narrator.modelReady()) narrator.voice else "not downloaded",
                        detail = Voices.describe(narrator.voice),
                        on = open == "voice",
                        onTap = { open = if (open == "voice") null else "voice" },
                    )
                    if (open == "voice") {
                        val known = Voices.ALL.map { it.name }.filter { it in narrator.voices }
                        val rest = narrator.voices.filter { it !in known }
                        Choices {
                            (known + rest).forEach { v ->
                                Choice(v, Voices.describe(v), narrator.voice == v) {
                                    narrator.chooseVoice(v); prefs.voice = v; prefs.save()
                                    open = null
                                }
                            }
                        }
                    }

                    Setting(
                        label = "Bed",
                        value = bed.track?.let { Beds.label(it) } ?: "None",
                        // What is chosen is a kind of music; the detail is the track of it
                        // that is playing now.
                        detail = bed.sounding?.let { Beds.label(it) },
                        on = open == "bed",
                        onTap = { open = if (open == "bed") null else "bed" },
                    )
                    if (open == "bed") {
                        val have = bed.kinds()
                        if (have.isEmpty()) {
                            // Nothing to pick from yet: say where the music comes from rather
                            // than showing an empty list.
                            Text(
                                "No music downloaded. Marks & settings → Settings → Music.",
                                fontFamily = UiFont, fontSize = 11.sp, color = p.inkSoft,
                                modifier = Modifier.padding(start = 2.dp, bottom = 10.dp),
                            )
                        } else {
                            Choices {
                                Choice("None", null, bed.track == null) {
                                    bed.stop(); prefs.bed = ""; prefs.save(); open = null
                                }
                                // A kind, not a track: the bed moves through every track
                                // of the kind chosen, so nothing comes round for a long while.
                                have.forEach { kind ->
                                    Choice(kind.label, kind.detail, bed.track == kind.name) {
                                        bed.choose(kind.name)
                                        prefs.bed = bed.track ?: ""; prefs.save()
                                    }
                                }
                            }
                            if (bed.track != null) {
                                Row(
                                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "Music", fontFamily = UiFont, fontSize = 11.sp,
                                        color = p.inkSoft, modifier = Modifier.width(52.dp),
                                    )
                                    Slider(
                                        value = prefs.bedLevel,
                                        onValueChange = { prefs.bedLevel = it; bed.setVolume(it) },
                                        onValueChangeFinished = { prefs.save() },
                                        colors = SliderDefaults.colors(
                                            thumbColor = p.gold, activeTrackColor = p.gold,
                                            inactiveTrackColor = p.rule,
                                        ),
                                        modifier = Modifier.weight(1f).height(26.dp),
                                    )
                                }
                            }
                        }
                    }

                    Setting(
                        label = "Pace",
                        value = "${"%.2f".format(prefs.rate).trimEnd('0').trimEnd('.')}×",
                        detail = null,
                        on = open == "pace",
                        onTap = { open = if (open == "pace") null else "pace" },
                    )
                    if (open == "pace") {
                        Choices {
                            listOf(0.9f, 1.0f, 1.15f, 1.3f).forEach { r ->
                                val name = "${"%.2f".format(r).trimEnd('0').trimEnd('.')}×"
                                Choice(name, null, kotlin.math.abs(prefs.rate - r) < 0.01f) {
                                    prefs.rate = r; narrator.rate = r; prefs.save()
                                    open = null
                                }
                            }
                        }
                    }

                    // "Then", because the label column fits one short word: it reads as
                    // "Then: carry on" / "Then: stop", which is the question being asked.
                    Setting(
                        label = "Then",
                        value = if (prefs.readOn) "Carry on" else "Stop",
                        detail = null,
                        on = open == "after",
                        onTap = { open = if (open == "after") null else "after" },
                    )
                    if (open == "after") {
                        Choices {
                            Choice("Carry on", "into the next chapter", prefs.readOn) {
                                prefs.readOn = true; prefs.save(); open = null
                            }
                            Choice("Stop", "at the end of this one", !prefs.readOn) {
                                prefs.readOn = false; prefs.save(); open = null
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(p.rule))
                }
            }

            Row(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 18.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = {
                        if (narrator.speaking) narrator.stop() else onPlay()
                    },
                    shape = CircleShape,
                    color = p.gold,
                    modifier = Modifier.size(50.dp),
                ) {
                    Text(
                        if (narrator.speaking) "❚❚" else "▶",
                        color = p.paper,
                        fontSize = if (narrator.speaking) 15.sp else 19.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.wrapContentHeight(),
                    )
                }
                Spacer(Modifier.width(15.dp))
                // The whole strip opens the panel: a 50dp chevron alone is a small target for
                // something you reach for one-handed, mid-chapter.
                Column(
                    Modifier.weight(1f).clickable {
                        expanded = !expanded
                        if (!expanded) open = null
                    },
                ) {
                    Text(
                        when {
                            narrator.preparing -> "warming up…"
                            // A question put before the first verse is at no verse.
                            narrator.speaking && (narrator.verseIndex >= 0 || narrator.posed != null) -> reading
                            narrator.error != null -> narrator.error!!
                            narrator.resumeAt > 0 && resume != null -> resume
                            else -> idle
                        },
                        fontFamily = UiFont, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        color = if (narrator.error != null) p.gold else p.ink,
                    )
                    Text(
                        buildString {
                            append(if (narrator.modelReady()) narrator.voice else "voice not downloaded")
                            // Name the bed that is sounding; under rotation that changes.
                            (bed.sounding ?: bed.track)?.let { append(" · ").append(Beds.label(it)) }
                        },
                        fontFamily = UiFont, fontSize = 11.sp, color = p.inkSoft,
                    )
                }
                Text(
                    if (expanded) "⌄" else "⌃",
                    fontFamily = UiFont, fontSize = 15.sp, color = p.inkSoft,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.size(38.dp).wrapContentHeight()
                        .clickable { expanded = !expanded; if (!expanded) open = null },
                )
            }
        }
    }
}

/** One line of the player panel: what it is, what it is set to, and a way in. */
@Composable
private fun Setting(
    label: String, value: String, detail: String?, on: Boolean, onTap: () -> Unit,
) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onTap).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontFamily = UiFont, fontSize = 11.sp, color = p.inkSoft,
            modifier = Modifier.width(52.dp))
        Text(value, fontFamily = UiFont, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = p.ink)
        detail?.let {
            Spacer(Modifier.width(7.dp))
            Text(it, fontFamily = UiFont, fontSize = 11.sp, color = p.inkSoft)
        }
        Spacer(Modifier.weight(1f))
        Text(if (on) "⌄" else "›", fontFamily = UiFont, fontSize = 13.sp, color = p.inkSoft)
    }
}

/** The list a setting opens into, inset so it reads as belonging to the row above. */
@Composable
private fun Choices(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = 52.dp, bottom = 6.dp)
            .heightIn(max = 196.dp).verticalScroll(rememberScrollState()),
        content = content,
    )
}

@Composable
private fun Choice(name: String, detail: String?, on: Boolean, onTap: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onTap).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) p.gold else p.rule))
        Spacer(Modifier.width(10.dp))
        Text(name, fontFamily = UiFont, fontSize = 13.sp, color = p.ink)
        detail?.let {
            Spacer(Modifier.width(7.dp))
            Text(it, fontFamily = UiFont, fontSize = 11.sp, color = p.inkSoft)
        }
    }
}
