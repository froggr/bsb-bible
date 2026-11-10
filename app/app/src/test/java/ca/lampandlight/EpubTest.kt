package ca.lampandlight

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Reads real books and writes down what the parser made of them.
 *
 * The books are not in the repository — they are somebody's library, not test data — so this
 * is run by hand with the paths in the environment, and skipped everywhere else:
 *
 *     EPUB_SAMPLES=/path/a.epub:/path/b.epub EPUB_DIGEST=/tmp/digest.txt \
 *         ./gradlew cleanTestDebugUnitTest testDebugUnitTest
 *
 * EPUB_FULL=-1 prints every block and EPUB_WIDTH=100000 every word of each.
 *
 * The digest is for reading, not asserting: whether a bold line is a subheading or a
 * sentence being stressed is a judgement, and the way to check it is to look.
 */
class EpubTest {
    @Test fun digest() {
        val samples = System.getenv("EPUB_SAMPLES").orEmpty().split(':').filter { it.isNotBlank() }
        assumeTrue(samples.isNotEmpty())
        val out = StringBuilder()
        val width = System.getenv("EPUB_WIDTH")?.toIntOrNull() ?: 150
        for (path in samples) {
            val t0 = System.nanoTime()
            Epub(File(path)).use { book ->
                val sections = book.sections()
                val ms = (System.nanoTime() - t0) / 1_000_000
                out.append("\n==== ${book.title} | ${book.author} | cover=${book.coverPath} | locked=${book.locked}")
                out.append(" | ${sections.size} sections, ${sections.sumOf { it.words }} words, ${ms} ms")
                out.append(" | starts at ${book.startOf(sections)}\n")
                for ((n, s) in sections.withIndex()) {
                    out.append("\n-- [$n] ${if (s.listed) "" else "(unlisted) "}${s.label ?: "-"} | ${s.title} | ${s.path} ${s.from}..${s.to} | ${s.words} words\n")
                    val blocks = book.blocks(s)
                    val full = System.getenv("EPUB_FULL").orEmpty().split(',').mapNotNull { it.toIntOrNull() }
                    val shown = if (n in full || full.contains(-1)) blocks else blocks.take(14)
                    for (b in shown) {
                        val marks = buildString {
                            if (b.marker != null) append(" «${b.marker}»")
                            if (b.level != 0) append(" L${b.level}")
                            if (b.align != Epub.Align.Start) append(" ${b.align}")
                            if (b.bold.isNotEmpty()) append(" b${b.bold}")
                            if (b.italic.isNotEmpty()) append(" i${b.italic}")
                            if (b.src != null) append(" ${b.src}")
                        }
                        val text = b.text.replace("\n", "⏎")
                        out.append("   ${b.kind.name.padEnd(7)}$marks | ${if (text.length > width) text.take(width) + "…" else text}\n")
                    }
                    if (shown.size < blocks.size) out.append("   … ${blocks.size - shown.size} more: ${blocks.drop(14).groupingBy { it.kind }.eachCount()}\n")
                }
            }
        }
        val to = System.getenv("EPUB_DIGEST")
        if (to.isNullOrBlank()) println(out) else File(to).writeText(out.toString())
    }
}
