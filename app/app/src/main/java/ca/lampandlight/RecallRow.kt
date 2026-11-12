package ca.lampandlight

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A chapter asked for from memory: its headings held back, one bar for each.
 *
 * The reader says what was in it, to themselves or to whoever is beside them, then looks,
 * a line at a time or all at once, and says whether they had it. The bars are as long as
 * what they cover, which is as much of a hint as a blank in a workbook gives. Nothing is
 * typed and nothing is marked: what the page cannot know it does not pretend to. See
 * [Places].
 */
@Composable
fun RecallPanel(
    /** The small line over it, until it is settled: where this is being asked. */
    label: String,
    question: String,
    headings: List<String>,
    /** Which headings have been looked at. */
    lifted: Set<Int>,
    /** [Quiz.RIGHT] or [Quiz.WRONG] once the reader has said; null before. */
    outcome: Int?,
    /** What to say once it is settled: when it comes back. */
    after: String,
    /** The voice has asked and is waiting, since [waitFrom] and for [waitFor]. */
    waiting: Boolean = false, waitFrom: Long = 0L, waitFor: Long = 1L,
    /** Look at one heading, or with null at all of them. */
    onLift: (Int?) -> Unit,
    onSaid: (Int) -> Unit,
) {
    val p = LocalPalette.current
    val scale = LocalTextScale.current
    val settled = outcome != null
    val all = lifted.size >= headings.size

    Surface(
        color = p.sunk,
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 22.dp),
    ) {
        Column {
            Column(Modifier.padding(start = 15.dp, end = 15.dp, top = 14.dp, bottom = 14.dp)) {
                Text(
                    when (outcome) { Quiz.RIGHT -> "You had it"; null -> label; else -> "Not yet" }.uppercase(),
                    fontFamily = UiFont, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.1.sp, color = p.gold,
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    question,
                    fontFamily = ReadingFont, fontWeight = FontWeight.Medium,
                    fontSize = (17 * scale).sp, lineHeight = (25 * scale).sp, color = p.ink,
                )
                Spacer(Modifier.height(9.dp))
                headings.forEachIndexed { i, heading ->
                    val shown = settled || i in lifted
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = (34 * scale).dp)
                            .clickable(
                                enabled = !shown, indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) { onLift(i) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The place in the order is given: the order is what is being learned.
                        if (headings.size > 1) Text(
                            "${i + 1}", fontFamily = UiFont, fontSize = (11 * scale).sp,
                            fontWeight = FontWeight.Medium, color = p.gold,
                            modifier = Modifier.width((20 * scale).dp),
                        )
                        Crossfade(shown, animationSpec = tween(320), label = "lift") { open ->
                            if (open) Text(
                                heading, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold,
                                fontSize = (19 * scale).sp, lineHeight = (23 * scale).sp, color = p.ink,
                                modifier = Modifier.padding(vertical = 5.dp),
                            ) else Box(
                                Modifier.fillMaxWidth((heading.length / 36f).coerceIn(0.26f, 0.94f))
                                    .height((9 * scale).dp).clip(RoundedCornerShape(50))
                                    .background(p.rule),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            settled -> after
                            all -> "Did you have it?"
                            waiting -> "Say it to yourself, or wait and hear it."
                            else -> "Say it to yourself, then look."
                        },
                        fontFamily = UiFont, fontSize = 12.sp, lineHeight = 17.sp, color = p.inkSoft,
                        modifier = Modifier.weight(1f).padding(end = 10.dp),
                    )
                    when {
                        settled -> Unit
                        !all -> Go("Show", quiet = true) { onLift(null) }
                        else -> {
                            Text(
                                "Not yet", fontFamily = UiFont, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                color = p.inkSoft,
                                modifier = Modifier.clickable { onSaid(Quiz.WRONG) }
                                    .padding(horizontal = 16.dp, vertical = 11.dp),
                            )
                            Go("I had it") { onSaid(Quiz.RIGHT) }
                        }
                    }
                }
            }
            if (waiting && !settled && !all) WaitBar("recall", waitFrom, waitFor)
        }
    }
}
