package ca.lampandlight

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The reader's own books.
 *
 * A shelf, not a file list: covers, with the one being read set out first and how far
 * through it. Books come in by pointing at a folder once — two hundred of them is one
 * gesture — and what would not come in is said plainly, by name, with the reason.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfScreen(
    library: Library,
    prefs: Prefs,
    /** The reading pace, so "left" is in the reader's own minutes. */
    rate: Float,
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
) {
    val p = LocalPalette.current
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var acting by remember { mutableStateOf<String?>(null) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) library.add(uris)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) library.addFolder(uri)
    }
    // Some file providers call an epub a plain stream of bytes; offering only the proper
    // type would leave those books greyed out with no way to say why.
    val chooseFiles = { pickFiles.launch(arrayOf("application/epub+zip", "application/octet-stream")) }
    val chooseFolder = { pickFolder.launch(null) }

    val volumes = library.volumes
    val order = prefs.shelfOrder
    val shown = remember(volumes, order, query) {
        val q = query.trim().lowercase()
        val some = if (q.isEmpty()) volumes
            else volumes.filter { q in it.title.lowercase() || q in it.author.lowercase() }
        when (order) {
            "title" -> some.sortedBy { Library.sortKey(it.title) }
            "author" -> some.sortedWith(compareBy({ surname(it.author) }, { Library.sortKey(it.title) }))
            else -> some.sortedWith(compareByDescending<Library.Volume> { it.opened }.thenByDescending { it.added })
        }
    }
    // The book in hand: the one opened last that is not yet finished.
    val current = remember(volumes) {
        volumes.filter { it.started && it.progress < 0.995f }.maxByOrNull { it.opened }
    }

    // A clean import needs no reading; anything that failed stays until it has been seen.
    val report = library.report
    LaunchedEffect(report) {
        if (report == null) return@LaunchedEffect
        if (report.nothing) library.report = null
        else if (report.failed.isEmpty()) { delay(5000); library.report = null }
    }

    Column(
        Modifier.fillMaxSize().background(p.paper)
            // The Bible is still laid out underneath; nothing here may fall through to it.
            .pointerInput(Unit) { }
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Books",
                fontFamily = DisplayFont, fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
                color = p.ink, modifier = Modifier.weight(1f),
            )
            if (volumes.isNotEmpty() && !library.busy) Text(
                "Add", fontFamily = UiFont, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = p.gold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { adding = true }.padding(10.dp),
            )
            Text(
                "Close", fontFamily = UiFont, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = p.gold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClose() }.padding(10.dp),
            )
        }

        if (volumes.isEmpty() && !library.busy && report == null) {
            EmptyShelf(chooseFolder, chooseFiles)
            return@Column
        }

        // Sorting the shelf another way, or searching it, starts it from the top. Left where
        // it was, the grid holds on to whatever cover was first in view and the book in
        // hand comes back in above the screen, out of sight.
        val grid = rememberLazyGridState()
        LaunchedEffect(order, query) { grid.scrollToItem(0) }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(100.dp),
            state = grid,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            library.adding?.let { a ->
                item("adding", span = { GridItemSpan(maxLineSpan) }) { AddingCard(a, library::cancel) }
            }
            if (report != null && !report.nothing) {
                item("report", span = { GridItemSpan(maxLineSpan) }) {
                    ReportCard(report) { library.report = null }
                }
            }
            if (current != null && query.isBlank()) {
                item("current", span = { GridItemSpan(maxLineSpan) }) {
                    CurrentCard(current, library, rate) { onOpen(current.id) }
                }
            }
            if (volumes.isNotEmpty()) item("order", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OrderTab("Recent", order == "recent") { prefs.shelfOrder = "recent"; prefs.save() }
                        Spacer(Modifier.width(20.dp))
                        OrderTab("Title", order == "title") { prefs.shelfOrder = "title"; prefs.save() }
                        Spacer(Modifier.width(20.dp))
                        OrderTab("Author", order == "author") { prefs.shelfOrder = "author"; prefs.save() }
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (query.isNotBlank()) "${shown.size} of ${volumes.size}"
                            else if (volumes.size == 1) "1 book" else "${volumes.size} books",
                            fontFamily = UiFont, fontSize = 11.5.sp, letterSpacing = 0.3.sp, color = p.inkSoft,
                        )
                    }
                    // A dozen covers can be taken in at a glance. Two hundred cannot.
                    if (volumes.size >= 9) {
                        Spacer(Modifier.height(12.dp))
                        val focus = LocalFocusManager.current
                        Row(
                            Modifier.fillMaxWidth()
                                .background(p.sunk, RoundedCornerShape(10.dp))
                                .border(1.dp, p.rule, RoundedCornerShape(10.dp))
                                .padding(start = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.weight(1f).padding(vertical = 11.dp)) {
                                if (query.isEmpty()) Text(
                                    "Find a book or an author",
                                    fontFamily = UiFont, fontSize = 14.sp, color = p.inkSoft.copy(alpha = 0.75f),
                                )
                                BasicTextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    singleLine = true,
                                    textStyle = TextStyle(fontFamily = UiFont, fontSize = 14.sp, color = p.ink),
                                    cursorBrush = SolidColor(p.gold),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            // One tap back to the whole shelf.
                            if (query.isNotEmpty()) Text(
                                "Clear",
                                fontFamily = UiFont, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                                color = p.gold,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                    .clickable { query = ""; focus.clearFocus() }
                                    .padding(horizontal = 12.dp, vertical = 11.dp),
                            )
                            else Spacer(Modifier.width(14.dp))
                        }
                    }
                }
            }
            if (shown.isEmpty() && query.isNotBlank()) item("none", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "No book or author here matches “${query.trim()}”.",
                    fontFamily = UiFont, fontSize = 13.sp, color = p.inkSoft,
                    modifier = Modifier.padding(vertical = 18.dp),
                )
            }
            items(shown, key = { it.id }) { v ->
                Column(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .combinedClickable(onClick = { onOpen(v.id) }, onLongClick = { acting = v.id }),
                ) {
                    Cover(v, library, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(7.dp))
                    // How far through, under the cover like a ribbon. Always the same
                    // height, so the titles along a row sit on one line.
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (v.started) p.rule else Color.Transparent)) {
                        if (v.started) Box(
                            Modifier.fillMaxWidth(v.progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(p.gold)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        v.title,
                        fontFamily = UiFont, fontSize = 12.5.sp, lineHeight = 16.sp,
                        fontWeight = FontWeight.Medium, color = p.ink,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    if (v.author.isNotBlank()) Text(
                        v.author,
                        fontFamily = UiFont, fontSize = 11.sp, lineHeight = 15.sp, color = p.inkSoft,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
        }
    }

    if (adding) AddSheet(
        onFolder = { adding = false; chooseFolder() },
        onFiles = { adding = false; chooseFiles() },
        onDismiss = { adding = false },
    )

    acting?.let { id ->
        val v = library.volume(id)
        if (v == null) acting = null
        else VolumeSheet(
            v = v, library = library, rate = rate,
            onOpen = { acting = null; onOpen(id) },
            onRemove = { acting = null; library.remove(id) },
            onDismiss = { acting = null },
        )
    }
}

/** "Heward-Mills" for "Dag Heward-Mills", so authors file the way a shop files them. */
private fun surname(author: String): String {
    val first = author.split(',', ';', '&').first().replace(" and .*".toRegex(), "").trim()
    // Nameless books go last, not first.
    if (first.isEmpty()) return "￿"
    return first.substringAfterLast(' ').lowercase() + " " + first.lowercase()
}

@Composable
private fun EmptyShelf(onFolder: () -> Unit, onFiles: () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxSize().padding(horizontal = 34.dp).padding(bottom = 70.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Three spines, drawn: the shelf before there is anything on it.
        Row(verticalAlignment = Alignment.Bottom) {
            listOf(46 to 0.55f, 58 to 0.9f, 50 to 0.7f).forEach { (h, a) ->
                Box(
                    Modifier.padding(horizontal = 2.5.dp).width(13.dp).height(h.dp)
                        .background(p.gold.copy(alpha = a), RoundedCornerShape(2.dp))
                )
            }
        }
        Box(Modifier.padding(top = 3.dp).width(74.dp).height(1.5.dp).background(p.rule))
        Spacer(Modifier.height(28.dp))
        Text(
            "Bring your books",
            fontFamily = DisplayFont, fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
            color = p.ink, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "The epubs you already own, on the same page and in the same voice as the Bible. " +
                "Every Scripture reference in them opens its passage.",
            fontFamily = ReadingFont, fontWeight = FontWeight.Light,
            fontSize = 16.sp, lineHeight = 25.sp, color = p.inkSoft, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(30.dp))
        Press("Choose a folder", filled = true, onClick = onFolder, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Press("Choose files", filled = false, onClick = onFiles, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
        Text(
            "A folder brings in every epub inside it.\nBooks stay on this phone. Nothing is uploaded.",
            fontFamily = UiFont, fontSize = 11.5.sp, lineHeight = 18.sp, color = p.inkSoft,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Press(label: String, filled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Surface(
        onClick = onClick,
        color = if (filled) p.gold else Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        border = if (filled) null else BorderStroke(1.dp, p.rule),
        modifier = modifier,
    ) {
        Text(
            label,
            fontFamily = UiFont, fontSize = 14.5.sp, fontWeight = FontWeight.Medium,
            color = if (filled) p.paper else p.ink, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 13.dp),
        )
    }
}

@Composable
private fun OrderTab(label: String, on: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier.width(IntrinsicSize.Max).clickable(
            indication = null,
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        ) { onClick() }.padding(top = 6.dp),
    ) {
        Text(
            label,
            fontFamily = UiFont, fontSize = 13.sp,
            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
            color = if (on) p.ink else p.inkSoft,
        )
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(if (on) p.gold else Color.Transparent))
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth()
            .background(p.sunk, RoundedCornerShape(12.dp))
            .border(1.dp, p.rule, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        content = content,
    )
}

@Composable
private fun AddingCard(a: Library.Adding, onStop: () -> Unit) {
    val p = LocalPalette.current
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (a.total > 0) "Adding ${(a.done + 1).coerceAtMost(a.total)} of ${a.total}" else "Looking for books",
                fontFamily = DisplayFont, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                color = p.ink, modifier = Modifier.weight(1f),
            )
            Text(
                "Stop",
                fontFamily = UiFont, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = p.gold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onStop() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (a.name.isNotBlank()) Text(
            Library.plain(a.name),
            fontFamily = UiFont, fontSize = 12.sp, color = p.inkSoft,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(12.dp))
        if (a.total > 0) Box(
            Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(p.rule)
        ) {
            Box(Modifier.fillMaxWidth((a.done.toFloat() / a.total).coerceIn(0f, 1f)).fillMaxHeight().background(p.gold))
        }
        else LinearProgressIndicator(
            color = p.gold, trackColor = p.rule,
            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun ReportCard(r: Library.Report, onDone: () -> Unit) {
    val p = LocalPalette.current
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    r.added == 1 -> "Added 1 book"
                    r.added > 1 -> "Added ${r.added} books"
                    r.already > 0 && r.failed.isEmpty() -> "Already on the shelf"
                    else -> "Nothing was added"
                },
                fontFamily = DisplayFont, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                color = p.ink, modifier = Modifier.weight(1f),
            )
            Text(
                "Done",
                fontFamily = UiFont, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = p.gold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onDone() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (r.already > 0 && (r.added > 0 || r.failed.isNotEmpty())) Text(
            if (r.already == 1) "1 was already here." else "${r.already} were already here.",
            fontFamily = UiFont, fontSize = 12.5.sp, color = p.inkSoft,
            modifier = Modifier.padding(top = 3.dp),
        )
        if (r.failed.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                if (r.failed.size == 1) "ONE WAS LEFT OUT" else "${r.failed.size} WERE LEFT OUT",
                fontFamily = UiFont, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                letterSpacing = 1.6.sp, color = p.inkSoft,
            )
            r.failed.take(6).forEach { (name, why) ->
                Text(
                    "${Library.plain(name)} $why.",
                    fontFamily = UiFont, fontSize = 12.5.sp, lineHeight = 18.sp, color = p.ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            if (r.failed.size > 6) Text(
                "And ${r.failed.size - 6} more.",
                fontFamily = UiFont, fontSize = 12.5.sp, color = p.inkSoft,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

/** The book in hand, set out before the rest: one tap from the shelf back into it. */
@Composable
private fun CurrentCard(v: Library.Volume, library: Library, rate: Float, onOpen: () -> Unit) {
    val p = LocalPalette.current
    Surface(
        onClick = onOpen,
        color = p.sunk,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, p.rule),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(v, library, Modifier.width(78.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "CONTINUE READING",
                    fontFamily = UiFont, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                    letterSpacing = 1.8.sp, color = p.gold,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    v.title,
                    fontFamily = DisplayFont, fontSize = 23.sp, lineHeight = 26.sp,
                    fontWeight = FontWeight.SemiBold, color = p.ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (v.author.isNotBlank()) Text(
                    v.author,
                    fontFamily = UiFont, fontSize = 12.sp, color = p.inkSoft,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(p.rule)) {
                    Box(Modifier.fillMaxWidth(v.progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(p.gold))
                }
                Text(
                    "${(v.progress * 100).toInt()}% · ${readingTime((v.words * (1f - v.progress)).toInt(), rate)} left",
                    fontFamily = UiFont, fontSize = 11.5.sp, letterSpacing = 0.3.sp, color = p.inkSoft,
                    modifier = Modifier.padding(top = 7.dp),
                )
            }
        }
    }
}

/** Cloth for a book that came without a cover. Dark enough for cream type in either theme. */
private val CLOTHS = listOf(
    Color(0xFF3F4B3A), Color(0xFF5B302B), Color(0xFF2F3C56), Color(0xFF6A5330),
    Color(0xFF41394F), Color(0xFF2F494B), Color(0xFF4B3F36),
)
private val CREAM = Color(0xFFEFE5D0)

/**
 * A book's cover, or one made for it.
 *
 * Plenty of epubs carry no picture. A grey box with a file icon would make half the shelf
 * look broken, so those are bound here instead: cloth in a colour that is always the same
 * for the same book, the title set on it, the author at the foot.
 */
@Composable
internal fun Cover(v: Library.Volume, library: Library, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val image by produceState(library.coverNow(v), v.id, v.cover) {
        if (value == null) value = library.cover(v)
    }
    val shape = RoundedCornerShape(3.dp)
    BoxWithConstraints(
        modifier
            .aspectRatio(2f / 3f)
            .shadow(if (p.dark) 0.dp else 3.dp, shape)
            .clip(shape)
            .background(CLOTHS[(v.id.hashCode() and 0x7fffffff) % CLOTHS.size])
            .then(if (p.dark) Modifier.border(1.dp, p.rule, shape) else Modifier)
            // The shadow of the hinge, down the bound edge: what makes it a book and not a card.
            .drawWithContent {
                drawContent()
                val w = 6.dp.toPx()
                drawRect(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.26f), 0.45f to Color.Black.copy(alpha = 0.05f),
                        1f to Color.Transparent, startX = 0f, endX = w,
                    ),
                    size = Size(w, size.height),
                )
            },
    ) {
        val im = image
        if (im != null) {
            Image(im, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (!v.cover) {
            // Sized from the cover's own width, so the same binding works at any size.
            val k = maxWidth.value / 104f
            Box(
                Modifier.fillMaxSize().padding((5 * k).dp)
                    .border((0.75f).dp, CREAM.copy(alpha = 0.28f), RoundedCornerShape(1.5.dp))
                    .padding(horizontal = (7 * k).dp, vertical = (9 * k).dp),
            ) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.weight(0.6f))
                    Text(
                        v.title,
                        fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold,
                        fontSize = (14.5f * k).sp, lineHeight = (16.5f * k).sp,
                        color = CREAM, textAlign = TextAlign.Center,
                        maxLines = 5, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height((7 * k).dp))
                    Box(Modifier.width((16 * k).dp).height(1.dp).background(Color(0xFFD9A84E)))
                    Spacer(Modifier.weight(1f))
                    if (v.author.isNotBlank()) Text(
                        v.author.uppercase(),
                        fontFamily = UiFont, fontWeight = FontWeight.Medium,
                        fontSize = (6.6f * k).sp, lineHeight = (9f * k).sp, letterSpacing = (0.9f * k).sp,
                        color = CREAM.copy(alpha = 0.8f), textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(onFolder: () -> Unit, onFiles: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.sunk,
        dragHandle = { BottomSheetDefaults.DragHandle(color = p.rule) },
    ) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
            Text(
                "Add books",
                fontFamily = DisplayFont, fontSize = 25.sp, fontWeight = FontWeight.SemiBold, color = p.ink,
            )
            Spacer(Modifier.height(10.dp))
            Source("A folder", "Every epub inside it, however deep", onFolder)
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.rule))
            Source("Files", "One book, or several chosen together", onFiles)
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.rule))
            Text(
                "Books are copied onto this phone and stay here. Nothing is uploaded, " +
                    "and a book already on the shelf is not added twice.",
                fontFamily = UiFont, fontSize = 11.5.sp, lineHeight = 18.sp, color = p.inkSoft,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

@Composable
private fun Source(title: String, detail: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 14.dp)) {
        Text(title, fontFamily = DisplayFont, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = p.ink)
        Text(detail, fontFamily = UiFont, fontSize = 12.sp, color = p.inkSoft, modifier = Modifier.padding(top = 2.dp))
    }
}

/** One book, on a long press: what it is, and the way to take it off the phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VolumeSheet(
    v: Library.Volume, library: Library, rate: Float,
    onOpen: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    // Removing asks twice, in the same place: no dialog over a sheet over a shelf.
    var sure by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.sunk,
        dragHandle = { BottomSheetDefaults.DragHandle(color = p.rule) },
    ) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(v, library, Modifier.width(70.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        v.title,
                        fontFamily = DisplayFont, fontSize = 22.sp, lineHeight = 26.sp,
                        fontWeight = FontWeight.SemiBold, color = p.ink,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                    if (v.author.isNotBlank()) Text(
                        v.author, fontFamily = UiFont, fontSize = 12.5.sp, color = p.inkSoft,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(
                        buildString {
                            append(readingTime(v.words, rate)).append(" to hear")
                            if (v.progress >= 0.995f) append(" · finished")
                            else if (v.started) append(" · ${(v.progress * 100).toInt()}% read")
                        },
                        fontFamily = UiFont, fontSize = 11.5.sp, letterSpacing = 0.3.sp, color = p.inkSoft,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        "Added " + java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG)
                            .format(java.util.Date(v.added)),
                        fontFamily = UiFont, fontSize = 11.5.sp, letterSpacing = 0.3.sp, color = p.inkSoft,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.rule))
            SheetAction(if (v.started && v.progress < 0.995f) "Continue reading" else "Open", onOpen)
            SheetAction(
                if (sure) "Tap again to remove" else "Remove from this phone",
                onClick = { if (sure) onRemove() else sure = true },
                colour = if (sure) (if (p.dark) Color(0xFFE39A88) else Color(0xFFA9412F)) else null,
            )
        }
    }
}
