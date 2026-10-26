package com.syam.haloring.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syam.haloring.data.Point
import com.syam.haloring.data.SleepNight
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val labelStyle = TextStyle(color = Muted, fontSize = 10.sp)

private fun DrawScope.label(tm: TextMeasurer, text: String, x: Float, y: Float, center: Boolean = false) {
    val r = tm.measure(text, labelStyle)
    val left = if (center) (x - r.size.width / 2f).coerceIn(0f, size.width - r.size.width) else x
    drawText(r, topLeft = Offset(left, y))
}

/** A day of readings drawn against a 24-hour axis. */
@Composable
fun DayLineChart(
    points: List<Point>,
    dayStartMs: Long,
    color: Color,
    modifier: Modifier = Modifier.fillMaxWidth().height(170.dp),
    gapMinutes: Int = 45,
    fixedMin: Float? = null,
    fixedMax: Float? = null,
) {
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        val left = 30.dp.toPx()
        val bottom = 18.dp.toPx()
        val w = size.width - left
        val h = size.height - bottom
        for (hour in listOf(0, 6, 12, 18, 24)) {
            val x = left + w * hour / 24f
            drawLine(Grid, Offset(x, 0f), Offset(x, h), 1f)
            label(tm, "%02d".format(hour % 24).let { if (hour == 24) "24" else it }, x, h + 3.dp.toPx(), center = true)
        }
        if (points.isEmpty()) {
            label(tm, "No readings for this day", left + w / 2f, h / 2f - 6.dp.toPx(), center = true)
            return@Canvas
        }
        val vals = points.map { it.value.toFloat() }
        var lo = fixedMin ?: (vals.min() - 4f)
        var hi = fixedMax ?: (vals.max() + 4f)
        if (hi - lo < 10f) { hi += 5f; lo -= 5f }
        for (k in 0..2) {
            val y = h * k / 2f
            drawLine(Grid, Offset(left, y), Offset(size.width, y), 1f)
            val v = hi - (hi - lo) * k / 2f
            label(tm, v.roundToInt().toString(), 0f, (y - 6.dp.toPx()).coerceIn(0f, h - 12.dp.toPx()))
        }
        fun xOf(ts: Long) = left + w * ((ts - dayStartMs) / 60000f / 1440f).coerceIn(0f, 1f)
        fun yOf(v: Float) = h - h * ((v - lo) / (hi - lo)).coerceIn(0f, 1f)

        val segments = mutableListOf<MutableList<Offset>>()
        var prevTs = Long.MIN_VALUE
        for (p in points.sortedBy { it.ts }) {
            if (segments.isEmpty() || p.ts - prevTs > gapMinutes * 60_000L) segments += mutableListOf<Offset>()
            segments.last() += Offset(xOf(p.ts), yOf(p.value.toFloat()))
            prevTs = p.ts
        }
        val fillBrush = Brush.verticalGradient(listOf(color.copy(alpha = 0.32f), Color.Transparent), 0f, h)
        for (seg in segments) {
            if (seg.size == 1) {
                drawCircle(color, 3.dp.toPx(), seg[0])
                continue
            }
            val line = Path().apply {
                moveTo(seg[0].x, seg[0].y)
                for (i in 1 until seg.size) lineTo(seg[i].x, seg[i].y)
            }
            val fill = Path().apply {
                moveTo(seg[0].x, h)
                for (o in seg) lineTo(o.x, o.y)
                lineTo(seg.last().x, h)
                close()
            }
            drawPath(fill, fillBrush)
            drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Simple bars, e.g. steps per hour or per day. */
@Composable
fun BarChart(
    values: List<Float>,
    labels: List<String>,
    color: Color,
    modifier: Modifier = Modifier.fillMaxWidth().height(150.dp),
    labelEvery: Int = 1,
    highlight: Int = -1,
    target: Float? = null,
) {
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        val bottom = 18.dp.toPx()
        val h = size.height - bottom
        val n = values.size.coerceAtLeast(1)
        val slot = size.width / n
        val barW = (slot * 0.62f).coerceAtMost(28.dp.toPx())
        val max = maxOf(values.maxOrNull() ?: 0f, target ?: 0f).coerceAtLeast(1f)
        if (target != null) {
            val y = h - h * (target / max)
            drawLine(Muted.copy(alpha = 0.6f), Offset(0f, y), Offset(size.width, y), 1.5f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
        values.forEachIndexed { i, v ->
            val bh = (h * (v / max)).coerceAtLeast(if (v > 0) 3.dp.toPx() else 0f)
            val x = slot * i + (slot - barW) / 2f
            val c = if (highlight == -1 || i == highlight) color else color.copy(alpha = 0.45f)
            if (bh > 0) drawRoundRect(c, Offset(x, h - bh), Size(barW, bh), CornerRadius(barW / 3f))
            if (i % labelEvery == 0 && i < labels.size) label(tm, labels[i], x + barW / 2f, h + 3.dp.toPx(), center = true)
        }
    }
}

private fun stageRow(stage: Int) = when (stage) {
    SleepNight.STAGE_AWAKE -> 0
    SleepNight.STAGE_REM -> 1
    SleepNight.STAGE_LIGHT -> 2
    else -> 3
}

fun stageColor(stage: Int) = when (stage) {
    SleepNight.STAGE_AWAKE -> AwakeColor
    SleepNight.STAGE_REM -> RemColor
    SleepNight.STAGE_LIGHT -> LightColor
    else -> DeepColor
}

/** The classic sleep-stage staircase. */
@Composable
fun Hypnogram(night: SleepNight, modifier: Modifier = Modifier.fillMaxWidth().height(180.dp)) {
    val tm = rememberTextMeasurer()
    val fmt = DateTimeFormatter.ofPattern("HH:mm")
    val zone = ZoneId.systemDefault()
    fun t(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).format(fmt)
    Canvas(modifier) {
        val left = 44.dp.toPx()
        val bottom = 18.dp.toPx()
        val w = size.width - left
        val h = size.height - bottom
        val rowH = h / 4f
        val names = listOf("Awake", "REM", "Light", "Deep")
        names.forEachIndexed { i, n ->
            label(tm, n, 0f, rowH * i + rowH / 2f - 7.dp.toPx())
            drawLine(Grid, Offset(left, rowH * (i + 1)), Offset(size.width, rowH * (i + 1)), 1f)
        }
        val span = (night.end - night.start).coerceAtLeast(1L).toFloat()
        for (s in night.stages) {
            val x0 = left + w * ((s.ts - night.start) / span).coerceIn(0f, 1f)
            val x1 = left + w * ((s.ts + s.minutes * 60_000L - night.start) / span).coerceIn(0f, 1f)
            val r = stageRow(s.stage)
            drawRoundRect(
                stageColor(s.stage),
                Offset(x0, rowH * r + rowH * 0.18f),
                Size((x1 - x0).coerceAtLeast(2f), rowH * 0.64f),
                CornerRadius(4.dp.toPx()),
            )
        }
        label(tm, t(night.start), left, h + 3.dp.toPx())
        val endText = t(night.end)
        val r = tm.measure(endText, labelStyle)
        drawText(r, topLeft = Offset(size.width - r.size.width, h + 3.dp.toPx()))
    }
}

/** Little line for the dashboard cards. */
@Composable
fun Sparkline(points: List<Point>, color: Color, modifier: Modifier = Modifier.fillMaxWidth().height(36.dp)) {
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val lo = points.minOf { it.value }.toFloat()
        val hi = points.maxOf { it.value }.toFloat().let { if (it - lo < 4f) lo + 4f else it }
        val t0 = points.first().ts
        val t1 = points.last().ts.coerceAtLeast(t0 + 1)
        val path = Path()
        points.forEachIndexed { i, p ->
            val x = size.width * (p.ts - t0) / (t1 - t0).toFloat()
            val y = size.height - size.height * ((p.value - lo) / (hi - lo))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Progress ring (steps goal). */
@Composable
fun ProgressRing(fraction: Float, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = 10.dp.toPx()
        val d = size.minDimension - stroke
        val tl = Offset((size.width - d) / 2f, (size.height - d) / 2f)
        drawArc(Grid, 0f, 360f, false, tl, Size(d, d), style = Stroke(stroke))
        drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
    }
}
