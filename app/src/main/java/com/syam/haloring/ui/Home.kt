package com.syam.haloring.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syam.haloring.App
import com.syam.haloring.data.Act
import com.syam.haloring.data.BatteryEstimator
import com.syam.haloring.data.Db
import com.syam.haloring.data.Point
import com.syam.haloring.data.SleepNight
import com.syam.haloring.data.dayEnd
import com.syam.haloring.data.dayStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private data class TimelineEvent(val ts: Long, val title: String, val detail: String, val color: Color, val metric: Metric?)

private data class TodayData(
    val readiness: Score?,
    val sleep: Score?,
    val activity: Score?,
    val night: SleepNight?,
    val hr: List<Point>,
    val hrv: List<Point>,
    val stress: List<Point>,
    val spo2: List<Point>,
    val acts: List<Act>,
    val updates: List<Pair<Metric, String>>,
    val timeline: List<TimelineEvent>,
)

private fun loadToday(app: App): TodayData {
    val today = LocalDate.now()
    val from = dayStart(today)
    val to = dayEnd(today)
    val night = app.db.sleepFor(today)
    val hr = app.db.series(Db.HR, from, to)
    val stress = app.db.series(Db.STRESS, from, to)
    val spo2 = app.db.spo2(from, to)
    val acts = app.db.activity(from, to)
    val events = mutableListOf<TimelineEvent>()
    night?.let {
        events += TimelineEvent(it.start, "Fell asleep", "Bedtime", Violet, Metric.SLEEP)
        events += TimelineEvent(it.end, "Woke up", "${fmtMinutes(Scores.asleepMinutes(it))} asleep", Violet, Metric.SLEEP)
    }
    hr.filter { it.ts % 300_000L != 0L }.forEach {
        events += TimelineEvent(it.ts, "Heart rate spot check", "${it.value} bpm", Coral, Metric.HEART)
    }
    spo2.filter { it.ts % 3_600_000L != 0L }.forEach {
        events += TimelineEvent(it.ts, "Blood oxygen spot check", "${it.value}%", Sky, Metric.SPO2)
    }
    val zone = ZoneId.systemDefault()
    if (acts.isNotEmpty()) {
        val byHour = acts.groupBy { Instant.ofEpochMilli(it.ts).atZone(zone).hour }.mapValues { e -> e.value.sumOf { it.steps } }
        byHour.maxByOrNull { it.value }?.takeIf { it.value >= 500 }?.let { (h, s) ->
            events += TimelineEvent(dayStart(today) + h * 3_600_000L, "Most active hour", "${fmtNumber(s)} steps", Orange, Metric.STEPS)
        }
        var cum = 0
        for (a in acts.sortedBy { it.ts }) {
            val before = cum
            cum += a.steps
            if (before < app.prefs.stepGoal && cum >= app.prefs.stepGoal) {
                events += TimelineEvent(a.ts + 15 * 60_000L, "Step goal reached", "${fmtNumber(app.prefs.stepGoal)} steps", Orange, Metric.STEPS)
            }
        }
    }
    stress.maxByOrNull { it.value }?.takeIf { it.value >= 60 }?.let {
        events += TimelineEvent(it.ts, "Stress peak", "Score ${it.value}", Amber, Metric.STRESS)
    }
    if (app.prefs.lastSync >= from) events += TimelineEvent(app.prefs.lastSync, "Synced with ring", "All data up to date", Teal, null)

    return TodayData(
        readiness = Scores.readiness(app, today),
        sleep = Scores.sleep(app, today),
        activity = Scores.activity(app, today),
        night = night,
        hr = hr,
        hrv = loadSeries(app, Metric.HRV, from, to),
        stress = stress,
        spo2 = spo2.map { Point(it.ts, it.value) },
        acts = acts,
        updates = todayUpdates(app),
        timeline = events.sortedBy { it.ts },
    )
}

