package ca.lampandlight

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Fetches the optional data layers and newer builds from the little server in `serve.py`.
 *
 * The APK carries the Bible text only, so the word-level study data and the commentary are
 * downloaded when asked for. The same manifest names the current build, so the app can tell
 * when a newer one is waiting.
 */
object Updates {
    private const val TAG = "lamplight"
    private const val PREFS = "updates"
    private const val KEY_HOST = "host"
    private const val KEY_CHECKED = "lastChecked"
    private const val KEY_READY = "readyVersion"
    private const val KEY_READY_SHA = "readySha"

    /** Where serve.py is. Settable, because it moves between the LAN and Tailscale. */
    fun host(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HOST, DEFAULT_HOST)!!

    fun setHost(ctx: Context, host: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOST, host.trim().removeSuffix("/")).apply()
    }

    /**
     * Where builds and optional downloads come from by default.
     *
     * A GitHub release, not a machine on anyone's home network: it costs nothing, has no
     * bandwidth limit, and keeps working when the laptop is shut. [setHost] still points the
     * app at a local `serve.py` for development.
     */
    const val DEFAULT_HOST =
        "https://github.com/froggr/bsb-bible/releases/latest/download"

    /**
     * Resolve a manifest path against [base].
     *
     * GitHub serves release assets from one flat directory, so `/beds/stillness.opus` has to
     * become `beds-stillness.opus` there, while the local server keeps its tidy folders. The
     * manifest stays the same either way and the host decides how to read it.
     */
    private fun resolve(base: String, path: String): String {
        val p = path.removePrefix("/")
        return if (base.contains("/releases/")) base + "/" + p.replace("/", "-")
        else "$base/$p"
    }

    /** How long to leave between looking for a new build. */
    private const val CHECK_EVERY = 20 * 60 * 1000L

    data class Remote(val version: String, val bytes: Long, val sha: String, val url: String)
    data class Layer(val name: String, val bytes: Long, val sha: String, val url: String)
    data class Manifest(
        val app: Remote?,
        val layers: List<Layer>,
        /** Bed name to the size the server holds, so a corrected bed replaces an old one. */
        val beds: Map<String, Long> = emptyMap(),
    )

    fun manifest(ctx: Context): Manifest {
        val base = host(ctx)
        val text = URL(resolve(base, "manifest.json")).openConnection().let { c ->
            (c as HttpURLConnection).apply { connectTimeout = 5000; readTimeout = 10000 }
            c.inputStream.bufferedReader().use { it.readText() }
        }
        val j = JSONObject(text)
        val app = j.optJSONObject("app")?.let {
            Remote(it.getString("version"), it.getLong("bytes"), it.getString("sha256"),
                resolve(base, it.getString("url")))
        }
        val layers = j.optJSONObject("layers")?.let { o ->
            o.keys().asSequence().map { k ->
                val v = o.getJSONObject(k)
                Layer(k, v.getLong("bytes"), v.getString("sha256"), resolve(base, v.getString("url")))
            }.toList()
        } ?: emptyList()
        val beds = j.optJSONObject("beds")?.let { o ->
            o.keys().asSequence().associateWith { k -> o.getJSONObject(k).optLong("bytes", 0L) }
        } ?: emptyMap()
        return Manifest(app, layers, beds)
    }

    /** Which layers are not on disk yet. */
    fun missing(ctx: Context, m: Manifest): List<Layer> =
        m.layers.filter { l ->
            val f = File(ctx.filesDir, l.name)
            !f.exists() || f.length() == 0L
        }

    /**
     * Download a layer, check it, and put it in place. The server gzips it, so this expands
     * as it reads; the checksum is of the compressed file, which is what the manifest lists.
     *
     * [onProgress] is called with bytes received out of [Layer.bytes].
     */
    fun fetchLayer(ctx: Context, layer: Layer, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        val dest = File(ctx.filesDir, layer.name)
        val tmp = File(ctx.filesDir, layer.name + ".part")
        val gz = File(ctx.filesDir, layer.name + ".gz.part")
        tmp.delete(); gz.delete()

        val conn = (URL(layer.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = 30000
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var seen = 0L
        conn.inputStream.use { input ->
            gz.outputStream().buffered(1 shl 16).use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                    seen += n
                    onProgress(seen, layer.bytes)
                }
            }
        }
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        if (got != layer.sha) {
            gz.delete()
            throw IllegalStateException("${layer.name}: checksum did not match")
        }
        GZIPInputStream(gz.inputStream().buffered(1 shl 16), 1 shl 16).use { g ->
            tmp.outputStream().buffered(1 shl 16).use { g.copyTo(it, 1 shl 16) }
        }
        gz.delete()
        if (dest.exists()) dest.delete()
        check(tmp.renameTo(dest)) { "could not move ${layer.name} into place" }
        Log.i(TAG, "${layer.name}: ${dest.length() / 1_000_000} MB ready")
        return dest
    }

    /**
     * Fetch the speech model from Hugging Face into app storage. It is ~65 MB and not everyone
     * wants it, so it is never shipped in the APK.
     */
    fun fetchModel(ctx: Context, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        val dir = File(ctx.filesDir, Narrator.MODEL_DIR).apply { mkdirs() }
        val files = Narrator.MODEL_FILES
        // Weight the progress by the one file that dominates the download.
        var done = 0
        for (name in files) {
            val dest = File(dir, name)
            if (dest.exists() && dest.length() > 0) { done++; continue }
            val tmp = File(dir, "$name.part")
            val conn = (URL(Narrator.MODEL_BASE + name).openConnection() as HttpURLConnection)
                .apply { connectTimeout = 8000; readTimeout = 60000; instanceFollowRedirects = true }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: 0L
            var seen = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().buffered(1 shl 16).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); seen += n
                        if (total > 0) onProgress(
                            ((done + seen.toFloat() / total) * 100 / files.size).toLong(), 100L)
                    }
                }
            }
            check(tmp.renameTo(dest)) { "could not move $name into place" }
            done++
            onProgress((done * 100L / files.size), 100L)
        }
        Log.i(TAG, "model ready in ${dir.path}")
        return dir
    }

    /**
     * Fetch what hearing needs into app storage: the recogniser and the voice detector,
     * 136 MB, for those who want a verse heard as they say it.
     *
     * From the release named in [Ears.BASE], whichever build this is, unless the app has
     * been pointed at another host, which then serves them from `ears/`. Each file is
     * checked against the length and SHA-256 written into the app, so what is loaded into
     * a native library is what was measured and nothing else. A file cut off part way is
     * taken up where it stopped: the big one is 131 MB, and a second try should not begin
     * it again.
     */
    fun fetchEars(ctx: Context, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        val dir = Ears.dir(ctx).apply { mkdirs() }
        val base = host(ctx).takeIf { it != DEFAULT_HOST } ?: Ears.BASE
        val total = Ears.PARTS.sumOf { it.bytes }
        var done = 0L
        for (part in Ears.PARTS) {
            val dest = File(dir, part.name)
            if (dest.length() == part.bytes) { done += part.bytes; onProgress(done, total); continue }
            dest.delete()
            val tmp = File(dir, "${part.name}.part")
            if (tmp.length() >= part.bytes) tmp.delete()
            var have = tmp.length()
            val conn = (URL(resolve(base, "${Ears.DIR}/${part.name}")).openConnection() as HttpURLConnection)
                .apply {
                    connectTimeout = 8000; readTimeout = 60000; instanceFollowRedirects = true
                    if (have > 0) setRequestProperty("Range", "bytes=$have-")
                }
            // Asked for the rest and sent all of it: begin again.
            if (have > 0 && conn.responseCode != HttpURLConnection.HTTP_PARTIAL) { tmp.delete(); have = 0 }
            conn.inputStream.use { input ->
                java.io.FileOutputStream(tmp, have > 0).buffered(1 shl 16).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); have += n
                        onProgress(done + have, total)
                    }
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            tmp.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
            }
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (tmp.length() != part.bytes || got != part.sha) {
                tmp.delete()
                throw IllegalStateException("${part.name} did not arrive whole")
            }
            check(tmp.renameTo(dest)) { "could not move ${part.name} into place" }
            done += part.bytes
            onProgress(done, total)
        }
        Log.i(TAG, "hearing ready in ${dir.path}")
        return dir
    }

    /**
     * Fetch the English voices into `<model>/voices/`, where Phonon looks for them.
     *
     * Each file is named after the voice, because Phonon takes the name from the filename:
     * `voices/Micah.safetensors` is the voice called "Micah". The repository stores them
     * under opaque ids instead, so this renames as it downloads.
     *
     * Voices already present are skipped, so this resumes after a failure.
     */
    fun fetchVoices(ctx: Context, onProgress: (Long, Long) -> Unit = { _, _ -> }): Int {
        val dir = File(File(ctx.filesDir, Narrator.MODEL_DIR), Voices.DIR).apply { mkdirs() }
        var done = 0
        val total = Voices.ALL.size.toLong()
        for (v in Voices.ALL) {
            val dest = File(dir, "${v.name}.safetensors")
            if (dest.exists() && dest.length() > 0) { done++; onProgress(done.toLong(), total); continue }
            val tmp = File(dir, "${v.name}.part")
            try {
                val conn = (URL(Voices.BASE + v.id + ".safetensors").openConnection() as HttpURLConnection)
                    .apply { connectTimeout = 8000; readTimeout = 60000; instanceFollowRedirects = true }
                conn.inputStream.use { input ->
                    tmp.outputStream().buffered(1 shl 16).use { input.copyTo(it, 1 shl 16) }
                }
                check(tmp.renameTo(dest)) { "could not move ${v.name} into place" }
                done++
            } catch (e: Exception) {
                tmp.delete()
                Log.w(TAG, "voice ${v.name} failed: ${e.message}")
            }
            onProgress(done.toLong(), total)
        }
        Log.i(TAG, "voices: $done of $total in ${dir.path}")
        return done
    }

    /** How many voices are already downloaded. */
    fun voicesPresent(ctx: Context): Int {
        val dir = File(File(ctx.filesDir, Narrator.MODEL_DIR), Voices.DIR)
        if (!dir.isDirectory) return 0
        return Voices.ALL.count { File(dir, "${it.name}.safetensors").let { f -> f.exists() && f.length() > 0 } }
    }

    /**
     * Fetch the music beds from the little server into `files/beds/`.
     *
     * About 12 MB for the eight of them, so they are downloaded rather than shipped: most of
     * the APK's weight is already the Bible text, and not everyone wants music.
     *
     * Beds already present are skipped, so an interrupted download resumes.
     */
    fun fetchBeds(ctx: Context, onProgress: (Long, Long) -> Unit = { _, _ -> }): Int {
        val dir = File(ctx.filesDir, Beds.DIR).apply { mkdirs() }
        val base = host(ctx)
        // What the server has. Unreachable means an empty map, and then nothing is replaced:
        // better to keep the beds on disk than to delete them because the network was down.
        val sizes = try { manifest(ctx).beds } catch (e: Exception) { emptyMap() }
        var done = 0
        val total = Beds.ALL.size.toLong()
        for (b in Beds.ALL) {
            val dest = File(dir, "${b.name}.opus")
            // Skip only a bed that matches what the server holds. Comparing the size as well
            // as mere existence is what lets a CORRECTED bed replace one already downloaded —
            // three of them were regenerated to cut vocal bleed, and without this the phones
            // that already had them would have kept the old ones for ever.
            val want = sizes[b.name] ?: 0L
            if (dest.exists() && dest.length() > 0 && (want == 0L || dest.length() == want)) {
                done++; onProgress(done.toLong(), total); continue
            }
            val tmp = File(dir, "${b.name}.part")
            try {
                val conn = (URL(resolve(base, "beds/${b.name}.opus")).openConnection() as HttpURLConnection)
                    .apply { connectTimeout = 8000; readTimeout = 60000; instanceFollowRedirects = true }
                conn.inputStream.use { input ->
                    tmp.outputStream().buffered(1 shl 16).use { input.copyTo(it, 1 shl 16) }
                }
                check(tmp.renameTo(dest)) { "could not move ${b.name} into place" }
                done++
            } catch (e: Exception) {
                tmp.delete()
                Log.w(TAG, "bed ${b.name} failed: ${e.message}")
            }
            onProgress(done.toLong(), total)
        }
        Log.i(TAG, "beds: $done of $total in ${dir.path}")
        return done
    }

    /** How many beds are already downloaded. */
    fun bedsPresent(ctx: Context): Int {
        val dir = File(ctx.filesDir, Beds.DIR)
        if (!dir.isDirectory) return 0
        return Beds.ALL.count { File(dir, "${it.name}.opus").let { f -> f.exists() && f.length() > 0 } }
    }

    /** True when the server advertises a build newer than this one. */
    fun updateAvailable(ctx: Context, m: Manifest): Boolean {
        val remote = m.app?.version ?: return false
        val local = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
        } catch (e: Exception) { "0" }
        return compareVersions(remote, local) > 0
    }

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrNull(i) ?: 0) - (pb.getOrNull(i) ?: 0)
            if (d != 0) return d
        }
        return 0
    }

    /**
     * Hand a downloaded APK to the system installer. Android asks the user to confirm, and
     * from Android 8 also to allow installs from this app the first time.
     */
    fun installApk(ctx: Context, apk: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, "${ctx.packageName}.files", apk)
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
    }

    /**
     * Look for a newer build and fetch it, ready to install.
     *
     * Quiet by design: it runs on a launch, says nothing when there is nothing to say, and
     * never interrupts a reading. The APK is only downloaded, never installed — Android
     * requires a person to confirm that, which is as it should be.
     *
     * Returns the version waiting, or null.
     */
    fun checkQuietly(ctx: Context): String? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        // Twenty minutes between checks. Six hours was the old figure, chosen when this
        // ran once per cold start; now that it runs whenever the app is resumed, six hours
        // meant a build published during the day would not be seen until the evening. The
        // check itself is one small JSON request, so the cost of being wrong is tiny — and
        // a build already downloaded is reported without going to the network at all.
        if (now - prefs.getLong(KEY_CHECKED, 0) < CHECK_EVERY) return pending(ctx)
        return runCatching {
            val m = manifest(ctx)
            prefs.edit().putLong(KEY_CHECKED, now).apply()
            if (!updateAvailable(ctx, m)) { clearPending(ctx); return@runCatching null }
            val app = m.app ?: return@runCatching null
            val apk = fetchApk(ctx, app)
            prefs.edit().putString(KEY_READY, app.version)
                .putString(KEY_READY_SHA, app.sha).apply()
            Log.i(TAG, "update ${app.version} downloaded, ${apk.length() / 1_000_000} MB")
            app.version
        }.getOrNull()
    }

    /** The version already downloaded and waiting, if the file is still there. */
    /** Record a downloaded build as the one waiting to be installed. */
    fun markReady(ctx: Context, app: Remote) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_READY, app.version).putString(KEY_READY_SHA, app.sha).apply()
    }

    /** The version name this app is actually running. */
    fun installed(ctx: Context): String =
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
        } catch (e: Exception) { "0" }

    /**
     * The build waiting to be installed, if one genuinely still is.
     *
     * The installed version is the authority, not the downloaded file. Android gives no
     * callback when a sideloaded install succeeds, so the APK and the stored version both
     * outlive it — and this used to go on offering a build the reader was already running.
     * Comparing against the package manager settles it, and clears up after the install.
     */
    fun pending(ctx: Context): String? {
        val ready = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_READY, null) ?: return null
        // Already on it, or past it: the install worked, so throw the APK away.
        if (compareVersions(ready, installed(ctx)) <= 0) { clearPending(ctx); return null }
        val f = File(ctx.cacheDir, "update.apk")
        if (!f.exists() || f.length() == 0L) { clearPending(ctx); return null }
        return ready
    }

    /** Forget the pending build and delete its APK: 49 MB is not worth keeping. */
    fun clearPending(ctx: Context) {
        File(ctx.cacheDir, "update.apk").delete()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_READY).remove(KEY_READY_SHA).apply()
    }

    /** Install what [checkQuietly] already fetched. */
    fun installPending(ctx: Context) {
        val f = File(ctx.cacheDir, "update.apk")
        if (f.exists() && f.length() > 0) installApk(ctx, f)
    }

    fun fetchApk(ctx: Context, app: Remote, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        val dest = File(ctx.cacheDir, "update.apk")
        if (dest.exists()) dest.delete()
        val conn = (URL(app.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = 30000
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var seen = 0L
        conn.inputStream.use { input ->
            dest.outputStream().buffered(1 shl 16).use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n); out.write(buf, 0, n); seen += n
                    onProgress(seen, app.bytes)
                }
            }
        }
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        if (got != app.sha) { dest.delete(); throw IllegalStateException("APK checksum did not match") }
        return dest
    }
}
