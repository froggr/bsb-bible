package ca.lampandlight

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Going over: what has been read and what is being learned, put again as it falls due.
 *
 * Reading a chapter and answering its question shows what was just read. What makes it stay
 * is being asked again later, when it has begun to slip, and [Quiz.dueAt] decides when that
 * is. Nothing is put here that was not first met on the page or chosen by the reader, so
 * this never becomes a quiz on chapters nobody has read, and a round is short enough to
 * finish standing up. Verses being learned by heart come first in it: they are the ones the
 * reader asked for. Then the chapters to call to mind, which take a breath each, and then
 * the questions.
 */
object GoOver {
    /** How many things make a round. More may be due; they wait for the next one. */
    const val ROUND = 8
    /** How many of a round may be verses to say. Saying one takes longer than a question. */
    const val VERSES = 3
    /** How many may be chapters to call to mind. */
    const val CHAPTERS = 3
    /** How many verses the voice will go through in one listening. */
    const val LISTEN = 12

    sealed interface Item {
        val place: String

        /** A question: the panel, the chapter it is from, and its first verse. */
        data class Ask(val row: Row.Ask, override val place: String, val at: VerseId) : Item

        /** A verse to say: its reference as written and as [spoken], and its text in phrases. */
        data class Verse(
            val heart: Heart, override val place: String, val spoken: String,
            val text: String, val phrases: List<String>,
            /** The verse after the last, where the chapter has one and there is room for it. */
            val next: VerseId? = null,
        ) : Item {
            val level: Int get() = heart.level
        }

        /** A chapter to call to mind: what is known of it, and its headings in order. */
        data class Chapter(val of: Place, override val place: String, val headings: List<String>) : Item
    }

    /** What is due now: verses to say, questions, and chapters to call to mind. */
    data class Waiting(val verses: Int, val asks: Int, val chapters: Int)

    data class Round(val items: List<Item>, val left: Int)

