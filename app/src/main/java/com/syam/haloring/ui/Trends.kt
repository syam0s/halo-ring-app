package com.syam.haloring.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syam.haloring.App
import com.syam.haloring.data.dayEnd
import com.syam.haloring.data.dayStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private data class DayRec(
    val date: LocalDate,
    val readiness: Int?,
    val sleepScore: Int?,
    val activityScore: Int?,
    val sleepMin: Int?,
    val rhr: Int?,
    val hrv: Double?,
    val steps: Int?,
    val spo2: Double?,
    val stress: Double?,
)

private fun loadDays(app: App, days: Int): List<DayRec> {
    val today = LocalDate.now()
    return (days - 1 downTo 0).map { i ->
        val d = today.minusDays(i.toLong())
        fun avg(m: Metric) = loadSeries(app, m, dayStart(d), dayEnd(d)).takeIf { it.isNotEmpty() }?.map { it.value }?.average()
        DayRec(
            date = d,
            readiness = Scores.readiness(app, d)?.value,
            sleepScore = Scores.sleep(app, d)?.value,
            activityScore = Scores.activity(app, d)?.value,
            sleepMin = app.db.sleepFor(d)?.let { Scores.asleepMinutes(it) },
            rhr = Scores.restingHr(app, d),
            hrv = avg(Metric.HRV),
            steps = app.db.activity(dayStart(d), dayEnd(d)).sumOf { it.steps }.takeIf { it > 0 },
            spo2 = avg(Metric.SPO2),
            stress = avg(Metric.STRESS),
        )
    }
}

@Composable
fun TrendsScreen(app: App, openDetail: (Metric, LocalDate) -> Unit) {
    val ver by app.db.version.collectAsState()
    var range by rememberSaveable { mutableIntStateOf(0) }
    val ranges = listOf(7, 30, 90)
    val days = ranges[range]
    val recs by produceState<List<DayRec>?>(null, ver, days) { value = null; value = withContext(Dispatchers.IO) { loadDays(app, days) } }

    Box {
        GlowBackdrop(Violet)
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column {
                    Text("Trends", style = androidx.compose.material3.MaterialTheme.typography.headlineLarge)
                    Text("How your body is changing over time", color = Muted, fontSize = 13.sp)
                }
            }
            item { SegmentedPills(listOf("7 days", "30 days", "90 days"), range, Violet) { range = it } }
            val list = recs
            if (list == null) {
                item { Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Violet) } }
                return@LazyColumn
            }
            item { SummaryCard(list) }
            item {
                TrendCard("Readiness", Teal, list, { it.readiness?.toFloat() }, { "${it.roundToInt()}" }, bars = true,
                    colorFor = { statusOf(it.roundToInt()).color }, fixedMin = 0f, fixedMax = 100f) { openDetail(Metric.READINESS, it) }
            }
            item {
                TrendCard("Sleep", Violet, list, { it.sleepMin?.toFloat()?.div(60f) }, { fmtMinutes((it * 60).roundToInt()) }, bars = true,
                    target = 7.5f, fixedMin = 0f) { openDetail(Metric.SLEEP, it) }
            }
            item {
                TrendCard("Activity score", Orange, list, { it.activityScore?.toFloat() }, { "${it.roundToInt()}" }, bars = true,
                    colorFor = { statusOf(it.roundToInt()).color }, fixedMin = 0f, fixedMax = 100f) { openDetail(Metric.STEPS, it) }
            }
            item {
                TrendCard("Steps", Orange, list, { it.steps?.toFloat() }, { fmtNumber(it.roundToInt()) }, bars = true,
                    target = app.prefs.stepGoal.toFloat(), fixedMin = 0f) { openDetail(Metric.STEPS, it) }
            }
            item {
                TrendCard("Resting heart rate", Coral, list, { it.rhr?.toFloat() }, { "${it.roundToInt()} bpm" }, lowerIsBetter = true) {
                    openDetail(Metric.HEART, it)
                }
            }
            item { TrendCard("HRV", Lime, list, { it.hrv?.toFloat() }, { "${it.roundToInt()} ms" }) { openDetail(Metric.HRV, it) } }
            item {
                TrendCard("Blood oxygen", Sky, list, { it.spo2?.toFloat() }, { "${it.roundToInt()}%" }, fixedMin = 85f, fixedMax = 100f) {
                    openDetail(Metric.SPO2, it)
                }
            }
            item {
                TrendCard("Stress", Amber, list, { it.stress?.toFloat() }, { "${it.roundToInt()}" }, lowerIsBetter = true, fixedMin = 0f, fixedMax = 100f) {
                    openDetail(Metric.STRESS, it)
                }
            }
        }
    }
}

