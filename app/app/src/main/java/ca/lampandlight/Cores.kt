package ca.lampandlight

import android.content.Context
import android.os.Process
import com.sun.jna.Library
import com.sun.jna.Native
import java.io.File

/**
 * Which processor cores the voice is made on.
 *
 * A phone's cores are not alike, and an app is not allowed the same ones on screen and off.
 * A Pixel 9 has four small cores (0-3), three middle ones (4-6) and one large (7). Its
 * platform's own start-up script gives the app in front all eight, and anything else that is
 * still running — which is what a reading becomes the moment the screen locks — only 0-6:
 *
 *     write /dev/cpuset/top-app/cpus 0-7
 *     write /dev/cpuset/foreground/cpus 0-6
 *
 * The speech model splits every step of its arithmetic evenly between its threads and waits
 * at the end of each step for the slowest of them. With four threads on screen there are
 * four fast cores for them: the large one and the three middle ones. Off screen there are
 * three, so one thread has to share a core or take a small one, and the others wait for it
 * at every step: the whole voice runs at the pace of its worst-placed thread. That fits a
 * reading that was fine in the hand and stopped between sentences in the pocket, and it is
 * read off the platform's files and the model's source, NOT measured on a phone: [Pulse]
 * reports the cores, the waiting and the clock speed so that a phone can confirm or refute it.
 *
 * So two things are decided here. How many threads the model gets is the number of fast
 * cores that are still there off screen, not the number on screen. And those threads are
 * kept on the fast cores, asked again before every sentence: moving an app between the two
 * groups resets where its threads may run.
 *
 * None of this can be seen on the emulator, whose cores are all alike and all allowed.
 */
object Cores {

    @Suppress("FunctionName")
    private interface C : Library {
        fun sched_setaffinity(pid: Int, size: Long, mask: ByteArray): Int
        fun sched_getaffinity(pid: Int, size: Long, mask: ByteArray): Int
    }

    private val libc: C? by lazy { runCatching { Native.load("c", C::class.java) }.getOrNull() }

    val count: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /** How much each core can do, in the kernel's own units; all alike when it will not say. */
    val capacity: IntArray by lazy {
        IntArray(count) { i ->
            number("cpu$i/cpu_capacity") ?: number("cpu$i/cpufreq/cpuinfo_max_freq") ?: 1
        }
    }

    private fun number(path: String): Int? = runCatching {
        File("/sys/devices/system/cpu/$path").readText().trim().toInt().takeIf { it > 0 }
    }.getOrNull()

    /**
     * The cores this app may run on at this moment.
     *
     * Asked of the main thread, which nothing here ever pins, so the answer is the system's
     * limit and not one of our own. Empty when the phone will not say.
     */
    fun allowed(): Set<Int> = mask(Process.myPid())

    private fun mask(tid: Int): Set<Int> {
        val c = libc ?: return emptySet()
        val bits = ByteArray(BYTES)
        if (runCatching { c.sched_getaffinity(tid, BYTES.toLong(), bits) }.getOrDefault(-1) != 0) {
            return emptySet()
        }
        return (0 until count).filter { bits[it / 8].toInt() shr (it % 8) and 1 == 1 }.toSet()
    }

    /** Keep the threads [tids] on [cores]. False if the phone refused any of them. */
    fun pin(tids: IntArray, cores: Set<Int>): Boolean {
        val c = libc ?: return false
        if (cores.isEmpty()) return false
        val bits = ByteArray(BYTES)
        cores.forEach { bits[it / 8] = (bits[it / 8].toInt() or (1 shl (it % 8))).toByte() }
        var ok = true
        for (tid in tids) {
            if (runCatching { c.sched_setaffinity(tid, BYTES.toLong(), bits) }.getOrDefault(-1) != 0) {
                ok = false
            }
        }
        return ok
    }

    /** The ids of this app's threads whose names begin with [prefix]. */
    fun threads(prefix: String): IntArray = runCatching {
        File("/proc/self/task").listFiles().orEmpty().mapNotNull { t ->
            val id = t.name.toIntOrNull() ?: return@mapNotNull null
            val name = runCatching { File(t, "comm").readText().trim() }.getOrNull()
            id.takeIf { name?.startsWith(prefix) == true }
        }.toIntArray()
    }.getOrDefault(IntArray(0))

    /**
     * How near full speed [cores] are running, 0 to 1, or null where the phone keeps that
     * to itself. A fast core held at a low clock is as slow as a small one, and looks the
     * same from everywhere else.
     */
    /**
     * How long a thread of this app has run, and how long it has stood ready to run with
     * no core to run on, in nanoseconds: the kernel's own account. The second number is the
     * one that says a thread is being kept waiting; null where the kernel does not keep it.
     */
    fun waited(tid: Int): LongArray? = runCatching {
        val f = File("/proc/self/task/$tid/schedstat").readText().trim().split(' ')
        longArrayOf(f[0].toLong(), f[1].toLong())
    }.getOrNull()

