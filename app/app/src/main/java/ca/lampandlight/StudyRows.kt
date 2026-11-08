package ca.lampandlight

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Study apparatus.
 *
 * The rule these all follow: scripture is the page, and everything here is apparatus around
 * it. So nothing in this file uses the reading face at reading size. Each kind of material
 * gets its own voice — the reader's own hand, the translators' note, a commentator, a
 * pointer elsewhere — and all of them sit indented behind a rule, narrower and quieter than
 * the text they serve. The eye should be able to run straight down the verses and skip all
 * of it without effort, then stop and read it deliberately when studying.
 *
 * The indent is the same for every kind, so the verses keep an unbroken left edge and the
 * apparatus an unbroken one of its own.
 */

/** How far study material is inset from the verse text. */
private val INDENT = 15.dp

/** The commentary lane of the margin: thinner and quieter than a note's. */
fun commentLaneColour(p: Palette): Color = p.gold.copy(alpha = if (p.dark) 0.55f else 0.5f)

/** The letter that marks a translator's note in the verse and on the note: a, b, c. */
fun noteLetter(index: Int): String = ('a' + (index % 26)).toString()

/**
 * The margin beside a row: whatever spans pass through it, drawn down its full height.
 *
 * A note's bracket is 3dp in the mark's colour; commentary's is a hairline in gold, drawn
 * underneath so a note's covers it where the two coincide. The row that ends a span draws
 * its own last stretch (see [NoteRow], [CommentRow]), stopping at the foot of the writing
 * rather than running on through the gap below it.
 */
@Composable
fun MarginLanes(margin: Margin, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    val note = margin.note?.color(p.dark)?.let { if (p.dark) it.copy(alpha = 0.75f) else it }
    val comment = if (margin.comment) commentLaneColour(p) else null
    Box(
        Modifier.fillMaxWidth().then(
            if (note == null && comment == null) Modifier else Modifier.drawBehind {
                comment?.let {
                    drawRect(it, androidx.compose.ui.geometry.Offset.Zero,
                        androidx.compose.ui.geometry.Size(1.5.dp.toPx(), size.height))
                }
                note?.let {
                    drawRect(it, androidx.compose.ui.geometry.Offset.Zero,
                        androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
                }
            }
        )
    ) { content() }
}

/**
 * The reader's own note.
 *
 * Drawn in their highlight's colour and in the reading face, italic — of everything on this
 * page it is the only thing that is theirs, and it should read like a hand in the margin
 * rather than like more apparatus. This shows whether or not study mode is on.
 *
 * The bracket in the margin is the same one that runs down the verses above: the verse
 * block draws it through its own height and the gap beneath, and this row carries it on
 * to the foot of the note, so the span and the words written on it are joined by one line.
 * [scope] names how far the note reaches ("verses 13–14") when that is more than one verse.
 */
@Composable
fun NoteRow(text: String, colour: MarkColor, scope: String? = null, onTap: () -> Unit) {
    val p = LocalPalette.current
    val accent = colour.color(p.dark)
    val bracket = if (p.dark) accent.copy(alpha = 0.75f) else accent
    val gap = 12.dp
    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(
                    color = bracket,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                    size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height - gap.toPx()),
                )
            }
            .padding(start = INDENT, bottom = gap)
    ) {
        androidx.compose.material3.Surface(
            color = accent.copy(alpha = if (p.dark) 0.13f else 0.3f),
            shape = RoundedCornerShape(3.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { onTap() },
        ) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 9.dp, bottom = 10.dp)) {
                Label(if (scope == null) "Your note" else "Your note \u00b7 $scope", p.inkSoft)
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.Text(
                    text,
                    fontFamily = ReadingFont,
                    fontStyle = FontStyle.Italic,
                    fontSize = (14.5 * LocalTextScale.current).sp,
                    lineHeight = (23 * LocalTextScale.current).sp,
                    color = p.ink,
                )
            }
        }
    }
}

/**
 * A translator's footnote.
 *
 * This is the apparatus a printed study Bible sets at the foot of the page: it is about the
 * text itself — a variant, a literal rendering, a measure — so it is the most tightly bound
 * to the verse and the most compact thing here. The letter is the one that marks the place
 * in the verse, and the words it marks are quoted first, so "Or *a canopy*" is read against
 * "expanse" without looking back up the page.
 */
