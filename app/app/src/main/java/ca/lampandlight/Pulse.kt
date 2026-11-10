package ca.lampandlight

import android.content.Context

/**
 * How well the voice kept up, with the app on screen and with it away, so a phone with no
 * cable attached can still say why a reading dragged.
 *
 * The voice is made on the phone a clause at a time, a little ahead of where it is heard.
 * While the model makes speech faster than it is spoken that is invisible. When Android
 * slows the app down — it may, once the app is off the screen — the voice finishes a clause
 * before the next exists, and the listener hears a pause nobody wrote. The emulator runs on
 * a desktop processor and makes speech fifteen times faster than it is heard whatever the
 * screen is doing, so it cannot show this; only the phone can, and this is where it says so.
 *
 * Two numbers per side: how fast speech was made against how fast it is heard, and how often
 * and how long the voice was left waiting. Kept across launches, started again with each
 * version so a new build is judged on its own readings.
 */
object Pulse {
    /** Whether the app is what the reader is looking at. Set by the activity. */
    @Volatile var onScreen = true

    class Side(val key: String) {
        var audio = 0f      // seconds of speech made
        var work = 0f       // seconds spent making it
        var slowest = 0f    // the worst single clause, audio over work; 0 until one is made
        var waits = 0       // times the voice ran out of things to say
        var waited = 0L     // the silence that cost, ms
        var longest = 0L
    }

    private val shown = Side("shown")
    private val away = Side("away")
    private fun side() = if (onScreen) shown else away

    /** A wait shorter than this is a breath, not a stall. */
    const val STALL_MS = 250L

    @Synchronized fun made(audio: Float, work: Float) {
        if (audio < 1f || work <= 0f) return     // a two-word clause times the clock, not the model
        val s = side()
        s.audio += audio; s.work += work
        val x = audio / work
        if (s.slowest == 0f || x < s.slowest) s.slowest = x
    }

    @Synchronized fun waited(ms: Long) {
        if (ms < STALL_MS) return
        val s = side()
        s.waits++; s.waited += ms
        if (ms > s.longest) s.longest = ms
    }

    /** What to show in Settings: one line per side that has read anything. */
    @Synchronized fun lines(): List<String> = listOf(
        "Reading aloud, app open" to shown, "Reading aloud, app away or screen off" to away,
    ).filter { it.second.work > 0f }.map { (name, s) ->
        val speed = "%.1f× listening speed, slowest %.1f×".format(s.audio / s.work, s.slowest)
        val waits = when (s.waits) {
            0 -> "never left waiting"
            1 -> "left waiting once, ${span(s.waited)}"
            else -> "left waiting ${s.waits} times, ${span(s.waited)} in all, longest ${span(s.longest)}"
        }
        "$name: voice made at $speed, over ${span((s.audio * 1000).toLong())} · $waits"
    }

    private fun span(ms: Long): String {
        val s = ms / 1000f
        return when {
            s < 10f -> "%.1f s".format(s)
            s < 90f -> "${s.toInt()} s"
            else -> "${(s / 60).toInt()} min"
        }
    }

    @Synchronized fun load(ctx: Context) {
        val sp = ctx.getSharedPreferences("pulse", Context.MODE_PRIVATE)
        if (sp.getString("version", "") != version(ctx)) return
        for (s in listOf(shown, away)) {
            s.audio = sp.getFloat("${s.key}.audio", 0f); s.work = sp.getFloat("${s.key}.work", 0f)
            s.slowest = sp.getFloat("${s.key}.slowest", 0f); s.waits = sp.getInt("${s.key}.waits", 0)
            s.waited = sp.getLong("${s.key}.waited", 0L); s.longest = sp.getLong("${s.key}.longest", 0L)
        }
    }

    @Synchronized fun save(ctx: Context) {
        val e = ctx.getSharedPreferences("pulse", Context.MODE_PRIVATE).edit()
        e.putString("version", version(ctx))
        for (s in listOf(shown, away)) {
            e.putFloat("${s.key}.audio", s.audio).putFloat("${s.key}.work", s.work)
                .putFloat("${s.key}.slowest", s.slowest).putInt("${s.key}.waits", s.waits)
                .putLong("${s.key}.waited", s.waited).putLong("${s.key}.longest", s.longest)
        }
        e.apply()
    }

    private fun version(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull() ?: "?"
}
