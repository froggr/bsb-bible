package ca.lampandlight

import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var bible: Bible? = null
    private var narrator: Narrator? = null
    private var bed: Bed? = null
    private var questions: Questions? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        latest = this
        enableEdgeToEdge()
        // Opened at a passage from outside: held until the Bible is open, then consumed.
        // Once only. Android keeps the intent a task was started with and hands it over
        // again whenever the activity has to be made afresh, so a reading begun from the
        // verse of the day, then left for a book, came back at the verse of the day every
        // time the app had been cleared from memory in between.
        val replayed = savedInstanceState != null ||
            intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        arriving = intent.takeIf { !replayed }
        setContent {
            var ready by remember { mutableStateOf<Bible?>(null) }
            var failed by remember { mutableStateOf<String?>(null) }

            // Android 13 and up will not show the reading notification without this, and
            // without the notification the system stops the audio when the screen goes off.
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
                }
            }

            LaunchedEffect(Unit) {
                try {
                    val b = withContext(Dispatchers.IO) { Bible.open(this@MainActivity) }
                    // The questions are an extra: a phone that cannot unpack them still reads.
                    questions = withContext(Dispatchers.IO) {
                        runCatching { Questions.open(this@MainActivity) }.getOrNull()
                    }
                    bible = b
                    narrator = Narrator(this@MainActivity)
                    bed = Bed(this@MainActivity)
                    ready = b
                } catch (e: Throwable) {
                    failed = e.message ?: e::class.simpleName
                }
            }

            val prefs = remember { Prefs(this@MainActivity) }
            val dark = when (prefs.theme) {
                "light" -> false
                "dark" -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            BibleTheme(dark = dark, scale = prefs.textScale) {
                val p = LocalPalette.current
                val b = ready
                when {
                    failed != null -> Box(
                        Modifier.fillMaxSize().background(p.paper), Alignment.Center
                    ) { Text(failed!!, color = p.ink, fontFamily = UiFont, fontSize = 13.sp) }

                    b == null -> Box(Modifier.fillMaxSize().background(p.paper))

                    else -> App(b, narrator!!, bed!!, prefs, questions)
                }
            }
        }
    }

    /**
     * A link while the app is already running. The activity is singleTask, so Android
     * hands the intent here instead of starting a second copy.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        arriving = intent
    }

    override fun onStart() {
        super.onStart()
        // Counted, not set. Android can make a second copy of the activity before it has
        // finished with the first, and the first one's onStop then arrives after the
        // second one's onStart: a plain flag ended up saying "away" with the app in hand.
        Pulse.onScreen = ++started > 0
        bed?.resume()             // only does anything if the bed was actually paused
        // Look for a new build every time the app is come back to, not only on a cold
        // start. The activity survives rotation and configuration changes now, and it can
        // sit in recents for days, so a LaunchedEffect(Unit) alone almost never fires
        // again — which is why updates were only ever arriving by hand.
        visits++
    }

    companion object {
        /** Bumped on every onStart, so composition can watch for the app being resumed. */
        var visits by mutableIntStateOf(0)
            private set

        private var started = 0
        private var latest: MainActivity? = null

        /** An intent that may name a passage, waiting for the reader to take it. */
        var arriving by mutableStateOf<Intent?>(null)
    }

    override fun onStop() {
        super.onStop()
        // Audio carries on when the phone is pocketed — a reading that stops on a walk is
        // no use, and neither is a bed that dies the moment the screen does. The foreground
        // service is what keeps Android from killing either. Nothing playing, nothing held.
        started = (started - 1).coerceAtLeast(0)
        Pulse.onScreen = started > 0
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only if no newer copy of the activity has taken over: its reading is not this
        // one's to end.
        if (latest === this) {
            latest = null
            ReadingService.stop(this)
            ReadingService.hold = null
        }
        narrator?.close()
        bed?.pause()
        bible?.close()
    }
}