    /** The round due now, or with [only] the one verse asked for whether it is due or not. */
    suspend fun load(bible: Bible, questions: Questions, only: VerseId? = null): Round =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val hearts = questions.hearts().let { all ->
                if (only != null) all.filter { it.verse == only }
                else all.filter { it.due <= now }.sortedBy { it.due }
            }
            val verses = hearts.take(VERSES).mapNotNull { verse(bible, it) }
            val places = if (only != null) emptyList() else Places.waiting(questions.places().values, now)
            val chapters = places.take(CHAPTERS).mapNotNull { chapter(bible, it) }
            val due = if (only != null) emptyList() else questions.due(now)
            val room = ROUND - verses.size - chapters.size
            val asks = due.take(room).mapNotNull { key ->
                val q = questions.byKey(key) ?: return@mapNotNull null
                val chapter = runCatching { bible.chapter(q.start.book, q.start.chapter) }.getOrNull()
                    ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val asked = Quiz.again(
                    q, chapter.map { it.id }, questions.of(q.start.book, q.start.chapter), questions.lastAt(key),
                )
                val byId = chapter.associateBy { it.id }
                Item.Ask(
                    row = Row.Ask(asked, choicesOf(asked) { byId[it]?.text }, key = "q$key"),
                    place = bible.reference(q.start).substringBeforeLast(':'),
                    at = q.start,
                )
            }
            Round(
                verses + chapters + asks,
                (hearts.size - VERSES).coerceAtLeast(0) + (places.size - CHAPTERS).coerceAtLeast(0) +
                    (due.size - room).coerceAtLeast(0),
            )
        }

    /** Every verse being learned, the longest due first: the list, and what the voice goes through. */
    suspend fun verses(bible: Bible, questions: Questions): List<Item.Verse> = withContext(Dispatchers.IO) {
        questions.hearts().sortedBy { it.due }.mapNotNull { verse(bible, it) }
    }

    /** How much is due now. */
    fun waiting(questions: Questions, now: Long = System.currentTimeMillis()): Waiting = Waiting(
        verses = questions.hearts().filter { it.due <= now }.sumOf { it.span },
        asks = questions.due(now).size,
        chapters = Places.waiting(questions.places().values, now).size,
    )

    /** When the next thing falls due that is not due yet. Null when nothing is on its way. */
    fun nextDue(questions: Questions, now: Long = System.currentTimeMillis()): Long? = listOfNotNull(
        questions.nextDue(now),
        questions.hearts().map { it.due }.filter { it > now }.minOrNull(),
        questions.places().values.map { it.due }.filter { it > now }.minOrNull(),
    ).minOrNull()

    private fun chapter(bible: Bible, place: Place): Item.Chapter? {
        val book = bible.books.getOrNull(place.book - 1) ?: return null
        val headings = Places.outline(bible.headings(place.book, place.number)).takeIf { it.isNotEmpty() }
            ?: return null
        return Item.Chapter(place, "${book.name} ${place.number}", headings)
    }

    private fun verse(bible: Bible, h: Heart): Item.Verse? {
        val chapter = runCatching { bible.chapter(h.verse.book, h.verse.chapter) }.getOrNull() ?: return null
        val text = chapter.filter { it.id in h.verse..h.last }.joinToString(" ") { it.text.trim() }
            .takeIf { it.isNotBlank() } ?: return null
        return Item.Verse(
            heart = h,
            place = bible.reference(h.verse) + if (h.last > h.verse) "\u2013${h.last.verse}" else "",
            spoken = ByHeart.spoken(bible.books[h.verse.book - 1].name, h.verse, h.last),
            text = text,
            // The voice keeps a place for each phrase, and there are only so many places.
            phrases = ByHeart.phrases(text).take(ByHeart.WHOLE - 1),
            next = (h.last + 1).takeIf { n -> h.span < ByHeart.SPAN && chapter.any { it.id == n } },
        )
    }

    /** "in a little while", "tomorrow", "in 6 days": how long until [at], as a person says it. */
    fun until(at: Long, now: Long = System.currentTimeMillis()): String {
        val hours = (at - now) / 3_600_000.0
        return when {
            hours < 1.5 -> "in a little while"
            hours < 18 -> "later today"
            hours < 42 -> "tomorrow"
            else -> "in ${Math.round(hours / 24)} days"
        }
    }

    /** What to say of the verses, questions and chapters waiting. Null when nothing is. */
    fun count(verses: Int, asks: Int, chapters: Int = 0): String? {
        fun n(count: Int, one: String, many: String) =
            if (count == 1) "one $one" else "$count $many"
        val parts = listOfNotNull(
            n(verses, "verse", "verses").takeIf { verses > 0 }?.let { "$it to say" },
            n(asks, "question", "questions").takeIf { asks > 0 },
            n(chapters, "chapter", "chapters").takeIf { chapters > 0 },
        )
        return when {
            parts.isEmpty() -> null
            parts.size > 1 -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
            asks > 0 -> parts[0] + " from what you have read"
            chapters > 0 -> parts[0] + " to call to mind"
            else -> parts[0]
        }?.replaceFirstChar { it.uppercase() }
    }
}

private const val TURN = 0
private const val LIST = 1
private const val HEARING = 2

