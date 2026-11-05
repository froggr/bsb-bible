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
import kotlinx.coroutines.withContext

/**
 * Finding a verse.
 *
 * Searches run against the FTS index as the user types, debounced so a four-letter word does
 * not run four queries. Matches are emboldened in the result, which is how you tell at a
 * glance whether the verse is the one you meant.
 */
@Composable
fun SearchScreen(
    bible: Bible,
    onOpen: (VerseId) -> Unit,
    onClose: () -> Unit,
) {
    val p = LocalPalette.current
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Pair<VerseId, String>>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    // References resolve from the typed text with no query at all, so they appear as you
    // type: "1cor12" is a jump, not a search. Shown above the word matches, which take a
    // round trip to the index.
    val refs = remember(query) {
        if (Reference.looksLikeReference(query)) Reference.parse(query, bible.books).take(5)
        else emptyList()
    }

    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = emptyList(); searching = false; return@LaunchedEffect }
        searching = true
        delay(220)                               // let the typing settle
        results = withContext(Dispatchers.IO) {
            runCatching { bible.search(prepare(q), limit = 200) }.getOrDefault(emptyList())
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
            Box(
                Modifier
                    .weight(1f)
                    .background(p.sunk, RoundedCornerShape(10.dp))
                    .border(1.dp, p.rule, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (query.isEmpty()) Text(
                    "Search, or a reference like 1cor12",
                    fontFamily = UiFont, fontSize = 15.sp, color = p.inkSoft,
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
                        .clickable { onOpen(hit.id) }
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
            q.length < 2 -> Hint("Type a reference to jump: 1cor12, ps23, jn3:16.\n\nOr search for words. Put a phrase in quotes to find it whole: “living water”.")
            searching -> Hint("Searching…")
            // A resolved reference is already an answer; do not report the word search's miss.
            results.isEmpty() && refs.isNotEmpty() -> Unit
            results.isEmpty() -> Hint("Nothing found for “$q”.")
            else -> {
                Text(
                    if (results.size >= 200) "200+ verses" else "${results.size} verses",
                    fontFamily = UiFont, fontSize = 11.sp, color = p.inkSoft,
                    modifier = Modifier.padding(start = 18.dp, bottom = 8.dp),
                )
                LazyColumn(Modifier.fillMaxSize()) {
                    items(results, key = { it.first }) { (id, text) ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(id) }
                                .padding(horizontal = 18.dp, vertical = 13.dp),
                        ) {
                            Text(
                                bible.reference(id),
                                fontFamily = DisplayFont, fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold, color = p.gold,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                emphasise(text, q, p.gold),
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
