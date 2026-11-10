package ca.lampandlight

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/**
 * An epub, read into the handful of things a page of prose is made of.
 *
 * A book is not rendered as its own HTML: it is taken apart into [Block]s — a title, a
 * paragraph, a quotation, the reference under it — and set again in the app's own type, so
 * a pastor's self-published book and a publisher's read alike and the narrator can follow
 * either word by word. What the publisher's stylesheet says is consulted for only three
 * things, because they carry meaning: bold, italic, and which way a line is aligned.
 *
 * Nothing here touches Android, so the whole of it runs under a plain JVM test against real
 * books. Nothing is ever unpacked to disk: entries are read into memory, capped, by name.
 *
 * The book's chapters are [Section]s. Usually one file of the spine is one section; a book
 * kept in a single file is cut where its table of contents points into it. Covers, empty
 * pages and the book's own contents page are left out — the app has its own.
 */
class Epub(file: File) : Closeable {

    enum class Kind {
        /** "CHAPTER 5", over a title. */
        Label,
        /** What a section is called, at its head. */
        Title,
        /** A heading further down the page. */
        Heading,
        Para,
        /** A paragraph set wholly in bold: a statement the author is leaning on. */
        Lead,
        /** A numbered or bulleted point; [Block.marker] is its number. */
        Point,
        /** Words quoted from somewhere else, most often Scripture. */
        Quote,
        /** A line that is nothing but a Scripture reference: "Genesis 3:8-9 (ESV)". */
        Cite,
        /** A break the author drew between two passages. */
        Break,
        Image,
    }

    enum class Align { Start, Center, End }

    data class Block(
        val kind: Kind,
        val text: String,
        /** Character ranges of [text] in bold and in italic. */
        val bold: List<IntRange> = emptyList(),
        val italic: List<IntRange> = emptyList(),
        /** A point's number or bullet; on a [Kind.Title], the label that was part of it. */
        val marker: String? = null,
        /** The heading level, 1 to 6, for a [Kind.Heading]. */
        val level: Int = 0,
        val align: Align = Align.Start,
        /** For an image: its path inside the book. */
        val src: String? = null,
    ) {
        val words: Int get() = if (text.isEmpty()) 0 else text.split(SPACES).count { it.isNotEmpty() }
    }

    /**
     * One chapter-sized piece of the book: blocks [from] until [to] of the file at [path].
     *
     * [listed] is false for a piece nobody named — the second half of a chapter a converter
     * split across two files — which is read straight through but has no line of its own
     * in the contents.
     */
    data class Section(
        val path: String,
        val from: Int,
        val to: Int,
        val label: String?,
        val title: String,
        val listed: Boolean,
        val words: Int,
    )

    private class Nav(val path: String, val fragment: String?, val text: String, val depth: Int)

    private class Page(val blocks: List<Block>, val anchors: Map<String, Int>, val linkish: Boolean)

    private val zip = ZipFile(file)
    private val names: Map<String, String> =
        zip.entries().asSequence().associate { it.name.lowercase() to it.name }

    val title: String
    val author: String

    /** The cover image's path inside the book, when it names one. */
    val coverPath: String?

    /**
     * True when the text itself is encrypted — a book bought with copy protection. Scrambled
     * fonts do not count: plenty of open books ship those, and the fonts are not used here.
     */
    val locked: Boolean

    private val spine: List<String>
    private val navs: List<Nav>
    /** Where the publisher says the reading starts, if it says. */
    private val startPath: String?
    private val classBold = HashSet<String>()
    private val classItalic = HashSet<String>()
    private val classAlign = HashMap<String, Align>()