@Composable
fun FootnoteRow(letter: Int, phrase: String?, text: String) {
    val p = LocalPalette.current
    val scale = LocalTextScale.current
    val body = remember(phrase, text) {
        buildAnnotatedString {
            if (phrase != null) {
                withStyle(SpanStyle(color = p.ink, fontWeight = FontWeight.Medium)) { append(phrase) }
                append("  ")
            }
            append(italicised(text))
        }
    }
    Row(Modifier.padding(start = INDENT, end = 4.dp, bottom = 14.dp)) {
        androidx.compose.material3.Text(
            noteLetter(letter),
            fontFamily = UiFont,
            fontSize = (11 * scale).sp,
            fontWeight = FontWeight.SemiBold,
            color = p.indigo,
            modifier = Modifier.width(14.dp).padding(top = 1.dp),
        )
        androidx.compose.material3.Text(
            body,
            fontFamily = UiFont,
            fontSize = (12.5 * scale).sp,
            lineHeight = (20 * scale).sp,
            color = p.inkSoft,
        )
    }
}

/**
 * Strip a commentator's chapter header.
 *
 * Jamieson-Fausset-Brown open ~2,450 of their notes with the printed page's running head —
 * "PSALM 23 Psa 23:1-6 ." or "CHAPTER 12 2Co 12:1-21 ." — which is navigation in a printed
 * book and noise on a screen that already says which chapter this is.
 */
fun commentaryBody(body: String): String =
    body.replace(
        Regex("""^\s*(?:[A-Z][A-Z]+(?:\s+[A-Z]+)*\s+\d+\s+)?[1-3]?\s?[A-Za-z]{2,4}\.?\s\d+:\d+(?:-\d+)?\s*\.\s*"""),
        "",
    ).trim()

/**
 * Commentary with Tyndale's quoted scripture set in italic.
 *
 * The build marks those spans with `*asterisks*`, which a plain `Text` would draw as
 * literal asterisks — they have to become real style runs. Wesley's lemma, the words of
 * the verse he is glossing, is marked the same way. Henry and JFB carry none, so they pass
 * through untouched.
 */
fun commentaryText(
    body: String, tinted: Set<Int> = emptySet(), tint: Color = Color.Unspecified,
): androidx.compose.ui.text.AnnotatedString =
    italicised(commentaryBody(body), tinted, tint)

/**
 * Text with `*asterisked*` runs set in italic: the translators' and Tyndale's quotations,
 * and Wesley's lemmas. The runs whose index is in [tinted] are also coloured [tint]: a
 * lemma whose words are underlined in the verse above, in the same colour.
 */
fun italicised(
    clean: String, tinted: Set<Int> = emptySet(), tint: Color = Color.Unspecified,
): androidx.compose.ui.text.AnnotatedString {
    if (!clean.contains('*')) return androidx.compose.ui.text.AnnotatedString(clean)
    return androidx.compose.ui.text.buildAnnotatedString {
        var i = 0; var n = 0
        while (true) {
            val open = clean.indexOf('*', i)
            if (open < 0) { append(clean.substring(i)); break }
            val close = clean.indexOf('*', open + 1)
            if (close < 0) { append(clean.substring(i)); break }
            append(clean.substring(i, open))
            pushStyle(androidx.compose.ui.text.SpanStyle(
                fontStyle = FontStyle.Italic,
                color = if (n in tinted) tint else Color.Unspecified,
            ))
            append(clean.substring(open + 1, close))
            pop()
            i = close + 1; n++
        }
    }
}

/**
 * The commentaries, plainest first — the order they appear in and can be switched off in.
 */
private val COMMENTARIES = listOf(
    "tyn" to ("Tyndale Study Notes" to "2022 · plain modern English"),
    "wes" to ("John Wesley" to "1755 · a line on each phrase"),
    "mhc" to ("Matthew Henry" to "1710 · devotional, discursive"),
    "jfb" to ("Jamieson, Fausset & Brown" to "1871 · close on the wording"),
)

/** What each commentary is actually called. The database keys are three letters. */
fun commentaryName(source: String): String = when (source) {
    "mhc" -> "Matthew Henry"
    "wes" -> "John Wesley"
    "jfb" -> "Jamieson, Fausset & Brown"
    "tyn" -> "Tyndale Study Notes"
    else -> source.uppercase()
}