private data class Line(val name: String, val now: Double?, val before: Double?, val fmt: (Double) -> String, val goodUp: Boolean, val color: Color)

@Composable
private fun SummaryCard(list: List<DayRec>) {
    val half = list.size / 2
    val first = list.take(half)
    val second = list.drop(half)
    fun <T : Number> avg(l: List<DayRec>, f: (DayRec) -> T?): Double? =
        l.mapNotNull { f(it)?.toDouble() }.takeIf { it.isNotEmpty() }?.average()
    val lines = listOf(
        Line("Readiness", avg(second) { it.readiness }, avg(first) { it.readiness }, { "${it.roundToInt()}" }, true, Teal),
        Line("Sleep", avg(second) { it.sleepMin }, avg(first) { it.sleepMin }, { fmtMinutes(it.roundToInt()) }, true, Violet),
        Line("Resting HR", avg(second) { it.rhr }, avg(first) { it.rhr }, { "${it.roundToInt()} bpm" }, false, Coral),
        Line("HRV", avg(second) { it.hrv }, avg(first) { it.hrv }, { "${it.roundToInt()} ms" }, true, Lime),
        Line("Steps", avg(second) { it.steps }, avg(first) { it.steps }, { fmtNumber(it.roundToInt()) }, true, Orange),
    )
    Panel(Modifier.fillMaxWidth()) {
        PanelTitle("Recent vs earlier", Violet, trailing = "average per day")
        Spacer(Modifier.height(4.dp))
        Text("The last ${list.size - half} days compared with the ${half} days before.", color = Faint, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        lines.forEach { l ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(end = 10.dp)) { Badge(l.color, 10.dp) {} }
                Text(l.name, color = TextMain, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(l.now?.let(l.fmt) ?: "--", color = TextMain, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.padding(start = 10.dp))
                val n = l.now
                val b = l.before
                if (n != null && b != null && b != 0.0) {
                    val pct = ((n / b - 1) * 100).roundToInt()
                    val good = if (l.goodUp) pct >= 0 else pct <= 0
                    Chip((if (pct >= 0) "▲ " else "▼ ") + "${kotlin.math.abs(pct)}%", if (pct == 0) Muted else if (good) Teal else Amber)
                } else {
                    Chip("—", Faint)
                }
            }
        }
    }
}

@Composable
private fun TrendCard(
    title: String,
    color: Color,
    list: List<DayRec>,
    pick: (DayRec) -> Float?,
    fmt: (Float) -> String,
    bars: Boolean = false,
    lowerIsBetter: Boolean = false,
    colorFor: ((Float) -> Color)? = null,
    target: Float? = null,
    fixedMin: Float? = null,
    fixedMax: Float? = null,
    onOpenDay: (LocalDate) -> Unit,
) {
    val values = remember(list) { list.map(pick) }
    var sel by remember(list) { mutableStateOf(-1) }
    val present = values.filterNotNull()
    Panel(Modifier.fillMaxWidth()) {
        PanelTitle(title, color, trailing = if (present.isEmpty()) null else "avg ${fmt(present.average().toFloat())}")
        Spacer(Modifier.height(12.dp))
        if (present.isEmpty()) {
            Text("No data in this period yet.", color = Faint, fontSize = 13.sp)
            return@Panel
        }
        TrendChart(values, list.map { it.date }, color, bars, colorFor, target, fixedMin, fixedMax, sel) { sel = it }
        Spacer(Modifier.height(10.dp))
        val i = sel
        val v = values.getOrNull(i)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (i < 0) {
                    val best = if (lowerIsBetter) present.min() else present.max()
                    "Best: ${fmt(best)} · tap a day for details"
                } else "${list[i].date.format(DateTimeFormatter.ofPattern("EEE d MMM"))}: ${v?.let(fmt) ?: "no data"}",
                color = if (i < 0) Faint else TextMain, fontSize = 13.sp, fontWeight = if (i < 0) FontWeight.Normal else FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (i >= 0 && v != null) {
                Text("Open ›", color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp).clickable { onOpenDay(list[i].date) })
            }
        }
    }
}