    init {
        val container = xml("META-INF/container.xml")
            ?: throw IllegalArgumentException("not an epub: no container")
        val opfPath = container.all("rootfile").firstOrNull()?.attr("full-path")
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("not an epub: no package file")
        val opf = xml(opfPath) ?: throw IllegalArgumentException("not an epub: package file missing")

        val meta = opf.all("metadata").firstOrNull()
        title = meta?.all("title")?.firstOrNull()?.text()?.clean()?.takeIf { it.isNotEmpty() }
            ?: file.nameWithoutExtension
        author = meta?.all("creator").orEmpty()
            .map { it.text().clean() }.filter { it.isNotEmpty() }.distinct().joinToString(", ")

        class Item(val id: String, val path: String, val type: String, val props: String)
        val items = opf.all("manifest").firstOrNull()?.all("item").orEmpty().map {
            Item(it.attr("id"), resolve(opfPath, it.attr("href")), it.attr("media-type").lowercase(),
                it.attr("properties"))
        }
        val byId = items.associateBy { it.id }
        val spineEl = opf.all("spine").firstOrNull()
        spine = spineEl?.all("itemref").orEmpty().mapNotNull { byId[it.attr("idref")]?.path }
            .filter { has(it) }.distinct()

        // The cover: EPUB 3 marks the image, EPUB 2 names it in a <meta>, and Sigil puts
        // the file name there instead of the id.
        val coverMeta = meta?.all("meta").orEmpty()
            .firstOrNull { it.attr("name").equals("cover", true) }?.attr("content").orEmpty()
        coverPath = (
            items.firstOrNull { "cover-image" in it.props.split(' ') }
                ?: byId[coverMeta]?.takeIf { it.type.startsWith("image/") }
                ?: items.firstOrNull {
                    coverMeta.isNotEmpty() && it.type.startsWith("image/") && it.path.endsWith(coverMeta, true)
                }
                ?: items.firstOrNull {
                    it.type.startsWith("image/") &&
                        (it.id.contains("cover", true) || it.path.substringAfterLast('/').contains("cover", true))
                }
            )?.path?.takeIf { has(it) }

        // The table of contents: the NCX when there is one, else the EPUB 3 nav document.
        val ncx = (byId[spineEl?.attr("toc").orEmpty()] ?: items.firstOrNull { it.type == "application/x-dtbncx+xml" })
        val navDoc = items.firstOrNull { "nav" in it.props.split(' ') }
        navs = ncx?.let { readNcx(it.path) }?.takeIf { it.isNotEmpty() }
            ?: navDoc?.let { readNavDoc(it.path) }.orEmpty()

        startPath = opf.all("guide").firstOrNull()?.all("reference").orEmpty()
            .firstOrNull { it.attr("type").lowercase() in setOf("text", "start", "bodymatter") }
            ?.let { resolve(opfPath, it.attr("href")) }

        for (css in items.filter { it.type == "text/css" }) readCss(css.path)

        locked = xml("META-INF/encryption.xml")?.all("encrypteddata").orEmpty().any { data ->
            val uri = data.all("cipherreference").firstOrNull()?.attr("URI").orEmpty().lowercase()
            val how = data.all("encryptionmethod").firstOrNull()?.attr("Algorithm").orEmpty()
            val font = uri.endsWith(".ttf") || uri.endsWith(".otf") || uri.endsWith(".woff") ||
                uri.endsWith(".woff2") || "embedding" in how || "pdf/enc#RC" in how
            !font
        } == true
    }

    override fun close() = zip.close()

    // ------------------------------------------------------------------ reading the zip

    private fun has(path: String) = names.containsKey(path.lowercase())

