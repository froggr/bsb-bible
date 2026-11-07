package ca.lampandlight

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Tap a word for its original; tap a verse number for everything about the verse. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudySheet(
    bible: Bible,
    verse: VerseId,
    wordAt: Int?,
    onGo: (VerseId) -> Unit,
    onReadFrom: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var tab by remember(verse, wordAt) { mutableIntStateOf(if (wordAt != null) 0 else 1) }

    val words = remember(verse) { bible.words(verse) }
    val refs = remember(verse) { bible.crossRefs(verse) }
    val comm = remember(verse) { bible.commentary(verse) }
    val row = remember(verse) { bible.chapter(verse.book, verse.chapter).firstOrNull { it.id == verse } }
    val notes = row?.footnotes ?: emptyList()
    val verseText = row?.text.orEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = p.sunk,
        dragHandle = { BottomSheetDefaults.DragHandle(color = p.rule) },
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 30.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    bible.reference(verse),
                    fontFamily = DisplayFont, fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                    color = p.ink, modifier = Modifier.weight(1f),
                )
                onReadFrom?.let { go ->
                    Surface(
                        onClick = { go(); onDismiss() },
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Transparent,
                        border = androidx.compose.foundation.BorderStroke(1.dp, p.gold),
                    ) {
                        Text(
                            "\u25b6  Read from here",
                            fontFamily = UiFont, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            color = p.gold,
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            val tabs = listOfNotNull(
                "Word".takeIf { words.isNotEmpty() },
                "Links".takeIf { refs.isNotEmpty() },
                "Notes".takeIf { notes.isNotEmpty() },
                "Commentary".takeIf { comm.isNotEmpty() },
            )
            if (tabs.isEmpty()) {
                Text(
                    "The study layer has not been downloaded yet.",
                    fontFamily = UiFont, fontSize = 13.sp, color = p.inkSoft,
                )
            } else {
                val safeTab = tab.coerceIn(0, tabs.lastIndex)
                Row {
                    tabs.forEachIndexed { i, name ->
                        val on = i == safeTab
                        Text(
                            name,
                            fontFamily = UiFont, fontSize = 12.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            letterSpacing = 0.8.sp,
                            color = if (on) p.ink else p.inkSoft,
                            modifier = Modifier
                                .clickable { tab = i }
                                .padding(end = 18.dp, top = 6.dp, bottom = 9.dp),
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.rule))
                Spacer(Modifier.height(16.dp))

                Box(Modifier.heightIn(max = 460.dp)) {
                    when (tabs[safeTab]) {
                        "Word" -> WordPane(words, wordAt)
                        "Links" -> LinkPane(bible, refs, onGo)
                        "Notes" -> NotePane(notes, verseText)
                        "Commentary" -> CommentaryPane(comm)
                    }
                }
            }
        }
    }
}

@Composable
private fun WordPane(words: List<Word>, focus: Int?) {
    val p = LocalPalette.current
    var picked by remember(focus) { mutableStateOf(focus ?: 0) }
    val real = remember(words) { words.filter { !it.untranslated } }
    val w = real.getOrNull(picked.coerceIn(0, (real.size - 1).coerceAtLeast(0)))

    Column {
        // Every word of the verse, so another can be chosen without closing the sheet.
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            modifier = Modifier.heightIn(max = 112.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(real.size) { i ->
                val on = i == picked
                Surface(
                    onClick = { picked = i },
                    shape = RoundedCornerShape(6.dp),
                    color = if (on) p.gold.copy(alpha = 0.22f) else Color.Transparent,
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (on) p.gold else p.rule),
                ) {
                    Text(
                        real[i].text,
                        fontFamily = ReadingFont, fontSize = 13.sp, color = p.ink,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        if (w == null) return@Column

        Text(
            w.original ?: "—",
            fontFamily = ReadingFont, fontSize = 31.sp, color = p.ink, lineHeight = 46.sp,
        )
        Text(
            w.translit ?: "",
            fontFamily = MonoFont, fontSize = 13.sp, color = p.indigo,
            modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
        )
        Field("English", w.text)
        Field("Strong's", w.strongsKey ?: "—", mono = true)
        Field("Language", w.lang ?: "—")
        Field("Grammar", w.parsing ?: "—")
    }
}

@Composable
private fun Field(label: String, value: String, mono: Boolean = false) {
    val p = LocalPalette.current
    Row(Modifier.padding(bottom = 7.dp)) {
        Text(
            label, fontFamily = UiFont, fontSize = 12.sp, color = p.inkSoft,
            modifier = Modifier.width(86.dp),
        )
        Text(
            value,
            fontFamily = if (mono) MonoFont else UiFont,
            fontSize = 12.sp,
            color = if (mono) p.gold else p.ink,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LinkPane(bible: Bible, refs: List<CrossRef>, onGo: (VerseId) -> Unit) {
    val p = LocalPalette.current
    val top = refs.firstOrNull()?.votes?.coerceAtLeast(1) ?: 1
    Column {
        Text(
            "Ranked by reader votes — strongest connection first.",
            fontFamily = UiFont, fontSize = 12.sp, color = p.inkSoft,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        LazyColumn {
            items(refs) { r ->
                Row(
                    Modifier.fillMaxWidth().clickable { onGo(r.dst) }.padding(vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        bible.reference(r.dst) + (r.dstEnd?.let { "–${it.verse}" } ?: ""),
                        fontFamily = UiFont, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        color = p.ink, modifier = Modifier.weight(1f),
                    )
                    // A small bar is easier to compare at a glance than the number alone.
                    Box(
                        Modifier
                            .width((34 * r.votes / top).coerceAtLeast(3).dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(p.gold.copy(alpha = 0.8f))
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${r.votes}", fontFamily = MonoFont, fontSize = 11.sp, color = p.inkSoft,
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.rule))
            }
        }
    }
}

@Composable
private fun NotePane(notes: List<Footnote>, verseText: String) {
    val p = LocalPalette.current
    val words = remember(verseText) { ReaderState.splitWords(verseText) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(
            "The translators' own notes: other readings, manuscript differences, and what the " +
                "original says literally.",
            fontFamily = UiFont, fontSize = 12.sp, color = p.inkSoft,
            modifier = Modifier.padding(bottom = 14.dp),
        )
        notes.forEachIndexed { k, n ->
            // Lettered and quoted the same way as on the page, so the two agree.
            val phrase = n.words?.let { r ->
                words.slice(r.first.coerceIn(words.indices)..r.last.coerceIn(words.indices))
                    .joinToString(" ").trim { !it.isLetterOrDigit() }
            }
            Row(Modifier.padding(bottom = 13.dp)) {
                Text(
                    noteLetter(k), fontFamily = UiFont, fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, color = p.indigo,
                    modifier = Modifier.width(16.dp).padding(top = 2.dp),
                )
                Text(
                    buildAnnotatedString {
                        if (phrase != null) {
                            withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(phrase) }
                            append("  ")
                        }
                        append(italicised(n.text))
                    },
                    fontFamily = UiFont, fontSize = 13.sp, lineHeight = 21.sp, color = p.ink,
                )
            }
        }
    }
}

@Composable
private fun CommentaryPane(comm: List<Commentary>) {
    val p = LocalPalette.current
    val names = mapOf(
        "jfb" to ("Jamieson, Fausset & Brown" to "1871"),
        "mhc" to ("Matthew Henry" to "1710"),
        "tyn" to ("Tyndale Study Notes" to "2022"),
    )
    Column(Modifier.verticalScroll(rememberScrollState())) {
        comm.forEach { c ->
            val (who, when_) = names[c.source] ?: (c.source to "")
            Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                Text(
                    who, fontFamily = UiFont, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp, color = p.gold,
                )
                Spacer(Modifier.width(7.dp))
                Text(when_, fontFamily = UiFont, fontSize = 11.sp, color = p.inkSoft)
            }
            Text(
                c.body,
                fontFamily = ReadingFont, fontWeight = FontWeight.Light,
                fontSize = 15.sp, lineHeight = 26.sp, color = p.ink,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

/** Book, then chapter. Two steps, each one screen, no scrolling through 1,189 chapters. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterPicker(
    bible: Bible,
    current: Int,
    onPick: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    var chosen by remember { mutableStateOf<Book?>(null) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = p.sunk,
        dragHandle = { BottomSheetDefaults.DragHandle(color = p.rule) },
    ) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 26.dp).fillMaxHeight(0.86f)) {
            val b = chosen
            Text(
                b?.name ?: "Go to",
                fontFamily = DisplayFont, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                color = p.ink, modifier = Modifier.padding(bottom = 14.dp),
            )
            if (b == null) {
                LazyColumn {
                    items(bible.books) { book ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { chosen = book }
                                .padding(vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                book.name,
                                fontFamily = ReadingFont, fontSize = 17.sp,
                                color = if (book.id == current) p.gold else p.ink,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${book.chapters}",
                                fontFamily = MonoFont, fontSize = 11.sp, color = p.inkSoft,
                            )
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(p.rule))
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(54.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    items(b.chapters) { i ->
                        Surface(
                            onClick = { onPick(b.id, i + 1) },
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent,
                            border = androidx.compose.foundation.BorderStroke(1.dp, p.rule),
                        ) {
                            Text(
                                "${i + 1}",
                                fontFamily = DisplayFont, fontSize = 17.sp, color = p.ink,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 13.dp).fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}