/**
 * A paragraph of commentary.
 *
 * Long — Matthew Henry on a single verse can run many hundreds of words — so it is clamped
 * to a few lines and opens on a tap. A study page that makes you scroll past a page of prose
 * to reach the next verse has stopped being a Bible; one that hides the commentary entirely
 * is not a study Bible at all. The clamp is the compromise: enough to see what it is about
 * and whether it is worth reading now.
 */
@Composable
fun CommentRow(
    source: String,
    body: String,
    /** The verse the paragraph hangs from: what "this book" and "v. 7" mean in it. */
    verse: VerseId,
    books: List<Book>,
    /** Follow a scripture cited in the text. */
    onGo: (VerseId) -> Unit,
    /** How far the paragraph reaches ("verses 1–9") when that is more than one verse. */
    scope: String? = null,
    onSpeak: (() -> Unit)? = null,
    /** Which `*lemmas*` of the body are underlined in the verse: drawn in the same colour. */
    anchored: Set<Int> = emptySet(),
) {
    val p = LocalPalette.current
    var open by remember(body) { mutableStateOf(false) }
    // The citations are links, in the cross-reference colour, so a commentator's "see
    // Lu 13:6" is one tap away instead of a search. Built once per paragraph. A tap on the
    // text is looked up against its layout: on a citation it goes there, anywhere else it
    // opens or closes the paragraph like a tap on the label.
    val text = remember(body, verse, anchored) {
        Citations.linked(commentaryText(body, anchored, p.gold), verse, books, p.indigo)
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val gap = 18.dp
    val lane = commentLaneColour(p)
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                // A paragraph on a run of verses closes the bracket that began at the
                // first of them: the last stretch runs to the foot of the paragraph. One
                // on a single verse sits right under it and needs no line to say so.
                if (scope != null) Modifier.drawBehind {
                    drawRect(lane, androidx.compose.ui.geometry.Offset.Zero,
                        androidx.compose.ui.geometry.Size(1.5.dp.toPx(), size.height - gap.toPx()))
                } else Modifier
            )
            .padding(start = INDENT, bottom = gap)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { open = !open },
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(
                    if (scope == null) commentaryName(source)
                    else commentaryName(source) + " \u00b7 " + scope,
                    p.gold,
                )
                Spacer(Modifier.weight(1f))
                if (onSpeak != null && open) {
                    androidx.compose.material3.Text(
                        "▶ read",
                        fontFamily = UiFont,
                        fontSize = (10 * LocalTextScale.current).sp,
                        color = p.inkSoft,
                        modifier = Modifier
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) { onSpeak() }
                            .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(5.dp))
            androidx.compose.material3.Text(
                text,
                fontFamily = ReadingFont,
                fontSize = (14 * LocalTextScale.current).sp,
                lineHeight = (23.5 * LocalTextScale.current).sp,
                color = p.inkSoft,
                maxLines = if (open) Int.MAX_VALUE else 4,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                onTextLayout = { layout = it },
                modifier = Modifier.pointerInput(text) {
                    detectTapGestures { pos ->
                        val target = layout?.getOffsetForPosition(pos)?.let { Citations.targetAt(text, it) }
                        if (target != null) onGo(target) else open = !open
                    }
                },
            )
            if (!open) {
                Spacer(Modifier.height(3.dp))
                androidx.compose.material3.Text(
                    "more",
                    fontFamily = UiFont,
                    fontSize = (10.5 * LocalTextScale.current).sp,
                    letterSpacing = 0.5.sp,
                    color = p.gold,
                )
            }
        }
    }
}