@Composable
private fun App(bible: Bible, narrator: Narrator, bed: Bed, prefs: Prefs, questions: Questions?) {
    val state = remember { ReaderState(bible, prefs.place, questions) }
    var picking by remember { mutableStateOf(false) }
    var studying by remember { mutableStateOf<Pair<VerseId, Int?>?>(null) }
    var layering by remember { mutableStateOf(false) }
    /** The span being dragged out, while the finger is still down. */
    var dragging by remember { mutableStateOf<Pair<VerseId, VerseId>?>(null) }
    var selection by remember { mutableStateOf<Set<VerseId>>(emptySet()) }
    // The verse the selection was started on. A range is this and the verse tapped last,
    // so a third tap moves the far end and never pivots on the wrong one — reading the
    // anchor back out of an unordered Set did exactly that.
    var anchor by remember { mutableStateOf<VerseId?>(null) }
    // The span being written on: one verse, or a range. Not a bare id — a note taken on a
    // selection belongs to all of it.
    var noting by remember { mutableStateOf<Pair<VerseId, VerseId>?>(null) }
    // A book that was open when the app was left is where it opens again, unless it was
    // started by a link to a passage or by a book handed over from another app.
    val cold = remember { MainActivity.arriving }
    var screen by remember {
        mutableStateOf(
            if (prefs.openBook.isNotBlank() && Deeplink.verseOf(cold, bible.books) == null &&
                epubOf(cold) == null) "book" else "read"
        )
    }
    // How many questions are due to be gone over, counted afresh whenever the menu opens.
    var due by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(screen) {
        if (screen == "menu" && questions != null) {
            due = withContext(Dispatchers.IO) { questions.due().size }
        }
    }
    // The verse whose action sheet is open, with its row index so "read from here" works.
    var acting by remember { mutableStateOf<Pair<VerseId, Int>?>(null) }
    // The last bookmark tap, kept just long enough to offer it back.
    var undo by remember { mutableStateOf<Triple<VerseId, MarkColor?, Boolean>?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current


    // Study material is read off disk, which must not happen on the scroll thread; the
    // state object has no scope of its own, so it borrows the screen's.
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        state.background = { work -> scope.launch(Dispatchers.IO) { work() } }
        state.restoreStudy(prefs.study, prefs.studyLayers())
    }

    // The reader's own books, and the one that is open.
    val library = remember { Library(ctx.applicationContext) }
    var session by remember { mutableStateOf<BookSession?>(null) }
    var opening by remember { mutableStateOf(false) }
    // A passage opened in the Bible from a book leaves the book's name in the top bar as
    // the way back. It holds for as long as the Bible is where the book put it — the depth
    // of its history then — and gives way to the Bible's own trail past that.
    var crumbAt by remember { mutableIntStateOf(-1) }
    var crumbPushed by remember { mutableStateOf(false) }

    val openBook: (String) -> Unit = open@{ id ->
        if (session?.id == id) { narrator.reset(); screen = "book"; return@open }
        if (opening) return@open
        opening = true
        scope.launch {
            try {
                val s = BookSession.open(id, library, bible)
                if (s == null) {
                    val name = library.volume(id)?.title ?: "That book"
                    library.report = Library.Report(0, 0, listOf(name to "could not be opened"))
                    if (prefs.openBook == id) prefs.openBook = ""
                    if (screen == "book") screen = "books"
                } else {
                    val v = library.volume(id)
                    s.go(v?.section ?: 0, v?.block ?: 0)
                    narrator.reset()
                    session?.close()
                    session = s
                    library.touch(id)
                    prefs.openBook = id
                    crumbAt = -1
                    screen = "book"
                }
            } finally {
                // Whatever happened: a book that failed part-way must not leave every
                // other book unopenable until the app is restarted.
                opening = false
            }
        }
    }
    // Back to the shelf: the book is shut, and the place it was shut at is kept by the page
    // itself as it leaves.
    val shutBook = {
        narrator.reset()
        val s = session
        session = null
        crumbAt = -1
        prefs.openBook = ""
        screen = "books"
        // After the page has gone and written its place.
        if (s != null) scope.launch { kotlinx.coroutines.delay(600); s.close() }
        Unit
    }
    LaunchedEffect(Unit) {
        val id = prefs.openBook
        if (screen == "book" && id.isNotBlank()) openBook(id)
    }

    // Pick the bed back up where it was left, including after the screen slept.
    LaunchedEffect(Unit) {
        bed.restore(prefs.bed.ifBlank { null }, prefs.bedLevel)
        narrator.rate = prefs.rate
    }
    // Open at a verse. A reference with a range ("Philippians 2:3-4") lands on its first
    // verse with the whole span selected, so it can be marked, noted or copied at once.
    val land: (VerseId, VerseId?) -> Unit = { at, to ->
        state.go(at.book, at.chapter, at = at.verse)
        if (to != null && to > at) { anchor = at; selection = setOf(at, to) }
        else { anchor = null; selection = emptySet() }
    }
    // A passage asked for from outside — a widget, a shortcut, a link — once the reader
    // is ready to show it. See [Deeplink] for the forms accepted.
    LaunchedEffect(MainActivity.arriving) {
        val intent = MainActivity.arriving ?: return@LaunchedEffect
        MainActivity.arriving = null
        // A book, opened from a file manager or a download: onto the shelf with it.
        epubOf(intent)?.let { uri ->
            narrator.reset()
            session?.let { s -> session = null; scope.launch { kotlinx.coroutines.delay(600); s.close() } }
            prefs.openBook = ""
            crumbAt = -1
            library.add(listOf(uri))
            screen = "books"
            return@LaunchedEffect
        }
        val landing = Deeplink.verseOf(intent, bible.books) ?: return@LaunchedEffect
        narrator.reset()
        crumbAt = -1
        screen = "read"
        land(landing.at, landing.to)
    }
    // A chapter read to its end runs on into the next, unless asked not to. The next
    // chapter's study material comes off disk, so in study mode the voice waits for it
    // — briefly — rather than reading the verses and skipping the commentary.
    // The voice asks the page's questions and has to know when one has been answered, and
    // the page has to show the answer when the voice gives it. A book has no questions, so
    // these are the Bible's alone and are never called while a book is being read.
    LaunchedEffect(Unit) {
        narrator.replied = { state.replied(it) }
        narrator.onUnanswered = { state.heard(it) }
    }
    LaunchedEffect(Unit) {
        narrator.onChapterEnd = {
            val s = session
            // What to read next, if anything. Fetching it takes a moment, and for that
            // moment the narrator is not speaking but is not finished either: it says so
            // ([Narrator.carrying]) until told here that the matter is settled, so that
            // nothing lets go of the reading in between.
            val next: (suspend () -> List<Narrator.Line>)? = when {
                !prefs.readOn -> null
                screen == "book" && s != null ->
                    if (!s.hasNext) null else suspend { s.go(s.index + 1); s.lines() }
                else -> {
                    val was = state.book to state.chapter
                    state.nextChapter()
                    // Revelation 22 has no next chapter: nextChapter() leaves us where we
                    // are, and reading it again would be a loop.
                    if (was == (state.book to state.chapter)) null else suspend {
                        var waited = 0
                        while (state.studyLoading && waited < 40) { kotlinx.coroutines.delay(100); waited++ }
                        state.narrationLines()
                    }
                }
            }
            if (next == null) narrator.settled()
            else scope.launch {
                try {
                    val lines = next()
                    // Still wanted: Stop pressed during the wait means stop.
                    if (narrator.carrying) narrator.speak(lines, 0)
                } finally {
                    narrator.settled()
                }
            }
        }
    }
    // Hold the music down while a verse is sounding, and let it up between them.
    LaunchedEffect(narrator.speaking) { bed.duck(narrator.speaking) }

    // Look for a newer build in the background, and offer it once it is downloaded and
    // checksummed. Never mid-reading: an installer prompt over a chapter is an ambush.
    var update by remember { mutableStateOf<String?>(null) }
    var dismissed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(MainActivity.visits) {
        val v = withContext(Dispatchers.IO) { Updates.checkQuietly(ctx) }
        // "Later" holds for the version it was pressed on, not for ever: the next build
        // asks again. Nothing is downloaded twice — checkQuietly returns what is already
        // on disk.
        // Null means nothing to offer — including the case where the reader has just
        // installed it, so the banner has to come down rather than simply not come up.
        update = if (v != null && v != dismissed) v else null
    }

    // The notification is what lets the reading survive the screen going off, and the only
    // way to stop it without unlocking.
    val heard = session?.takeIf { screen == "book" }
    // Between two chapters the voice is silent and the reading is not over. The service
    // must not be let go for that moment: Android will not start one again from behind a
    // locked screen, and trying to is what ended the reading at the first chapter end.
    val voiced = narrator.speaking || narrator.carrying
    LaunchedEffect(voiced, bed.playing, state.book, state.chapter, heard, heard?.index) {
        if (voiced || bed.playing) {
            ReadingService.hold = { narrator.stop(); bed.pause() }
            ReadingService.start(
                ctx,
                // Music on its own is not "reading Psalm 23"; say what is actually sounding.
                if (!voiced) "Music"
                else if (heard != null) heard.title
                else bible.reference(verseId(state.book, state.chapter, 1)).substringBeforeLast(":"),
                buildString {
                    if (voiced && heard != null) {
                        val at = heard.section
                        val name = at?.title?.replace('\n', ' ')?.ifBlank { null } ?: at?.label
                        if (name != null) append(name).append(" \u00b7 ")
                    }
                    if (voiced) append(narrator.voice)
                    // What is actually sounding — under rotation the bed changes while the
                    // notification stays up.
                    (bed.sounding ?: bed.track)?.let {
                        if (isNotEmpty()) append(" \u00b7 ")
                        append(if (it == Bed.ROTATE) "Music" else Beds.label(it))
                    }
                },
            )
        } else {
            ReadingService.stop(ctx)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Reader(
            bible = bible,
            narrator = narrator,
            state = state,
            onOpenPicker = { picking = true },
            onOpenStudy = { v, w -> studying = v to w },
            onReadFrom = { i -> narrator.speak(state.narrationLines(), state.lineOfVerse(i)) },
            onStudyLayers = { layering = true },
            // Press and drag marks the span the moment the finger lifts, in whatever
            // colour was used last. Nothing to confirm: in a sermon the verse is gone by
            // the time a dialog is answered, and an unwanted highlight is one tap to undo.
            dragSpan = dragging,
            onDragStart = { v -> dragging = v to v },
            onDragTo = { v -> dragging = dragging?.let { it.first to v } },
            onDragEnd = {
                dragging?.let { (from, to) ->
                    val lo = minOf(from, to)
                    val hi = maxOf(from, to)
                    val before = state.markOf(lo)
                    state.markRange(lo, hi, prefs.lastColour())
                    undo = Triple(lo, before, before != null)
                    // Leave it selected, so the colour row and Note are one tap away for
                    // anyone who wants them — but the mark is already saved either way.
                    anchor = lo
                    selection = setOf(lo, hi)
                }
                dragging = null
            },
            bed = bed,
            prefs = prefs,
            onSearch = { screen = "search" },
            onMenu = { screen = "menu" },
            // The voice belongs to whichever page is in front.
            voiced = screen != "book",
            onBooks = { narrator.reset(); screen = if (session != null) "book" else "books" },
            crumb = session?.takeIf { crumbAt >= 0 && state.depth == crumbAt }?.title,
            onCrumb = {
                narrator.reset()
                // Put the Bible back where it was before the book sent it somewhere.
                if (crumbPushed) state.back()
                crumbAt = -1
                selection = emptySet()
                screen = "book"
            },
            selection = selection,
            // Tap a verse number and the verse is selected; tap another, further down the
            // page or up it, and everything between the two is. Then the colour row and
            // Note at the foot of the screen apply to the whole span, one verse or twenty.
            // Nothing is saved until a colour is picked, and tapping the one selected
            // verse again lets it go.
            onPickVerse = { v ->
                val from = anchor
                when {
                    selection.isEmpty() || from == null -> { anchor = v; selection = setOf(v) }
                    selection == setOf(v) -> { anchor = null; selection = emptySet() }
                    else -> selection = setOf(from, v)
                }
            },
            onOpenVerse = { v, i ->
                if (selection.isNotEmpty()) selection = emptySet()
                acting = v to i
            },
            onOpenNote = { lo, hi ->
                if (selection.isNotEmpty()) selection = emptySet()
                noting = lo to hi
            },
        )

        // What just happened to a bookmark, and the chance to take it back. It sits above the
        // colour bar's place so the two never fight for the same strip of screen.
        undo?.let { (verse, before, had) ->
            androidx.compose.runtime.LaunchedEffect(verse, had) {
                kotlinx.coroutines.delay(3600)
                undo = null
            }
            UndoBar(
                text = if (had) "Bookmark removed" else "Bookmarked ${bible.reference(verse)}",
                onUndo = { state.restore(verse, before); undo = null },
                onDismiss = { undo = null },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // The colour bar, only while something is selected.
        androidx.compose.animation.AnimatedVisibility(
            visible = selection.isNotEmpty(),
            enter = androidx.compose.animation.fadeIn() +
                androidx.compose.animation.slideInVertically { it },
            exit = androidx.compose.animation.fadeOut() +
                androidx.compose.animation.slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            // AnimatedVisibility keeps drawing this while it slides away, by which time the
            // selection is already empty: hold the last range rather than reading it live.
            val span = remember(selection) {
                if (selection.isEmpty()) null else selection.min() to selection.max()
            }
            val held = remember { mutableStateOf(span) }
            if (span != null) held.value = span
            val (lo, hi) = held.value ?: (0 to 0)

            MarkBar(
                // Count the verses actually on the page between the two ends: verse ids are
                // book/chapter/verse packed into an int, so subtracting them means nothing.
                count = state.verses.count { it.id in lo..hi }.coerceAtLeast(1),
                // One mark across the whole span, not one per verse.
                onPick = { c ->
                    state.markRange(lo, hi, c)
                    prefs.rememberColour(c)      // the drag gesture follows suit
                    selection = emptySet()
                },
                onClear = { state.markRange(lo, hi, null); selection = emptySet() },
                onNote = {
                    if (lo > 0) noting = lo to hi
                    selection = emptySet()
                },
                onCopy = {
                    val picked = state.verses.filter { it.id in lo..hi }
                    val book = bible.books.getOrNull(lo.book - 1)
                    if (picked.isNotEmpty() && book != null) {
                        val first = picked.first().id.verse
                        val last = picked.last().id.verse
                        val ref = "${book.name} ${lo.chapter}:$first" +
                            if (last > first) "\u2013$last" else ""
                        // Verse numbers only when there is more than one verse to tell apart.
                        val body = if (picked.size == 1) picked[0].text
                            else picked.joinToString(" ") { "${it.id.verse} ${it.text}" }
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString("$ref (BSB)\n$body"))
                        // Android 13 and up show their own "Copied" overlay.
                        if (Build.VERSION.SDK_INT < 33) {
                            android.widget.Toast.makeText(ctx, "Copied $ref", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    selection = emptySet()
                },
                onDone = { selection = emptySet() },
            )
        }

        // A new build, already downloaded and checked. Offered at a quiet moment only.
        androidx.compose.animation.AnimatedVisibility(
            visible = update != null && !narrator.speaking && selection.isEmpty(),
            enter = androidx.compose.animation.fadeIn() +
                androidx.compose.animation.slideInVertically { it },
            exit = androidx.compose.animation.fadeOut() +
                androidx.compose.animation.slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            UpdateBar(
                version = update ?: "",
                onInstall = { Updates.installPending(ctx) },
                onDismiss = { dismissed = update; update = null },
            )
        }
    }

    // Back closes whatever is open before it leaves the app.
    androidx.activity.compose.BackHandler(
        enabled = screen != "read" || selection.isNotEmpty() || state.canGoBack ||
            (session != null && crumbAt >= 0 && state.depth == crumbAt)
    ) {
        when {
            screen == "book" -> shutBook()
            screen == "books" -> screen = "read"
            selection.isNotEmpty() -> selection = emptySet()
            screen == "library" || screen == "settings" || screen == "goover" -> screen = "menu"
            screen != "read" -> screen = "read"
            // A passage a book opened: back is back to the book.
            session != null && crumbAt >= 0 && state.depth == crumbAt -> {
                narrator.reset()
                if (crumbPushed) state.back()
                crumbAt = -1
                screen = "book"
            }
            // On the page itself, back retraces the links that were followed.
            else -> { narrator.reset(); state.back() }
        }
    }

    when (screen) {
        "search" -> SearchScreen(
            bible = bible,
            onOpen = { v, to -> screen = "read"; narrator.reset(); land(v, to) },
            onClose = { screen = "read" },
        )
        "books" -> ShelfScreen(
            library = library,
            prefs = prefs,
            rate = prefs.rate,
            onOpen = openBook,
            onClose = { screen = "read" },
        )
        "book" -> {
            val s = session
            // The moment between the tap and the first page: paper, not the Bible showing through.
            if (s == null) Box(
                Modifier.fillMaxSize().background(LocalPalette.current.paper)
                    .pointerInput(Unit) { }
            )
            else BookReader(
                session = s,
                bible = bible,
                narrator = narrator,
                bed = bed,
                prefs = prefs,
                onShelf = shutBook,
                onBible = { narrator.reset(); crumbAt = -1; screen = "read" },
                onOpenBible = { at, to ->
                    narrator.reset()
                    val before = state.depth
                    land(at, to)
                    crumbPushed = state.depth > before
                    crumbAt = state.depth
                    screen = "read"
                },
            )
        }
        "menu" -> MenuScreen(
            onBooks = { screen = if (session != null) "book" else "books" },
            onLibrary = { screen = "library" },
            onSettings = { screen = "settings" },
            onClose = { screen = "read" },
            due = due,
            onGoOver = { screen = "goover" },
        )
        "goover" -> questions?.let { q ->
            GoOverScreen(
                bible = bible,
                questions = q,
                onRead = { v ->
                    screen = "read"; narrator.reset(); state.go(v.book, v.chapter, at = v.verse)
                },
                onClose = { screen = "read" },
            )
        }
        "library" -> LibraryScreen(
            bible = bible,
            onOpen = { v ->
                screen = "read"; narrator.reset(); state.go(v.book, v.chapter, at = v.verse)
            },
            onClose = { screen = "read" },
        )
        "settings" -> SettingsScreen(
            prefs = prefs,
            bible = bible,
            narrator = narrator,
            onClose = { screen = "read" },
            onLayers = { layering = true },
        )
    }

    if (picking) ChapterPicker(
        bible = bible,
        current = state.book,
        onPick = { b, c -> picking = false; narrator.reset(); state.go(b, c) },
        onDismiss = { picking = false },
    )

    noting?.let { (lo, hi) ->
        NoteSheet(
            reference = if (lo == hi) bible.reference(lo)
                        else bible.reference(lo) + "\u2013" + hi.verse,
            verseText = state.verses.filter { it.id in lo..hi }
                .joinToString(" ") { it.text },
            existing = state.noteAcross(lo, hi),
            onSave = { state.noteRange(lo, hi, it) },
            onDismiss = { noting = null },
        )
    }

    acting?.let { (verse, index) ->
        VerseSheet(
            reference = bible.reference(verse),
            verseText = state.verses.firstOrNull { it.id == verse }?.text.orEmpty(),
            bookmarked = state.markOf(verse) != null,
            colour = state.markOf(verse),
            note = state.noteOf(verse),
            onBookmark = { state.toggle(verse) },
            onColour = { c -> state.mark(setOf(verse), c); acting = null },
            onNote = { acting = null; noting = verse to verse },
            onStudy = { acting = null; studying = verse to null },
            onReadFrom = {
                acting = null
                narrator.speak(state.narrationLines(), state.lineOfVerse(index))
            },
            onCopy = {
                val text = state.verses.firstOrNull { it.id == verse }?.text.orEmpty()
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText(
                    bible.reference(verse), "$text\n\n\u2014 ${bible.reference(verse)} (BSB)"))
                acting = null
            },
            onSelectRange = { acting = null; anchor = verse; selection = setOf(verse) },
            onDismiss = { acting = null },
        )
    }

    if (layering) {
        StudyLayersSheet(
            layers = state.layers,
            hasCommentary = bible.hasCommentary,
            hasStudy = bible.hasStudy,
            onChange = { state.chooseLayers(it); prefs.setStudyLayers(it) },
            onDismiss = { layering = false },
        )
    }

    studying?.let { (verse, word) ->
        StudySheet(
            bible = bible,
            verse = verse,
            wordAt = word,
            onGo = { target ->
                studying = null
                narrator.reset()
                state.go(target.book, target.chapter, at = target.verse)
            },
            onReadFrom = {
                val i = state.rows.filterIsInstance<Row.Verse>()
                    .firstOrNull { it.verse.id == verse }?.index
                if (i != null) narrator.speak(state.narrationLines(), state.lineOfVerse(i))
            },
            onDismiss = { studying = null },
        )
    }
}

/**
 * Shown while verses are selected: pick a colour, note, copy, clear, or dismiss.
 *
 * Two lines: the count and the colours, then the actions. On one line the four actions
 * pushed Done off the edge of a narrow phone.
 */
@Composable
private fun MarkBar(
    count: Int,
    onPick: (MarkColor) -> Unit,
    onClear: () -> Unit,
    onNote: () -> Unit,
    onCopy: () -> Unit,
    onDone: () -> Unit,
) {
    val p = LocalPalette.current
    Surface(color = p.sunk) {
        androidx.compose.foundation.layout.Column(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.rule))
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (count == 1) "1 verse" else "$count verses",
                    fontFamily = UiFont, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = p.inkSoft,
                )
                Spacer(Modifier.weight(1f))
                MarkColor.entries.forEach { c ->
                    Spacer(Modifier.width(9.dp))
                    Box(
                        Modifier.size(34.dp).clip(CircleShape)
                            .background(c.color(p.dark))
                            .clickable { onPick(c) }
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Clear", fontFamily = UiFont, fontSize = 12.sp, color = p.inkSoft,
                    modifier = Modifier.clickable { onClear() }.padding(8.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "Copy", fontFamily = UiFont, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = p.ink,
                    modifier = Modifier.clickable { onCopy() }.padding(8.dp),
                )
                Text(
                    "Note", fontFamily = UiFont, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = p.ink,
                    modifier = Modifier.clickable { onNote() }.padding(8.dp),
                )
                Text(
                    "Done", fontFamily = UiFont, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = p.gold,
                    modifier = Modifier.clickable { onDone() }.padding(8.dp),
                )
            }
        }
    }
}


/**
 * A brief word on what just happened, with the chance to undo it.
 *
 * Bookmarking is one tap and saves at once, which is only safe if a mis-tap costs one more
 * tap to put right. It fades itself after a few seconds rather than waiting to be dismissed.
 */
@Composable
private fun UndoBar(
    text: String,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    Surface(
        color = p.sunk,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(11.dp),
        shadowElevation = 8.dp,
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            // Clear of the player, which keeps the bottom strip.
            .padding(start = 18.dp, end = 18.dp, bottom = 108.dp),
    ) {
        Row(
            Modifier.padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, fontFamily = UiFont, fontSize = 13.sp, color = p.ink)
            Spacer(Modifier.width(18.dp))
            Text(
                "Undo",
                fontFamily = UiFont, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = p.gold,
                modifier = Modifier
                    .clickable { onUndo() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * A new build is ready to install.
 *
 * It sits above the player rather than interrupting, and says the version so the reader can
 * see it is a real change. Dismissing it is for this sitting only: the APK stays downloaded,
 * and Settings still offers it.
 */
@Composable
private fun UpdateBar(version: String, onInstall: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    Surface(
        color = p.gold,
        modifier = Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Version $version is ready",
                    fontFamily = UiFont, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    color = p.paper,
                )
                Text(
                    "Downloaded and checked",
                    fontFamily = UiFont, fontSize = 11.sp,
                    color = p.paper.copy(alpha = 0.75f),
                )
            }
            Text(
                "Later",
                fontFamily = UiFont, fontSize = 13.sp, color = p.paper.copy(alpha = 0.8f),
                modifier = Modifier.clickable(onClick = onDismiss)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
            Surface(onClick = onInstall, shape = RoundedCornerShape(8.dp), color = p.paper) {
                Text(
                    "Install",
                    fontFamily = UiFont, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    color = p.gold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
        }
    }
}

/**
 * The book an intent hands over, if it hands one over: an epub opened from a file manager,
 * a download or a message. Some senders give the proper type and some only the name.
 */
internal fun epubOf(intent: Intent?): android.net.Uri? {
    if (intent == null || intent.action != Intent.ACTION_VIEW) return null
    val uri = intent.data ?: return null
    if (uri.scheme != "content" && uri.scheme != "file") return null
    val named = uri.lastPathSegment?.endsWith(".epub", ignoreCase = true) == true
    return if (intent.type == "application/epub+zip" || named) uri else null
}
