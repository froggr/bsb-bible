package ca.lampandlight

import android.content.Intent
import android.net.Uri

/**
 * Opening the app at a passage from outside: a widget, a shortcut, another app.
 *
 * The app's own form is
 *
 *     bsb://open?ref=John+3:16
 *
 * where `ref` is anything the search box accepts ("jn3:16", "1 cor 13", "ps 23:4"). The
 * same reference may be given as an extra, `ref`, on an explicit intent to MainActivity.
 *
 * The app also answers for the places a verse-of-the-day widget already points at, so such
 * a widget needs no change — pick this app when Android asks:
 *
 *     https://www.biblegateway.com/passage/?search=John+3:16
 *     https://www.bible.com/bible/59/JHN.3.16
 *     https://biblehub.com/john/3-16.htm
 */
object Deeplink {

    /** The verse an intent asks for, or null when it asks for none. */
    fun verseOf(intent: Intent?, books: List<Book>): VerseId? {
        if (intent == null) return null
        intent.getStringExtra("ref")?.let { return byReference(it, books) }
        val uri = intent.data ?: return null
        val host = uri.host?.lowercase().orEmpty()
        return when {
            uri.scheme == "bsb" ->
                byReference(uri.getQueryParameter("ref") ?: uri.path?.trimStart('/') ?: "", books)
            "biblegateway.com" in host ->
                byReference(uri.getQueryParameter("search") ?: "", books)
            "bible.com" in host -> {
                // /bible/<version>/JHN.3.16[.BSB]
                val seg = uri.pathSegments.getOrNull(2) ?: return null
                val parts = seg.split('.')
                val book = USFM[parts.getOrNull(0)?.uppercase()] ?: return null
                val chapter = parts.getOrNull(1)?.toIntOrNull() ?: 1
                val verse = parts.getOrNull(2)?.toIntOrNull() ?: 1
                verseId(book, chapter, verse)
            }
            "biblehub.com" in host -> {
                // /john/3-16.htm, /1_john/3-16.htm, /psalms/23.htm
                val name = uri.pathSegments.getOrNull(0)?.replace('_', ' ') ?: return null
                val rest = uri.pathSegments.getOrNull(1)?.removeSuffix(".htm") ?: "1"
                byReference("$name ${rest.replace('-', ':')}", books)
            }
            else -> null
        }
    }

    private fun byReference(ref: String, books: List<Book>): VerseId? {
        val text = ref.replace('+', ' ').trim()
        if (text.isEmpty()) return null
        val hits = Reference.parse(text, books)
        // The search box offers every candidate for an ambiguous prefix; a link has to
        // choose, and takes the best — which is the exact or aliased one when there is one.
        return hits.firstOrNull()?.id
    }

    /** The three-letter book codes YouVersion URLs use, in canon order. */
    private val USFM = listOf(
        "GEN", "EXO", "LEV", "NUM", "DEU", "JOS", "JDG", "RUT", "1SA", "2SA", "1KI", "2KI",
        "1CH", "2CH", "EZR", "NEH", "EST", "JOB", "PSA", "PRO", "ECC", "SNG", "ISA", "JER",
        "LAM", "EZK", "DAN", "HOS", "JOL", "AMO", "OBA", "JON", "MIC", "NAM", "HAB", "ZEP",
        "HAG", "ZEC", "MAL", "MAT", "MRK", "LUK", "JHN", "ACT", "ROM", "1CO", "2CO", "GAL",
        "EPH", "PHP", "COL", "1TH", "2TH", "1TI", "2TI", "TIT", "PHM", "HEB", "JAS", "1PE",
        "2PE", "1JN", "2JN", "3JN", "JUD", "REV",
    ).withIndex().associate { (i, code) -> code to i + 1 }
}
