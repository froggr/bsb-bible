package ca.lampandlight

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The handful of things worth changing, kept on one screen. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("reader", Context.MODE_PRIVATE)

    var textScale by mutableFloatStateOf(sp.getFloat("scale", 1f))
    var theme by mutableStateOf(sp.getString("theme", "system") ?: "system")
    var voice by mutableStateOf(sp.getString("voice", "") ?: "")
    /** The chosen music bed, empty for none. */
    var bed by mutableStateOf(sp.getString("bed", "") ?: "")
    var bedLevel by mutableFloatStateOf(sp.getFloat("bedLevel", 0.5f))
    /** Narration speed, 0.9 to 1.3. */
    var rate by mutableFloatStateOf(sp.getFloat("rate", 1f))

    /** Whether the chapter is laid out for study, and which layers show in it. */
    var study by mutableStateOf(sp.getBoolean("study", false))
    var studyCommentary by mutableStateOf(sp.getBoolean("studyComm", true))
    var studyRefs by mutableStateOf(sp.getBoolean("studyRefs", true))
    var studyFootnotes by mutableStateOf(sp.getBoolean("studyNotes", true))

    /**
     * Commentaries switched off, by source key, comma separated.
     *
     * Hidden rather than deleted: they all live in one downloaded file, so turning Henry
     * off saves no space and he is still there the day you want him. Empty means show all.
     */
    var commentaryOff by mutableStateOf(sp.getString("commOff", "") ?: "")

    fun hiddenCommentaries(): Set<String> =
        commentaryOff.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun isCommentaryOn(source: String) = source !in hiddenCommentaries()

    fun setCommentaryOn(source: String, on: Boolean) {
        val now = hiddenCommentaries().toMutableSet()
        if (on) now -= source else now += source
        commentaryOff = now.sorted().joinToString(",")
        save()
    }

    fun studyLayers() = StudyLayers(studyCommentary, studyRefs, studyFootnotes, hiddenCommentaries())

    /**
     * The colour the quick gestures use.
     *
     * Whatever was chosen last, so someone who marks everything in sage gets sage without
     * asking for it each time. Gold until they choose otherwise.
     */
    var colour by mutableStateOf(sp.getString("colour", "gold") ?: "gold")

    fun lastColour(): MarkColor = MarkColor.of(colour)

    fun rememberColour(c: MarkColor) {
        colour = c.name.lowercase()
        save()
    }

    fun setStudyLayers(l: StudyLayers) {
        studyCommentary = l.commentary; studyRefs = l.refs; studyFootnotes = l.footnotes
        commentaryOff = l.hiddenCommentaries.sorted().joinToString(",")
        save()
    }

    /**
     * Where the reader was, kept across launches.
     *
     * Written on every scroll, so it is deliberately cheap: `apply()` is asynchronous, and
     * the same value is not written twice.
     */
    val place = object : ReaderState.Place {
        private var last = Triple(0, 0, 0)

        override fun load(): Triple<Int, Int, Int>? {
            val b = sp.getInt("atBook", 0)
            val c = sp.getInt("atChapter", 0)
            if (b <= 0 || c <= 0) return null
            return Triple(b, c, sp.getInt("atVerse", 0)).also { last = it }
        }

        override fun save(book: Int, chapter: Int, verse: Int) {
            val now = Triple(book, chapter, verse)
            if (now == last) return
            last = now
            sp.edit().putInt("atBook", book).putInt("atChapter", chapter)
                .putInt("atVerse", verse).apply()
        }
    }

    fun save() {
        sp.edit()
            .putFloat("scale", textScale)
            .putString("theme", theme)
            .putString("voice", voice)
            .putString("bed", bed)
            .putFloat("bedLevel", bedLevel)
            .putFloat("rate", rate)
            .putString("colour", colour)
            .putBoolean("study", study)
            .putBoolean("studyComm", studyCommentary)
            .putBoolean("studyRefs", studyRefs)
            .putBoolean("studyNotes", studyFootnotes)
            .putString("commOff", commentaryOff)
            .apply()
    }
}

