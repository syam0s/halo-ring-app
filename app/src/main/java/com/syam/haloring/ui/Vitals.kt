package com.syam.haloring.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syam.haloring.App
import com.syam.haloring.data.dayEnd
import com.syam.haloring.data.dayStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.roundToInt

private data class VitalRow(
    val metric: Metric,
    val title: String,
    val value: Double?,
    val text: String,
    val unit: String,
    val low: Double?,
    val high: Double?,
    val min: Float,
    val max: Float,
    val color: Color,
    val higherIsBetter: Boolean?,
    val usualText: (Double, Double) -> String,
)

/** 25th–75th percentile of past values = "your usual range". */
private fun usualRange(vals: List<Double>): Pair<Double, Double>? {
    if (vals.size < 3) return null
    val s = vals.sorted()
    fun q(p: Double) = s[((s.size - 1) * p).roundToInt()]
    return q(0.25) to q(0.75)
}

private fun loadVitals(app: App, date: LocalDate): List<VitalRow> {
    val past = (1..14).map { date.minusDays(it.toLong()) }
    fun avgOf(m: Metric, d: LocalDate): Double? =
        loadSeries(app, m, dayStart(d), dayEnd(d)).takeIf { it.isNotEmpty() }?.map { it.value }?.average()

    val rows = mutableListOf<VitalRow>()

    val rhr = Scores.restingHr(app, date)?.toDouble()
    val rhrR = usualRange(past.mapNotNull { Scores.restingHr(app, it)?.toDouble() })
    rows += VitalRow(Metric.HEART, "Resting heart rate", rhr, rhr?.roundToInt()?.toString() ?: "--", "bpm",
        rhrR?.first, rhrR?.second, 40f, 90f, Coral, false) { a, b -> "Usual ${a.roundToInt()}–${b.roundToInt()} bpm" }

    val hrv = avgOf(Metric.HRV, date)
    val hrvR = usualRange(past.mapNotNull { avgOf(Metric.HRV, it) })
    rows += VitalRow(Metric.HRV, "Heart rate variability", hrv, hrv?.roundToInt()?.toString() ?: "--", "ms",
        hrvR?.first, hrvR?.second, 0f, 120f, Lime, true) { a, b -> "Usual ${a.roundToInt()}–${b.roundToInt()} ms" }

    val hr = avgOf(Metric.HEART, date)
    val hrR = usualRange(past.mapNotNull { avgOf(Metric.HEART, it) })
    rows += VitalRow(Metric.HEART, "Average heart rate", hr, hr?.roundToInt()?.toString() ?: "--", "bpm",
        hrR?.first, hrR?.second, 40f, 120f, Rose, null) { a, b -> "Usual ${a.roundToInt()}–${b.roundToInt()} bpm" }

    val ox = avgOf(Metric.SPO2, date)
    val oxR = usualRange(past.mapNotNull { avgOf(Metric.SPO2, it) })
    rows += VitalRow(Metric.SPO2, "Blood oxygen", ox, ox?.roundToInt()?.toString() ?: "--", "%",
        oxR?.first, oxR?.second, 85f, 100f, Sky, true) { a, b -> "Usual ${a.roundToInt()}–${b.roundToInt()}%" }

    val stress = avgOf(Metric.STRESS, date)
    val stressR = usualRange(past.mapNotNull { avgOf(Metric.STRESS, it) })
    rows += VitalRow(Metric.STRESS, "Stress", stress, stress?.roundToInt()?.toString() ?: "--", "",
        stressR?.first, stressR?.second, 0f, 100f, Amber, false) { a, b -> "Usual ${a.roundToInt()}–${b.roundToInt()}" }

    fun sleepH(d: LocalDate) = app.db.sleepFor(d)?.let { Scores.asleepMinutes(it) / 60.0 }
    val sl = sleepH(date)
    val slR = usualRange(past.mapNotNull { sleepH(it) })
    rows += VitalRow(Metric.SLEEP, "Sleep duration", sl, sl?.let { fmtMinutes((it * 60).roundToInt()) } ?: "--", "",
        slR?.first, slR?.second, 3f, 10f, Violet, true) { a, b -> "Usual ${fmtMinutes((a * 60).roundToInt())}–${fmtMinutes((b * 60).roundToInt())}" }

    fun steps(d: LocalDate) = app.db.activity(dayStart(d), dayEnd(d)).sumOf { it.steps }.takeIf { it > 0 }?.toDouble()
    val stp = steps(date)
    val stpR = usualRange(past.mapNotNull { steps(it) })
    rows += VitalRow(Metric.STEPS, "Steps", stp, stp?.roundToInt()?.let { fmtNumber(it) } ?: "--", "",
        stpR?.first, stpR?.second, 0f, (app.prefs.stepGoal * 1.6f), Orange, true) { a, b -> "Usual ${fmtNumber(a.roundToInt())}–${fmtNumber(b.roundToInt())}" }
    return rows
}

@Composable
fun VitalsScreen(app: App, openDetail: (Metric, LocalDate) -> Unit) {
    val ver by app.db.version.collectAsState()
    var date by rememberSaveable { mutableStateOf(LocalDate.now()) }
    val rows by produceState<List<VitalRow>?>(null, ver, date) { value = null; value = withContext(Dispatchers.IO) { loadVitals(app, date) } }

    Box {
        GlowBackdrop(Coral)
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column {
                    Text("Vitals", style = androidx.compose.material3.MaterialTheme.typography.headlineLarge)
                    Text("Each measure against your usual range from the last two weeks", color = Muted, fontSize = 13.sp)
                }
            }
            item { DayPicker(date, { date = it }) }
            if (date == LocalDate.now()) item { SpotCheckPanel(app) }
            val list = rows
            if (list == null) {
                item { Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Coral) } }
            } else {
                items(list, key = { it.title }) { r -> VitalCard(r) { openDetail(r.metric, date) } }
                item {
                    Text(
                        "The shaded band is your usual range (the middle half of your last 14 days). The dot is this day.",
                        color = Faint, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun VitalCard(r: VitalRow, onClick: () -> Unit) {
    Panel(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.title.uppercase(), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Spacer(Modifier.height(4.dp))
                BigValue(r.text, r.unit, size = 28)
            }
            val (label, c) = when {
                r.value == null -> "No data" to Faint
                r.low == null || r.high == null -> "Building baseline" to Muted
                r.value < r.low -> "Below usual" to when (r.higherIsBetter) { true -> Amber; false -> Teal; null -> Sky }
                r.value > r.high -> "Above usual" to when (r.higherIsBetter) { true -> Teal; false -> Amber; null -> Sky }
                else -> "In your range" to Teal
            }
            Chip(label, c)
        }
        Spacer(Modifier.height(12.dp))
        BaselineBar(r.value?.toFloat(), r.low?.toFloat(), r.high?.toFloat(), r.min, r.max, r.color)
        Spacer(Modifier.height(6.dp))
        Row {
            Text(if (r.low != null && r.high != null) r.usualText(r.low, r.high) else "Needs a few more days of data", color = Faint, fontSize = 12.sp,
                modifier = Modifier.weight(1f))
            Text("Details ›", color = r.color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}
