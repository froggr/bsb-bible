package ca.lampandlight

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Finding a verse.
 *
 * Searches run against the FTS index as the user types, debounced so a four-letter word does
 * not run four queries. Matches are emboldened in the result, which is how you tell at a
 * glance whether the verse is the one you meant.
 *
 * It can be said instead of typed. The microphone in the box listens for one breath, and
 * what it hears goes into the box as if it had been typed: a reference said aloud ("John
 * three sixteen") is offered as a place to go, and anything else is searched for. What the
 * recogniser hears is often a word out, so when no verse has every word the nearest are
 * shown, and said to be the nearest.
 */
@Composable
fun SearchScreen(
    bible: Bible,
    ears: Ears,
    /** The microphone is about to open, and whatever is being read aloud would be all it heard. */
    onListen: () -> Unit,
    /** Open at a verse; a reference typed as a range ("phil 2:3-4") also says where it ends. */
    onOpen: (VerseId, VerseId?) -> Unit,
    onClose: () -> Unit,
) {
    val p = LocalPalette.current
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Pair<VerseId, String>>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    /** No verse had every word, and [results] are the ones that came nearest. */
    var near by remember { mutableStateOf(false) }
    /** The words to embolden in the results: what was asked for, or what was found in its place. */
    var marks by remember { mutableStateOf("") }

    // References resolve from the typed text with no query at all, so they appear as you
    // type: "1cor12" is a jump, not a search. Shown above the word matches, which take a
    // round trip to the index. One that is said aloud is written in words, and read second.
    val refs = remember(query) {
        val typed = if (Reference.looksLikeReference(query)) Reference.parse(query, bible.books) else emptyList()
        typed.ifEmpty { Reference.spoken(query, bible.books) }.take(5)
    }

    // The microphone was asked for, and is to open as soon as it can.
    var wanted by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    var loadedHere by remember { mutableStateOf(false) }
    val mic = remember { intArrayOf(0) }
    val voice = rememberVoice(
        title = "Searching by voice",
        about = "Say a reference, or some words of the verse you are after, and it is looked for.",
    ) { wanted = true }
    val open = wanted && ears.listening

    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }

    LifecycleStartEffect(Unit) {
        shown = true
        onStopOrDispose { shown = false; wanted = false; ears.stop(mic[0]) }
    }
    // The recogniser is a few hundred megabytes of memory: brought in at the first tap of
    // the microphone, not for everybody who opens search to type, and let go on the way out.
    LaunchedEffect(wanted) {
        if (!wanted || ears.loaded) return@LaunchedEffect
        loadedHere = true
        ears.load()
        snapshotFlow { ears.loading }.first { !it }
        // It would not load: do not leave the mark waiting for it.
        if (!ears.loaded) wanted = false
    }
    DisposableEffect(Unit) { onDispose { if (loadedHere) ears.release() } }
    DisposableEffect(wanted, ears.loaded, shown) {
        if (!wanted || !ears.loaded || !shown) return@DisposableEffect onDispose { }
        onListen()
        // Not only the keyboard: left in the box, the caret sits in front of what is heard.
        focusManager.clearFocus()
        keyboard?.hide()
        query = ""
        val opened = ears.listen(
            onPartial = { query = asHeard(it) },
            // One breath is one search: the first pause ends it.
            onHeard = { query = asHeard(it); wanted = false },
        )
        mic[0] = opened
        if (opened == 0) wanted = false
        onDispose { ears.stop(opened) }
    }
    // The microphone closed by itself.
    LaunchedEffect(ears.listening) { if (!ears.listening && mic[0] != 0) wanted = false }
    // Nobody is speaking: it is not left open on a room.
    LaunchedEffect(open, ears.voiced) {
        if (open && !ears.voiced) { delay(QUIET_MS); wanted = false }
    }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = emptyList(); near = false; searching = false; return@LaunchedEffect }
        searching = true
        delay(220)                               // let the typing settle
        val (all, close) = withContext(Dispatchers.IO) {
            val all = runCatching { bible.search(prepare(q), limit = 200) }.getOrDefault(emptyList())
            // A reference has its answer already, and a phrase in quotes was asked for whole.
            if (all.isNotEmpty() || refs.isNotEmpty() || q.startsWith("\"")) all to null
            else all to runCatching { bible.nearest(terms(q)) }.getOrNull()
        }
        if (close != null && close.verses.isNotEmpty()) {
            results = close.verses; marks = close.terms.joinToString(" "); near = true
        } else {
            results = all; marks = q; near = false
        }
        searching = false
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(p.paper)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .background(p.sunk, RoundedCornerShape(10.dp))
                    .border(1.dp, if (open) p.gold.copy(alpha = 0.55f) else p.rule, RoundedCornerShape(10.dp))
                    .padding(start = 14.dp, end = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    if (query.isEmpty()) Text(
                        when {
                            open -> "Listening…"
                            wanted -> "Getting ready to listen…"
                            else -> "Search, or a reference like 1cor12"
                        },
                        // The field's own style and no more, or the box is a line taller empty
                        // than full and jumps as what is heard arrives.
                        style = TextStyle(fontFamily = UiFont, fontSize = 15.sp),
                        color = if (wanted) p.gold else p.inkSoft, maxLines = 1,
                    )
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = UiFont, fontSize = 15.sp, color = p.ink),
                        cursorBrush = SolidColor(p.gold),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                HearMark(
                    on = wanted, listening = open, voiced = ears.voiced,
                    label = if (wanted) "Stop listening" else "Search by voice",
                ) { if (wanted) wanted = false else voice.ask() }
            }
            Text(
                "Done",
                fontFamily = UiFont, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = p.gold,
                modifier = Modifier.clickable { onClose() }.padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
            )
        }

        val q = query.trim()

        if (refs.isNotEmpty()) {
            Text(
                "Go to",
                fontFamily = UiFont, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                color = p.inkSoft,
                modifier = Modifier.padding(start = 18.dp, bottom = 6.dp),
            )
            refs.forEach { hit ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(hit.id, hit.endId) }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        hit.label,
                        fontFamily = DisplayFont, fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold, color = p.ink,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "\u2192",
                        fontFamily = UiFont, fontSize = 16.sp, color = p.gold,
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.rule))
            }
            Spacer(Modifier.height(14.dp))
        }

        when {
            refs.isNotEmpty() && q.length < 2 -> Unit
            q.length < 2 && wanted -> Hint("Say where: “John three sixteen”, “Psalm twenty-three”.\n\nOr say some of the verse you are looking for.")
            q.length < 2 -> Hint("Type a reference to jump: 1cor12, ps23, jn3:16.\n\nOr search for words. Put a phrase in quotes to find it whole: “living water”.\n\nOr tap the microphone and say either.")
            // What is heard so far is still changing: nothing is said about it until it stops.
            searching && wanted -> Unit
            searching -> Hint("Searching…")
            // A resolved reference is already an answer; do not report the word search's miss.
            results.isEmpty() && refs.isNotEmpty() -> Unit
            results.isEmpty() -> Hint("Nothing found for “$q”.")
            else -> {
                Text(
                    if (near) "No verse has all of that. The nearest:"
                    else if (results.size >= 200) "200+ verses" else "${results.size} verses",
                    fontFamily = UiFont, fontSize = 11.sp, color = p.inkSoft,
                    modifier = Modifier.padding(start = 18.dp, bottom = 8.dp),
                )
                LazyColumn(Modifier.fillMaxSize()) {
                    items(results, key = { it.first }) { (id, text) ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(id, null) }
                                .padding(horizontal = 18.dp, vertical = 13.dp),
                        ) {
                            Text(
                                bible.reference(id),
                                fontFamily = DisplayFont, fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold, color = p.gold,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                emphasise(text, marks, p.gold),
                                fontFamily = ReadingFont, fontWeight = FontWeight.Light,
                                fontSize = 16.sp, lineHeight = 25.sp, color = p.ink,
                                maxLines = 3,
                            )
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(p.rule))
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    val p = LocalPalette.current
    Text(
        text,
        fontFamily = UiFont, fontSize = 13.sp, lineHeight = 21.sp, color = p.inkSoft,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
    )
}

/** How long the microphone stays open on nobody speaking. */
private const val QUIET_MS = 7_000L

/** What the recogniser wrote, as it would have been typed: it ends every breath with a full stop. */
private fun asHeard(text: String) = text.trim().trimEnd('.', ',', '?', '!', ';', ':').trim()

/** The words of what was typed, as the index has them: small letters, split wherever it splits. */
private fun terms(input: String): List<String> {
    val out = ArrayList<String>()
    val word = StringBuilder()
    for (c in input) {
        if (c.isLetterOrDigit()) word.append(c.lowercaseChar())
        else if (word.isNotEmpty()) { out += word.toString(); word.setLength(0) }
    }
    if (word.isNotEmpty()) out += word.toString()
    return out
}

/**
 * Turn what was typed into an FTS query.
 *
 * A quoted phrase is passed through; anything else becomes a prefix match on each word, so
 * "shep" finds "shepherd" while the user is still typing. Characters FTS treats as operators
 * are dropped rather than escaped, since a stray one is a typo, not an intent.
 */
private fun prepare(input: String): String {
    val q = input.trim()
    if (q.startsWith("\"") && q.endsWith("\"") && q.length > 2) return q
    val words = q.split(Regex("\\s+"))
        .map { it.filter { c -> c.isLetterOrDigit() || c == '\'' } }
        .filter { it.isNotEmpty() }
    if (words.isEmpty()) return "\"\""
    return words.joinToString(" ") { "$it*" }
}

/** Embolden the words that matched, so the eye lands on them. */
private fun emphasise(text: String, query: String, colour: Color) = buildAnnotatedString {
    val terms = query.trim('"').split(Regex("\\s+"))
        .map { it.filter { c -> c.isLetterOrDigit() } }
        .filter { it.length > 1 }
        .map { it.lowercase() }
    if (terms.isEmpty()) { append(text); return@buildAnnotatedString }

    var i = 0
    val lower = text.lowercase()
    while (i < text.length) {
        val hit = terms
            .mapNotNull { t -> lower.indexOf(t, i).takeIf { it >= 0 }?.let { it to t } }
            .minByOrNull { it.first }
        if (hit == null) { append(text.substring(i)); break }
        val (at, term) = hit
        append(text.substring(i, at))
        // Carry on to the end of the word, so a prefix match emboldens the whole word.
        var end = at + term.length
        while (end < text.length && text[end].isLetter()) end++
        withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = colour)) {
            append(text.substring(at, end))
        }
        i = end
    }
}