@Composable
fun SettingsScreen(
    prefs: Prefs,
    bible: Bible,
    narrator: Narrator,
    onClose: () -> Unit,
) {
    val p = LocalPalette.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var message by remember { mutableStateOf<String?>(null) }
    var layersPresent by remember { mutableStateOf(bible.hasStudy to bible.hasCommentary) }
    var modelPresent by remember { mutableStateOf(narrator.modelReady()) }
    var voicesHave by remember { mutableIntStateOf(Updates.voicesPresent(ctx)) }
    var bedsHave by remember { mutableIntStateOf(Updates.bedsPresent(ctx)) }
    // Discover the voices on arrival, so the picker is there without reading something first.
    LaunchedEffect(Unit) { narrator.loadVoices() }

    Column(
        Modifier.fillMaxSize().background(p.paper)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Settings",
                fontFamily = DisplayFont, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                color = p.ink, modifier = Modifier.weight(1f),
            )
            Text(
                "Done", fontFamily = UiFont, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = p.gold, modifier = Modifier.clickable { onClose() }.padding(8.dp),
            )
        }

        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {

            Section("Reading")
            Label("Text size")
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf(0.85f to "A", 1f to "A", 1.2f to "A", 1.45f to "A").forEachIndexed { i, (v, t) ->
                    val on = kotlin.math.abs(prefs.textScale - v) < 0.02f
                    Surface(
                        onClick = { prefs.textScale = v; prefs.save() },
                        shape = RoundedCornerShape(8.dp),
                        color = if (on) p.gold.copy(alpha = 0.2f) else Color.Transparent,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (on) p.gold else p.rule),
                        modifier = Modifier.padding(end = 9.dp),
                    ) {
                        Text(
                            t, fontFamily = ReadingFont, fontSize = (13 + i * 5).sp, color = p.ink,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "The LORD is my shepherd; I shall not want.",
                fontFamily = ReadingFont, fontWeight = FontWeight.Light,
                fontSize = 19.sp * prefs.textScale, lineHeight = 34.sp * prefs.textScale,
                color = p.ink,
                modifier = Modifier.padding(bottom = 6.dp),
            )

            Label("Theme")
            Row {
                listOf("system" to "Match the phone", "light" to "Paper", "dark" to "Night")
                    .forEach { (key, name) ->
                        val on = prefs.theme == key
                        Surface(
                            onClick = { prefs.theme = key; prefs.save() },
                            shape = RoundedCornerShape(8.dp),
                            color = if (on) p.gold.copy(alpha = 0.2f) else Color.Transparent,
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (on) p.gold else p.rule),
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Text(
                                name, fontFamily = UiFont, fontSize = 12.sp, color = p.ink,
                                modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                            )
                        }
                    }
            }

            Section("Reading aloud")
            if (!modelPresent) {
                Text(
                    "The voice has not been downloaded yet (about 65 MB).",
                    fontFamily = UiFont, fontSize = 12.sp, color = p.inkSoft,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                Action("Download the voice", busy == "voice", progress) {
                    scope.launch {
                        busy = "voice"; message = null
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                Updates.fetchModel(ctx) { a, b -> progress = a.toFloat() / b.coerceAtLeast(1) }
                            }.isSuccess
                        }
                        busy = null
                        modelPresent = narrator.modelReady()
                        message = if (ok) "Voice ready." else "Could not download the voice."
                    }
                }
            } else {
                // The voice list is in the player now; this stays the place to fetch them.
                Text(
                    if (voicesHave > 0) "Voice ready \u00b7 $voicesHave extra voices downloaded \u00b7 choose one in the player"
                    else "Voice ready \u00b7 Phonon, on this phone",
                    fontFamily = UiFont, fontSize = 12.sp, color = p.inkSoft,
                )
                MoreVoices(
                    have = voicesHave, busy = busy, progress = progress,
                    onGet = {
                        scope.launch {
                            busy = "voices"; progress = 0f
                            val n = withContext(Dispatchers.IO) {
                                runCatching {
                                    Updates.fetchVoices(ctx) { a, b ->
                                        progress = a.toFloat() / b.coerceAtLeast(1)
                                    }
                                }.getOrDefault(0)
                            }
                            narrator.reloadVoices()
                            voicesHave = Updates.voicesPresent(ctx)
                            busy = null
                            message = if (n > 0) "$n voices ready. Choose one in the player." else
                                "Could not download the voices."
                        }
                    },
                )
            }

            Section("Music")
            // The picker itself lives in the player, where it is reached while reading.
            // This is only the download.
            Label(
                if (bedsHave == 0) "Quiet music under the reading, if you want it. Choose a bed in the player."
                else "$bedsHave beds downloaded. Choose one in the player."
            )
            // Always offered, even with all eight on disk: a bed can be CORRECTED on the
            // server (three were, to cut vocal bleed), and fetchBeds now replaces any whose
            // size no longer matches. Hiding this button once the count was full left no way
            // to pull a fix down.
            GetBeds(
                busy = busy, progress = progress, rest = bedsHave > 0,
                complete = bedsHave == Beds.ALL.size,
                onGet = {
                    scope.launch {
                        busy = "beds"; progress = 0f
                        val n = withContext(Dispatchers.IO) {
                            runCatching {
                                Updates.fetchBeds(ctx) { a, b ->
                                    progress = a.toFloat() / b.coerceAtLeast(1)
                                }
                            }.getOrDefault(0)
                        }
                        bedsHave = Updates.bedsPresent(ctx)
                        busy = null
                        message = if (n > 0) "$n beds ready. Choose one in the player." else
                            "Could not download the music."
                    }
                },
            )

            Section("Study material")
            LayerRow(
                "Original languages",
                "Hebrew and Greek for every word, with Strong's numbers and 344,799 cross-references.",
                present = layersPresent.first,
                busy = busy == "study", progress = progress,
            ) {
                scope.launch {
                    busy = "study"; message = null
                    val ok = fetchLayer(ctx, "bible-study.db") { progress = it }
                    busy = null
                    message = if (ok) "Downloaded. Restart to use it." else "Could not download."
                    layersPresent = layersPresent.copy(first = ok || layersPresent.first)
                }
            }
            LayerRow(
                "Commentary",
                "Tyndale Study Notes (2022), plus Matthew Henry (1710) and " +
                    "Jamieson, Fausset & Brown (1871).",
                present = layersPresent.second,
                busy = busy == "comm", progress = progress,
            ) {
                scope.launch {
                    busy = "comm"; message = null
                    val ok = fetchLayer(ctx, "bible-commentary.db") { progress = it }
                    busy = null
                    message = if (ok) "Downloaded. Restart to use it." else "Could not download."
                    layersPresent = layersPresent.copy(second = ok || layersPresent.second)
                }
            }

            message?.let {
                Text(
                    it, fontFamily = UiFont, fontSize = 12.sp, color = p.gold,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            Section("About")
            // The build and the text version, so a report of a wrong verse can say which
            // build it came from. The text version moves independently of the app's.
            val build = remember {
                runCatching {
                    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
                }.getOrNull() ?: "?"
            }
            // The check lives on the version line, because that is the line you look at
            // when you are wondering whether you are on the latest build.
            UpdateSection(ctx, build)
            Text(
                "Berean Standard Bible, dedicated to the public domain.\n" +
                    "Cross-references from OpenBible.info, CC-BY.\n" +
                    "Matthew Henry and Jamieson, Fausset & Brown from CrossWire, " +
                    "public domain.\n" +
                    "Tyndale Open Study Notes \u00a9 2022 Tyndale House Publishers, " +
                    "CC BY-SA 4.0, available free at tyndaleopenresources.com.\n" +
                    "Speech by Phonon (Gradium), Apache 2.0, generated on this phone.",
                fontFamily = UiFont, fontSize = 11.sp, lineHeight = 19.sp, color = p.inkSoft,
                modifier = Modifier.padding(bottom = 40.dp),
            )
        }
    }
}