/**
 * The cross-references for a verse, as one line of links.
 *
 * Last under a verse, because they lead off the page. Set as a single run of small type
 * rather than a list: a dozen stacked rows of "Psalm 27:1" would outweigh the verse they
 * hang from, and these are a glance, not a reading.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RefsRow(labels: List<Pair<String, VerseId>>, onGo: (VerseId) -> Unit) {
    val p = LocalPalette.current
    FlowRow(
        Modifier.padding(start = INDENT, end = 4.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        androidx.compose.material3.Text(
            "\u2192",
            fontFamily = UiFont,
            fontSize = (11 * LocalTextScale.current).sp,
            color = p.indigo,
            modifier = Modifier.padding(end = 9.dp, top = 2.dp),
        )
        labels.forEachIndexed { i, (label, id) ->
            // The separator is drawn inside the reference that follows it, so the two can
            // never be split by a wrap: a line ending in a lone interpunct looks like a
            // missing reference. The first needs none, because the arrow introduces it.
            androidx.compose.material3.Text(
                if (i == 0) label else "\u00b7  $label",
                fontFamily = UiFont,
                fontSize = (12 * LocalTextScale.current).sp,
                color = p.indigo,
                modifier = Modifier
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { onGo(id) }
                    .padding(end = 10.dp, top = 2.dp, bottom = 2.dp),
            )
        }
    }
}

/** The small caps label every piece of apparatus is introduced by. */
@Composable
private fun Label(text: String, colour: androidx.compose.ui.graphics.Color) {
    androidx.compose.material3.Text(
        text.uppercase(),
        fontFamily = UiFont,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.1.sp,
        color = colour,
    )
}

/**
 * Which layers study mode puts on the page.
 *
 * Notes are not offered here. They are the reader's own writing and show whether or not
 * study mode is on, so making them switchable would imply they could be lost.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun StudyLayersSheet(
    layers: StudyLayers,
    /** False when the download that feeds a layer is not on the device. */
    hasCommentary: Boolean,
    hasStudy: Boolean,
    onChange: (StudyLayers) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = p.sunk,
        dragHandle = { androidx.compose.material3.BottomSheetDefaults.DragHandle(color = p.rule) },
    ) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 34.dp)) {
            androidx.compose.material3.Text(
                "Study mode",
                fontFamily = DisplayFont, fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                color = p.ink,
            )
            Spacer(Modifier.height(4.dp))
            androidx.compose.material3.Text(
                "What sits under each verse. Your own notes always show.",
                fontFamily = UiFont, fontSize = 12.sp, lineHeight = 18.sp, color = p.inkSoft,
            )
            Spacer(Modifier.height(18.dp))
            LayerRow(
                "Commentary", "Explanations of the verse",
                layers.commentary,
                enabled = hasCommentary,
            ) { onChange(layers.copy(commentary = it)) }
            // Which of them, indented under the switch that governs all four. Wesley, Henry
            // and JFB are worth having but read like their centuries; someone who only wants
            // plain modern English can leave Tyndale on and the others off.
            if (layers.commentary && hasCommentary) {
                Column(Modifier.padding(start = 18.dp)) {
                    COMMENTARIES.forEach { (key, about) ->
                        val (name, detail) = about
                        LayerRow(name, detail, key !in layers.hiddenCommentaries) { on ->
                            val hidden = layers.hiddenCommentaries.toMutableSet()
                            if (on) hidden -= key else hidden += key
                            onChange(layers.copy(hiddenCommentaries = hidden))
                        }
                    }
                }
            }
            LayerRow(
                "Cross-references", "Where else Scripture says this",
                layers.refs,
            ) { onChange(layers.copy(refs = it)) }
            LayerRow(
                "Translator's notes", "Variants, literal renderings, measures",
                layers.footnotes,
            ) { onChange(layers.copy(footnotes = it)) }
        }
    }
}

@Composable
private fun LayerRow(
    label: String,
    detail: String,
    on: Boolean,
    enabled: Boolean = true,
    onSet: (Boolean) -> Unit,
) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onSet(!on) }
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            androidx.compose.material3.Text(
                label,
                fontFamily = UiFont, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = if (enabled) p.ink else p.inkSoft,
            )
            Spacer(Modifier.height(2.dp))
            androidx.compose.material3.Text(
                detail,
                fontFamily = UiFont, fontSize = 11.sp, color = p.inkSoft,
            )
        }
        androidx.compose.material3.Switch(
            // Off and unusable when the data is not there: a switch that turns on but
            // changes nothing on the page is worse than one that says why it cannot.
            checked = on && enabled,
            enabled = enabled,
            onCheckedChange = onSet,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = p.paper,
                checkedTrackColor = p.gold,
                uncheckedThumbColor = p.inkSoft,
                uncheckedTrackColor = Color.Transparent,
                uncheckedBorderColor = p.rule,
            ),
        )
    }
}
