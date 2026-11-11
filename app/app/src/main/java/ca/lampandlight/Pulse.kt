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
 * and how long the voice was left waiting. Then the reason, as far as the phone will give
 * it: which cores the model was on out of those the app was allowed (see [Cores]), how much
 * of the time it stood ready to run with no core to run on, and how near full speed those
 * cores were. Kept across launches, started again with each version so a new
 * build is judged on its own readings.
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
        var ran = 0f        // seconds the thread making it had a core
        var queued = 0f     // seconds it stood ready to run and had none
        var clock = 0f      // how near full speed its cores were, summed over [clocked] looks
        var clocked = 0
        var cores = ""      // "3 threads on cores 4-6 of 0-6", as last seen
    }

    private val shown = Side("shown")
    private val away = Side("away")
    private fun side() = if (onScreen) shown else away

    /** A wait shorter than this is a breath, not a stall. */
    const val STALL_MS = 250L

    /**
     * One clause made: [audio] seconds of speech in [work] seconds, during which the thread
     * making it had a core for [ran] seconds and wanted one for [queued] more.
     * [clock] is [Cores.clock] as the clause ended.
     */
    @Synchronized fun made(
        audio: Float, work: Float, ran: Float = 0f, queued: Float = 0f, clock: Float? = null,
    ) {
        if (audio < 1f || work <= 0f) return     // a two-word clause times the clock, not the model
        val s = side()
        s.audio += audio; s.work += work
        s.ran += ran.coerceAtLeast(0f); s.queued += queued.coerceAtLeast(0f)
        if (clock != null) { s.clock += clock; s.clocked++ }
        val x = audio / work
        if (s.slowest == 0f || x < s.slowest) s.slowest = x
    }

    /** Where the model is running: [threads] of them on [chosen], out of [allowed]. */
    @Synchronized fun on(threads: Int, chosen: Set<Int>, allowed: Set<Int>, held: Boolean) {
        if (allowed.isEmpty()) return
        side().cores = "$threads threads on cores ${Cores.span(chosen)} of ${Cores.span(allowed)}" +
            if (held) "" else ", not held there"
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
        val where = buildList {
            if (s.cores.isNotEmpty()) add(s.cores)
            if (s.ran + s.queued > 0f)
                add("kept waiting for a core ${(100 * s.queued / (s.ran + s.queued)).toInt()}% of the time")
            if (s.clocked > 0) add("cores at ${(100 * s.clock / s.clocked).toInt()}% of full speed")
        }.joinToString(", ")
        "$name: voice made at $speed, over ${span((s.audio * 1000).toLong())} · $waits · $where"
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
            s.ran = sp.getFloat("${s.key}.ran", 0f); s.queued = sp.getFloat("${s.key}.queued", 0f)
            s.clock = sp.getFloat("${s.key}.clock", 0f)
            s.clocked = sp.getInt("${s.key}.clocked", 0); s.cores = sp.getString("${s.key}.cores", "") ?: ""
        }
    }

    @Synchronized fun save(ctx: Context) {
        val e = ctx.getSharedPreferences("pulse", Context.MODE_PRIVATE).edit()
        e.putString("version", version(ctx))
        for (s in listOf(shown, away)) {
            e.putFloat("${s.key}.audio", s.audio).putFloat("${s.key}.work", s.work)
                .putFloat("${s.key}.slowest", s.slowest).putInt("${s.key}.waits", s.waits)
                .putLong("${s.key}.waited", s.waited).putLong("${s.key}.longest", s.longest)
                .putFloat("${s.key}.ran", s.ran).putFloat("${s.key}.queued", s.queued)
                .putFloat("${s.key}.clock", s.clock)
                .putInt("${s.key}.clocked", s.clocked).putString("${s.key}.cores", s.cores)
        }
        e.apply()
    }

    private fun version(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull() ?: "?"
}
