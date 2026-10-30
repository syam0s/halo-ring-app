package com.syam.haloring.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syam.haloring.App
import com.syam.haloring.data.Act
import com.syam.haloring.data.Db
import com.syam.haloring.data.Point
import com.syam.haloring.data.SleepNight
import com.syam.haloring.data.Stage
import com.syam.haloring.data.dayEnd
import com.syam.haloring.data.dayStart
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Metric(val title: String) {
    HEART("Heart rate"), SPO2("Blood oxygen"), HRV("HRV"), STRESS("Stress"), STEPS("Steps"), SLEEP("Sleep")
}

private val hmFmt = DateTimeFormatter.ofPattern("HH:mm")
internal fun hhmm(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(hmFmt)

fun stageName(stage: Int) = when (stage) {
    SleepNight.STAGE_DEEP -> "Deep"
    SleepNight.STAGE_LIGHT -> "Light"
    SleepNight.STAGE_REM -> "REM"
    SleepNight.STAGE_AWAKE -> "Awake"
    else -> "Sleep"
}

private fun stressBand(v: Int) = when {
    v < 30 -> "Relaxed" to Teal
    v < 60 -> "Normal" to Sky
    v < 80 -> "Medium" to Amber
    else -> "High" to Coral
}

private fun spo2Band(v: Int) = when {
    v >= 95 -> "Normal" to Teal
    v >= 91 -> "Slightly low" to Amber
    else -> "Low" to Coral
}

// ============================================================ shared data helpers

internal fun loadSeries(app: App, m: Metric, from: Long, to: Long): List<Point> = when (m) {
    Metric.HEART -> app.db.series(Db.HR, from, to)
    Metric.HRV -> app.db.series(Db.HRV, from, to)
    Metric.STRESS -> app.db.series(Db.STRESS, from, to)
    Metric.SPO2 -> app.db.spo2(from, to).map { Point(it.ts, it.value) }
    else -> emptyList()
}

private fun dayAverage(app: App, m: Metric, d: LocalDate): Double? =
    loadSeries(app, m, dayStart(d), dayEnd(d)).takeIf { it.isNotEmpty() }?.map { it.value }?.average()

/** Average of the daily averages for the 7 days before [date]. */
internal fun previousAverage(app: App, m: Metric, date: LocalDate): Double? {
    val avgs = (1..7).mapNotNull { dayAverage(app, m, date.minusDays(it.toLong())) }
    return if (avgs.isEmpty()) null else avgs.average()
}

private fun previousSteps(app: App, date: LocalDate): Double? {
    val totals = (1..7).map { d ->
        val day = date.minusDays(d.toLong())
        app.db.activity(dayStart(day), dayEnd(day)).sumOf { it.steps }
    }.filter { it > 0 }
    return if (totals.isEmpty()) null else totals.average()
}

private fun previousSleep(app: App, date: LocalDate): List<SleepNight> =
    (1..7).mapNotNull { app.db.sleepFor(date.minusDays(it.toLong())) }

/** Minutes after 18:00, so bedtimes either side of midnight average properly. */
private fun eveningMinutes(ms: Long): Int {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime()
    return ((t.hour * 60 + t.minute) - 18 * 60 + 1440) % 1440
}

private fun fromEveningMinutes(m: Int): String {
    val total = (m + 18 * 60) % 1440
    return "%02d:%02d".format(total / 60, total % 60)
}

private fun versus(avg: Double, prev: Double?, unit: String): String {
    val a = avg.roundToInt()
    if (prev == null) return "Average $a$unit. After a few more days of data you'll see how this compares with your usual."
    val p = prev.roundToInt()
    val diff = a - p
    return when {
        diff == 0 -> "Average $a$unit, the same as your 7-day average."
        diff > 0 -> "Average $a$unit, $diff$unit higher than your 7-day average ($p$unit)."
        else -> "Average $a$unit, ${-diff}$unit lower than your 7-day average ($p$unit)."
    }
}

// ============================================================ insights ("updates")

internal fun seriesInsights(app: App, m: Metric, date: LocalDate, pts: List<Point>): List<String> {
    if (pts.isEmpty()) {
        return listOf(
            "No readings for this day yet. Sync with the ring nearby, and check that automatic " +
                "${m.title.lowercase()} is switched on in the Ring tab."
        )
    }
    val out = mutableListOf<String>()
    val avg = pts.map { it.value }.average()
    val prev = previousAverage(app, m, date)
    val lo = pts.minBy { it.value }
    val hi = pts.maxBy { it.value }
    val unit = when (m) { Metric.HEART -> " bpm"; Metric.SPO2 -> "%"; Metric.HRV -> " ms"; else -> "" }

    when (m) {
        Metric.HEART -> {
            out += versus(avg, prev, unit)
            out += "Lowest ${lo.value} bpm at ${hhmm(lo.ts)} · highest ${hi.value} bpm at ${hhmm(hi.ts)}."
            app.db.sleepFor(date)?.let { night ->
                val asleep = app.db.series(Db.HR, night.start, night.end)
                if (asleep.isNotEmpty()) {
                    val sLo = asleep.minBy { it.value }
                    out += "While asleep (${hhmm(night.start)}–${hhmm(night.end)}) it averaged " +
                        "${asleep.map { it.value }.average().roundToInt()} bpm, lowest ${sLo.value} at ${hhmm(sLo.ts)}. " +
                        "A lower sleeping heart rate usually means better rest."
                }
            }
            if (hi.value >= 120) {
                out += "The ${hi.value} bpm peak at ${hhmm(hi.ts)} is normal if you were walking fast, climbing stairs " +
                    "or exercising. If you were sitting still then, keep an eye on whether it repeats."
            }
        }
        Metric.SPO2 -> {
            out += versus(avg, prev, unit)
            val low = pts.filter { it.value < 92 }
            out += if (low.isEmpty()) {
                "All readings were 92% or higher. That's a good night."
            } else {
                "${low.size} reading${if (low.size > 1) "s were" else " was"} below 92% (lowest ${lo.value}% at ${hhmm(lo.ts)}). " +
                    "Ring sensors dip occasionally if your hand is pressed or cold. If it happens most nights, mention it to a doctor."
            }
        }
        Metric.HRV -> {
            out += versus(avg, prev, unit)
            if (prev != null) {
                val ratio = avg / prev
                out += when {
                    ratio >= 1.1 -> "That's above your usual: generally a sign you're well recovered."
                    ratio <= 0.9 -> "That's below your usual. This often follows short sleep, late meals, alcohol, illness or a stressful day. Worth taking it easier and sleeping early."
                    else -> "That's close to your usual, so recovery looks steady."
                }
            }
            out += "Lowest ${lo.value} ms at ${hhmm(lo.ts)} · highest ${hi.value} ms at ${hhmm(hi.ts)}."
        }
        Metric.STRESS -> {
            out += versus(avg, prev, unit) + " (${stressBand(avg.roundToInt()).first.lowercase()})"
            val n = pts.size.toFloat()
            val bands = listOf("Relaxed", "Normal", "Medium", "High")
            val counts = bands.map { b -> pts.count { stressBand(it.value).first == b } }
            out += bands.zip(counts).filter { it.second > 0 }
                .joinToString(" · ") { "${it.first} ${(100 * it.second / n).roundToInt()}%" } + " of the day."
            out += "Most stressed around ${hhmm(hi.ts)} (score ${hi.value}); calmest around ${hhmm(lo.ts)} (score ${lo.value})."
        }
        else -> {}
    }
    out += "Last reading at ${hhmm(pts.maxOf { it.ts })}. ${pts.size} readings in total."
    return out
}

internal fun stepsInsights(app: App, date: LocalDate, acts: List<Act>, hourly: List<Float>): List<String> {
    val total = acts.sumOf { it.steps }
    if (total == 0) return listOf("No steps recorded for this day yet. Sync with the ring nearby to update.")
    val out = mutableListOf<String>()
    val goal = app.prefs.stepGoal
    val prev = previousSteps(app, date)
    out += if (prev == null) "${fmtNumber(total)} steps." else {
        val diff = total - prev.roundToInt()
        "${fmtNumber(total)} steps, " + (if (diff >= 0) "${fmtNumber(diff)} more" else "${fmtNumber(-diff)} fewer") +
            " than your 7-day average (${fmtNumber(prev.roundToInt())})."
    }
    out += if (total >= goal) "Goal of ${fmtNumber(goal)} reached." else "${fmtNumber(goal - total)} steps short of your ${fmtNumber(goal)} goal."
    val best = hourly.indices.maxBy { hourly[it] }
    out += "Most active hour: %02d:00–%02d:00 with ${fmtNumber(hourly[best].toInt())} steps.".format(best, (best + 1) % 24)
    // Longest stretch of sitting still during the day (08:00–21:00, hours already passed).
    val lastHour = if (date == LocalDate.now()) java.time.LocalTime.now().hour - 1 else 20
    var run = 0; var bestRun = 0; var bestEnd = -1
    for (h in 8..minOf(20, lastHour)) {
        if (hourly[h] < 100f) { run++; if (run > bestRun) { bestRun = run; bestEnd = h } } else run = 0
    }
    if (bestRun >= 2) {
        out += "Longest still stretch: ${bestRun} hours (%02d:00–%02d:00) with under 100 steps an hour. A short walk every hour helps.".format(bestEnd - bestRun + 1, bestEnd + 1)
    }
    return out
}

internal fun sleepInsights(app: App, date: LocalDate, night: SleepNight): List<String> {
    val out = mutableListOf<String>()
    val prevNights = previousSleep(app, date)
    val asleep = night.asleepMinutes
    if (prevNights.isNotEmpty()) {
        val avg = prevNights.map { it.asleepMinutes }.average().roundToInt()
        val diff = asleep - avg
        out += "Slept ${fmtMinutes(asleep)}, " + when {
            abs(diff) < 10 -> "about the same as your 7-night average."
            diff > 0 -> "${fmtMinutes(diff)} more than your 7-night average (${fmtMinutes(avg)})."
            else -> "${fmtMinutes(-diff)} less than your 7-night average (${fmtMinutes(avg)})."
        }
        val bedAvg = prevNights.map { eveningMinutes(it.start) }.average().roundToInt()
        val bedDiff = eveningMinutes(night.start) - bedAvg
        out += "Went to bed at ${hhmm(night.start)}" + when {
            abs(bedDiff) < 20 -> ", close to your usual bedtime (${fromEveningMinutes(bedAvg)})."
            bedDiff > 0 -> ", ${fmtMinutes(bedDiff)} later than usual (${fromEveningMinutes(bedAvg)}). Regular bedtimes improve sleep quality."
            else -> ", ${fmtMinutes(-bedDiff)} earlier than usual (${fromEveningMinutes(bedAvg)})."
        }
    } else {
        out += "Slept ${fmtMinutes(asleep)}, in bed ${hhmm(night.start)}–${hhmm(night.end)}. Most adults need 7–9 hours."
    }
    val total = night.stages.sumOf { it.minutes }.coerceAtLeast(1)
    val deepPct = 100 * night.minutesOf(SleepNight.STAGE_DEEP) / total
    val remPct = 100 * night.minutesOf(SleepNight.STAGE_REM) / total
    out += "Deep sleep $deepPct% " + when {
        deepPct < 13 -> "is on the low side (typical 13–23%). Alcohol, late meals and screens before bed reduce it."
        deepPct > 23 -> "is above the typical 13–23%, often a sign of catching up on rest."
        else -> "is within the typical 13–23%."
    }
    if (night.minutesOf(SleepNight.STAGE_REM) > 0) {
        out += "REM $remPct% " + when {
            remPct < 15 -> "is below the typical 20–25%. REM is mostly in the last hours of sleep, so waking early cuts it."
            else -> "is in a healthy range (typical 20–25%)."
        }
    }
    val wakes = night.stages.count { it.stage == SleepNight.STAGE_AWAKE }
    if (wakes > 0) out += "Woke up $wakes time${if (wakes > 1) "s" else ""}, ${fmtMinutes(night.minutesOf(SleepNight.STAGE_AWAKE))} awake in total."
    val hr = app.db.series(Db.HR, night.start, night.end)
    if (hr.isNotEmpty()) {
        val lo = hr.minBy { it.value }
        out += "Sleeping heart rate averaged ${hr.map { it.value }.average().roundToInt()} bpm, lowest ${lo.value} at ${hhmm(lo.ts)}."
    }
    val ox = app.db.spo2(night.start, night.end)
    if (ox.isNotEmpty()) {
        val lo = ox.minBy { it.min }
        out += "Blood oxygen during sleep: lowest ${lo.min}% around ${hhmm(lo.ts)}."
    }
    return out
}

/** Short headline updates for the Today screen; each opens its detail page. */
internal fun todayUpdates(app: App): List<Pair<Metric, String>> {
    val today = LocalDate.now()
    val out = mutableListOf<Pair<Metric, String>>()
    app.db.sleepFor(today)?.let { night ->
        val prev = previousSleep(app, today)
        val text = if (prev.isEmpty()) "You slept ${fmtMinutes(night.asleepMinutes)} last night."
        else {
            val diff = night.asleepMinutes - prev.map { it.asleepMinutes }.average().roundToInt()
            "You slept ${fmtMinutes(night.asleepMinutes)}, " +
                if (abs(diff) < 10) "about your usual." else if (diff > 0) "${fmtMinutes(diff)} more than usual." else "${fmtMinutes(-diff)} less than usual."
        }
        out += Metric.SLEEP to text
    }
    val from = dayStart(today)
    val to = dayEnd(today)
    val hrv = loadSeries(app, Metric.HRV, from, to)
    val hrvPrev = previousAverage(app, Metric.HRV, today)
    if (hrv.isNotEmpty() && hrvPrev != null) {
        val pct = ((hrv.map { it.value }.average() / hrvPrev - 1) * 100).roundToInt()
        out += Metric.HRV to when {
            pct >= 10 -> "HRV is $pct% above your usual: you look well recovered."
            pct <= -10 -> "HRV is ${-pct}% below your usual: your body may need an easier day."
            else -> "HRV is close to your usual."
        }
    }
    app.db.sleepFor(today)?.let { night ->
        val hr = app.db.series(Db.HR, night.start, night.end)
        if (hr.isNotEmpty()) out += Metric.HEART to "Sleeping heart rate averaged ${hr.map { it.value }.average().roundToInt()} bpm."
    }
    val ox = app.db.spo2(dayStart(today.minusDays(1)) + 18 * 3_600_000L, to).filter { it.value < 92 }
    if (ox.isNotEmpty()) out += Metric.SPO2 to "Blood oxygen dipped below 92% ${ox.size} time${if (ox.size > 1) "s" else ""} since last evening."
    val stress = loadSeries(app, Metric.STRESS, from, to)
    if (stress.isNotEmpty()) {
        val hi = stress.maxBy { it.value }
        if (hi.value >= 80) out += Metric.STRESS to "Stress peaked at ${hi.value} around ${hhmm(hi.ts)}."
    }
    val steps = app.db.activity(from, to).sumOf { it.steps }
    val prevSteps = previousSteps(app, today)
    if (steps > 0 && prevSteps != null) {
        out += Metric.STEPS to "${fmtNumber(steps)} steps so far; you average ${fmtNumber(prevSteps.roundToInt())} a day."
    }
    return out.take(5)
}

// ============================================================ UI pieces

@Composable
private fun DetailHeader(title: String, color: Color, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = TextMain) }
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(title, color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun UpdatesPanel(lines: List<String>, color: Color, title: String = "Updates") {
    Panel(Modifier.fillMaxWidth()) {
        PanelTitle(title, color)
        Spacer(Modifier.height(10.dp))
        lines.forEach { line ->
            Row(Modifier.padding(vertical = 5.dp)) {
                Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(10.dp))
                Text(line, color = TextMain, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
    }
}

@Composable
private fun ExplainPanel(about: String, ranges: List<Pair<String, String>>, color: Color) {
    Panel(Modifier.fillMaxWidth()) {
        PanelTitle("What this shows", color)
        Spacer(Modifier.height(8.dp))
        Text(about, color = TextMain, fontSize = 14.sp, lineHeight = 20.sp)
        if (ranges.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            ranges.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(k, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(0.42f))
                    Text(v, color = TextMain, fontSize = 13.sp, modifier = Modifier.weight(0.58f))
                }
            }
        }
    }
}

@Composable
private fun Readout(text: String, color: Color) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.13f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ReadingRow(time: String, value: String, tag: String?, tagColor: Color, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(alpha = 0.16f) else CardColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(time, color = Muted, fontSize = 14.sp, modifier = Modifier.width(110.dp))
        Text(value, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (tag != null) Text(tag, color = tagColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun LazyListScope.listHeader(title: String, note: String) {
    item {
        Column(Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp)) {
            Text(title, color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(note, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

private val detailPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp)

// ============================================================ detail screen

@Composable
fun DetailScreen(app: App, metric: Metric, startDate: LocalDate, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var date by rememberSaveable(metric) { mutableStateOf(startDate) }
    when (metric) {
        Metric.SLEEP -> SleepDetail(app, date, { date = it }, onBack)
        Metric.STEPS -> StepsDetail(app, date, { date = it }, onBack)
        else -> SeriesDetail(app, metric, date, { date = it }, onBack)
    }
}

private data class Spec(
    val color: Color,
    val unit: String,
    val about: String,
    val ranges: List<Pair<String, String>>,
    val timing: String,
    val fixedMin: Float? = null,
    val fixedMax: Float? = null,
    val gap: Int = 45,
)

private fun specFor(m: Metric) = when (m) {
    Metric.HEART -> Spec(
        Coral, "bpm",
        "Each point is one heart-rate reading, in beats per minute, taken automatically by the ring (how often is set in the Ring tab) " +
            "or when you press Measure now. The line sits low and flat while you rest or sleep, and rises when you move, climb stairs, " +
            "eat, have coffee or feel stressed. Gaps mean the ring had no reading, usually because it was off your finger or charging.",
        listOf(
            "Resting, awake" to "60–100 bpm is typical for adults",
            "Asleep" to "Usually 5–15 bpm below your resting rate",
            "Brisk walking" to "Often 100–130 bpm",
        ),
        "Automatic readings are saved in 5-minute slots, so their times are rounded to the nearest 5 minutes. Measure now readings show the exact minute.",
    )
    Metric.SPO2 -> Spec(
        Sky, "%",
        "Blood oxygen saturation: how much of your blood's oxygen-carrying capacity is in use. The ring measures mostly while you're still or " +
            "asleep and keeps one value per hour (the middle of that hour's lowest and highest reading). Ring readings are less precise than a " +
            "fingertip clip meter, so look at the pattern over several nights rather than one number.",
        listOf(
            "95–100%" to "Normal",
            "91–94%" to "Slightly low: common now and then at night on ring sensors",
            "90% or below" to "Low: if it keeps happening, mention it to a doctor",
        ),
        "Each value covers one hour, starting at the time shown. Measure now readings show the exact minute.",
        fixedMin = 85f, fixedMax = 100f, gap = 90,
    )
    Metric.HRV -> Spec(
        Lime, "ms",
        "Heart rate variability: the tiny changes in time between heartbeats, in milliseconds. Higher usually means your body is relaxed and " +
            "recovered; lower often follows poor sleep, alcohol, illness, hard exercise or stress. HRV is very personal, so compare it with " +
            "your own average rather than other people's.",
        listOf(
            "Compare with" to "Your own 7-day average (in Updates below)",
            "Typical adults" to "Roughly 20–100 ms; it falls gradually with age",
        ),
        "The ring saves one HRV value every 30 minutes, so each time is the start of that half-hour.",
        gap = 90,
    )
    Metric.STRESS -> Spec(
        Amber, "",
        "A 0–100 score the ring works out from your heart rate and HRV every 30 minutes. It reacts to mental stress but also to caffeine, heat, " +
            "illness and physical effort, so read it alongside what you were doing at the time.",
        listOf("0–29" to "Relaxed", "30–59" to "Normal", "60–79" to "Medium", "80–100" to "High"),
        "The ring saves one stress score every 30 minutes, so each time is the start of that half-hour.",
        fixedMin = 0f, fixedMax = 100f, gap = 90,
    )
    else -> Spec(Teal, "", "", emptyList(), "")
}

@Composable
private fun SeriesDetail(app: App, m: Metric, date: LocalDate, onDate: (LocalDate) -> Unit, onBack: () -> Unit) {
    val ver by app.db.version.collectAsState()
    val spec = specFor(m)
    val from = dayStart(date)
    val pts = remember(ver, date, m) { loadSeries(app, m, from, dayEnd(date)) }
    val spo2Rows = remember(ver, date, m) { if (m == Metric.SPO2) app.db.spo2(from, dayEnd(date)).associateBy { it.ts } else emptyMap() }
    val updates = remember(ver, date, m) { seriesInsights(app, m, date, pts) }
    var sel by remember(date, m) { mutableStateOf<Long?>(null) }
    val selPoint = pts.firstOrNull { it.ts == sel }
    val unitText = if (spec.unit.isEmpty()) "" else if (spec.unit == "%") "%" else " ${spec.unit}"

    LazyColumn(contentPadding = detailPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { DetailHeader(m.title, spec.color, onBack) }
        item { DayPicker(date, onDate) }
        item {
            Panel(Modifier.fillMaxWidth()) {
                StatRow(
                    "Lowest" to (pts.minOfOrNull { it.value }?.let { "$it$unitText" } ?: "--"),
                    "Average" to (pts.takeIf { it.isNotEmpty() }?.map { it.value }?.average()?.roundToInt()?.let { "$it$unitText" } ?: "--"),
                    "Highest" to (pts.maxOfOrNull { it.value }?.let { "$it$unitText" } ?: "--"),
                )
                Spacer(Modifier.height(14.dp))
                DayLineChart(
                    pts, from, spec.color,
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    gapMinutes = spec.gap, fixedMin = spec.fixedMin, fixedMax = spec.fixedMax,
                    selectedTs = sel, onSelect = { sel = it.ts },
                )
                Spacer(Modifier.height(10.dp))
                Readout(
                    if (selPoint == null) "Tap or slide along the chart to see a single reading"
                    else "${selPoint.value}$unitText at ${hhmm(selPoint.ts)}" +
                        (if (m == Metric.STRESS) " · ${stressBand(selPoint.value).first}" else "") +
                        (if (m == Metric.SPO2) spo2Rows[selPoint.ts]?.let { " · range ${it.min}–${it.max}%" } ?: "" else ""),
                    spec.color,
                )
            }
        }
        item { UpdatesPanel(updates, spec.color) }
        item { ExplainPanel(spec.about, spec.ranges, spec.color) }
        listHeader("All readings (${pts.size})", spec.timing + " Tap a reading to find it on the chart.")
        val rows = pts.sortedByDescending { it.ts }
        items(rows, key = { it.ts }) { p ->
            val band = when (m) {
                Metric.STRESS -> stressBand(p.value)
                Metric.SPO2 -> spo2Band(p.value)
                else -> null
            }
            val value = if (m == Metric.SPO2) {
                spo2Rows[p.ts]?.let { r -> if (r.min != r.max) "${p.value}%  (${r.min}–${r.max})" else "${p.value}%" } ?: "${p.value}%"
            } else "${p.value}$unitText"
            ReadingRow(hhmm(p.ts), value, band?.first, band?.second ?: Muted, sel == p.ts, spec.color) { sel = p.ts }
        }
    }
}

@Composable
private fun StepsDetail(app: App, date: LocalDate, onDate: (LocalDate) -> Unit, onBack: () -> Unit) {
    val ver by app.db.version.collectAsState()
    val acts = remember(ver, date) { app.db.activity(dayStart(date), dayEnd(date)) }
    val zone = ZoneId.systemDefault()
    val hourly = remember(acts) {
        val arr = FloatArray(24)
        acts.forEach { arr[Instant.ofEpochMilli(it.ts).atZone(zone).hour] += it.steps.toFloat() }
        arr.toList()
    }
    val updates = remember(ver, date) { stepsInsights(app, date, acts, hourly) }
    var selHour by remember(date) { mutableStateOf(-1) }

    LazyColumn(contentPadding = detailPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { DetailHeader("Steps", Teal, onBack) }
        item { DayPicker(date, onDate) }
        item {
            Panel(Modifier.fillMaxWidth()) {
                StatRow(
                    "Steps" to fmtNumber(acts.sumOf { it.steps }),
                    "Distance" to "%.2f km".format(acts.sumOf { it.distance } / 1000.0),
                    "Calories" to "${acts.sumOf { it.calories }} kcal",
                )
                Spacer(Modifier.height(14.dp))
                BarChart(
                    hourly, (0..23).map { if (it % 6 == 0) "%02d".format(it) else "" }, Teal,
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    highlight = selHour, onSelect = { selHour = it },
                )
                Spacer(Modifier.height(10.dp))
                val hourActs = acts.filter { Instant.ofEpochMilli(it.ts).atZone(zone).hour == selHour }
                Readout(
                    if (selHour < 0) "Tap a bar to see that hour"
                    else "%02d:00–%02d:00 · ${fmtNumber(hourActs.sumOf { it.steps })} steps · %.2f km · ${hourActs.sumOf { it.calories }} kcal"
                        .format(selHour, (selHour + 1) % 24, hourActs.sumOf { it.distance } / 1000.0),
                    Teal,
                )
            }
        }
        item { UpdatesPanel(updates, Teal) }
        item {
            ExplainPanel(
                "Steps the ring counted, grouped by hour. The ring counts from hand movement, so it can under-count when your hands are " +
                    "still (pushing a trolley, carrying something, holding a phone) and over-count during busy hand work. Distance and " +
                    "calories are estimates worked out from your steps.",
                listOf("Goal" to "${fmtNumber(app.prefs.stepGoal)} steps (change it in the Ring tab)", "Active hour" to "250+ steps in that hour"),
                Teal,
            )
        }
        val rows = acts.filter { it.steps > 0 }.sortedByDescending { it.ts }
        listHeader("Every 15 minutes (${rows.size})", "The ring saves steps in 15-minute blocks. Each row shows when the block started and ended.")
        items(rows, key = { it.ts }) { a ->
            val h = Instant.ofEpochMilli(a.ts).atZone(zone).hour
            ReadingRow(
                "${hhmm(a.ts)}–${hhmm(a.ts + 15 * 60_000L)}",
                "${fmtNumber(a.steps)} steps",
                "%.2f km · %d kcal".format(a.distance / 1000.0, a.calories), Muted,
                selHour == h, Teal,
            ) { selHour = h }
        }
    }
}

@Composable
private fun SleepDetail(app: App, date: LocalDate, onDate: (LocalDate) -> Unit, onBack: () -> Unit) {
    val ver by app.db.version.collectAsState()
    val night = remember(ver, date) { app.db.sleepFor(date) }
    val updates = remember(ver, date, night) { night?.let { sleepInsights(app, date, it) } ?: emptyList() }
    var sel by remember(date) { mutableStateOf<Stage?>(null) }

    LazyColumn(contentPadding = detailPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { DetailHeader("Sleep", Violet, onBack) }
        item {
            DayPicker(date, onDate) { d ->
                if (d == LocalDate.now()) "Last night" else "Woke up " + d.format(DateTimeFormatter.ofPattern("EEE d MMM"))
            }
        }
        if (night == null) {
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Text("No sleep recorded for this night.", color = TextMain, fontWeight = FontWeight.SemiBold)
                    Text("Wear the ring to bed and sync in the morning.", color = Muted)
                }
            }
        } else {
            item {
                Panel(Modifier.fillMaxWidth()) {
                    StatRow(
                        "Asleep" to fmtMinutes(night.asleepMinutes),
                        "Bedtime" to hhmm(night.start),
                        "Woke up" to hhmm(night.end),
                    )
                    Spacer(Modifier.height(14.dp))
                    Hypnogram(night, selected = sel, onSelect = { sel = it })
                    Spacer(Modifier.height(10.dp))
                    val s = sel
                    Readout(
                        if (s == null) "Tap or slide along the chart to see each stage's times"
                        else "${stageName(s.stage)} sleep · ${hhmm(s.ts)}–${hhmm(s.ts + s.minutes * 60_000L)} · ${fmtMinutes(s.minutes)}",
                        if (s == null) Violet else stageColor(s.stage),
                    )
                }
            }
            item { UpdatesPanel(updates, Violet) }
            item {
                ExplainPanel(
                    "This chart, called a hypnogram, shows which sleep stage you were in through the night, left to right from bedtime to " +
                        "wake-up. You normally cycle through the stages every 90 minutes or so: more deep sleep early in the night, more " +
                        "REM towards morning. Short wake-ups are normal and you usually don't remember them.",
                    listOf(
                        "Deep" to "Physical repair and immune system. Typically 13–23% of the night",
                        "Light" to "The largest share of the night, around 50–60%",
                        "REM" to "Dreaming, memory and mood. Typically 20–25%",
                        "Awake" to "Brief wake-ups; under 30 min in total is common",
                    ),
                    Violet,
                )
            }
            listHeader(
                "Stage by stage (${night.stages.size})",
                "Exact start and end time of every stage the ring recorded, in order through the night. Tap one to find it on the chart.",
            )
            items(night.stages, key = { it.ts }) { s ->
                ReadingRow(
                    "${hhmm(s.ts)}–${hhmm(s.ts + s.minutes * 60_000L)}",
                    stageName(s.stage),
                    fmtMinutes(s.minutes), stageColor(s.stage),
                    sel?.ts == s.ts, stageColor(s.stage),
                ) { sel = s }
            }
        }
    }
}