@Composable
private fun TrendChart(
    values: List<Float?>,
    dates: List<LocalDate>,
    color: Color,
    bars: Boolean,
    colorFor: ((Float) -> Color)?,
    target: Float?,
    fixedMin: Float?,
    fixedMax: Float?,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val tm = rememberTextMeasurer()
    val pick by rememberUpdatedState(onSelect)
    val n = values.size
    val style = TextStyle(color = Faint, fontSize = 10.sp)
    Canvas(
        Modifier.fillMaxWidth().height(150.dp)
            .pointerInput(n) { detectTapGestures { o -> pick((o.x / (size.width / n.toFloat())).toInt().coerceIn(0, n - 1)) } }
            .pointerInput(n) {
                detectHorizontalDragGestures { c, _ -> pick((c.position.x / (size.width / n.toFloat())).toInt().coerceIn(0, n - 1)) }
            }
    ) {
        val bottom = 18.dp.toPx()
        val h = size.height - bottom
        val present = values.filterNotNull()
        var lo = fixedMin ?: (present.min() - (present.max() - present.min()) * 0.25f - 1f)
        var hi = fixedMax ?: (maxOf(present.max(), target ?: present.max()) * 1.08f + 0.5f)
        if (hi - lo < 1f) { hi += 1f; lo -= 1f }
        val slot = size.width / n
        fun x(i: Int) = slot * i + slot / 2f
        fun y(v: Float) = h - h * ((v - lo) / (hi - lo)).coerceIn(0f, 1f)

        for (k in 0..2) drawLine(Grid, Offset(0f, h * k / 2f), Offset(size.width, h * k / 2f), 1f)
        if (target != null) {
            drawLine(Muted.copy(alpha = 0.7f), Offset(0f, y(target)), Offset(size.width, y(target)), 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
        if (bars) {
            val bw = (slot * 0.62f).coerceIn(2.dp.toPx(), 22.dp.toPx())
            values.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                val c = colorFor?.invoke(v) ?: color
                val top = y(v)
                val alpha = if (selected == -1 || selected == i) 1f else 0.35f
                drawRoundRect(
                    Brush.verticalGradient(listOf(c.copy(alpha = alpha), c.copy(alpha = alpha * 0.45f)), top, h),
                    Offset(x(i) - bw / 2f, top), Size(bw, (h - top).coerceAtLeast(2f)), CornerRadius(minOf(bw / 2f, 6.dp.toPx())),
                )
            }
        } else {
            val path = Path()
            var started = false
            val fill = Path()
            var firstX = 0f
            var lastX = 0f
            values.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                if (!started) { path.moveTo(x(i), y(v)); fill.moveTo(x(i), h); fill.lineTo(x(i), y(v)); firstX = x(i); started = true }
                else { path.lineTo(x(i), y(v)); fill.lineTo(x(i), y(v)) }
                lastX = x(i)
            }
            if (started) {
                fill.lineTo(lastX, h); fill.lineTo(firstX, h); fill.close()
                drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent), 0f, h))
                drawPath(path, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (n <= 31) values.forEachIndexed { i, v -> if (v != null) drawCircle(color, 2.5.dp.toPx(), Offset(x(i), y(v))) }
            }
        }
        val sv = values.getOrNull(selected)
        if (selected >= 0) {
            drawLine(TextMain.copy(alpha = 0.4f), Offset(x(selected), 0f), Offset(x(selected), h), 1.5f)
            if (sv != null && !bars) {
                drawCircle(Bg, 7.dp.toPx(), Offset(x(selected), y(sv)))
                drawCircle(color, 5.dp.toPx(), Offset(x(selected), y(sv)))
            }
        }
        // labels: first, middle, last
        val idx = listOf(0, n / 2, n - 1).distinct()
        val f = DateTimeFormatter.ofPattern(if (n <= 7) "EEE" else "d MMM")
        for (i in idx) {
            val r = tm.measure(dates[i].format(f), style)
            val lx = (x(i) - r.size.width / 2f).coerceIn(0f, size.width - r.size.width)
            drawText(r, topLeft = Offset(lx, h + 4.dp.toPx()))
        }
        if (n <= 7) {
            for (i in 0 until n) if (i !in idx) {
                val r = tm.measure(dates[i].format(f), style)
                drawText(r, topLeft = Offset(x(i) - r.size.width / 2f, h + 4.dp.toPx()))
            }
        }
    }
}