    /** One entry's bytes, or null when it is missing or too large to be a page of a book. */
    fun bytes(path: String, limit: Int = 24 shl 20): ByteArray? {
        val entry = zip.getEntry(names[path.lowercase()] ?: return null) ?: return null
        if (entry.size > limit) return null
        zip.getInputStream(entry).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > limit) return null
            }
            return out.toByteArray()
        }
    }

    /**
     * Parsed as XML, not HTML: an epub's pages are XHTML, and the HTML rules swallow a page
     * whole after a self-closed `<title/>`. The XML reader does not validate, so the sloppy
     * page still comes through, and the walker below does not trust the nesting it is given.
     */
    private fun xml(path: String): Document? {
        val b = bytes(path) ?: return null
        return runCatching { Jsoup.parse(ByteArrayInputStream(b), null, "", Parser.xmlParser()) }.getOrNull()
    }

    private fun readNcx(path: String): List<Nav> {
        val doc = xml(path) ?: return emptyList()
        return doc.all("navpoint").mapNotNull { point ->
            val src = point.children().firstOrNull { it.local() == "content" }?.attr("src").orEmpty()
            val text = point.children().firstOrNull { it.local() == "navlabel" }?.text().orEmpty().clean()
            if (src.isEmpty()) null
            else Nav(
                resolve(path, src), src.substringAfter('#', "").takeIf { it.isNotEmpty() }, text,
                point.parents().count { it.local() == "navpoint" },
            )
        }
    }

    private fun readNavDoc(path: String): List<Nav> {
        val doc = xml(path) ?: return emptyList()
        val navEls = doc.all("nav")
        val toc = navEls.firstOrNull { "toc" in it.attr("epub:type") } ?: navEls.firstOrNull() ?: return emptyList()
        return toc.all("a").mapNotNull { a ->
            val src = a.attr("href")
            if (src.isEmpty()) null
            else Nav(
                resolve(path, src), src.substringAfter('#', "").takeIf { it.isNotEmpty() },
                a.text().clean(), (a.parents().count { it.local() == "ol" } - 1).coerceAtLeast(0),
            )
        }
    }

    /** Which classes mean bold, italic, centred or ranged right. Nothing else is read. */
    private fun readCss(path: String) {
        val css = bytes(path, 2 shl 20)?.toString(Charsets.UTF_8) ?: return
        for (rule in RULE.findAll(css.replace(COMMENT, " "))) {
            val body = rule.groupValues[2].lowercase()
            val bold = BOLD.containsMatchIn(body)
            val italic = ITALIC.containsMatchIn(body)
            val align = ALIGN.find(body)?.groupValues?.get(1)
            if (!bold && !italic && align == null) continue
            for (selector in rule.groupValues[1].split(',')) {
                val cls = CLASS.find(selector.trim())?.groupValues?.get(1) ?: continue
                if (bold) classBold += cls
                if (italic) classItalic += cls
                when (align) {
                    "center" -> classAlign[cls] = Align.Center
                    "right", "end" -> classAlign[cls] = Align.End
                    "left", "start", "justify" -> classAlign[cls] = Align.Start
                }
            }
        }
    }

    // ------------------------------------------------------------------ sections

    private var cachedPath: String? = null
    private var cachedPage: Page? = null

    private fun page(path: String): Page {
        if (path == cachedPath) cachedPage?.let { return it }
        val doc = xml(path)
        val page = if (doc == null) Page(emptyList(), emptyMap(), false) else {
            val w = Walker(path)
            // The body when there is one; a page with none is walked whole.
            val root: Node = doc.all("body").firstOrNull() ?: doc
            NodeTraversor.traverse(w, root)
            w.finish()
        }
        cachedPath = path; cachedPage = page
        return page
    }

    /** The blocks of one section, with the headings at its top told apart. */
    fun blocks(section: Section): List<Block> {
        val all = page(section.path).blocks
        val from = section.from.coerceIn(0, all.size)
        return opened(all.subList(from, section.to.coerceIn(from, all.size)))
    }

    /**
     * Every section of the book, in reading order. Reads the whole book once; the answer is
     * what the library keeps, so it is not asked for again.
     */
    fun sections(): List<Section> {
        val out = ArrayList<Section>()
        var seenText = false
        var lastTitle: String? = null
        var lastLabel: String? = null
        for (path in spine) {
            val page = page(path)
            if (page.blocks.isEmpty() || page.linkish) continue
            // Cut the file where the contents points into it — but only at the outermost
            // level it uses for this file, or a chapter comes apart at every subheading.
            val here = navs.filter { it.path == path }
            val top = here.minOfOrNull { it.depth }
            val cuts = here.filter { it.depth == top }
                .map { (it.fragment?.let { f -> page.anchors[f] } ?: 0) to it }
                .sortedBy { it.first }.distinctBy { it.first }
            val pieces: List<Triple<Int, Int, Nav?>> = when {
                cuts.isEmpty() -> listOf(Triple(0, page.blocks.size, null))
                cuts.size == 1 -> listOf(Triple(0, page.blocks.size, cuts[0].second))
                else -> buildList {
                    if (cuts[0].first > 0) add(Triple(0, cuts[0].first, null))
                    cuts.forEachIndexed { i, (at, nav) ->
                        add(Triple(at, cuts.getOrNull(i + 1)?.first ?: page.blocks.size, nav))
                    }
                }
            }
            for ((from, to, nav) in pieces) {
                if (to <= from) continue
                val slice = opened(page.blocks.subList(from, to))
                val words = slice.sumOf { it.words }
                // A page of nothing but a picture, before any text: the cover, shown again.
                if (words == 0 && (!seenText || slice.none { it.kind == Kind.Image })) continue
                seenText = seenText || words > 0

                val head = slice.firstOrNull { it.kind == Kind.Title }
                val headLabel = slice.firstOrNull { it.kind == Kind.Label }?.text ?: head?.marker
                val named = nav?.text?.takeIf { it.isNotEmpty() }?.let { splitLabel(it) }
                    ?: head?.let { headLabel to it.text.replace('\n', ' ') }
                    ?: slice.firstOrNull { it.kind == Kind.Label }?.let { null to it.text }
                val guess = if (named == null && lastTitle == null) frontMatter(slice) else null
                val title = named?.second ?: guess ?: lastTitle ?: this.title
                // "CHAPTER 5" in one book's contents and "Chapter 5" in the next: one way here.
                val label = (if (named != null) named.first else if (guess != null) null else lastLabel)
                    ?.let { l -> if (l.none { it.isLowerCase() }) l.lowercase().replaceFirstChar { it.uppercase() } else l }
                out += Section(path, from, to, label, title, listed = named != null || guess != null, words = words)
                lastTitle = title; lastLabel = label
            }
        }
        return out
    }

    /**
     * Where a book opened for the first time should begin: where the publisher says the text
     * starts, else the first thing the contents names — past the copyright page either way.
     */
    fun startOf(sections: List<Section>): Int {
        startPath?.let { p -> sections.indexOfFirst { it.path == p }.takeIf { it >= 0 }?.let { return it } }
        val named = navs.map { it.path }.toSet()
        return sections.indexOfFirst { it.path in named && it.listed }.coerceAtLeast(0)
    }

    /** A name for an untitled page at the front of a book, when what it is can be told. */
    private fun frontMatter(blocks: List<Block>): String? {
        val text = blocks.take(30).joinToString(" ") { it.text }.lowercase()
        return when {
            "all rights reserved" in text || "copyright ©" in text || "isbn" in text -> "Copyright"
            text.length < 400 && ("dedicat" in text || text.startsWith("to my") || text.startsWith("for my")) -> "Dedication"
            else -> null
        }
    }

    /**
     * Tell apart the headings a section opens with.
     *
     * "CHAPTER 5" then "The Baptism of the Holy Ghost" is a label and a title, whichever of
     * h2 and h3 the publisher used for which. A single "Chapter 1: Where…" is both at once:
     * it stays one block — the count of blocks must not change, because sections are kept
     * as ranges of them — and carries its label as [Block.marker].
     */
    private fun opened(blocks: List<Block>): List<Block> {
        val lead = blocks.takeWhile { it.kind == Kind.Heading }.take(3)
        if (lead.isEmpty()) return blocks
        val out = blocks.toMutableList()
        val first = lead[0]
        val split = splitLabel(first.text.replace('\n', ' '))
        when {
            lead.size >= 2 && split.first != null && split.second == split.first -> {
                out[0] = first.copy(kind = Kind.Label)
                out[1] = lead[1].copy(kind = Kind.Title)
            }
            split.first != null && split.second != split.first ->
                out[0] = first.copy(kind = Kind.Title, text = split.second, marker = split.first,
                    bold = emptyList(), italic = emptyList())
            else -> out[0] = first.copy(kind = Kind.Title)
        }
        return out
    }

    // ------------------------------------------------------------------ one page

    /**
     * Walks a page as a flat run of events rather than a tree, because the tree cannot be
     * trusted: an unclosed `<br>` or `<p>` leaves everything after it nested inside. A block
     * tag opening or closing ends the paragraph being gathered, wherever it sits.
     */
    private inner class Walker(private val path: String) : NodeVisitor {
        private val out = ArrayList<Block>()
        private val anchors = HashMap<String, Int>()
        private val pendingIds = ArrayList<String>()

        private val text = StringBuilder()
        private val bolds = ArrayList<IntRange>()
        private val italics = ArrayList<IntRange>()
        private var bold = 0; private var boldFrom = 0
        private var italic = 0; private var italicFrom = 0
        private var skip = 0
        private var link = 0
        private var space = false

        private var chars = 0
        private var linkedChars = 0
        private var links = 0

        /** What each open element switched on, to switch off again when it closes. */
        private val flags = ArrayList<Int>()

        private inner class Ctx(
            val heading: Int, val quote: Boolean, val align: Align?,
            val ordered: Boolean?, var count: Int,
        ) { var marker: String? = null }
        private val stack = ArrayList<Ctx>()

        override fun head(node: Node, depth: Int) {
            if (node is TextNode) { if (skip == 0) say(node.wholeText); return }
            val el = node as? Element ?: return
            var f = 0
            el.attr("id").takeIf { it.isNotEmpty() }?.let { pendingIds += it }
            val tag = el.local()
            if (tag == "a") el.attr("name").takeIf { it.isNotEmpty() }?.let { pendingIds += it }
            val classes = el.attr("class").split(' ').filter { it.isNotEmpty() }
            val style = el.attr("style").lowercase()
            when {
                tag in DROPPED || (tag == "sup" && NOTE_MARK.matches(el.text().trim())) ||
                    (tag == "a" && "noteref" in el.attr("epub:type")) ||
                    pageMark(el, tag, classes) || noteLink(el, tag, classes) -> { skip++; f = f or SKIP }
                tag in BLOCKS -> {
                    flush()
                    val up = stack.lastOrNull()
                    val align = alignOf(style) ?: el.attr("align").lowercase().let {
                        when (it) { "center" -> Align.Center; "right" -> Align.End; else -> null }
                    } ?: classes.firstNotNullOfOrNull { classAlign[it] } ?: (if (tag == "center") Align.Center else null)
                    val ctx = Ctx(
                        heading = if (tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6') tag[1] - '0'
                                  else up?.heading ?: 0,
                        quote = tag == "blockquote" || up?.quote == true,
                        align = align ?: up?.align,
                        ordered = when (tag) { "ol" -> true; "ul" -> false; else -> null },
                        count = (el.attr("start").toIntOrNull() ?: 1) - 1,
                    )
                    if (tag == "li") {
                        val list = stack.lastOrNull { it.ordered != null }
                        ctx.marker = if (list?.ordered == true) "${++list.count}." else "•"
                    } else if (tag != "ul" && tag != "ol") ctx.marker = up?.marker?.also { up.marker = null }
                    stack += ctx
                    f = f or BLOCK
                }
                tag == "br" -> { while (text.isNotEmpty() && text.last() == ' ') text.setLength(text.length - 1)
                    if (text.isNotEmpty()) text.append('\n'); space = false }
                tag == "hr" -> { flush(); emit(Block(Kind.Break, "")) }
                tag == "img" || tag == "image" -> {
                    val src = el.attr("src").ifEmpty { el.attr("xlink:href") }.ifEmpty { el.attr("href") }
                    if (src.isNotEmpty() && !src.startsWith("data:")) {
                        flush(); emit(Block(Kind.Image, "", src = resolve(path, src)))
                    }
                }
            }
            if (f and SKIP == 0) {
                val isBold = tag == "b" || tag == "strong" || BOLD.containsMatchIn(style) ||
                    classes.any { it in classBold }
                val isItalic = tag == "i" || tag == "em" || tag == "cite" || tag == "dfn" ||
                    ITALIC.containsMatchIn(style) || classes.any { it in classItalic }
                // A heading class is bold in every stylesheet; that is the heading, not emphasis.
                if (isBold && f and BLOCK == 0 || isBold && tag !in HEADINGS && f and BLOCK != 0) {
                    if (bold++ == 0) boldFrom = text.length; f = f or BOLDED
                }
                if (isItalic) { if (italic++ == 0) italicFrom = text.length; f = f or ITALICISED }
                if (tag == "a" && el.hasAttr("href")) { link++; links++; f = f or LINKED }
            }
            flags += f
        }

        override fun tail(node: Node, depth: Int) {
            if (node !is Element) return
            val f = flags.removeAt(flags.lastIndex)
            if (f and SKIP != 0) { skip--; return }
            if (f and LINKED != 0) link--
            // Close the span before the block ends, so the range lies inside its text.
            if (f and BLOCK != 0) flush()
            if (f and BOLDED != 0 && --bold == 0 && f and BLOCK == 0) close(bolds, boldFrom)
            if (f and ITALICISED != 0 && --italic == 0 && f and BLOCK == 0) close(italics, italicFrom)
            if (f and BLOCK != 0) stack.removeAt(stack.lastIndex)
        }

        /**
         * Where a printed page turned. Scanned books keep these, sometimes as an empty
         * element and sometimes as "[pg 196]" in the middle of a sentence, and nobody
         * listening wants to hear it.
         */
        private fun pageMark(el: Element, tag: String, classes: List<String>): Boolean =
            "pagebreak" in el.attr("epub:type") || el.attr("role") == "doc-pagebreak" ||
                classes.any { it in PAGE_CLASSES || it.endsWith("pageno") || it.endsWith("pagenum") } ||
                (tag == "span" && el.childNodeSize() == 1 && PAGE_TEXT.matches(el.text().trim()))

        /**
         * A footnote's mark in the running text: a link into the book whose whole text is a
         * number or a dagger, and whose target or class says it is a note. The same link at
         * the head of a block is the note's own number in the list of notes, and stays. A
         * bare number linked elsewhere ("see page 12") is prose and stays too.
         */
        private fun noteLink(el: Element, tag: String, classes: List<String>): Boolean {
            if (tag != "a" || text.isBlank()) return false
            val to = el.attr("href").substringAfter('#', "")
            if (to.isEmpty()) return false
            if (!NOTE_HINT.containsMatchIn(to) && classes.none { NOTE_HINT.containsMatchIn(it) }) return false
            return LINK_MARK.matches(el.text().trim())
        }

        private fun close(into: ArrayList<IntRange>, from: Int) {
            var end = text.length
            while (end > from && text[end - 1].isWhitespace()) end--
            if (end > from) into += from until end
        }

        private fun say(s: String) {
            for (c in s) {
                if (c.isWhitespace() || c == ' ' || c == '​' || c == '﻿') { space = true; continue }
                if (space && text.isNotEmpty() && text.last() != '\n') text.append(' ')
                space = false
                text.append(c)
                chars++; if (link > 0) linkedChars++
            }
        }

        private fun emit(b: Block) {
            for (id in pendingIds) anchors.putIfAbsent(id, out.size)
            pendingIds.clear()
            out += b
        }

        /** End the paragraph being gathered and decide what it was. */
        private fun flush() {
            if (bold > 0) close(bolds, boldFrom)
            if (italic > 0) close(italics, italicFrom)
            var raw = text.toString().trimEnd()
            val ctx = stack.lastOrNull()
            val boldNow = bolds.toList(); val italicNow = italics.toList()
            text.setLength(0); bolds.clear(); italics.clear(); space = false
            boldFrom = 0; italicFrom = 0
            if (raw.isBlank()) return

            var marker = ctx?.marker?.also { ctx.marker = null }
            var shift = 0
            if (marker == null && (ctx?.heading ?: 0) == 0) {
                POINT.find(raw)?.let { m ->
                    marker = m.groupValues[1].let { if (it.first().isLetterOrDigit()) it else "•" }
                    shift = m.range.last + 1
                    raw = raw.substring(shift)
                }
            }
            if (raw.isBlank()) return
            val body = typeset(raw)
            fun moved(ranges: List<IntRange>) = ranges.mapNotNull { r ->
                val a = (r.first - shift).coerceAtLeast(0)
                val b = (r.last - shift).coerceAtMost(body.length - 1)
                if (b >= a) a..b else null
            }
            val b = moved(boldNow)
            val i = moved(italicNow)
            val inked = body.count { !it.isWhitespace() }
            val allBold = b.sumOf { r -> (r.first..r.last).count { !body[it].isWhitespace() } } >= inked
            val allItalic = i.sumOf { r -> (r.first..r.last).count { !body[it].isWhitespace() } } >= inked
            val align = ctx?.align ?: Align.Start
            val heading = ctx?.heading ?: 0

            val block = when {
                heading > 0 -> Block(Kind.Heading, body, italic = if (allItalic) emptyList() else i,
                    level = heading, align = align)
                marker != null -> Block(Kind.Point, body, if (allBold) emptyList() else b, i,
                    marker = marker, level = if (allBold) 1 else 0, align = Align.Start)
                body.length <= 90 && CITE.matches(body) -> Block(Kind.Cite, body, align = Align.End)
                ctx?.quote == true -> Block(Kind.Quote, body, emptyList(), if (allItalic) emptyList() else i)
                allBold && headingLike(body) -> Block(Kind.Heading, body, level = 5, align = align)
                allBold -> Block(Kind.Lead, body, emptyList(), i, align = align)
                else -> Block(Kind.Para, body, b, if (allItalic && body.length > 400) emptyList() else i, align = align)
            }
            emit(block)
        }

        fun finish(): Page {
            flush()
            for (id in pendingIds) anchors.putIfAbsent(id, out.size)
            // The book's own contents page: nearly every letter on it is a link.
            val linkish = links >= 4 && chars > 0 && linkedChars >= chars * 0.6
            return Page(out, anchors, linkish)
        }
    }

    /** Media in the book is found from the page it is used on. */
    private fun resolve(base: String, href: String): String {
        val clean = runCatching {
            URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8")
        }.getOrDefault(href.substringBefore('#'))
        val parts = ArrayList<String>()
        if (!clean.startsWith("/")) parts += base.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }
        for (p in clean.split('/')) when (p) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts += p
        }
        return parts.joinToString("/")
    }

    companion object {
        private val SPACES = Regex("\\s+")
        private const val SKIP = 1
        private const val BLOCK = 2
        private const val BOLDED = 4
        private const val ITALICISED = 8
        private const val LINKED = 16

        private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
        private val BLOCKS = HEADINGS + setOf(
            "p", "div", "blockquote", "li", "ul", "ol", "dl", "dt", "dd", "table", "tr", "td", "th",
            "section", "article", "aside", "header", "footer", "nav", "figure", "figcaption", "pre",
            "body", "center", "address", "main", "caption", "thead", "tbody",
        )
        private val DROPPED = setOf("script", "style", "head", "title", "desc", "metadata", "math")
        /** What a superscript holds when it is a footnote's mark and not part of a word. */
        private val NOTE_MARK = Regex("""[\[(]?(?:\d{1,3}|[*†‡§])[\])]?|[\[(]\d{4}[\])]""")
        /** The same, as the text of a link: long books run past a thousand notes. */
        private val LINK_MARK = Regex("""[\[(]?(?:\d{1,4}|[*†‡§]{1,3})[\])]?""")
        private val NOTE_HINT = Regex("""foot|note|fn|annot""", RegexOption.IGNORE_CASE)
        private val PAGE_CLASSES = setOf("pagenum", "pageno", "page-number", "pagenumber", "pagebreak",
            "page-break", "tei-pb", "pb")
        private val PAGE_TEXT = Regex("""[\[{(]\s*(?:pg|page|p)\.?\s*[0-9ivxlc]{1,5}\s*[\]})]""", RegexOption.IGNORE_CASE)

        private val RULE = Regex("""([^{}]+)\{([^{}]*)\}""")
        private val COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        private val CLASS = Regex("""\.([A-Za-z0-9_-]+)$""")
        private val BOLD = Regex("""font-weight\s*:\s*(?:bold|bolder|[6-9]00)""")
        private val ITALIC = Regex("""font-style\s*:\s*(?:italic|oblique)""")
        private val ALIGN = Regex("""text-align\s*:\s*(center|right|left|justify|start|end)""")

        private fun alignOf(style: String): Align? = when (ALIGN.find(style)?.groupValues?.get(1)) {
            "center" -> Align.Center
            "right", "end" -> Align.End
            "left", "start", "justify" -> Align.Start
            else -> null
        }

        /**
         * "1. " or "12) " or a bullet at the head of a paragraph — and "ii. " or "b. ", but
         * those only before a capital, so that "i.e. the" and "v. 12 says" stay sentences.
         */
        private val POINT = Regex(
            """^\s*(\d{1,2}[.)]|[ivx]{1,4}[.)](?=\s+[A-Z“"])|[a-h][.)](?=\s+[A-Z“"])|[•●▪‣⁃·])\s+(?=\S)"""
        )

        private const val VERSION =
            """(?:[A-Z]{2,5}|The Message|Amplified(?: Bible)?|(?:New )?King James(?: Version)?|New International Version|New Living Translation|English Standard Version|(?:The )?Living Bible|Good News(?: Bible| Translation)?)"""
        private const val REF =
            """(?:(?:[1-3]|I{1,3}|First|Second|Third|1st|2nd|3rd)\s?)?[A-Z][A-Za-z]+(?:\s+of\s+[A-Z][a-z]+)?\.?\s+\d{1,3}\s*[:.]\s*\d{1,3}(?:\s*[-–—,]\s*\d{1,3}(?::\d{1,3})?)*"""
        /** A line that is a Scripture reference and nothing else, with or without a version. */
        val CITE = Regex(
            """^[\s(\[—–-]*$REF(?:\s*[;,&]\s*(?:and\s+)?(?:$REF|\d{1,3}:\d{1,3}(?:\s*[-–—,]\s*\d{1,3})*))*\s*[,;]?\s*(?:[(\[]?\s*$VERSION\s*[)\]]?)?[\s)\].]*$"""
        )

        private val LABELLED = Regex(
            """^((?:chapter|part|section|book|lesson|day|week|step|key|session|unit|study|principle|appendix|volume|letter)\s+(?:\d{1,3}|[ivxlc]{1,7}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty)\b)\s*[:.–—-]?\s*(.*)$""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * "Chapter 1: Where Has the Presence of God Been?" as its label and its title. A
         * heading that is only a label comes back as both; anything else has no label.
         */
        fun splitLabel(text: String): Pair<String?, String> {
            val t = text.clean()
            val m = LABELLED.find(t) ?: return null to t
            val label = m.groupValues[1].clean()
            val rest = m.groupValues[2].clean()
            return label to rest.ifEmpty { label }
        }

        /**
         * Whether a paragraph set all in bold is a subheading rather than a sentence being
         * stressed: short, not ending the way a sentence does, and capitalised like a title.
         */
        private fun headingLike(text: String): Boolean {
            if ('\n' in text) return false
            val words = text.split(' ').filter { it.isNotEmpty() }
            if (words.size > 14) return false
            val end = text.trimEnd('”', '"', '\'', '’', ' ').lastOrNull() ?: return false
            if (end == '.' || end == ':' || end == ',' || end == ';') return false
            if (words.size <= 3) return true
            val weighty = words.filter { w -> w.count { it.isLetter() } > 3 }
            if (weighty.isEmpty()) return true
            return weighty.count { w -> w.first { it.isLetter() }.isUpperCase() } >= weighty.size * 0.6
        }

        /** Typewriter quotes set as the real ones. The words are untouched. */
        fun typeset(s: String): String {
            if ('"' !in s && '\'' !in s) return s
            val out = StringBuilder(s.length)
            for ((i, c) in s.withIndex()) {
                val before = if (i == 0) ' ' else s[i - 1]
                val opening = before.isWhitespace() || before in "([{—–“‘"
                out.append(when (c) {
                    '"' -> if (opening) '“' else '”'
                    '\'' -> if (opening && i + 1 < s.length && s[i + 1].isLetter()) '‘' else '’'
                    else -> c
                })
            }
            return out.toString()
        }

        private fun String.clean() = replace(' ', ' ').replace(SPACES, " ").trim()
        private fun Element.local() = tagName().substringAfter(':').lowercase()
        private fun Element.all(name: String) = getAllElements().filter { it.local() == name }
    }
}
