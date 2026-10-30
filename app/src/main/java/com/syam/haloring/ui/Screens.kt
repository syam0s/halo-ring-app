package com.syam.haloring.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syam.haloring.App
import com.syam.haloring.SyncWorker
import com.syam.haloring.ble.Cmd
import com.syam.haloring.ble.RingManager
import com.syam.haloring.data.BatteryEstimator
import com.syam.haloring.data.Db
import com.syam.haloring.data.Point
import com.syam.haloring.data.SleepNight
import com.syam.haloring.data.dayEnd
import com.syam.haloring.data.dayStart
import com.syam.haloring.data.toLocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val listPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)

private fun clock(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

private fun ago(ms: Long): String {
    if (ms == 0L) return "never"
    val m = ((System.currentTimeMillis() - ms) / 60000).toInt()
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 60 * 24 -> "${m / 60} h ago"
        else -> "${m / 1440} days ago"
    }
}

private fun stressLabel(v: Int) = when {
    v < 30 -> "Relaxed"
    v < 60 -> "Normal"
    v < 80 -> "Medium"
    else -> "High"
}

internal fun metricColor(m: Metric) = when (m) {
    Metric.HEART -> Coral
    Metric.SPO2 -> Sky
    Metric.HRV -> Lime
    Metric.STRESS -> Amber
    Metric.STEPS -> Teal
    Metric.SLEEP -> Violet
}

private fun List<Point>.avg(): Int? = if (isEmpty()) null else map { it.value }.average().roundToInt()

// ===================================================================== TODAY

@Composable
fun TodayScreen(app: App, open: (Metric) -> Unit) {
    val ver by app.db.version.collectAsState()
    val st by app.ring.status.collectAsState()
    val today = LocalDate.now()
    val from = dayStart(today)
    val to = dayEnd(today)
    val acts = remember(ver) { app.db.activity(from, to) }
    val hr = remember(ver) { app.db.series(Db.HR, from, to) }
    val stress = remember(ver) { app.db.series(Db.STRESS, from, to) }
    val hrv = remember(ver) { app.db.series(Db.HRV, from, to) }
    val spo2 = remember(ver) { app.db.spo2(from, to) }
    val sleep = remember(ver) { app.db.sleepFor(today) }
    val steps = acts.sumOf { it.steps }
    val goal = app.prefs.stepGoal
    val updates = remember(ver) { todayUpdates(app) }

    LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Header(app, st) }

        if (updates.isNotEmpty()) item {
            Panel(Modifier.fillMaxWidth()) {
                PanelTitle("Today's updates", Teal)
                Spacer(Modifier.height(6.dp))
                updates.forEach { (metric, text) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open(metric) }.padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(metricColor(metric)))
                        Spacer(Modifier.width(10.dp))
                        Text(text, color = TextMain, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text("›", color = Muted, fontSize = 18.sp)
                    }
                }
            }
        }

        item {
            Panel(Modifier.fillMaxWidth(), onClick = { open(Metric.STEPS) }) {
                PanelTitle("Steps", Teal, trailing = "Goal ${fmtNumber(goal)}")
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                        ProgressRing(steps / goal.toFloat(), Teal, Modifier.size(92.dp))
                        Text("${(100f * steps / goal).roundToInt()}%", color = TextMain, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(18.dp))
                    Column {
                        BigValue(fmtNumber(steps), "steps")
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "%.2f km · %d kcal".format(acts.sumOf { it.distance } / 1000.0, acts.sumOf { it.calories }),
                            color = Muted,
                        )
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Panel(Modifier.weight(1f), onClick = { open(Metric.HEART) }) {
                    PanelTitle("Heart", Coral)
                    Spacer(Modifier.height(8.dp))
                    BigValue(hr.lastOrNull()?.value?.toString() ?: "--", "bpm", size = 28)
                    Text(
                        if (hr.isEmpty()) "No readings yet" else "${hr.minOf { it.value }}–${hr.maxOf { it.value }} today",
                        color = Muted, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Sparkline(hr.takeLast(48), Coral)
                }
                Panel(Modifier.weight(1f), onClick = { open(Metric.SLEEP) }) {
                    PanelTitle("Sleep", Violet)
                    Spacer(Modifier.height(8.dp))
                    BigValue(sleep?.let { fmtMinutes(it.asleepMinutes) } ?: "--", size = 28)
                    Text(sleep?.let { "${clock(it.start)} – ${clock(it.end)}" } ?: "No sleep recorded", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    if (sleep != null) SleepMixBar(sleep)
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Panel(Modifier.weight(1f), onClick = { open(Metric.SPO2) }) {
                    PanelTitle("Blood oxygen", Sky)
                    Spacer(Modifier.height(8.dp))
                    BigValue(spo2.takeIf { it.isNotEmpty() }?.map { it.value }?.average()?.roundToInt()?.toString() ?: "--", "%", size = 28)
                    Text(if (spo2.isEmpty()) "No readings yet" else "Lowest ${spo2.minOf { it.min }}%", color = Muted, fontSize = 12.sp)
                }
                Panel(Modifier.weight(1f), onClick = { open(Metric.STRESS) }) {
                    PanelTitle("Stress", Amber)
                    Spacer(Modifier.height(8.dp))
                    val s = stress.avg()
                    BigValue(s?.toString() ?: "--", size = 28)
                    Text(s?.let { stressLabel(it) } ?: "No readings yet", color = Muted, fontSize = 12.sp)
                }
            }
        }

        item {
            Panel(Modifier.fillMaxWidth(), onClick = { open(Metric.HRV) }) {
                PanelTitle("HRV", Lime, trailing = "heart rate variability")
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BigValue(hrv.avg()?.toString() ?: "--", "ms", size = 28)
                    Spacer(Modifier.width(16.dp))
                    Sparkline(hrv, Lime, Modifier.weight(1f).height(36.dp))
                }
            }
        }
    }
}