private suspend fun fetchLayer(ctx: Context, name: String, onProgress: (Float) -> Unit): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val m = Updates.manifest(ctx)
            val layer = m.layers.firstOrNull { it.name == name } ?: return@runCatching false
            Updates.fetchLayer(ctx, layer) { a, b -> onProgress(a.toFloat() / b.coerceAtLeast(1)) }
            true
        }.getOrDefault(false)
    }

@Composable
private fun Section(title: String) {
    val p = LocalPalette.current
    Spacer(Modifier.height(26.dp))
    Text(
        title, fontFamily = UiFont, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.4.sp, color = p.gold,
    )
    Spacer(Modifier.height(4.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.rule))
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun Label(text: String) {
    val p = LocalPalette.current
    Text(
        text, fontFamily = UiFont, fontSize = 12.sp, color = p.inkSoft,
        modifier = Modifier.padding(bottom = 8.dp, top = 6.dp),
    )
}

@Composable
private fun Action(label: String, busy: Boolean, progress: Float, onClick: () -> Unit) {
    val p = LocalPalette.current
    Surface(
        onClick = { if (!busy) onClick() },
        shape = RoundedCornerShape(9.dp),
        color = if (busy) p.sunk else p.gold,
    ) {
        Text(
            if (busy) "${(progress * 100).toInt()}%" else label,
            fontFamily = UiFont, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = if (busy) p.inkSoft else p.paper,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 11.dp),
        )
    }
}