    fun name(tid: Int): String =
        runCatching { File("/proc/self/task/$tid/comm").readText().trim() }.getOrDefault("?")

    fun clock(cores: Set<Int>): Float? {
        val parts = cores.mapNotNull { i ->
            val now = number("cpu$i/cpufreq/scaling_cur_freq") ?: return@mapNotNull null
            val top = number("cpu$i/cpufreq/cpuinfo_max_freq") ?: return@mapNotNull null
            (now.toFloat() / top).coerceIn(0f, 1f)
        }
        return if (parts.isEmpty()) null else parts.average().toFloat()
    }

    // ------------------------------------------------------------------ the two decisions

    /** Cores worth making speech on: within reach of the best one. Small cores are not. */
    fun fast(capacity: IntArray): Set<Int> {
        val best = capacity.maxOrNull() ?: return emptySet()
        val least = capacity.min()
        // The small cores are the lowest kind, when that kind is far below the best. Every
        // kind above them counts as fast, however its number compares with the best: the
        // middle cores of a Pixel 6 report 427 against 1024 and are still four times the
        // small ones. Where the lowest kind is itself near the best, there are no small
        // cores and all of them are fast.
        if (least * 100L >= best * SMALL_PERCENT) return capacity.indices.toSet()
        return capacity.indices.filter { capacity[it] > least }.toSet()
    }

    /**
     * How many threads the model should have.
     *
     * [away] is the set of cores the app was last seen held to off screen, when that has
     * been seen and was a real limit. Until it has, the largest cores are assumed to be
     * withheld off screen wherever there are at least two a step below them, which is how
     * the Pixels are set up and costs a thread at most where it is wrong.
     */
    fun width(capacity: IntArray, away: Set<Int>?): Int {
        val fast = fast(capacity)
        val seen = away?.let { fast intersect it }?.takeIf { it.size >= 2 }
        val usable = seen ?: run {
            val best = capacity.maxOrNull() ?: 0
            val below = fast.filter { capacity[it] < best }
            if (below.size >= 2) below.toSet() else fast
        }
        return usable.size.coerceIn(2, 4).coerceAtMost(maxOf(2, capacity.size / 2))
    }

    /**
     * The cores to keep [width] threads on, out of those [allowed]: the fastest [width] of
     * them, and any others as fast as the slowest of those, so the system still has a
     * choice where the cores are alike.
     */
    fun choose(capacity: IntArray, allowed: Set<Int>, width: Int): Set<Int> {
        val known = allowed.filter { it in capacity.indices }
        if (known.isEmpty() || width <= 0) return emptySet()
        val floor = known.map { capacity[it] }.sortedDescending().let { it[minOf(width, it.size) - 1] }
        return known.filter { capacity[it] >= floor }.toSet()
    }

    /** "4-6" or "0-3,7": a set of cores as a person would write it. */
    fun span(cores: Set<Int>): String {
        val out = StringBuilder()
        val sorted = cores.sorted()
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
            if (out.isNotEmpty()) out.append(',')
            out.append(sorted[i])
            if (j > i) out.append('-').append(sorted[j])
            i = j + 1
        }
        return out.toString()
    }

    /** [span] read back. */
    fun parse(text: String): Set<Int> = runCatching {
        text.split(',').filter { it.isNotBlank() }.flatMap { part ->
            val ends = part.trim().split('-').map { it.toInt() }
            (ends.first()..ends.last()).toList()
        }.toSet()
    }.getOrDefault(emptySet())

    // ------------------------------------------------------------------ what was seen

    /**
     * Remember the cores the app was held to off screen, for the next launch to size the
     * model by: the thread count is fixed the first time the model runs and cannot follow
     * the screen. Only a real limit is kept. The first sentence after the screen goes off
     * can still see every core, and that must not overwrite what a later one learns.
     */
    fun sawAway(ctx: Context, allowed: Set<Int>) {
        if (allowed.isEmpty() || allowed.size >= count) return
        val text = span(allowed)
        val sp = ctx.getSharedPreferences("cores", Context.MODE_PRIVATE)
        if (sp.getString("away", "") != text) sp.edit().putString("away", text).apply()
    }

    fun away(ctx: Context): Set<Int>? =
        ctx.getSharedPreferences("cores", Context.MODE_PRIVATE).getString("away", "")
            ?.takeIf { it.isNotBlank() }?.let { parse(it) }?.takeIf { it.isNotEmpty() }

    /** Room for 256 cores, which is what the kernel's own set holds on these phones. */
    private const val BYTES = 32
    private const val SMALL_PERCENT = 45
}
