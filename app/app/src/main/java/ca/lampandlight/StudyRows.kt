package ca.lampandlight

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

/**
 * The reader's own note.
 *
 * Drawn in their highlight's colour and in the reading face, italic — of everything on this
 * page it is the only thing that is theirs, and it should read like a hand in the margin
 * rather than like more apparatus. This shows whether or not study mode is on.
 */
@Composable
fun NoteRow(text: String, colour: MarkColor, onTap: () -> Unit) {
    val p = LocalPalette.current
    val accent = colour.color(p.dark)
    androidx.compose.material3.Surface(
        color = accent.copy(alpha = if (p.dark) 0.13f else 0.3f),
        shape = RoundedCornerShape(3.dp),
        modifier = Modifier
            .padding(start = INDENT, bottom = 16.dp)
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onTap() },
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            // A bar in the mark's own colour, so a note and the highlight it belongs to are
            // visibly the same thought.
            Box(
                Modifier.width(2.5.dp).fillMaxHeight()
                    .background(if (p.dark) accent.copy(alpha = 0.75f) else accent)
            )
            Column(Modifier.padding(start = 11.dp, end = 12.dp, top = 9.dp, bottom = 10.dp)) {
                Label("Your note", p.inkSoft)
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
 * to the verse and the most compact thing here.
 */
@Composable
fun FootnoteRow(text: String) {
    val p = LocalPalette.current
    Row(Modifier.padding(start = INDENT, end = 4.dp, bottom = 14.dp)) {
        androidx.compose.material3.Text(
            "†",
            fontFamily = UiFont,
            fontSize = (11 * LocalTextScale.current).sp,
            color = p.indigo,
            modifier = Modifier.padding(end = 9.dp, top = 1.dp),
        )
        androidx.compose.material3.Text(
            text,
            fontFamily = UiFont,
            fontSize = (12.5 * LocalTextScale.current).sp,
            lineHeight = (20 * LocalTextScale.current).sp,
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
 * The commentaries, plainest first — the order they appear in and can be switched off in.
 */
private val COMMENTARIES = listOf(
    "tyn" to ("Tyndale Study Notes" to "2022 · plain modern English"),
    "mhc" to ("Matthew Henry" to "1710 · devotional, discursive"),
    "jfb" to ("Jamieson, Fausset & Brown" to "1871 · close on the wording"),
)

/** What each commentary is actually called. The database keys are three letters. */
fun commentaryName(source: String): String = when (source) {
    "mhc" -> "Matthew Henry"
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
fun CommentRow(source: String, body: String, onSpeak: (() -> Unit)? = null) {
    val p = LocalPalette.current
    var open by remember(body) { mutableStateOf(false) }
    Row(
        Modifier
            .padding(start = INDENT, bottom = 18.dp)
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { open = !open },
    ) {
        // No bar here: commentary is the bulkiest thing on the page, and a marker against
        // every verse would turn the margin into a ladder. The gold label is enough.
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(commentaryName(source), p.gold)
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
                commentaryBody(body),
                fontFamily = ReadingFont,
                fontSize = (14 * LocalTextScale.current).sp,
                lineHeight = (23.5 * LocalTextScale.current).sp,
                color = p.inkSoft,
                maxLines = if (open) Int.MAX_VALUE else 4,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
            // Which of them, indented under the switch that governs all three. Henry and
            // JFB are worth having but read like their centuries; someone who only wants
            // plain modern English can leave Tyndale on and the other two off.
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