@Composable
private fun Header(app: App, st: RingManager.Status) {
    val hour = LocalTime.now().hour
    val greet = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(greet, color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), color = Muted)
            }
            if (st.busy) {
                CircularProgressIndicator(Modifier.size(40.dp).padding(6.dp), color = Teal, strokeWidth = 3.dp)
            } else {
                FilledIconButton(
                    onClick = { app.scope.launch { app.ring.sync() } },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Teal, contentColor = Bg),
                    modifier = Modifier.size(48.dp),
                ) { Icon(Icons.Rounded.Sync, "Sync now") }
            }
        }
        Spacer(Modifier.height(10.dp))
        val ver by app.db.version.collectAsState()
        val rs by app.ring.settings.collectAsState()
        val est = remember(ver, rs, st.battery, st.charging) {
            BatteryEstimator.estimate(app.prefs, app.db, rs ?: RingManager.RingSettings())
        }
        val battery = when {
            est.level == null -> "Ring"
            est.charging -> "Ring ${est.level}% (charging)"
            else -> "Ring ${est.level}% · ~${BatteryEstimator.formatHours(est.hoursLeft ?: 0.0)} left"
        }
        Text(
            if (st.busy) "$battery · ${st.step}…" else "$battery · synced ${ago(st.lastSync)}",
            color = Muted, fontSize = 13.sp,
        )
        if (st.syncing) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { st.progress },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = Teal, trackColor = Grid,
            )
        }
        st.message?.let { msg ->
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Coral.copy(alpha = 0.15f)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(msg, color = TextMain, fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { app.ring.clearMessage() }) { Text("OK", color = Coral) }
            }
        }
    }
}

@Composable
private fun SleepMixBar(n: SleepNight) {
    val total = n.stages.sumOf { it.minutes }.coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
        listOf(SleepNight.STAGE_DEEP, SleepNight.STAGE_LIGHT, SleepNight.STAGE_REM, SleepNight.STAGE_AWAKE).forEach { s ->
            val m = n.minutesOf(s)
            if (m > 0) Box(Modifier.weight(m / total.toFloat()).height(8.dp).background(stageColor(s)))
        }
    }
}

// ===================================================================== SLEEP

