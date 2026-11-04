package com.syam.haloring.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

private const val ARC_START = 125f
private const val ARC_SWEEP = 290f

/** Animated gradient score dial (0–100), open at the bottom like a premium watch face. */
@Composable
fun ScoreDial(
    score: Int?,
    colors: List<Color>,
    size: Dp,
    stroke: Dp,
    numberSize: Int,
    caption: String? = null,
) {
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(score) {
        sweep.animateTo((score ?: 0) / 100f, tween(1200, easing = FastOutSlowInEasing))
    }
    val shown by animateIntAsState(score ?: 0, tween(1200, easing = FastOutSlowInEasing), label = "score")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = stroke.toPx()
            val d = this.size.minDimension - sw
            val tl = Offset((this.size.width - d) / 2f, (this.size.height - d) / 2f)
            // soft glow under the arc
            if (score != null) {
                drawCircle(
                    Brush.radialGradient(listOf(colors.last().copy(alpha = 0.16f), Color.Transparent)),
                    radius = this.size.minDimension / 2f,
                )
            }
            drawArc(Grid, ARC_START, ARC_SWEEP, false, tl, Size(d, d), style = Stroke(sw, cap = StrokeCap.Round))
            if (score != null && sweep.value > 0f) {
                rotate(ARC_START) {
                    drawArc(
                        Brush.sweepGradient(
                            0f to colors.first(),
                            ARC_SWEEP / 360f to colors.last(),
                            1f to colors.first(),
                        ),
                        0f, ARC_SWEEP * sweep.value, false, tl, Size(d, d),
                        style = Stroke(sw, cap = StrokeCap.Round),
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (score == null) "--" else "$shown",
                color = TextMain, fontSize = numberSize.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp,
            )
            if (caption != null) Text(caption, color = Muted, fontSize = (numberSize * 0.3f).sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Dial + label + status underneath; used on the Today hero. */
@Composable
fun ScoreTile(
    title: String,
    score: Int?,
    colors: List<Color>,
    size: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier.clip(RoundedCornerShape(22.dp))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ScoreDial(score, colors, size, if (size > 110.dp) 11.dp else 8.dp, if (size > 110.dp) 40 else 28)
        Spacer(Modifier.height(6.dp))
        Text(title.uppercase(), color = TextMain, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp)
        val st = statusOf(score)
        Text(st.label, color = st.color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Contributor bar: 0–100 with status colour. */
@Composable
fun ContributorBar(score: Int, modifier: Modifier = Modifier.fillMaxWidth()) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(score) { anim.animateTo(score / 100f, tween(900, easing = FastOutSlowInEasing)) }
    val c = statusOf(score).color
    Canvas(modifier.height(6.dp)) {
        val r = CornerRadius(size.height / 2f)
        drawRoundRect(Grid, cornerRadius = r)
        drawRoundRect(
            Brush.horizontalGradient(listOf(c.copy(alpha = 0.6f), c)),
            size = Size(size.width * anim.value, size.height), cornerRadius = r,
        )
    }
}

/**
 * Personal-baseline bar (Oura style): the shaded band is your usual range,
 * the dot is today's value.
 */
@Composable
fun BaselineBar(
    value: Float?,
    low: Float?,
    high: Float?,
    min: Float,
    max: Float,
    color: Color,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    Canvas(modifier.height(14.dp)) {
        val y = size.height / 2f
        val track = 4.dp.toPx()
        fun x(v: Float) = ((v - min) / (max - min)).coerceIn(0f, 1f) * size.width
        drawRoundRect(Grid, Offset(0f, y - track / 2f), Size(size.width, track), CornerRadius(track / 2f))
        if (low != null && high != null) {
            val x0 = x(low)
            val x1 = maxOf(x(high), x0 + 6.dp.toPx())
            drawRoundRect(color.copy(alpha = 0.35f), Offset(x0, y - track / 2f), Size(x1 - x0, track), CornerRadius(track / 2f))
        }
        if (value != null) {
            val cx = x(value)
            drawCircle(Bg, 7.dp.toPx(), Offset(cx, y))
            drawCircle(color, 5.dp.toPx(), Offset(cx, y))
        }
    }
}

/** Small rounded square icon badge. */
@Composable
fun Badge(color: Color, size: Dp = 34.dp, content: @Composable () -> Unit) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(11.dp)).background(color.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) { content() }
}