@Composable
fun GoOverScreen(
    bible: Bible,
    questions: Questions,
    narrator: Narrator,
    /** A verse to go straight to: the one just taken up from the page. */
    start: VerseId? = null,
    /** Open the chapter a question is from, at its answer. */
    onRead: (VerseId) -> Unit,
    onClose: () -> Unit,
) {
    val p = LocalPalette.current
    var page by remember { mutableIntStateOf(TURN) }
    var round by remember { mutableStateOf<GoOver.Round?>(null) }
    var turn by remember { mutableIntStateOf(0) }           // which round this is
    var only by remember { mutableStateOf(start) }          // one verse, asked for by itself
    var direct by remember { mutableStateOf(start != null) } // and asked for from the page
    var at by remember { mutableIntStateOf(0) }
    var chosen by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var knew by remember { mutableStateOf<Map<Int, Boolean>>(emptyMap()) }
    var lifted by remember { mutableStateOf<Map<Int, Set<Int>>>(emptyMap()) }
    var back by remember { mutableStateOf("tomorrow") }
    var next by remember { mutableStateOf<Long?>(null) }
    var verses by remember { mutableStateOf<List<GoOver.Item.Verse>?>(null) }

    LaunchedEffect(turn, only) {
        at = 0; chosen = emptyMap(); knew = emptyMap(); lifted = emptyMap()
        round = GoOver.load(bible, questions, only)
        next = withContext(Dispatchers.IO) { GoOver.nextDue(questions) }
    }
    LaunchedEffect(page, turn) {
        if (page != TURN) verses = GoOver.verses(bible, questions)
    }
    // A verse being read here stops when the page is left; the Bible's own reading does not.
    DisposableEffect(Unit) { onDispose { if (narrator.alone) narrator.stop() } }
    // Back goes up one: from the voice to the list, from one verse to the list it was
    // picked from, from the list to the round. A verse come to from the page goes back there.
    fun up() {
        if (narrator.alone) narrator.stop()
        when {
            page == HEARING -> page = LIST
            page == LIST -> { page = TURN; round = null; turn++ }
            else -> { only = null; round = null; page = LIST }
        }
    }
    androidx.activity.compose.BackHandler(enabled = page != TURN || (only != null && !direct)) { up() }

    val items = round?.items
    Column(
        // The Bible is still composed underneath; touches must not reach it.
        Modifier.fillMaxSize().background(p.paper).pointerInput(Unit) { }
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 18.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (page == TURN) "GOING OVER" else "BY HEART",
                fontFamily = UiFont, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp, color = p.gold,
            )
            if (page == TURN && items != null && at < items.size && items.size > 1) Text(
                "  ${at + 1} of ${items.size}", fontFamily = UiFont, fontSize = 11.sp, color = p.inkSoft,
            )
            Spacer(Modifier.weight(1f))
            // The other half of this place is always one tap away, and says its own name.
            Text(
                when { page == HEARING -> "Your verses"; page == LIST -> "Going over"; else -> "By heart" },
                fontFamily = UiFont, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = p.inkSoft,
                modifier = Modifier.clickable {
                    if (narrator.alone) narrator.stop()
                    when (page) {
                        HEARING -> page = LIST
                        LIST -> { direct = false; only = null; round = null; turn++; page = TURN }
                        else -> { direct = false; only = null; page = LIST }
                    }
                }.padding(8.dp),
            )
            Text(
                "Close", fontFamily = UiFont, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = p.gold, modifier = Modifier.clickable { onClose() }.padding(8.dp),
            )
        }
        when {
            page == LIST -> HeartList(
                verses = verses,
                canListen = narrator.modelReady(),
                onListen = { page = HEARING },
                onSay = { v -> direct = false; round = null; only = v; page = TURN },
                onRemove = { v ->
                    questions.unlearn(v)
                    verses = verses?.filter { it.heart.verse != v }
                },
                onStretch = { v, upto -> questions.stretch(v, upto); turn++ },
            )
            page == HEARING -> HeartListen(verses = verses?.take(GoOver.LISTEN), narrator = narrator)
            items == null -> Unit
            items.isEmpty() -> Stillness(
                title = "Nothing to go over",
                body = "A question you miss while reading comes back here a few minutes later. " +
                    "One you knew comes back after a week, and then less and less often. " +
                    "So do the chapters you have read, and any verse you are learning by heart." +
                    (next?.let { " The next is due ${GoOver.until(it)}." } ?: ""),
                action = "Back to reading", onAction = onClose,
            )
            at >= items.size -> {
                val had = items.indices.count { knew[it] == true }
                val missed = items.size - had
                val more = round?.left ?: 0
                Stillness(
                    title = "That is all for now",
                    body = when {
                        missed == 0 && items.size == 1 -> "You knew it."
                        missed == 0 -> "You knew all ${items.size}."
                        else -> "You knew $had of ${items.size}. " +
                            (if (missed == 1) "The other one comes" else "The other $missed come") +
                            " back in a few minutes."
                    } + when {
                        more == 1 -> " One more is waiting."
                        more > 1 -> " $more more are waiting."
                        else -> ""
                    },
                    action = if (more > 0) "Go on" else "Back to reading",
                    onAction = { if (more > 0) turn++ else onClose() },
                )
            }
            else -> AnimatedContent(
                targetState = at,
                transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(140)) },
                label = "goOver",
            ) { i ->
                // The round may be replaced while the last card is still fading out.
                val item = items.getOrNull(i) ?: return@AnimatedContent
                val settled = i in knew
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp),
                ) {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        item.place, fontFamily = DisplayFont, fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold, color = p.ink,
                        modifier = Modifier.padding(start = 2.dp, bottom = 10.dp),
                    )
                    when (item) {
                        is GoOver.Item.Ask -> AskRow(
                            row = item.row, chosen = chosen[i],
                            waiting = false, waitFrom = 0L, waitFor = 1L,
                            missed = "It will come back in a few minutes.",
                            onChoose = { option ->
                                if (!settled) {
                                    val right = option == item.row.asked.correct
                                    chosen = chosen + (i to option)
                                    knew = knew + (i to right)
                                    questions.record(
                                        item.row.asked.question.key, if (right) Quiz.RIGHT else Quiz.WRONG,
                                    )
                                }
                            },
                        )
                        is GoOver.Item.Verse -> HeartCard(
                            item = item, slot = i, narrator = narrator,
                            said = knew[i]?.let { if (it) Quiz.RIGHT else Quiz.WRONG },
                            back = back,
                            onSaid = { outcome ->
                                if (!settled) {
                                    val now = System.currentTimeMillis()
                                    questions.said(item.heart.verse, outcome, now)
                                    back = GoOver.until(
                                        ByHeart.dueAt(item.heart.added, item.heart.history + Past(outcome, now)), now,
                                    )
                                    knew = knew + (i to (outcome == Quiz.RIGHT))
                                }
                            },
                        )
                        is GoOver.Item.Chapter -> RecallPanel(
                            label = "From memory",
                            question = "What is this chapter about?",
                            headings = item.headings,
                            lifted = lifted[i] ?: emptySet(),
                            outcome = knew[i]?.let { if (it) Quiz.RIGHT else Quiz.WRONG },
                            after = if (knew[i] == true) "It comes back $back."
                                    else "It will come back in a few minutes.",
                            onLift = { line ->
                                lifted = lifted + (i to (
                                    if (line == null) item.headings.indices.toSet()
                                    else (lifted[i] ?: emptySet()) + line
                                ))
                            },
                            onSaid = { outcome ->
                                if (!settled) {
                                    val now = System.currentTimeMillis()
                                    questions.recalled(item.of.chapter, outcome, now)
                                    back = GoOver.until(
                                        Places.dueAt(item.of.readAt, item.of.history + Past(outcome, now)), now,
                                    )
                                    knew = knew + (i to (outcome == Quiz.RIGHT))
                                }
                            },
                        )
                    }
                    if (settled) Row(
                        Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 28.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Read ${item.place.substringBeforeLast(':')}", maxLines = 1, fontFamily = UiFont, fontSize = 13.sp,
                            fontWeight = FontWeight.Medium, color = p.inkSoft,
                            modifier = Modifier.clickable {
                                onRead(when (item) {
                                    is GoOver.Item.Ask -> item.at
                                    is GoOver.Item.Verse -> item.heart.verse
                                    // The head of the chapter, not its first verse.
                                    is GoOver.Item.Chapter -> item.of.chapter * 1_000
                                })
                            }.padding(vertical = 8.dp),
                        )
                        Go(
                            when { only != null -> "Done"; i == items.lastIndex -> "Finish"; else -> "Next" },
                        ) {
                            when {
                                direct -> onClose()
                                only != null -> { only = null; round = null; page = LIST }
                                else -> at = i + 1
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The verses being learned: what each is, when it comes back, and the voice for all of them.
 */
@Composable
private fun HeartList(
    verses: List<GoOver.Item.Verse>?,
    canListen: Boolean,
    onListen: () -> Unit,
    onSay: (VerseId) -> Unit,
    onRemove: (VerseId) -> Unit,
    /** Learn it through to this verse, or with null back to the one. */
    onStretch: (VerseId, VerseId?) -> Unit,
) {
    val p = LocalPalette.current
    if (verses == null) return
    if (verses.isEmpty()) {
        Stillness(
            title = "No verses yet",
            body = "While reading, press and hold a verse and choose Learn by heart. " +
                "It will be here, and in Go over on the days it is due.",
            action = null, onAction = {},
        )
        return
    }
    val now = remember(verses) { System.currentTimeMillis() }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 18.dp)) {
                Text(
                    verses.sumOf { it.heart.span }.let { if (it == 1) "One verse" else "$it verses" },
                    fontFamily = DisplayFont, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = p.ink,
                )
                if (canListen) {
                    Spacer(Modifier.height(16.dp))
                    Listen(
                        playing = false,
                        label = if (verses.size == 1) "Listen, and say it with the voice"
                        else "Listen, and say them with the voice",
                        onClick = onListen,
                    )
                    Text(
                        "It leaves room for you to speak. It works with the screen off.",
                        fontFamily = UiFont, fontSize = 12.sp, color = p.inkSoft,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.rule))
        }
        itemsIndexed(verses, key = { _, v -> v.heart.verse }) { _, v ->
            Column(
                Modifier.fillMaxWidth().clickable { onSay(v.heart.verse) }
                    .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        v.place, fontFamily = DisplayFont, fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold, color = p.ink, modifier = Modifier.weight(1f),
                    )
                    Text(
                        when {
                            v.level == ByHeart.NEW -> "New"
                            v.heart.due <= now -> "Ready to say"
                            else -> "Comes back ${GoOver.until(v.heart.due, now)}"
                        }.uppercase(),
                        fontFamily = UiFont, fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                        color = if (v.heart.due <= now) p.gold else p.inkSoft,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Text(
                    v.text, fontFamily = ReadingFont, fontWeight = FontWeight.Light,
                    fontStyle = FontStyle.Italic, fontSize = 14.sp, lineHeight = 22.sp,
                    color = p.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp, end = 8.dp),
                )
                // What can be done to it, small and at the foot: the row itself is the way in.
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    @Composable fun Small(label: String, onClick: () -> Unit) = Text(
                        label, fontFamily = UiFont, fontSize = 12.sp, color = p.inkSoft,
                        modifier = Modifier.clickable { onClick() }.padding(end = 18.dp, top = 8.dp, bottom = 8.dp),
                    )
                    v.next?.let { n -> Small("Add verse ${n.verse}") { onStretch(v.heart.verse, n) } }
                    if (v.heart.last > v.heart.verse) Small("Drop verse ${v.heart.last.verse}") {
                        onStretch(v.heart.verse, (v.heart.last - 1).takeIf { it > v.heart.verse })
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Remove", fontFamily = UiFont, fontSize = 12.sp, color = p.inkSoft,
                        modifier = Modifier.clickable { onRemove(v.heart.verse) }.padding(8.dp),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.rule))
        }
    }
}

