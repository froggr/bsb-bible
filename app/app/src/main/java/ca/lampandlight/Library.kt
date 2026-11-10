package ca.lampandlight

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * The reader's own books: epubs brought in from the phone and kept in the app's private
 * storage, with where each was left.
 *
 * A book is copied in, not linked to. The file a picker hands over can vanish with the
 * permission that came with it, and a shelf with holes in it is worse than one that costs
 * a megabyte a volume. Nothing here is uploaded, backed up or shipped: the files sit under
 * `files/books/` beside a small database of what they are.
 *
 * A book is known by a hash of its bytes, so bringing the same folder in twice adds
 * nothing, and the same book under two file names is one book.
 */
class Library(private val ctx: Context) {

    data class Volume(
        val id: String,
        val title: String,
        val author: String,
        val added: Long,
        /** When it was last read; 0 for a book never opened. */
        val opened: Long,
        /** Where it was left: a section, and a block within it. */
        val section: Int,
        val block: Int,
        val words: Int,
        /** How far through, 0 to 1, by words. */
        val progress: Float,
        val cover: Boolean,
        val sections: Int,
    ) {
        val started: Boolean get() = opened > 0
    }

    /** An import under way: [done] of [total], and the file in hand. */
    data class Adding(val done: Int, val total: Int, val name: String)

    /** What an import came to. [failed] pairs a file's name with why it was left out. */
    data class Report(val added: Int, val already: Int, val failed: List<Pair<String, String>>) {
        val nothing: Boolean get() = added == 0 && already == 0 && failed.isEmpty()
    }

    var volumes by mutableStateOf<List<Volume>>(emptyList()); private set
    var adding by mutableStateOf<Adding?>(null); private set
    var report by mutableStateOf<Report?>(null)

