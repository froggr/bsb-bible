package ca.lampandlight

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A question on the section just read, with verses to choose between.
 *
 * It sits where a printed study guide puts its questions, at the end of the passage, and it
 * is the one piece of apparatus that asks something of the reader, so it is the one that is
 * drawn as a thing to touch: a sunk panel, the question in the reading face, and each choice
 * a slip of the page. The choices are scripture, so they are set as scripture, only smaller.
 *
 * Nothing here keeps score. A right choice turns gold and says which verse it is; a wrong
 * one shows the right verse and says where it is, which is the whole point of having asked.
 *
 * [chosen] is the choice made, [ReaderState.HEARD] when the voice gave the answer, or null
 * while the question is open. [waiting] is true while the voice has asked it aloud and is
 * holding for an answer, from [waitFrom] for [waitFor] on the uptime clock.
 */
@Composable
fun AskRow(
    row: Row.Ask,
    chosen: Int?,
    waiting: Boolean,
    waitFrom: Long,
    waitFor: Long,
    /** What to say under a question that was not known, about its coming back. */
    missed: String = "It will come back under Marks & settings, in Go over.",
    onChoose: (Int) -> Unit,
) {
    val p = LocalPalette.current
    val scale = LocalTextScale.current
    val settled = chosen != null
    val right = row.asked.correct
    val where = row.choices[right].where

    Surface(
        color = p.sunk,
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 22.dp),
    ) {
        Column {
            Column(Modifier.padding(start = 15.dp, end = 15.dp, top = 14.dp, bottom = 15.dp)) {
                Text(
                    (if (chosen == right) "That\u2019s it" else "Question").uppercase(),
                    fontFamily = UiFont, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.1.sp, color = p.gold,
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    row.asked.question.text,
                    fontFamily = ReadingFont, fontWeight = FontWeight.Medium,
                    fontSize = (17 * scale).sp, lineHeight = (25 * scale).sp,
                    color = p.ink,
                )
                Spacer(Modifier.height(13.dp))
                row.choices.forEachIndexed { i, choice ->
                    if (i > 0) Spacer(Modifier.height(8.dp))
                    Slip(
                        choice = choice,
                        // Before an answer every slip is the same. After it the right one
                        // is lit, the one wrongly chosen is named, and the rest stand back.
                        state = when {
                            !settled -> Slip.Open
                            i == right -> Slip.Right
                            i == chosen -> Slip.Chosen
                            else -> Slip.Other
                        },
                        onTap = { onChoose(i) },
                    )
                }
                if (waiting && !settled) {
                    Spacer(Modifier.height(11.dp))
                    Text(
                        "Choose one, or wait and hear it.",
                        fontFamily = UiFont, fontSize = 11.sp, color = p.inkSoft,
                    )
                }
                // Not known this time: say that it will be asked again, which is the point.
                if (settled && chosen != right) {
                    Spacer(Modifier.height(11.dp))
                    Text(missed, fontFamily = UiFont, fontSize = 11.sp, color = p.inkSoft)
                }
            }
            // The time the voice will wait, running out along the foot of the panel.
            if (waiting && !settled) WaitBar(row.key, waitFrom, waitFor)
        }
    }
}

/** The time the voice will wait, running out along the foot of a panel. */
@Composable
internal fun WaitBar(key: String, waitFrom: Long, waitFor: Long) {
    val p = LocalPalette.current
    val left = remember(key) { Animatable(1f) }
    LaunchedEffect(waitFrom, waitFor) {
        val gone = SystemClock.elapsedRealtime() - waitFrom
        left.snapTo((1f - gone.toFloat() / waitFor).coerceIn(0f, 1f))
        left.animateTo(
            0f, tween((waitFor - gone).coerceAtLeast(0L).toInt(), easing = LinearEasing),
        )
    }
    Box(Modifier.fillMaxWidth().height(2.dp).background(p.rule)) {
        Box(Modifier.fillMaxWidth(left.value).height(2.dp).background(p.gold))
    }
}

private enum class Slip { Open, Right, Chosen, Other }

@Composable
private fun Slip(choice: Row.Ask.Choice, state: Slip, onTap: () -> Unit) {
    val p = LocalPalette.current
    val scale = LocalTextScale.current
    val edge by animateColorAsState(
        when (state) {
            Slip.Right -> p.gold
            Slip.Other -> p.rule.copy(alpha = 0.5f)
            else -> p.rule
        }, tween(350), label = "slipEdge",
    )
    val fill by animateColorAsState(
        when (state) {
            Slip.Right -> p.gold.copy(alpha = if (p.dark) 0.16f else 0.12f)
            Slip.Other -> Color.Transparent
            else -> p.paper
        }, tween(350), label = "slipFill",
    )
    val ink by animateColorAsState(
        when (state) {
            Slip.Open, Slip.Right -> p.ink
            Slip.Chosen -> p.inkSoft
            Slip.Other -> p.inkSoft.copy(alpha = 0.7f)
        }, tween(350), label = "slipInk",
    )
    Surface(
        color = p.sunk,
        shape = RoundedCornerShape(5.dp),
        border = BorderStroke(if (state == Slip.Right) 1.5.dp else 1.dp, edge),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .clickable(
                enabled = state == Slip.Open,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onTap() },
    ) {
        Column(
            Modifier.background(fill).padding(start = 13.dp, end = 13.dp, top = 10.dp, bottom = 11.dp),
        ) {
            Text(
                choice.text,
                fontFamily = ReadingFont, fontWeight = FontWeight.Light,
                fontSize = (15 * scale).sp, lineHeight = (23 * scale).sp,
                color = ink,
                // Enough to know the verse by. The answer is shown whole once it is known.
                maxLines = if (state == Slip.Right) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (state == Slip.Right || state == Slip.Chosen) {
                Spacer(Modifier.height(6.dp))
                Text(
                    (if (state == Slip.Chosen) "You chose ${choice.where}" else choice.where).uppercase(),
                    fontFamily = UiFont, fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    color = if (state == Slip.Right) p.gold else p.inkSoft,
                )
            }
        }
    }
}