@Composable
fun SleepScreen(app: App, openDetail: (Metric, LocalDate) -> Unit) {
    val ver by app.db.version.collectAsState()
    var date by rememberSaveable { mutableStateOf(LocalDate.now()) }
    val night = remember(ver, date) { app.db.sleepFor(date) }
    val week = remember(ver, date) {
        (6 downTo 0).map { val d = date.minusDays(it.toLong()); d to (app.db.sleepFor(d)?.asleepMinutes ?: 0) }
    }

    LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Sleep", style = androidx.compose.material3.MaterialTheme.typography.headlineLarge) }
        item {
            DayPicker(date, { date = it }) { d ->
                if (d == LocalDate.now()) "Last night" else "Woke up " + d.format(DateTimeFormatter.ofPattern("EEE d MMM"))
            }
        }
        if (night == null) {
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Text("No sleep recorded for this night.", color = TextMain, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Wear the ring to bed and sync in the morning. The ring keeps about a week of nights.", color = Muted)
                }
            }
        } else {
            item {
                Panel(Modifier.fillMaxWidth(), onClick = { openDetail(Metric.SLEEP, date) }) {
                    PanelTitle("Time asleep", Violet, trailing = "Details ›")
                    Spacer(Modifier.height(8.dp))
                    BigValue(fmtMinutes(night.asleepMinutes))
                    Text("In bed ${clock(night.start)} – ${clock(night.end)} (${fmtMinutes(((night.end - night.start) / 60000).toInt())})", color = Muted)
                    Spacer(Modifier.height(16.dp))
                    Hypnogram(night)
                }
            }
            item {
                Panel(Modifier.fillMaxWidth(), onClick = { openDetail(Metric.SLEEP, date) }) {
                    PanelTitle("Stages", Violet, trailing = "Stage times ›")
                    Spacer(Modifier.height(12.dp))
                    val total = night.stages.sumOf { it.minutes }.coerceAtLeast(1)
                    listOf(
                        Triple("Deep", SleepNight.STAGE_DEEP, "Body repair. More is better"),
                        Triple("Light", SleepNight.STAGE_LIGHT, "Most of the night is normally light"),
                        Triple("REM", SleepNight.STAGE_REM, "Dreaming, memory and mood"),
                        Triple("Awake", SleepNight.STAGE_AWAKE, "Short wake-ups are normal"),
                    ).forEach { (name, stage, hint) ->
                        val m = night.minutesOf(stage)
                        StageRow(name, hint, m, m / total.toFloat(), stageColor(stage))
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                val withData = week.filter { it.second > 0 }
                PanelTitle("Last 7 nights", Violet, trailing = if (withData.isEmpty()) null else "avg ${fmtMinutes(withData.map { it.second }.average().roundToInt())}")
                Spacer(Modifier.height(12.dp))
                BarChart(
                    week.map { it.second / 60f },
                    week.map { it.first.format(DateTimeFormatter.ofPattern("EEE")) },
                    Violet, highlight = 6, target = 8f,
                )
            }
        }
    }
}

@Composable
private fun StageRow(name: String, hint: String, minutes: Int, frac: Float, color: Color) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = TextMain, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${fmtMinutes(minutes)}  ·  ${(frac * 100).roundToInt()}%", color = TextMain)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Grid)) {
            Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        }
        Spacer(Modifier.height(4.dp))
        Text(hint, color = Muted, fontSize = 12.sp)
    }
}

// ===================================================================== HEALTH