/**
 * The voice going through the verses by itself, for hands that are busy.
 *
 * Each verse is done as it would be on its card: a new one is read and then said back a
 * line at a time, one that is known is waited for. Nothing is recorded: nobody is there to
 * say whether they had it. The page only follows, for whoever is looking.
 */
@Composable
private fun HeartListen(verses: List<GoOver.Item.Verse>?, narrator: Narrator) {
    val p = LocalPalette.current
    if (verses.isNullOrEmpty()) return
    val script = remember(verses) {
        verses.flatMapIndexed { i, v -> ByHeart.script(i, v.spoken, v.phrases, v.level) }
    }
    val mine = narrator.alone && narrator.speaking
    val index = if (mine) narrator.verseIndex - ByHeart.base(0) else -1
    var slot by remember { mutableIntStateOf(0) }
    var seen by remember { mutableStateOf(emptySet<Int>()) }
    var begun by remember { mutableStateOf(false) }
    val hush = mine && narrator.hushed
    val at = if (index >= 0) index % 100 else -1

    LaunchedEffect(Unit) { narrator.speak(script, alone = true); begun = true }
    LaunchedEffect(index, hush) {
        if (index < 0) return@LaunchedEffect
        val now = index / 100
        if (now != slot) { slot = now; seen = emptySet() }
        if (hush || at >= ByHeart.NAME) return@LaunchedEffect
        seen = if (at == ByHeart.WHOLE) verses[now].phrases.indices.toSet() else seen + at
    }

    val v = verses.getOrNull(slot) ?: return
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(18.dp))
            Text(
                v.place, fontFamily = DisplayFont, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                color = p.ink, modifier = Modifier.padding(start = 2.dp, bottom = 10.dp),
            )
            VersePanel(
                phrases = v.phrases, level = v.level, seen = seen,
                at = at.takeIf { it in 0 until ByHeart.NAME } ?: -1, hush = hush, onTap = null,
            )
            Spacer(Modifier.height(12.dp))
            Cue(
                when {
                    !mine && begun && !narrator.preparing -> "That is all of them."
                    hush && v.level == ByHeart.NEW -> "Your turn. Say it back."
                    hush -> "Your turn. Say it."
                    else -> "Listen."
                },
                yours = hush,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 2.dp, top = 12.dp, bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Listen(playing = mine, preparing = mine && narrator.preparing, label = "Again") {
                if (mine) narrator.stop() else { slot = 0; seen = emptySet(); narrator.speak(script, alone = true) }
            }
            Spacer(Modifier.weight(1f))
            if (verses.size > 1) Text(
                "${slot + 1} of ${verses.size}", fontFamily = UiFont, fontSize = 12.sp, color = p.inkSoft,
            )
        }
    }
}

/** The one thing to press. [quiet] is for a first step on the reading page: an outline, not a block. */
@Composable
internal fun Go(label: String, quiet: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    Surface(
        onClick = onClick, shape = RoundedCornerShape(9.dp),
        color = if (quiet) Color.Transparent else p.gold,
        border = if (quiet) BorderStroke(1.dp, p.gold.copy(alpha = 0.7f)) else null,
    ) {
        Text(
            label, fontFamily = UiFont, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = if (quiet) p.gold else p.paper,
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 11.dp),
        )
    }
}

/** A page with nothing to do on it: a line, a sentence or two, and the way out. */
@Composable
private fun Stillness(title: String, body: String, action: String?, onAction: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        Spacer(Modifier.weight(0.8f))
        Text(
            title, fontFamily = DisplayFont, fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
            color = p.ink,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            body, fontFamily = ReadingFont, fontWeight = FontWeight.Light,
            fontSize = 17.sp, lineHeight = 26.sp, color = p.inkSoft,
        )
        if (action != null) {
            Spacer(Modifier.height(26.dp))
            Go(action, onClick = onAction)
        }
        Spacer(Modifier.weight(1.2f))
    }
}