@Composable
private fun LayerRow(
    title: String,
    detail: String,
    present: Boolean,
    busy: Boolean,
    progress: Float,
    onDownload: () -> Unit,
) {
    val p = LocalPalette.current
    Column(Modifier.padding(bottom = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title, fontFamily = UiFont, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = p.ink, modifier = Modifier.weight(1f),
            )
            if (present) Text("Installed", fontFamily = UiFont, fontSize = 11.sp, color = p.inkSoft)
            else Action("Download", busy, progress, onDownload)
        }
        Text(
            detail, fontFamily = UiFont, fontSize = 11.sp, lineHeight = 18.sp, color = p.inkSoft,
            modifier = Modifier.padding(top = 4.dp, end = 60.dp),
        )
    }
}

/** Everything the reader has marked or written, newest first. */
@Composable
fun LibraryScreen(bible: Bible, onOpen: (VerseId) -> Unit, onClose: () -> Unit) {
    val p = LocalPalette.current
    val all = remember { bible.allMarks() }

    Column(
        Modifier.fillMaxSize().background(p.paper)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Marks and notes",
                fontFamily = DisplayFont, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                color = p.ink, modifier = Modifier.weight(1f),
            )
            Text(
                "Done", fontFamily = UiFont, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = p.gold, modifier = Modifier.clickable { onClose() }.padding(8.dp),
            )
        }
        if (all.isEmpty()) {
            Text(
                "Nothing marked yet.\n\nTap a verse to keep it. Press and hold for colours, notes and the original words.",
                fontFamily = UiFont, fontSize = 13.sp, lineHeight = 21.sp, color = p.inkSoft,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(all, key = { it.id }) { m ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onOpen(m.start) }
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(10.dp).clip(CircleShape)
                                    .background(MarkColor.of(m.color).color(p.dark))
                            )
                            Spacer(Modifier.width(9.dp))
                            Text(
                                bible.reference(m.start),
                                fontFamily = DisplayFont, fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold, color = p.ink,
                            )
                        }
                        m.excerpt?.let {
                            Text(
                                it,
                                fontFamily = ReadingFont, fontWeight = FontWeight.Light,
                                fontStyle = FontStyle.Italic,
                                fontSize = 14.sp, lineHeight = 22.sp, color = p.inkSoft,
                                maxLines = 2,
                                modifier = Modifier.padding(top = 5.dp),
                            )
                        }
                        m.note?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                fontFamily = ReadingFont, fontSize = 15.sp, lineHeight = 24.sp,
                                color = p.ink,
                                modifier = Modifier.padding(top = 8.dp, start = 2.dp),
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(p.rule))
                }
            }
        }
    }
}

/** The small menu behind "Marks & settings". */
@Composable
fun MenuScreen(onLibrary: () -> Unit, onSettings: () -> Unit, onClose: () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxSize().background(p.paper)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                "Close", fontFamily = UiFont, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = p.gold, modifier = Modifier.clickable { onClose() }.padding(8.dp),
            )
        }
        MenuItem("Marks and notes", "Everything you have coloured or written on", onLibrary)
        MenuItem("Settings", "Text size, theme, music, study material", onSettings)
    }
}

@Composable
private fun MenuItem(title: String, detail: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text(
            title, fontFamily = DisplayFont, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            color = p.ink,
        )
        Text(
            detail, fontFamily = UiFont, fontSize = 12.sp, color = p.inkSoft,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.rule))
}


/**
 * The offer to download the rest of the voices.
 *
 * Twenty-four more English readers, about 6 MB in all. Small enough to take in one go, but
 * not worth shipping to someone who reads in silence, so it is asked for.
 */
