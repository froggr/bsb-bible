package ca.lampandlight

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Going over: the questions already met while reading, put again as they fall due.
 *
 * Reading a chapter and answering its question shows what was just read. What makes it stay
 * is being asked again later, when it has begun to slip, and [Quiz.dueAt] decides when that
 * is. Nothing is put here that was not first met on the page, so this never becomes a quiz
 * on chapters nobody has read, and a round is short enough to finish standing up.
 */
object GoOver {
    /** How many questions make a round. More may be due; they wait for the next one. */
    const val ROUND = 8

    /** One question of a round: the panel, the chapter it is from, and its first verse. */
    data class Item(val row: Row.Ask, val place: String, val at: VerseId)

    data class Round(val items: List<Item>, val left: Int)

    suspend fun load(bible: Bible, questions: Questions): Round = withContext(Dispatchers.IO) {
        val due = questions.due()
        val items = due.take(ROUND).mapNotNull { key ->
            val q = questions.byKey(key) ?: return@mapNotNull null
            val verses = runCatching { bible.chapter(q.start.book, q.start.chapter) }.getOrNull()
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val asked = Quiz.again(
                q, verses.map { it.id }, questions.of(q.start.book, q.start.chapter), questions.lastAt(key),
            )
            val byId = verses.associateBy { it.id }
            Item(
                row = Row.Ask(asked, choicesOf(asked) { byId[it]?.text }, key = "q$key"),
                place = bible.reference(q.start).substringBeforeLast(':'),
                at = q.start,
            )
        }
        Round(items, (due.size - ROUND).coerceAtLeast(0))
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
}

@Composable
fun GoOverScreen(
    bible: Bible,
    questions: Questions,
    /** Open the chapter a question is from, at its answer. */
    onRead: (VerseId) -> Unit,
    onClose: () -> Unit,
) {
    val p = LocalPalette.current
    var round by remember { mutableStateOf<GoOver.Round?>(null) }
    var turn by remember { mutableIntStateOf(0) }           // which round this is
    var at by remember { mutableIntStateOf(0) }
    var chosen by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var next by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(turn) {
        at = 0; chosen = emptyMap()
        round = GoOver.load(bible, questions)
        next = withContext(Dispatchers.IO) { questions.nextDue() }
    }

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
                "GOING OVER", fontFamily = UiFont, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp, color = p.gold,
            )
            if (items != null && at < items.size) Text(
                "  ${at + 1} of ${items.size}", fontFamily = UiFont, fontSize = 11.sp, color = p.inkSoft,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Close", fontFamily = UiFont, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = p.gold, modifier = Modifier.clickable { onClose() }.padding(8.dp),
            )
        }
        when {
            items == null -> Unit
            items.isEmpty() -> Stillness(
                title = "Nothing to go over",
                body = "A question you miss while reading comes back here a few minutes later. " +
                    "One you knew comes back after a week, and then less and less often." +
                    (next?.let { " The next one is due ${GoOver.until(it)}." } ?: ""),
                action = "Back to reading", onAction = onClose,
            )
            at >= items.size -> {
                val knew = items.indices.count { chosen[it] == items[it].row.asked.correct }
                val missed = items.size - knew
                val more = round?.left ?: 0
                Stillness(
                    title = "That is all for now",
                    body = when {
                        missed == 0 && items.size == 1 -> "You knew it."
                        missed == 0 -> "You knew all ${items.size}."
                        else -> "You knew $knew of ${items.size}. " +
                            (if (missed == 1) "The other one comes" else "The other $missed come") +
                            " back in a few minutes."
                    } + when {
                        more > 0 -> if (more == 1) " One more is waiting." else " $more more are waiting."
                        missed == 0 && next != null -> " The next one is due ${GoOver.until(next!!)}."
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
                val item = items[i]
                val mine = chosen[i]
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
                    AskRow(
                        row = item.row, chosen = mine,
                        waiting = false, waitFrom = 0L, waitFor = 1L,
                        missed = "It will come back in a few minutes.",
                        onChoose = { option ->
                            if (i !in chosen) {
                                chosen = chosen + (i to option)
                                questions.record(
                                    item.row.asked.question.key,
                                    if (option == item.row.asked.correct) Quiz.RIGHT else Quiz.WRONG,
                                )
                            }
                        },
                    )
                    if (mine != null) Row(
                        Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 28.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Read ${item.place}", fontFamily = UiFont, fontSize = 13.sp,
                            fontWeight = FontWeight.Medium, color = p.inkSoft,
                            modifier = Modifier.clickable { onRead(item.at) }.padding(vertical = 8.dp),
                        )
                        Go(if (i == items.lastIndex) "Finish" else "Next") { at = i + 1 }
                    }
                }
            }
        }
    }
}

/** The one thing to press. */
@Composable
private fun Go(label: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Surface(onClick = onClick, shape = RoundedCornerShape(9.dp), color = p.gold) {
        Text(
            label, fontFamily = UiFont, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = p.paper, modifier = Modifier.padding(horizontal = 26.dp, vertical = 11.dp),
        )
    }
}

/** A page with nothing to do on it: a line, a sentence or two, and the way out. */
@Composable
private fun Stillness(title: String, body: String, action: String, onAction: () -> Unit) {
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
        Spacer(Modifier.height(26.dp))
        Go(action, onAction)
        Spacer(Modifier.weight(1.2f))
    }
}