    private val dir = File(ctx.filesDir, "books").apply { mkdirs() }
    private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(File(ctx.filesDir, "books.db"), null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val covers = LruCache<String, ImageBitmap>(48)

    init {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS book(
                 id TEXT PRIMARY KEY, title TEXT NOT NULL, author TEXT NOT NULL,
                 added INTEGER NOT NULL, opened INTEGER NOT NULL DEFAULT 0,
                 section INTEGER NOT NULL DEFAULT 0, block INTEGER NOT NULL DEFAULT 0,
                 words INTEGER NOT NULL, progress REAL NOT NULL DEFAULT 0,
                 cover INTEGER NOT NULL DEFAULT 0, parser INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS section(
                 book TEXT NOT NULL, idx INTEGER NOT NULL, path TEXT NOT NULL,
                 lo INTEGER NOT NULL, hi INTEGER NOT NULL, label TEXT, title TEXT NOT NULL,
                 listed INTEGER NOT NULL, words INTEGER NOT NULL, PRIMARY KEY(book, idx))"""
        )
        // A copy that was cut short — the app killed mid-import — is only litter.
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
        reload()
    }

    fun file(id: String) = File(dir, "$id.epub")
    private fun coverFile(id: String) = File(dir, "$id.jpg")

    fun volume(id: String): Volume? = volumes.firstOrNull { it.id == id }

    private fun reload() {
        val rows = db.rawQuery(
            """SELECT b.id,b.title,b.author,b.added,b.opened,b.section,b.block,b.words,b.progress,b.cover,
                      (SELECT COUNT(*) FROM section s WHERE s.book=b.id)
               FROM book b""", null
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Volume(
                        c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4),
                        c.getInt(5), c.getInt(6), c.getInt(7), c.getFloat(8), c.getInt(9) != 0, c.getInt(10),
                    )
                )
            }
        }
        volumes = rows
    }

    /** The book's sections as they were found when it was brought in. */
    fun sections(id: String): List<Epub.Section> =
        db.rawQuery(
            "SELECT path,lo,hi,label,title,listed,words FROM section WHERE book=? ORDER BY idx", arrayOf(id)
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Epub.Section(
                        c.getString(0), c.getInt(1), c.getInt(2), if (c.isNull(3)) null else c.getString(3),
                        c.getString(4), c.getInt(5) != 0, c.getInt(6),
                    )
                )
            }
        }

    /**
     * Read the book again with the parser as it is now, when it was brought in by an older
     * one. The place is kept by section number, which a better parser may shift by a page;
     * that is the price of not being stuck with yesterday's mistakes on every book owned.
     */
    fun refresh(id: String) {
        val parser = db.rawQuery("SELECT parser FROM book WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToNext()) c.getInt(0) else return
        }
        if (parser >= PARSER) return
        runCatching {
            Epub(file(id)).use { epub ->
                val sections = epub.sections()
                if (sections.isEmpty()) return
                db.beginTransaction()
                try {
                    writeSections(id, sections)
                    db.execSQL(
                        "UPDATE book SET parser=?, words=? WHERE id=?",
                        arrayOf<Any>(PARSER, sections.sumOf { it.words }, id),
                    )
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }.onFailure { Log.w(TAG, "refresh $id", it) }
    }

    private fun writeSections(id: String, sections: List<Epub.Section>) {
        db.execSQL("DELETE FROM section WHERE book=?", arrayOf(id))
        sections.forEachIndexed { i, s ->
            db.execSQL(
                "INSERT INTO section(book,idx,path,lo,hi,label,title,listed,words) VALUES(?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(id, i, s.path, s.from, s.to, s.label, s.title, if (s.listed) 1 else 0, s.words),
            )
        }
    }

    /** Mark a book as being read now, so the shelf can put it first. */
    fun touch(id: String) {
        val now = System.currentTimeMillis()
        volumes = volumes.map { if (it.id == id) it.copy(opened = now) else it }
        scope.launch { db.execSQL("UPDATE book SET opened=? WHERE id=?", arrayOf<Any>(now, id)) }
    }

    /** Remember the place. Called as the page moves, so the write is off the main thread. */
    fun place(id: String, section: Int, block: Int, progress: Float) {
        val was = volume(id) ?: return
        if (was.section == section && was.block == block) return
        volumes = volumes.map { if (it.id == id) it.copy(section = section, block = block, progress = progress) else it }
        scope.launch {
            db.execSQL(
                "UPDATE book SET section=?, block=?, progress=? WHERE id=?",
                arrayOf<Any>(section, block, progress, id),
            )
        }
    }

    fun remove(id: String) {
        volumes = volumes.filterNot { it.id == id }
        covers.remove(id)
        scope.launch {
            db.execSQL("DELETE FROM section WHERE book=?", arrayOf(id))
            db.execSQL("DELETE FROM book WHERE id=?", arrayOf(id))
            file(id).delete(); coverFile(id).delete()
        }
    }

    /** The cover, read off disk once and kept. Null for a book that came without one. */
    suspend fun cover(v: Volume): ImageBitmap? {
        if (!v.cover) return null
        covers.get(v.id)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(coverFile(v.id).path)?.asImageBitmap() }.getOrNull()
        }?.also { covers.put(v.id, it) }
    }

    fun coverNow(v: Volume): ImageBitmap? = if (v.cover) covers.get(v.id) else null

    // ------------------------------------------------------------------ bringing books in

    val busy: Boolean get() = adding != null

    fun cancel() { job?.cancel() }

    /** Bring in the files picked, or handed over by another app. */
    fun add(uris: List<Uri>) {
        if (uris.isEmpty() || busy) return
        adding = Adding(0, uris.size, "")
        job = scope.launch { take(uris.map { it to nameOf(it) }) }
    }

    /** Bring in every epub under a folder the reader pointed at, however deep. */
    fun addFolder(tree: Uri) {
        if (busy) return
        adding = Adding(0, 0, "Looking through the folder")
        job = scope.launch {
            val found = runCatching { walk(tree) }.getOrElse {
                Log.w(TAG, "walk", it); emptyList()
            }
            if (found.isEmpty()) {
                adding = null
                report = Report(0, 0, listOf("That folder" to "has no epub files in it"))
                return@launch
            }
            take(found)
        }
    }

    private suspend fun take(files: List<Pair<Uri, String>>) {
        var added = 0
        var already = 0
        val failed = ArrayList<Pair<String, String>>()
        try {
            files.forEachIndexed { i, (uri, name) ->
                if (!scope.isActive || job?.isActive == false) return@forEachIndexed
                adding = Adding(i, files.size, name)
                when (val r = runCatching { one(uri) }.getOrElse {
                    Log.w(TAG, "import $name", it)
                    Outcome.Failed(reason(it))
                }) {
                    Outcome.Added -> { added++; reload() }
                    Outcome.Already -> already++
                    is Outcome.Failed -> failed += name to r.why
                }
            }
        } finally {
            reload()
            adding = null
            report = Report(added, already, failed)
        }
    }

    private sealed interface Outcome {
        data object Added : Outcome
        data object Already : Outcome
        data class Failed(val why: String) : Outcome
    }

    private fun reason(e: Throwable): String = when {
        e is java.util.zip.ZipException -> "is not an epub"
        e is IllegalArgumentException && e.message?.startsWith("not an epub") == true -> "is not an epub"
        e is TooBig -> "is too large to be a book"
        e is OutOfMemoryError -> "is too large to open"
        e is java.io.FileNotFoundException || e is SecurityException -> "could not be opened"
        else -> "could not be read"
    }

    private class TooBig : RuntimeException()

    private fun one(uri: Uri): Outcome {
        // Copied to a file of our own first, hashing on the way: a ZipFile needs to seek,
        // and the hash is the book's name here.
        val part = File(dir, "incoming-${System.nanoTime()}.part")
        try {
            val sha = MessageDigest.getInstance("SHA-256")
            val input = ctx.contentResolver.openInputStream(uri) ?: return Outcome.Failed("could not be opened")
            input.use { src ->
                part.outputStream().use { dst ->
                    val buf = ByteArray(64 shl 10)
                    var total = 0L
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw TooBig()
                        sha.update(buf, 0, n)
                        dst.write(buf, 0, n)
                    }
                }
            }
            val id = sha.digest().take(10).joinToString("") { "%02x".format(it) }
            if (file(id).exists() && volumes.any { it.id == id }) return Outcome.Already

            Epub(part).use { epub ->
                if (epub.locked) return Outcome.Failed("is copy-protected")
                val sections = epub.sections()
                val words = sections.sumOf { it.words }
                if (sections.isEmpty() || words == 0) return Outcome.Failed("has no text in it")
                val start = epub.startOf(sections).coerceIn(0, sections.lastIndex)
                val cover = epub.coverPath?.let { epub.bytes(it, 12 shl 20) }?.let { saveCover(id, it) } == true

                if (!part.renameTo(file(id))) return Outcome.Failed("could not be saved")
                db.beginTransaction()
                try {
                    db.execSQL(
                        """INSERT OR REPLACE INTO book(id,title,author,added,opened,section,block,words,progress,cover,parser)
                           VALUES(?,?,?,?,0,?,0,?,0,?,?)""",
                        arrayOf<Any>(
                            id, Epub.typeset(epub.title), epub.author, System.currentTimeMillis(),
                            start, words, if (cover) 1 else 0, PARSER,
                        ),
                    )
                    writeSections(id, sections)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            return Outcome.Added
        } finally {
            part.delete()
        }
    }

    /** The cover, scaled to what a shelf shows. A book's own can be a 4000-pixel scan. */
    private fun saveCover(id: String, bytes: ByteArray): Boolean = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= COVER_PX) sample *= 2
        val raw = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return false
        val k = COVER_PX.toFloat() / raw.height
        val fit = if (k < 1f)
            Bitmap.createScaledBitmap(raw, (raw.width * k).toInt().coerceAtLeast(1), COVER_PX, true)
        else raw
        coverFile(id).outputStream().use { fit.compress(Bitmap.CompressFormat.JPEG, 86, it) }
        true
    }.getOrDefault(false)

    private fun nameOf(uri: Uri): String = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "A file"

    /** Every epub under [tree]. Folders within folders are followed, to a sane depth. */
    private fun walk(tree: Uri): List<Pair<Uri, String>> {
        val out = ArrayList<Pair<Uri, String>>()
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        fun into(docId: String, depth: Int) {
            if (depth > 8 || out.size >= MAX_FILES) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < MAX_FILES) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: ""
                    val mime = c.getString(2) ?: ""
                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR -> into(id, depth + 1)
                        name.endsWith(".epub", true) || mime == "application/epub+zip" ->
                            out += DocumentsContract.buildDocumentUriUsingTree(tree, id) to name
                    }
                }
            }
        }
        into(DocumentsContract.getTreeDocumentId(tree), 0)
        return out.sortedBy { it.second.lowercase() }
    }

    companion object {
        private const val TAG = "library"
        /** Bumped when [Epub] reads a book differently enough that old sections are wrong. */
        const val PARSER = 1
        private const val COVER_PX = 640
        private const val MAX_BYTES = 300L shl 20
        private const val MAX_FILES = 5000

        /** "The Pursuit of God" sorts under P. */
        fun sortKey(title: String): String =
            title.lowercase().removePrefix("the ").removePrefix("a ").removePrefix("an ").trim()

        /** A file name with its extension off, for a list of what went wrong. */
        fun plain(name: String): String = name.removeSuffix(".epub").removeSuffix(".EPUB")
    }
}
