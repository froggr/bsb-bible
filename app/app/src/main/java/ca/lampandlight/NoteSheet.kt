package ca.lampandlight

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A pencil: the sign for what the reader has written, wherever it is shown or offered. It
 * stands where a label would, because whose note it is needs no saying to the one who wrote
 * it. Drawn, not a glyph, so it is the same weight on every phone and takes the ink's colour.
 */
@Composable
fun NoteMark(colour: Color, size: Dp = 13.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        // On a grid of 24: the barrel from the top right down to the point at the bottom left.
        val u = this.size.minDimension / 24f
        val barrel = Path().apply {
            moveTo(16.5f * u, 3.5f * u)
            lineTo(20.5f * u, 7.5f * u)
            lineTo(8f * u, 20f * u)
            lineTo(3f * u, 21f * u)
            lineTo(4f * u, 16f * u)
            close()
        }
        val pen = Stroke(width = 1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(barrel, colour, style = pen)
        // Where the wood meets the barrel.
        drawLine(colour, androidx.compose.ui.geometry.Offset(13.5f * u, 6.5f * u),
            androidx.compose.ui.geometry.Offset(17.5f * u, 10.5f * u), 1.9f * u, StrokeCap.Round)
    }
}

/**
 * Writing on a verse.
 *
 * The verse stays above the field, because a note written without the words in front of you
 * is a note about something half-remembered. Saving is explicit: a note is worth a deliberate
 * keystroke, and an autosave that fires mid-thought is worse than one that does not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteSheet(
    reference: String,
    verseText: String,
    existing: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var value by remember(reference) {
        mutableStateOf(TextFieldValue(existing.orEmpty(), androidx.compose.ui.text.TextRange(existing?.length ?: 0)))
    }

    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = p.sunk,
        dragHandle = { BottomSheetDefaults.DragHandle(color = p.rule) },
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .imePadding(),
        ) {
            Text(
                reference,
                fontFamily = DisplayFont, fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                color = p.ink,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                verseText,
                fontFamily = ReadingFont, fontWeight = FontWeight.Light, fontStyle = FontStyle.Italic,
                fontSize = 15.sp, lineHeight = 24.sp, color = p.inkSoft,
                maxLines = 4,
                modifier = Modifier.padding(bottom = 18.dp),
            )

            CompositionLocalProvider(
                LocalTextSelectionColors provides TextSelectionColors(
                    handleColor = p.gold, backgroundColor = p.gold.copy(alpha = 0.3f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp)
                        .background(p.paper, RoundedCornerShape(9.dp))
                        .border(1.dp, p.rule, RoundedCornerShape(9.dp))
                        .padding(14.dp)
                ) {
                    if (value.text.isEmpty()) Text(
                        "What is God saying here?",
                        fontFamily = ReadingFont, fontSize = 16.sp, color = p.inkSoft,
                    )
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it },
                        textStyle = TextStyle(
                            fontFamily = ReadingFont, fontSize = 16.sp, lineHeight = 26.sp,
                            color = p.ink,
                        ),
                        cursorBrush = SolidColor(p.gold),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!existing.isNullOrBlank()) {
                    Text(
                        "Delete",
                        fontFamily = UiFont, fontSize = 13.sp, color = p.inkSoft,
                        modifier = Modifier
                            .clickable { onSave(""); onDismiss() }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Surface(
                    onClick = { onSave(value.text); onDismiss() },
                    shape = RoundedCornerShape(9.dp),
                    color = p.gold,
                ) {
                    Text(
                        "Save",
                        fontFamily = UiFont, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = p.paper,
                        modifier = Modifier.padding(horizontal = 26.dp, vertical = 11.dp),
                    )
                }
            }
        }
    }
}
