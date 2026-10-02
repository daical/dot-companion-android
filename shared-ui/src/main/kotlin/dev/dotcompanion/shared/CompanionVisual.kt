package dev.dotcompanion.shared

import android.animation.ValueAnimator
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.dotcompanion.core.MessageStatus

val CompanionNight = Color(0xFF10121C)
val CompanionCoral = Color(0xFFFFAC98)
val CompanionViolet = Color(0xFFB6A5FF)

/** Original coral lantern character. No third-party assets or official brand animation. */
@Composable
fun CompanionVisual(modifier: Modifier = Modifier, status: MessageStatus? = null, animate: Boolean = true) {
    val moving = animate && ValueAnimator.areAnimatorsEnabled()
    val breath = if (moving) {
        val transition = rememberInfiniteTransition(label = "companion breathing")
        val value by transition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "gentle movement",
        )
        value
    } else 0.4f
    val description = when (status) {
        MessageStatus.SENDING -> "Original coral companion, sending"
        MessageStatus.AWAITING_REPLY -> "Original coral companion, waiting for a reply"
        MessageStatus.FAILED -> "Original coral companion, delivery needs attention"
        else -> "Original coral companion, local preview"
    }
    Canvas(modifier.semantics { contentDescription = description }) {
        val unit = size.minDimension
        val center = Offset(size.width / 2, size.height / 2)
        drawCircle(
            Brush.radialGradient(listOf(CompanionViolet.copy(alpha = .22f), Color.Transparent), center, unit * .49f),
            radius = unit * .49f, center = center,
        )
        drawCircle(CompanionViolet.copy(alpha = .35f), unit * (.405f + breath * .012f), center, style = Stroke(unit * .006f))
        withTransform({
            translate(center.x - unit / 2, center.y - unit / 2 - breath * unit * .014f)
            scale(1f + breath * .02f, 1f - breath * .01f, Offset(unit / 2, unit / 2))
        }) {
            val body = Path().apply {
                moveTo(unit * .28f, unit * .24f)
                cubicTo(unit * .43f, unit * .10f, unit * .69f, unit * .18f, unit * .76f, unit * .37f)
                cubicTo(unit * .88f, unit * .52f, unit * .82f, unit * .72f, unit * .63f, unit * .79f)
                cubicTo(unit * .44f, unit * .88f, unit * .22f, unit * .75f, unit * .20f, unit * .56f)
                cubicTo(unit * .15f, unit * .39f, unit * .19f, unit * .30f, unit * .28f, unit * .24f)
                close()
            }
            drawPath(body, Brush.linearGradient(listOf(Color(0xFFFFD5B9), CompanionCoral, Color(0xFFE382B3)), Offset(unit * .25f, unit * .2f), Offset(unit * .8f, unit * .85f)))
            val ink = Color(0xFF302039)
            val eyeHeight = if (status == MessageStatus.AWAITING_REPLY) unit * .055f else unit * .095f
            drawOval(ink, Offset(unit * .36f, unit * .455f), Size(unit * .038f, eyeHeight))
            drawOval(ink, Offset(unit * .585f, unit * .455f), Size(unit * .038f, eyeHeight))
            val smile = Path().apply {
                moveTo(unit * .455f, unit * .585f)
                quadraticTo(unit * .50f, unit * .63f, unit * .545f, unit * .585f)
            }
            drawPath(smile, ink.copy(alpha = .7f), style = Stroke(unit * .012f))
        }
    }
}

fun MessageStatus.displayLabel(): String = when (this) {
    MessageStatus.QUEUED -> "Queued on this device"
    MessageStatus.SENDING -> "Sending · no reply yet"
    MessageStatus.AWAITING_REPLY -> "Waiting for reply"
    MessageStatus.REPLIED -> "Reply received"
    MessageStatus.FAILED -> "Delivery failed · tap Retry"
}
