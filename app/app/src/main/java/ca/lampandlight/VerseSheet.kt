package ca.lampandlight

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Everything you can do to one verse, on a long press.
 *
 * A tap already bookmarks, so nothing here is required to keep a verse — this is the second
 * thought: give the bookmark a colour, write on it, look the words up, or read on from here.
 * The verse itself is at the top, because every one of those choices is about these words.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerseSheet(
    reference: String,
    verseText: String,
    bookmarked: Boolean,
    colour: MarkColor?,
    note: String?,
    onBookmark: () -> Unit,
    onColour: (MarkColor?) -> Unit,
    onNote: () -> Unit,
    onStudy: () -> Unit,
    onReadFrom: () -> Unit,
    onCopy: () -> Unit,
    onSelectRange: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = p.sunk,
        dragHandle = { BottomSheetDefaults.DragHandle(color = p.rule) },
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(
                reference,
                fontFamily = DisplayFont, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                color = p.ink,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                verseText,
                fontFamily = ReadingFont, fontWeight = FontWeight.Light, fontStyle = FontStyle.Italic,
                fontSize = 15.sp, lineHeight = 24.sp, color = p.inkSoft,
                maxLines = 3,
            )

            Spacer(Modifier.height(20.dp))

            // The colour row doubles as the bookmark control: tapping a colour keeps the
            // verse in that colour, and the slash clears it.
            Text(
                if (bookmarked) "Bookmark" else "Bookmark in",
                fontFamily = UiFont, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                color = p.inkSoft,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MarkColor.entries.forEach { c ->
                    val on = bookmarked && colour == c
                    Box(
                        Modifier
                            .size(if (on) 40.dp else 34.dp)
                            .clip(CircleShape)
                            .background(c.color(p.dark))
                            .then(
                                if (on) Modifier.border(
                                    2.dp, p.ink, CircleShape) else Modifier
                            )
                            .clickable { onColour(c) },
                    )
                    Spacer(Modifier.width(12.dp))
                }
                if (bookmarked) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Remove",
                        fontFamily = UiFont, fontSize = 12.sp, color = p.inkSoft,
                        modifier = Modifier
                            .clickable { onColour(null) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.rule))

            Action(if (note.isNullOrBlank()) "Write a note" else "Edit note", onNote)
            if (!note.isNullOrBlank()) {
                Text(
                    note,
                    fontFamily = ReadingFont, fontSize = 14.sp, lineHeight = 22.sp,
                    color = p.inkSoft, maxLines = 3,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Action("Mark through to…", onSelectRange)
            Action("Read from here", onReadFrom)
            Action("Original words", onStudy)
            Action("Copy", onCopy)
        }
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Text(
        label,
        fontFamily = UiFont, fontSize = 15.sp, color = p.ink,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 15.dp),
    )
}
