package ca.lampandlight

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A microphone: the sign that a line is listened for. Drawn, like the pencil, so it has the
 * pencil's weight and takes the ink's colour. [full] fills its head: a voice is being heard.
 */
@Composable
fun EarMark(colour: Color, size: Dp = 18.dp, full: Float = 0f, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        // On a grid of 24: the head, the cradle it sits in, the stem and the foot.
        val u = this.size.minDimension / 24f
        val w = 1.9f * u
        val head = Size(6.4f * u, 11.4f * u)
        val at = Offset(8.8f * u, 2.6f * u)
        val round = CornerRadius(3.2f * u)
        if (full > 0f) drawRoundRect(colour.copy(alpha = colour.alpha * full), at, head, round)
        drawRoundRect(colour, at, head, round, style = Stroke(w))
        drawArc(
            colour, startAngle = 0f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(5.4f * u, 4.2f * u), size = Size(13.2f * u, 13.2f * u),
            style = Stroke(w, cap = StrokeCap.Round),
        )
        drawLine(colour, Offset(12f * u, 17.6f * u), Offset(12f * u, 21.2f * u), w, StrokeCap.Round)
        drawLine(colour, Offset(8.6f * u, 21.2f * u), Offset(15.4f * u, 21.2f * u), w, StrokeCap.Round)
    }
}

/**
 * Whether a line being learned is listened for, and the way to change it.
 *
 * [on] is what the reader has asked for and the phone can do: the switch is on, the files
 * are here and the microphone is allowed. [set] changes it, and does whatever has to come
 * first: with the files not on the phone it opens the sheet that offers them, and with the
 * microphone not yet allowed it asks. So it can be called from a mark on a card or from a
 * row in Settings and the same things happen.
 */
class HearSwitch(val on: Boolean, val set: (Boolean) -> Unit)

@Composable
fun rememberHearSwitch(prefs: Prefs): HearSwitch {
    val ctx = LocalContext.current
    var present by remember { mutableStateOf(Ears.present(ctx)) }
    var allowed by remember { mutableStateOf(Ears.allowed(ctx)) }
    var offering by remember { mutableStateOf(false) }
    fun switchOn() { prefs.hearing = true; prefs.save() }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { yes ->
        allowed = yes
        // Refused, perhaps for good: the sheet says where it can be allowed.
        if (yes) { switchOn(); offering = false } else offering = true
    }
    if (offering) HearSheet(
        present = present,
        onFetched = {
            present = true
            allowed = Ears.allowed(ctx)
            if (allowed) { switchOn(); offering = false } else ask.launch(Manifest.permission.RECORD_AUDIO)
        },
        onDismiss = { offering = false },
    )
    return HearSwitch(on = prefs.hearing && present && allowed) { want ->
        allowed = Ears.allowed(ctx)
        when {
            !want -> { prefs.hearing = false; prefs.save() }
            !present -> offering = true
            !allowed -> ask.launch(Manifest.permission.RECORD_AUDIO)
            else -> switchOn()
        }
    }
}

/**
 * The mark on a verse's card. Quiet ink while nothing listens, gold while it is switched on,
 * a soft disc behind it while the microphone is open, and its head filled while somebody is
 * heard speaking. A tap switches it.
 */
@Composable
fun HearMark(on: Boolean, listening: Boolean, voiced: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val colour by animateColorAsState(if (on) p.gold else p.inkSoft.copy(alpha = 0.75f), tween(220), label = "ear")
    val open by animateFloatAsState(if (listening) 1f else 0f, tween(260), label = "earOpen")
    val full by animateFloatAsState(if (listening && voiced) 1f else 0f, tween(140), label = "earFull")
    Box(
        modifier.size(40.dp)
            .semantics { contentDescription = if (on) "Listening for your line: on" else "Listening for your line: off" }
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(40.dp)) {
            if (open > 0f) drawCircle(
                p.gold.copy(alpha = (0.13f + 0.12f * full) * open),
                radius = size.minDimension / 2f * (0.74f + 0.26f * full),
            )
        }
        EarMark(colour, size = 19.dp, full = full)
    }
}

/**
 * What hearing is, before it is switched on: what it does, what it costs and where the
 * sound goes. The download is offered here and nowhere pressed on anyone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HearSheet(present: Boolean, onFetched: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var failed by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.sunk,
        dragHandle = { BottomSheetDefaults.DragHandle(color = p.rule) },
    ) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 30.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EarMark(p.gold, size = 22.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Hearing your line",
                    fontFamily = DisplayFont, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = p.ink,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (present) "The microphone is switched off for this app. It can be allowed in the phone's settings."
                else "When it is your turn the app can listen, and go on by itself once you have said the " +
                    "line. It marks nothing: whether you had it is still yours to say.",
                fontFamily = UiFont, fontSize = 14.sp, lineHeight = 22.sp, color = p.ink,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "What the microphone hears is worked out on this phone. It is sent nowhere, and it is not kept.",
                fontFamily = UiFont, fontSize = 13.sp, lineHeight = 21.sp, color = p.inkSoft,
            )
            Spacer(Modifier.height(22.dp))
            // The same height whichever it holds, so the sheet does not jump as it changes.
            Box(Modifier.fillMaxWidth().height(46.dp), contentAlignment = Alignment.CenterStart) {
                when {
                    present -> Go("Open settings") {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        onDismiss()
                    }
                    busy -> Column {
                        Text(
                            "Downloading · ${(progress * 100).toInt()}%",
                            fontFamily = UiFont, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = p.inkSoft,
                        )
                        Spacer(Modifier.height(9.dp))
                        Box(Modifier.fillMaxWidth().height(3.dp).background(p.rule, RoundedCornerShape(2.dp))) {
                            Box(
                                Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp)
                                    .background(p.gold, RoundedCornerShape(2.dp)),
                            )
                        }
                    }
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Go(if (failed) "Try again" else "Download") {
                            busy = true; failed = false; progress = 0f
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching {
                                        Updates.fetchEars(ctx) { a, b -> progress = a.toFloat() / b.coerceAtLeast(1) }
                                    }.isSuccess
                                }
                                busy = false
                                if (ok && Ears.present(ctx)) onFetched() else failed = true
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Text(
                            if (failed) "It did not all arrive. What came is kept."
                            else "${Ears.MEGABYTES} MB, once",
                            fontFamily = UiFont, fontSize = 12.5.sp, color = p.inkSoft,
                        )
                    }
                }
            }
        }
    }
}