@Composable
fun TodayScreen(app: App, open: (Metric) -> Unit, openTab: (Int) -> Unit) {
    val ver by app.db.version.collectAsState()
    val st by app.ring.status.collectAsState()
    val rs by app.ring.settings.collectAsState()
    val data by produceState<TodayData?>(null, ver) { value = withContext(Dispatchers.IO) { loadToday(app) } }
    val glow = statusOf(data?.readiness?.value).color.takeIf { data?.readiness != null } ?: Teal

    Box {
        GlowBackdrop(glow)
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // ---------- header
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM")).uppercase(),
                            color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp,
                        )
                        val greet = when (LocalTime.now().hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
                        Text("$greet, Syam", color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
                    }
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(CardHi).border(1.dp, Border, CircleShape)
                            .clickable(enabled = !st.busy) { app.scope.launch { app.ring.sync() } },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (st.busy) CircularProgressIndicator(Modifier.size(22.dp), color = Teal, strokeWidth = 2.5.dp)
                        else Icon(Icons.Rounded.Sync, "Sync now", tint = TextMain)
                    }
                }
            }
            // ---------- status chips
            item {
                val est = BatteryEstimator.estimate(app.prefs, app.db, rs ?: com.syam.haloring.ble.RingManager.RingSettings())
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        est.level?.let { lvl ->
                            Chip(
                                "Ring $lvl%" + when {
                                    est.charging -> " · charging"
                                    est.hoursLeft != null -> " · ${BatteryEstimator.formatHours(est.hoursLeft)} left"
                                    else -> ""
                                },
                                if (lvl <= 15 && !est.charging) Coral else Lime,
                                Modifier.clickable { openTab(3) },
                            )
                        }
                        Chip(if (st.busy) "${st.step}…" else "Synced ${ago(st.lastSync)}", if (st.busy) Teal else Muted)
                    }
                    AnimatedVisibility(st.syncing) {
                        LinearProgressIndicator(
                            progress = { st.progress },
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                            color = Teal, trackColor = Grid,
                        )
                    }
                    st.message?.let { msg ->
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Coral.copy(alpha = 0.12f)).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(msg, color = TextMain, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { app.ring.clearMessage() }) { Text("OK", color = Coral) }
                        }
                    }
                }
            }

            val d = data
            if (d == null) {
                item {
                    Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Teal)
                    }
                }
                return@LazyColumn
            }

            // ---------- hero dials
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScoreTile("Sleep", d.sleep?.value, SleepGrad, 92.dp) { open(Metric.SLEEP) }
                    ScoreTile("Readiness", d.readiness?.value, ReadinessGrad, 138.dp) { open(Metric.READINESS) }
                    ScoreTile("Activity", d.activity?.value, ActivityGrad, 92.dp) { open(Metric.STEPS) }
                }
            }

            // ---------- daily focus
            item {
                val r = d.readiness
                val weakest = r?.contributors?.minByOrNull { it.score }?.takeIf { it.score < 70 }
                val c = statusOf(r?.value).color.takeIf { r != null } ?: Teal
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
                        .background(Brush.linearGradient(listOf(c.copy(alpha = 0.16f), CardColor)))
                        .border(1.dp, c.copy(alpha = 0.25f), RoundedCornerShape(26.dp))
                        .clickable { open(Metric.READINESS) }
                        .padding(18.dp)
                ) {
                    Text("TODAY'S FOCUS", color = c, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(Scores.readinessHeadline(r?.value), color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
                    if (weakest != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("Held back by ${weakest.name.lowercase()}: ${weakest.value}.", color = Muted, fontSize = 13.sp)
                    }
                }
            }

            // ---------- vitals grid
            item { SectionLabel("Vitals", trailing = "See all ›", modifier = Modifier.clickable { openTab(1) }) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        VitalTile("Heart rate", d.hr.lastOrNull()?.value?.toString(), "bpm",
                            d.hr.lastOrNull()?.let { "at ${hhmm(it.ts)}" }, d.hr.takeLast(60), Coral, Modifier.weight(1f)) { open(Metric.HEART) }
                        VitalTile("HRV", d.hrv.takeIf { it.isNotEmpty() }?.map { it.value }?.average()?.roundToInt()?.toString(), "ms",
                            "today's average", d.hrv, Lime, Modifier.weight(1f)) { open(Metric.HRV) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        VitalTile("Blood oxygen", d.spo2.takeIf { it.isNotEmpty() }?.map { it.value }?.average()?.roundToInt()?.toString(), "%",
                            d.spo2.minByOrNull { it.value }?.let { "lowest ${it.value}%" }, d.spo2, Sky, Modifier.weight(1f)) { open(Metric.SPO2) }
                        VitalTile("Stress", d.stress.takeIf { it.isNotEmpty() }?.map { it.value }?.average()?.roundToInt()?.toString(), "",
                            d.stress.takeIf { it.isNotEmpty() }?.map { it.value }?.average()?.let { stressWord(it.roundToInt()) },
                            d.stress, Amber, Modifier.weight(1f)) { open(Metric.STRESS) }
                    }
                }
            }

            // ---------- steps strip
            item {
                val steps = d.acts.sumOf { it.steps }
                val goal = app.prefs.stepGoal
                Panel(Modifier.fillMaxWidth(), onClick = { open(Metric.STEPS) }) {
                    PanelTitle("Steps", Orange, trailing = if (steps >= goal) "Goal reached ✓" else "${fmtNumber(goal - steps)} to go")
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        BigValue(fmtNumber(steps), "/ ${fmtNumber(goal)}", size = 30)
                        Spacer(Modifier.weight(1f))
                        Text("%.1f km · %d kcal".format(d.acts.sumOf { it.distance } / 1000.0, d.acts.sumOf { it.calories }),
                            color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Grid)) {
                        Box(
                            Modifier.fillMaxWidth((steps / goal.toFloat()).coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp))
                                .background(Brush.horizontalGradient(ActivityGrad))
                        )
                    }
                }
            }

            // ---------- insights
            if (d.updates.isNotEmpty()) {
                item { SectionLabel("Insights") }
                item {
                    Panel(Modifier.fillMaxWidth()) {
                        d.updates.forEachIndexed { i, (metric, text) ->
                            if (i > 0) Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Border))
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open(metric) }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Badge(metricColor(metric)) { Box(Modifier.size(8.dp).clip(CircleShape).background(metricColor(metric))) }
                                Spacer(Modifier.width(12.dp))
                                Text(text, color = TextMain, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
                                Text("›", color = Faint, fontSize = 20.sp)
                            }
                        }
                    }
                }
            }

            // ---------- timeline
            if (d.timeline.isNotEmpty()) {
                item { SectionLabel("Timeline", trailing = "today") }
                item {
                    Panel(Modifier.fillMaxWidth()) {
                        d.timeline.forEachIndexed { i, e ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .then(e.metric?.let { m -> Modifier.clickable { open(m) } } ?: Modifier),
                            ) {
                                Text(hhmm(e.ts), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.width(50.dp).padding(top = 10.dp))
                                Column(Modifier.width(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.width(2.dp).height(12.dp).background(if (i == 0) Color.Transparent else Grid))
                                    Box(Modifier.size(10.dp).clip(CircleShape).background(e.color))
                                    Box(Modifier.width(2.dp).height(30.dp).background(if (i == d.timeline.lastIndex) Color.Transparent else Grid))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.padding(top = 6.dp)) {
                                    Text(e.title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text(e.detail, color = Muted, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun stressWord(v: Int) = when {
    v < 30 -> "Relaxed"
    v < 60 -> "Normal"
    v < 80 -> "Medium"
    else -> "High"
}

@Composable
private fun VitalTile(
    title: String,
    value: String?,
    unit: String,
    sub: String?,
    pts: List<Point>,
    color: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Panel(modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(title.uppercase(), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        }
        Spacer(Modifier.height(8.dp))
        BigValue(value ?: "--", unit, size = 28)
        Text(sub ?: "No readings yet", color = Faint, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Sparkline(pts, color, Modifier.fillMaxWidth().height(30.dp))
    }
}