@Composable
fun HealthScreen(app: App, openDetail: (Metric, LocalDate) -> Unit) {
    val ver by app.db.version.collectAsState()
    val live by app.ring.live.collectAsState()
    val st by app.ring.status.collectAsState()
    var date by rememberSaveable { mutableStateOf(LocalDate.now()) }
    val from = dayStart(date)
    val to = dayEnd(date)
    val hr = remember(ver, date) { app.db.series(Db.HR, from, to) }
    val hrv = remember(ver, date) { app.db.series(Db.HRV, from, to) }
    val stress = remember(ver, date) { app.db.series(Db.STRESS, from, to) }
    val spo2 = remember(ver, date) { app.db.spo2(from, to) }

    LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Health", style = androidx.compose.material3.MaterialTheme.typography.headlineLarge) }
        item { DayPicker(date, { date = it }) }

        live?.let { l -> item { LivePanel(app, l) } }

        item {
            Panel(Modifier.fillMaxWidth(), onClick = { openDetail(Metric.HEART, date) }) {
                PanelTitle("Heart rate", Coral, trailing = "Details ›")
                Spacer(Modifier.height(10.dp))
                StatRow(
                    "Lowest" to (hr.minOfOrNull { it.value }?.toString() ?: "--"),
                    "Average" to (hr.avg()?.toString() ?: "--"),
                    "Highest" to (hr.maxOfOrNull { it.value }?.toString() ?: "--"),
                )
                Spacer(Modifier.height(12.dp))
                DayLineChart(hr, from, Coral)
                Spacer(Modifier.height(12.dp))
                MeasureButton("Measure heart rate now", Coral, enabled = !st.busy) {
                    app.scope.launch(Dispatchers.Default) { app.ring.measure(Cmd.LIVE_HR) }
                }
            }
        }
        item {
            Panel(Modifier.fillMaxWidth(), onClick = { openDetail(Metric.SPO2, date) }) {
                PanelTitle("Blood oxygen (SpO2)", Sky, trailing = "Details ›")
                Spacer(Modifier.height(10.dp))
                StatRow(
                    "Lowest" to (spo2.minOfOrNull { it.min }?.toString() ?: "--"),
                    "Average" to (spo2.takeIf { it.isNotEmpty() }?.map { it.value }?.average()?.roundToInt()?.toString() ?: "--"),
                    "Readings" to spo2.size.toString(),
                )
                Spacer(Modifier.height(12.dp))
                DayLineChart(spo2.map { Point(it.ts, it.value) }, from, Sky, gapMinutes = 90, fixedMin = 85f, fixedMax = 100f)
                Spacer(Modifier.height(12.dp))
                MeasureButton("Measure blood oxygen now", Sky, enabled = !st.busy) {
                    app.scope.launch(Dispatchers.Default) { app.ring.measure(Cmd.LIVE_SPO2) }
                }
            }
        }
        item {
            Panel(Modifier.fillMaxWidth(), onClick = { openDetail(Metric.HRV, date) }) {
                PanelTitle("HRV", Lime, trailing = "Details ›")
                Spacer(Modifier.height(10.dp))
                StatRow(
                    "Lowest" to (hrv.minOfOrNull { it.value }?.toString() ?: "--"),
                    "Average" to (hrv.avg()?.toString() ?: "--"),
                    "Highest" to (hrv.maxOfOrNull { it.value }?.toString() ?: "--"),
                )
                Spacer(Modifier.height(12.dp))
                DayLineChart(hrv, from, Lime, gapMinutes = 90)
                Spacer(Modifier.height(6.dp))
                Text("Higher HRV usually means you're well rested and recovered.", color = Muted, fontSize = 12.sp)
            }
        }
        item {
            Panel(Modifier.fillMaxWidth(), onClick = { openDetail(Metric.STRESS, date) }) {
                PanelTitle("Stress", Amber, trailing = "Details ›")
                Spacer(Modifier.height(10.dp))
                StatRow(
                    "Lowest" to (stress.minOfOrNull { it.value }?.toString() ?: "--"),
                    "Average" to (stress.avg()?.toString() ?: "--"),
                    "Highest" to (stress.maxOfOrNull { it.value }?.toString() ?: "--"),
                )
                Spacer(Modifier.height(12.dp))
                DayLineChart(stress, from, Amber, gapMinutes = 90, fixedMin = 0f, fixedMax = 100f)
                if (date != LocalDate.now() && stress.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("The ring only hands over today's stress, so older days fill in as you sync daily.", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun MeasureButton(text: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
    ) { Text(text) }
}

@Composable
private fun LivePanel(app: App, l: RingManager.Live) {
    val isHr = l.type == Cmd.LIVE_HR
    val color = if (isHr) Coral else Sky
    Panel(Modifier.fillMaxWidth()) {
        PanelTitle(if (isHr) "Live heart rate" else "Live blood oxygen", color)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigValue(l.value?.toString() ?: "--", if (isHr) "bpm" else "%", color = color, size = 44)
            Spacer(Modifier.weight(1f))
            if (l.running) CircularProgressIndicator(Modifier.size(28.dp), color = color, strokeWidth = 3.dp)
        }
        Text(l.note, color = Muted)
        Spacer(Modifier.height(10.dp))
        if (l.running) {
            TextButton(onClick = { app.ring.cancelLive() }) { Text("Stop", color = color) }
        } else {
            TextButton(onClick = { app.ring.clearLive() }) { Text("Close", color = color) }
        }
    }
}

// ===================================================================== ACTIVITY

@Composable
fun ActivityScreen(app: App, openDetail: (Metric, LocalDate) -> Unit) {
    val ver by app.db.version.collectAsState()
    var date by rememberSaveable { mutableStateOf(LocalDate.now()) }
    var range by rememberSaveable { mutableStateOf(7) }
    val acts = remember(ver, date) { app.db.activity(dayStart(date), dayEnd(date)) }
    val hourly = remember(acts) {
        val zone = ZoneId.systemDefault()
        val arr = FloatArray(24)
        acts.forEach { arr[Instant.ofEpochMilli(it.ts).atZone(zone).hour] += it.steps.toFloat() }
        arr.toList()
    }
    val daily = remember(ver, date, range) { app.db.dailySteps(date, range) }
    val goal = app.prefs.stepGoal
    val steps = acts.sumOf { it.steps }

    LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Activity", style = androidx.compose.material3.MaterialTheme.typography.headlineLarge) }
        item { DayPicker(date, { date = it }) }
        item {
            Panel(Modifier.fillMaxWidth(), onClick = { openDetail(Metric.STEPS, date) }) {
                PanelTitle("Steps", Teal, trailing = "Details ›")
                Spacer(Modifier.height(8.dp))
                BigValue(fmtNumber(steps), "steps")
                Text(if (steps >= goal) "Goal reached" else "${fmtNumber(goal - steps)} to go", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                StatRow(
                    "Distance" to "%.2f km".format(acts.sumOf { it.distance } / 1000.0),
                    "Calories" to "${acts.sumOf { it.calories }} kcal",
                    "Active hours" to hourly.count { it >= 250f }.toString(),
                )
                Spacer(Modifier.height(16.dp))
                BarChart(hourly, (0..23).map { if (it % 6 == 0) "%02d".format(it) else "" }, Teal)
            }
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                val withData = daily.filter { it.second > 0 }
                PanelTitle("Trend", Teal, trailing = if (withData.isEmpty()) null else "avg ${fmtNumber(withData.map { it.second }.average().roundToInt())}/day")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7, 30).forEach { r ->
                        FilterChip(
                            selected = range == r,
                            onClick = { range = r },
                            label = { Text("$r days") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Teal, selectedLabelColor = Bg),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                val fmt = DateTimeFormatter.ofPattern(if (range == 7) "EEE" else "d")
                BarChart(
                    daily.map { it.second.toFloat() },
                    daily.map { it.first.format(fmt) },
                    Teal,
                    labelEvery = if (range == 7) 1 else 5,
                    highlight = daily.size - 1,
                    target = goal.toFloat(),
                )
            }
        }
    }
}

// ===================================================================== RING / SETTINGS

@Composable
fun MoreScreen(app: App, onForget: () -> Unit) {
    val ctx = LocalContext.current
    val st by app.ring.status.collectAsState()
    val ringSettings by app.ring.settings.collectAsState()
    val ver by app.db.version.collectAsState()
    val current = ringSettings ?: RingManager.RingSettings()
    val est = remember(ver, ringSettings, st.battery, st.charging) {
        BatteryEstimator.estimate(app.prefs, app.db, current)
    }
    var autoSync by remember { mutableStateOf(app.prefs.autoSync) }
    var goal by remember { mutableStateOf(app.prefs.stepGoal) }
    var confirmOff by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf<String?>(null) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) app.scope.launch(Dispatchers.IO) {
            val ok = runCatching { ctx.contentResolver.openOutputStream(uri)?.use { app.db.exportCsv(it) } }.isSuccess
            exported = if (ok) "Exported. Open it in Excel or Google Sheets." else "Export failed."
        }
    }

    LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Ring", style = androidx.compose.material3.MaterialTheme.typography.headlineLarge) }
        item {
            Panel(Modifier.fillMaxWidth()) {
                PanelTitle(app.prefs.name ?: "Ring", Teal, trailing = when (st.conn) {
                    RingManager.Conn.Connected -> "Connected"
                    RingManager.Conn.Connecting -> "Connecting…"
                    RingManager.Conn.Disconnected -> if (st.lastSync > 0) "Standby" else "Not synced yet"
                })
                Spacer(Modifier.height(10.dp))
                StatRow(
                    "Battery" to (est.level?.let { "$it%" } ?: "--"),
                    "Last sync" to ago(st.lastSync),
                )
                Spacer(Modifier.height(10.dp))
                StatRow(
                    "Firmware" to (app.prefs.firmware ?: "--"),
                    "Hardware" to (app.prefs.hardware ?: "--"),
                )
                if (st.busy) {
                    Spacer(Modifier.height(10.dp))
                    Text("${st.step}…", color = Teal)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { app.scope.launch { app.ring.sync() } }, enabled = !st.busy, modifier = Modifier.weight(1f)) { Text("Sync now") }
                    OutlinedButton(onClick = { app.scope.launch { app.ring.findRing() } }, enabled = !st.busy, modifier = Modifier.weight(1f)) { Text("Find my ring") }
                }
            }
        }

        item { BatteryPanel(app, est, current) }

        item {
            Panel(Modifier.fillMaxWidth()) {
                PanelTitle("What the ring measures by itself", Coral)
                Spacer(Modifier.height(6.dp))
                val s = ringSettings
                if (s == null) {
                    Text("Settings load during a sync. Tap below to read them now.", color = Muted)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { app.scope.launch { app.ring.loadSettings() } }, enabled = !st.busy) { Text("Read ring settings") }
                } else {
                    fun save(n: RingManager.RingSettings) { app.scope.launch { app.ring.applySettings(n) } }
                    ToggleRow("Heart rate", "Automatic readings through the day and night", s.hrEnabled, !st.busy) { save(s.copy(hrEnabled = it)) }
                    if (s.hrEnabled) {
                        Text("Measure every", color = Muted, fontSize = 13.sp)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(5, 10, 15, 30, 60).forEach { m ->
                                FilterChip(
                                    selected = s.hrInterval == m,
                                    onClick = { if (!st.busy) save(s.copy(hrInterval = m)) },
                                    label = { Text("${m}m") },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Coral, selectedLabelColor = Bg),
                                )
                            }
                        }
                        Text("Shorter = more detail, but the ring battery runs down faster.", color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                    }
                    ToggleRow("Blood oxygen", "Hourly SpO2, mostly while you sleep", s.spo2, !st.busy) { save(s.copy(spo2 = it)) }
                    ToggleRow("Stress", "Every 30 minutes", s.stress, !st.busy) { save(s.copy(stress = it)) }
                    ToggleRow("HRV", "Heart rate variability, every 30 minutes", s.hrv, !st.busy) { save(s.copy(hrv = it)) }
                }
            }
        }

        item {
            Panel(Modifier.fillMaxWidth()) {
                PanelTitle("App", Sky)
                Spacer(Modifier.height(6.dp))
                ToggleRow("Sync in the background", "About once an hour, even when the app is closed", autoSync, true) {
                    autoSync = it
                    app.prefs.autoSync = it
                    SyncWorker.schedule(app, it)
                }
                Text("Daily step goal", color = TextMain, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(6000, 8000, 10000, 12000).forEach { g ->
                        FilterChip(
                            selected = goal == g,
                            onClick = { goal = g; app.prefs.stepGoal = g; app.db.bump() },
                            label = { Text("${g / 1000}k") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Sky, selectedLabelColor = Bg),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = { exporter.launch("ring-data-${LocalDate.now()}.csv") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Export all data (CSV)")
                }
                exported?.let { Text(it, color = Muted, fontSize = 12.sp) }
            }
        }

        item {
            Panel(Modifier.fillMaxWidth()) {
                PanelTitle("Careful", Coral)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { confirmOff = true }, enabled = !st.busy, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Coral)) { Text("Turn the ring off") }
                OutlinedButton(onClick = { confirmForget = true }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Coral)) { Text("Forget this ring") }
                Text("Forgetting keeps all your saved data.", color = Muted, fontSize = 12.sp)
            }
        }
    }

    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text("Turn the ring off?") },
            text = { Text("It stops tracking until you put it back on the charger.") },
            confirmButton = { TextButton(onClick = { confirmOff = false; app.scope.launch { app.ring.powerOff() } }) { Text("Turn off") } },
            dismissButton = { TextButton(onClick = { confirmOff = false }) { Text("Cancel") } },
        )
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget this ring?") },
            text = { Text("You'll go back to the connect screen. Your saved readings stay on the phone.") },
            confirmButton = { TextButton(onClick = { confirmForget = false; onForget() }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BatteryPanel(app: App, est: BatteryEstimator.Estimate, s: RingManager.RingSettings) {
    Panel(Modifier.fillMaxWidth()) {
        PanelTitle("Battery", Lime, trailing = if (est.learned) "learned from your ring" else "rough estimate")
        Spacer(Modifier.height(10.dp))
        val level = est.level
        if (level == null) {
            Text("Sync once to read the battery.", color = Muted)
            return@Panel
        }
        Row(verticalAlignment = Alignment.Bottom) {
            BigValue("$level", "%", color = if (level <= 15) Coral else TextMain)
            Spacer(Modifier.width(14.dp))
            Text(
                when {
                    est.charging -> "Charging"
                    est.hoursLeft != null -> "about ${BatteryEstimator.formatHours(est.hoursLeft)} left"
                    else -> ""
                },
                color = if (level <= 15 && !est.charging) Coral else Lime,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Grid)) {
            Box(
                Modifier.fillMaxWidth(level / 100f).height(8.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (level <= 15) Coral else Lime)
            )
        }
        Spacer(Modifier.height(8.dp))
        if (!est.charging && est.hoursLeft != null) {
            val emptyAt = java.time.LocalDateTime.now().plusMinutes((est.hoursLeft * 60).toLong())
            Text("Runs out around " + emptyAt.format(DateTimeFormatter.ofPattern("EEE d MMM, h a")), color = Muted, fontSize = 13.sp)
        }
        Text("A full charge lasts about ${BatteryEstimator.formatDays(est.fullChargeDays)} with your current settings.", color = Muted, fontSize = 13.sp)

        if (s.hrEnabled) {
            Spacer(Modifier.height(14.dp))
            Text("Full charge by heart-rate interval", color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            val factor = est.fullChargeDays * BatteryEstimator.modelDrainPerDay(s) / 100.0
            Row(Modifier.fillMaxWidth()) {
                listOf(5, 10, 15, 30, 60).forEach { m ->
                    val days = 100.0 / (BatteryEstimator.modelDrainPerDay(s.copy(hrInterval = m)) / factor)
                    val selected = s.hrInterval == m
                    Column(
                        Modifier.weight(1f).padding(horizontal = 2.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Lime.copy(alpha = 0.18f) else CardHi).padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("${m}m", color = if (selected) Lime else Muted, fontSize = 12.sp)
                        Text("%.1fd".format(days), color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
            }
        }
        if (!est.learned) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Based on typical use for now. After a day or two of syncs it learns your ring's real drain and gets more accurate.",
                color = Muted, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Teal, checkedThumbColor = Bg),
        )
    }
}

@Suppress("unused")
private fun Long.isToday() = toLocalDate() == LocalDate.now()