@Composable
private fun MoreVoices(have: Int, busy: String?, progress: Float, onGet: () -> Unit) {
    val p = LocalPalette.current
    val working = busy == "voices"
    Column(Modifier.padding(top = 14.dp)) {
        Text(
            if (have == 0) "24 more voices \u00b7 British and American \u00b7 about 6 MB"
            else "$have of ${Voices.ALL.size} voices downloaded",
            fontFamily = UiFont, fontSize = 12.sp, color = p.inkSoft,
        )
        Spacer(Modifier.height(9.dp))
        if (working) {
            LinearProgressIndicator(
                progress = { progress },
                color = p.gold, trackColor = p.rule,
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        } else {
            Surface(
                onClick = onGet,
                shape = RoundedCornerShape(9.dp),
                color = p.sunk,
                border = androidx.compose.foundation.BorderStroke(1.dp, p.rule),
            ) {
                Text(
                    if (have == 0) "Get the voices" else "Get the rest",
                    fontFamily = UiFont, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    color = p.ink,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }
}


/**
 * The offer to download the music beds.
 *
 * About 12 MB of instrumental lofi, generated for this app, so it is asked for rather than
 * shipped: the APK is mostly Bible text already and silence is a perfectly good default.
 */
@Composable
private fun GetBeds(
    busy: String?,
    progress: Float,
    rest: Boolean = false,
    /** True when every bed is already on disk, so this is a re-check rather than a download. */
    complete: Boolean = false,
    onGet: () -> Unit,
) {
    val p = LocalPalette.current
    val working = busy == "beds"
    Column(Modifier.padding(top = 4.dp)) {
        if (!rest) {
            Text(
                "Eight beds \u00b7 instrumental \u00b7 about 12 MB",
                fontFamily = UiFont, fontSize = 12.sp, color = p.inkSoft,
            )
            Spacer(Modifier.height(9.dp))
        }
        if (working) {
            LinearProgressIndicator(
                progress = { progress },
                color = p.gold, trackColor = p.rule,
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        } else {
            Surface(
                onClick = onGet,
                shape = RoundedCornerShape(9.dp),
                color = p.sunk,
                border = androidx.compose.foundation.BorderStroke(1.dp, p.rule),
            ) {
                Text(
                    when {
                        complete -> "Check the music"
                        rest -> "Get the rest"
                        else -> "Get the music"
                    },
                    fontFamily = UiFont, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    color = p.ink,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * The build you are on, and a way to go and look for a newer one.
 *
 * The automatic check is a convenience; this is the one that always works. An app that
 * updates itself but cannot be *asked* to update leaves the reader with nothing to try
 * when it quietly stops working.
 */
@Composable
private fun UpdateSection(ctx: android.content.Context, installed: String) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(Updates.pending(ctx)?.let { "ready:$it" } ?: "") }
    var pct by remember { mutableFloatStateOf(0f) }

    // Re-read after coming back from the installer. Android tells the app nothing when a
    // sideloaded install succeeds — the process is replaced — so the only way to know is
    // to look at the package manager again on the way back in.
    LaunchedEffect(MainActivity.visits) {
        if (!state.startsWith("downloading") && state != "checking") {
            state = Updates.pending(ctx)?.let { "ready:$it" } ?: ""
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "BSB Bible $installed  \u00b7  text v${Bible.TEXT_VERSION}",
                fontFamily = MonoFont, fontSize = 12.sp, color = p.ink,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                when {
                    state.startsWith("ready:") -> "Version ${state.removePrefix("ready:")} downloaded — tap to install"
                    state == "checking" -> "Looking…"
                    state == "downloading" -> "Downloading — ${(pct * 100).toInt()}%"
                    state == "current" -> "Up to date"
                    state == "failed" -> "Could not reach the server"
                    else -> "Tap Check to look for a newer build"
                },
                fontFamily = UiFont, fontSize = 11.sp, color = p.inkSoft,
            )
        }
        val ready = state.startsWith("ready:")
        Surface(
            onClick = {
                if (ready) { Updates.installPending(ctx); return@Surface }
                if (state == "checking" || state == "downloading") return@Surface
                scope.launch {
                    state = "checking"
                    val found = withContext(Dispatchers.IO) {
                        runCatching {
                            val m = Updates.manifest(ctx)
                            if (!Updates.updateAvailable(ctx, m)) return@runCatching null
                            val app = m.app ?: return@runCatching null
                            state = "downloading"
                            Updates.fetchApk(ctx, app) { a, b ->
                                pct = a.toFloat() / b.coerceAtLeast(1)
                            }
                            Updates.markReady(ctx, app)
                            app.version
                        }.getOrElse { "" }
                    }
                    state = when (found) {
                        null -> "current"
                        "" -> "failed"
                        else -> "ready:$found"
                    }
                }
            },
            shape = RoundedCornerShape(8.dp),
            color = if (ready) p.gold else Color.Transparent,
            border = androidx.compose.foundation.BorderStroke(1.dp, if (ready) p.gold else p.rule),
        ) {
            Text(
                if (ready) "Install" else "Check",
                fontFamily = UiFont, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                color = if (ready) p.paper else p.ink,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
    Spacer(Modifier.height(12.dp))
}
