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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
