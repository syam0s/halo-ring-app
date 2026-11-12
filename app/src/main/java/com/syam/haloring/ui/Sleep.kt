package com.syam.haloring.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.syam.haloring.App
import com.syam.haloring.BedtimeWorker
import com.syam.haloring.data.Db
import com.syam.haloring.data.Point
import com.syam.haloring.data.SleepNight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Nightly sleep need in minutes. The sleep score uses the same 7½ hours. */
private const val SLEEP_NEED = 450

private val tileShape = RoundedCornerShape(18.dp)
private val pill = RoundedCornerShape(50)
private val dayFmt = DateTimeFormatter.ofPattern("EEE")

/** Minutes after 18:00, so times either side of midnight average properly. */
private fun eve(ms: Long): Int {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime()
    return ((t.hour * 60 + t.minute) - 18 * 60 + 1440) % 1440
}

private fun fromEve(m: Int): String = clock(((m % 1440) + 1440 + 18 * 60) % 1440)

private fun clock(minuteOfDay: Int): String {
    val m = ((minuteOfDay % 1440) + 1440) % 1440
    return "%02d:%02d".format(m / 60, m % 60)
}

// ---------------------------------------------------------------- shared pieces

@Composable
private fun SleepPage(content: LazyListScope.() -> Unit) {
    Box {
        GlowBackdrop(Violet)
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun SleepHeader(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(subtitle, color = Muted, fontSize = 13.sp)
    }
}

/** The big centre card: a caption, one huge number, a line under it, and optional extras. */
@Composable
private fun Hero(
    caption: String,
    footline: String,
    extra: @Composable ColumnScope.() -> Unit = {},
    value: @Composable RowScope.() -> Unit,
) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(caption, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.Bottom, content = value)
            Text(footline, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            extra()
        }
    }
}

@Composable
private fun HeroNumber(text: String, unit: String) {
    Text(text, color = Violet, fontSize = 76.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-2.5).sp)
    if (unit.isNotEmpty()) {
        Spacer(Modifier.width(4.dp))
        Text(
            unit, color = Muted, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp, end = 8.dp),
        )
    }
}

private data class Tile(val label: String, val value: String, val color: Color = TextMain)

@Composable
private fun Tiles(a: Tile, b: Tile, c: Tile) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(a, b, c).forEach { t ->
            Column(
                Modifier.weight(1f).clip(tileShape).background(CardColor).border(1.dp, Border, tileShape).padding(12.dp),
            ) {
                Text(t.label, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Text(t.value, color = t.color, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ShareRow(label: String, fraction: Float, color: Color, trailing: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(50.dp))
        Box(Modifier.weight(1f).height(8.dp).clip(pill).background(Grid)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(pill).background(color))
        }
        Text(
            trailing, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End, modifier = Modifier.width(108.dp),
        )
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Violet)
    }
}

@Composable
private fun NoNight(app: App, busy: Boolean, text: String = "No sleep recorded for last night.") {
    Panel(Modifier.fillMaxWidth()) {
        Text(text, color = TextMain, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("Wear the ring to bed and sync in the morning.", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        PillButton("Sync now", Violet, Modifier.fillMaxWidth(), filled = true, enabled = !busy) {
            app.scope.launch { app.ring.sync() }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
}

// ---------------------------------------------------------------- A · Last night

private data class NightData(val night: SleepNight?, val score: Score?)

@Composable
fun LastNightScreen(app: App, openDetail: (Metric, LocalDate) -> Unit, openTab: (Int) -> Unit) {
    val ver by app.db.version.collectAsState()
    val st by app.ring.status.collectAsState()
    val today = LocalDate.now()
    val data by produceState<NightData?>(null, ver) {
        value = withContext(Dispatchers.IO) { NightData(app.db.sleepFor(today), Scores.sleep(app, today)) }
    }

    SleepPage {
        item { SleepHeader("Last night", "How you slept, first thing you see") }
        val d = data
        val night = d?.night
        if (d == null) {
            item { Loading() }
        } else if (night == null) {
            item { NoNight(app, st.busy) }
        } else {
            val asleep = Scores.asleepMinutes(night)
            val inBed = ((night.end - night.start) / 60000).toInt()
            val order = listOf(SleepNight.STAGE_DEEP, SleepNight.STAGE_LIGHT, SleepNight.STAGE_REM, SleepNight.STAGE_AWAKE)
            val parts = order.map { it to night.minutesOf(it) }.filter { it.second > 0 }
            val score = d.score
            item {
                Hero(
                    caption = "${hhmm(night.start)} – ${hhmm(night.end)}",
                    footline = if (score != null) "Sleep score ${score.value} · ${statusOf(score.value).label}"
                    else "In bed ${fmtMinutes(inBed)}",
                    extra = {
                        if (parts.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                parts.forEach { part ->
                                    Box(Modifier.weight(part.second.toFloat()).height(12.dp).clip(pill).background(stageColor(part.first)))
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                parts.forEach { part ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(8.dp).clip(CircleShape).background(stageColor(part.first)))
                                        Spacer(Modifier.width(5.dp))
                                        Text(stageName(part.first), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    },
                ) {
                    HeroNumber("${asleep / 60}", "h")
                    HeroNumber("${asleep % 60}", "m")
                }
            }
            if (parts.isNotEmpty()) {
                val total = night.stages.sumOf { it.minutes }.coerceAtLeast(1)
                val deep = night.minutesOf(SleepNight.STAGE_DEEP)
                val rem = night.minutesOf(SleepNight.STAGE_REM)
                val light = night.minutesOf(SleepNight.STAGE_LIGHT)
                val awake = night.minutesOf(SleepNight.STAGE_AWAKE)
                item { Tiles(Tile("Deep", fmtMinutes(deep)), Tile("REM", fmtMinutes(rem)), Tile("Awake", fmtMinutes(awake))) }
                item {
                    Panel(Modifier.fillMaxWidth()) {
                        PanelTitle("Share of the night", Violet)
                        Spacer(Modifier.height(8.dp))
                        fun tag(pct: Int, lo: Int, hi: Int) = when {
                            pct < lo -> "low"
                            pct > hi -> "high"
                            else -> "typical"
                        }
                        val lp = 100 * light / total
                        val rp = 100 * rem / total
                        val deepPct = 100 * deep / total
                        ShareRow("Light", light.toFloat() / total, LightColor, "$lp% · ${tag(lp, 45, 65)}")
                        if (rem > 0) ShareRow("REM", rem.toFloat() / total, RemColor, "$rp% · ${tag(rp, 15, 30)}")
                        ShareRow("Deep", deep.toFloat() / total, DeepColor, "$deepPct% · ${tag(deepPct, 13, 23)}")
                    }
                }
            } else {
                item { Note("The ring did not send sleep stages for this night, only the start and end times.") }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Full night chart", Violet, Modifier.weight(1f)) { openDetail(Metric.SLEEP, today) }
                    PillButton("Tonight's plan", Violet, Modifier.weight(1f), filled = true) { openTab(1) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- B · Tonight's plan

private data class PlanData(
    val nights: List<Pair<LocalDate, Int?>>,
    val debt: Int,
    val usualWake: Int?,
    val efficiency: Double,
)

private fun loadPlan(app: App, today: LocalDate): PlanData {
    val nights = (6 downTo 0).map { i ->
        val d = today.minusDays(i.toLong())
        d to app.db.sleepFor(d)?.let { Scores.asleepMinutes(it) }
    }
    val recorded = nights.mapNotNull { it.second }
    val debt = recorded.sumOf { SLEEP_NEED - it }.coerceIn(0, 600)
    val recent = (0..13).mapNotNull { app.db.sleepFor(today.minusDays(it.toLong())) }
    val zone = ZoneId.systemDefault()
    val wakes = recent.map { Instant.ofEpochMilli(it.end).atZone(zone).toLocalTime().let { t -> t.hour * 60 + t.minute } }
    val usualWake = if (wakes.isEmpty()) null else (wakes.average() / 5).roundToInt() * 5
    val effs = recent.filter { it.stages.isNotEmpty() && it.end > it.start }
        .map { it.asleepMinutes.toDouble() / ((it.end - it.start) / 60000.0) }
    val eff = if (effs.isEmpty()) 0.92 else effs.average().coerceIn(0.85, 0.97)
    return PlanData(nights, debt, usualWake, eff)
}

@Composable
private fun NeedBars(values: List<Int?>, need: Int) {
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        val top = maxOf(need * 1.2f, (values.filterNotNull().maxOrNull() ?: 0).toFloat())
        val gap = 10.dp.toPx()
        val w = (size.width - gap * (values.size - 1)) / values.size
        values.forEachIndexed { i, v ->
            val x = i * (w + gap)
            if (v == null) {
                val h = 4.dp.toPx()
                drawRoundRect(Grid, Offset(x, size.height - h), Size(w, h), CornerRadius(h / 2))
            } else {
                val h = size.height * v / top
                drawRoundRect(
                    if (v >= need) Violet else Violet.copy(alpha = 0.45f),
                    Offset(x, size.height - h), Size(w, h), CornerRadius(6.dp.toPx()),
                )
            }
        }
        val y = size.height - size.height * need / top
        drawLine(
            TextMain.copy(alpha = 0.8f), Offset(0f, y), Offset(size.width, y),
            strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
        )
    }
}

@Composable
fun TonightScreen(app: App) {
    val ctx = LocalContext.current
    val ver by app.db.version.collectAsState()
    val today = LocalDate.now()
    val data by produceState<PlanData?>(null, ver) { value = withContext(Dispatchers.IO) { loadPlan(app, today) } }
    var wakeOverride by remember { mutableIntStateOf(app.prefs.wakeMinutes) }
    var reminder by remember { mutableStateOf<String?>(null) }

    SleepPage {
        item { SleepHeader("Tonight's plan", "When to go to bed, from the sleep you have missed") }
        val d = data
        if (d == null) {
            item { Loading() }
        } else {
            val wake = if (wakeOverride >= 0) wakeOverride else d.usualWake ?: (6 * 60 + 30)
            val extra = (d.debt / 2).coerceAtMost(60) / 15 * 15
            val inBed = (((SLEEP_NEED + extra) / d.efficiency) / 5).roundToInt() * 5
            val bed = ((wake - inBed) % 1440 + 1440) % 1440
            val windDown = ((bed - 30) % 1440 + 1440) % 1440

            item {
                Hero(caption = "Be in bed by", footline = "Up at ${clock(wake)} · ${fmtMinutes(inBed)} in bed") {
                    HeroNumber(clock(bed), "")
                }
            }
            item {
                Tiles(
                    Tile("Sleep debt", if (d.debt == 0) "None" else fmtMinutes(d.debt), if (d.debt >= 60) Amber else TextMain),
                    Tile("Your need", fmtMinutes(SLEEP_NEED)),
                    Tile("Wind down", clock(windDown)),
                )
            }
            item {
                Panel(Modifier.fillMaxWidth()) {
                    PanelTitle("Last 7 nights", Violet, trailing = "dashed line = your need")
                    Spacer(Modifier.height(12.dp))
                    NeedBars(d.nights.map { it.second }, SLEEP_NEED)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        d.nights.forEach { (day, _) ->
                            Text(
                                day.format(dayFmt).take(1), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    val recorded = d.nights.mapNotNull { it.second }
                    val met = recorded.count { it >= SLEEP_NEED }
                    Note(
                        when {
                            recorded.isEmpty() -> "No nights recorded this week yet. The plan uses a standard 7½ hour need until there is data."
                            d.debt == 0 -> "You met your need on $met of ${recorded.size} nights and carry no sleep debt."
                            else -> "You met your need on $met of ${recorded.size} nights. Tonight adds ${fmtMinutes(extra)} to start paying back the ${fmtMinutes(d.debt)} you are short."
                        }
                    )
                }
            }
            item {
                Panel(Modifier.fillMaxWidth()) {
                    PanelTitle("Wake-up time", Violet, trailing = if (wakeOverride >= 0) "set by you" else "your usual")
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("− 15 min", Violet, Modifier.weight(1f)) {
                            val n = ((wake - 15) % 1440 + 1440) % 1440
                            wakeOverride = n; app.prefs.wakeMinutes = n; reminder = null
                        }
                        Text(clock(wake), color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                        PillButton("+ 15 min", Violet, Modifier.weight(1f)) {
                            val n = (wake + 15) % 1440
                            wakeOverride = n; app.prefs.wakeMinutes = n; reminder = null
                        }
                    }
                    if (wakeOverride >= 0) {
                        Spacer(Modifier.height(10.dp))
                        PillButton("Use my usual wake time", Violet, Modifier.fillMaxWidth()) {
                            wakeOverride = -1; app.prefs.wakeMinutes = -1; reminder = null
                        }
                    }
                }
            }
            item {
                val schedule = {
                    val now = LocalTime.now()
                    val nowMin = now.hour * 60 + now.minute
                    val wait = ((windDown - nowMin) % 1440 + 1440) % 1440
                    BedtimeWorker.schedule(ctx, wait, clock(bed))
                    reminder = "Reminder set for ${clock(windDown)}" + if (windDown <= nowMin) " tomorrow." else "."
                }
                val asker = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
                    if (ok) schedule() else reminder = "Allow notifications for Halo in your phone settings to get the reminder."
                }
                PillButton("Remind me at ${clock(windDown)}", Violet, Modifier.fillMaxWidth(), filled = true) {
                    val allowed = Build.VERSION.SDK_INT < 33 ||
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    if (allowed) schedule() else asker.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                val r = reminder
                if (r != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(r, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- C · Sleep rhythm

private data class RhythmRow(val day: LocalDate, val bed: Int?, val wake: Int?)

private data class RhythmData(
    val rows: List<RhythmRow>,
    val count: Int,
    val meanBed: Int?,
    val meanWake: Int?,
    val spread: Int?,
    val weekendShift: Int?,
)

private fun loadRhythm(app: App, today: LocalDate): RhythmData {
    val zone = ZoneId.systemDefault()
    val all = (0..13).mapNotNull { i -> app.db.sleepFor(today.minusDays(i.toLong())) }
    val beds = all.map { eve(it.start) }
    val wakes = all.map { eve(it.end) }
    val meanBed = if (beds.isEmpty()) null else beds.average().roundToInt()
    val meanWake = if (wakes.isEmpty()) null else wakes.average().roundToInt()
    val spread = if (beds.size < 3) null else {
        val m = beds.average()
        sqrt(beds.sumOf { (it - m) * (it - m) } / beds.size).roundToInt()
    }
    // The evening a night began on: Friday and Saturday evenings count as the weekend.
    fun isWeekend(n: SleepNight): Boolean {
        val evening = Instant.ofEpochMilli(n.start - 6 * 3600_000L).atZone(zone).dayOfWeek
        return evening == DayOfWeek.FRIDAY || evening == DayOfWeek.SATURDAY
    }
    val weekend = all.filter { isWeekend(it) }.map { eve(it.start) }
    val weekday = all.filterNot { isWeekend(it) }.map { eve(it.start) }
    val shift = if (weekend.isEmpty() || weekday.isEmpty()) null else (weekend.average() - weekday.average()).roundToInt()
    val rows = (6 downTo 0).map { i ->
        val d = today.minusDays(i.toLong())
        val n = app.db.sleepFor(d)
        RhythmRow(d, n?.let { eve(it.start) }, n?.let { eve(it.end) })
    }
    return RhythmData(rows, all.size, meanBed, meanWake, spread, shift)
}

@Composable
fun RhythmScreen(app: App) {
    val ver by app.db.version.collectAsState()
    val st by app.ring.status.collectAsState()
    val today = LocalDate.now()
    val data by produceState<RhythmData?>(null, ver) { value = withContext(Dispatchers.IO) { loadRhythm(app, today) } }

    SleepPage {
        item { SleepHeader("Sleep rhythm", "How regular your bed and wake times are") }
        val d = data
        val spread = d?.spread
        val meanBed = d?.meanBed
        if (d == null) {
            item { Loading() }
        } else if (spread == null || meanBed == null) {
            item { NoNight(app, st.busy, "Rhythm needs at least 3 recorded nights. You have ${d.count} so far.") }
        } else {
            item {
                Hero(
                    caption = "Your bedtime moves by",
                    footline = when {
                        spread < 30 -> "Very regular"
                        spread < 60 -> "Fairly regular · aim for under 30"
                        else -> "Irregular · aim for under 30"
                    },
                ) { HeroNumber("±$spread", "min") }
            }
            item {
                val shift = d.weekendShift
                Tiles(
                    Tile("Usual bedtime", fromEve(meanBed)),
                    Tile("Usual wake", d.meanWake?.let { fromEve(it) } ?: "--"),
                    Tile(
                        "Weekend shift",
                        when {
                            shift == null -> "--"
                            abs(shift) < 10 -> "None"
                            shift > 0 -> "+" + fmtMinutes(shift)
                            else -> "−" + fmtMinutes(-shift)
                        },
                        if (shift != null && abs(shift) >= 60) Amber else TextMain,
                    ),
                )
            }
            item {
                val shown = d.rows.filter { it.bed != null && it.wake != null }
                Panel(Modifier.fillMaxWidth()) {
                    PanelTitle("In bed, last 7 nights", Violet, trailing = "by wake-up day")
                    Spacer(Modifier.height(10.dp))
                    if (shown.isEmpty()) {
                        Note("No nights recorded in the last week.")
                    } else {
                        val lo = (shown.minOf { it.bed ?: 0 } - 20).floorDiv(60) * 60
                        val hi = ((shown.maxOf { it.wake ?: 0 } + 20) + 59).floorDiv(60) * 60
                        val span = (hi - lo).coerceAtLeast(60).toFloat()
                        d.rows.forEach { r ->
                            val bed = r.bed
                            val wake = r.wake
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    r.day.format(dayFmt), color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.width(40.dp),
                                )
                                Row(Modifier.weight(1f).height(12.dp).clip(pill).background(Grid.copy(alpha = 0.6f))) {
                                    if (bed != null && wake != null && wake > bed) {
                                        val before = ((bed - lo) / span).coerceAtLeast(0.001f)
                                        val length = ((wake - bed) / span).coerceAtLeast(0.001f)
                                        val after = ((hi - wake) / span).coerceAtLeast(0.001f)
                                        val late = abs(bed - meanBed) > 60
                                        Spacer(Modifier.weight(before))
                                        Box(
                                            Modifier.weight(length).fillMaxHeight().clip(pill)
                                                .background(if (late) Violet.copy(alpha = 0.45f) else Violet)
                                        )
                                        Spacer(Modifier.weight(after))
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(start = 40.dp, top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            listOf(lo, (lo + hi) / 2, hi).forEach {
                                Text(fromEve(it), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        val off = shown.filter { abs((it.bed ?: meanBed) - meanBed) > 60 }.map { it.day.format(dayFmt) }
                        Note(
                            if (off.isEmpty()) "Every night started within an hour of your usual bedtime."
                            else "Dim bars started more than an hour off your usual bedtime: ${off.joinToString(", ")}."
                        )
                    }
                }
            }
            item { Note("Going to bed and waking at similar times each day helps sleep quality as much as the hours themselves.") }
        }
    }
}

// ---------------------------------------------------------------- D · Overnight recovery

private data class RecoveryData(
    val night: SleepNight?,
    val hr: List<Point>,
    val avgHr: Double?,
    val hrv: Double?,
    val spo2Low: Int?,
    val spo2Avg: Double?,
    val usualHr: Double?,
    val usualHrv: Double?,
    val usualSpo2: Double?,
)

private fun nightHr(app: App, n: SleepNight): Double? =
    app.db.series(Db.HR, n.start, n.end).takeIf { it.isNotEmpty() }?.map { it.value }?.average()

private fun nightHrv(app: App, n: SleepNight): Double? =
    app.db.series(Db.HRV, n.start, n.end).takeIf { it.isNotEmpty() }?.map { it.value }?.average()

private fun nightSpo2(app: App, n: SleepNight): Double? =
    app.db.spo2(n.start, n.end).filter { it.value > 0 }.takeIf { it.isNotEmpty() }?.map { it.value }?.average()

private fun loadRecovery(app: App, today: LocalDate): RecoveryData {
    val night = app.db.sleepFor(today) ?: return RecoveryData(null, emptyList(), null, null, null, null, null, null, null)
    val hr = app.db.series(Db.HR, night.start, night.end)
    val prev = (1..7).mapNotNull { app.db.sleepFor(today.minusDays(it.toLong())) }
    fun usual(f: (SleepNight) -> Double?): Double? = prev.mapNotNull(f).takeIf { it.isNotEmpty() }?.average()
    val lows = app.db.spo2(night.start, night.end).map { it.min }.filter { it > 0 }
    return RecoveryData(
        night, hr, nightHr(app, night), nightHrv(app, night), lows.minOrNull(), nightSpo2(app, night),
        usual { nightHr(app, it) }, usual { nightHrv(app, it) }, usual { nightSpo2(app, it) },
    )
}

@Composable
private fun NightHrChart(pts: List<Point>, start: Long, end: Long, low: Point) {
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        val lo = pts.minOf { it.value } - 3f
        val hi = pts.maxOf { it.value } + 3f
        val span = (end - start).coerceAtLeast(1L).toFloat()
        fun x(ts: Long): Float = ((ts - start) / span * size.width).coerceIn(0f, size.width)
        fun y(v: Int): Float = size.height - (v - lo) / (hi - lo) * size.height
        for (i in 1..3) {
            val gy = size.height * i / 4f
            drawLine(Grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
        }
        val path = Path()
        pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.ts), y(p.value)) else path.lineTo(x(p.ts), y(p.value)) }
        drawPath(path, Violet, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val c = Offset(x(low.ts), y(low.value))
        drawCircle(Bg, radius = 6.dp.toPx(), center = c)
        drawCircle(TextMain, radius = 6.dp.toPx(), center = c, style = Stroke(2.5.dp.toPx()))
    }
}

@Composable
private fun UsualRow(label: String, now: Double?, usual: Double?, unit: String, lowerIsBetter: Boolean) {
    val (text, color) = when {
        now == null -> "No data" to Muted
        usual == null -> "Building your usual" to Muted
        else -> {
            val diff = (now - usual).roundToInt()
            when {
                diff == 0 -> "Same as usual" to TextMain
                diff < 0 -> "${-diff}$unit below usual" to if (lowerIsBetter) Teal else Amber
                else -> "$diff$unit above usual" to if (lowerIsBetter) Amber else Teal
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(text, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun RecoveryScreen(app: App) {
    val ver by app.db.version.collectAsState()
    val st by app.ring.status.collectAsState()
    val today = LocalDate.now()
    val data by produceState<RecoveryData?>(null, ver) { value = withContext(Dispatchers.IO) { loadRecovery(app, today) } }

    SleepPage {
        item { SleepHeader("Overnight recovery", "What your body did while you slept") }
        val d = data
        val night = d?.night
        if (d == null) {
            item { Loading() }
        } else if (night == null) {
            item { NoNight(app, st.busy) }
        } else {
            val low = d.hr.minByOrNull { it.value }
            if (low == null) {
                item {
                    Panel(Modifier.fillMaxWidth()) {
                        Text("No heart-rate readings during last night's sleep.", color = TextMain, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("Turn on automatic heart rate in the Ring tab so the ring measures through the night.", color = Muted, fontSize = 13.sp)
                    }
                }
            } else {
                item {
                    Hero(
                        caption = "Lowest heart rate · ${hhmm(low.ts)}",
                        footline = "Asleep ${hhmm(night.start)} – ${hhmm(night.end)}",
                        extra = {
                            if (d.hr.size >= 2) {
                                Spacer(Modifier.height(14.dp))
                                NightHrChart(d.hr, night.start, night.end, low)
                                Spacer(Modifier.height(6.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    listOf(night.start, (night.start + night.end) / 2, night.end).forEach { t ->
                                        Text(hhmm(t), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        },
                    ) { HeroNumber("${low.value}", "bpm") }
                }
            }
            item {
                Tiles(
                    Tile("Average HR", d.avgHr?.let { "${it.roundToInt()}" } ?: "--"),
                    Tile("HRV", d.hrv?.let { "${it.roundToInt()} ms" } ?: "--"),
                    Tile("Lowest SpO2", d.spo2Low?.let { "$it%" } ?: "--", if ((d.spo2Low ?: 100) < 90) Amber else TextMain),
                )
            }
            item {
                Panel(Modifier.fillMaxWidth()) {
                    PanelTitle("Against your usual", Violet, trailing = "last 7 nights")
                    Spacer(Modifier.height(4.dp))
                    UsualRow("Sleeping heart rate", d.avgHr, d.usualHr, " bpm", lowerIsBetter = true)
                    UsualRow("HRV", d.hrv, d.usualHrv, " ms", lowerIsBetter = false)
                    UsualRow("Blood oxygen", d.spo2Avg, d.usualSpo2, "%", lowerIsBetter = false)
                    if (low != null && night.end > night.start) {
                        Spacer(Modifier.height(6.dp))
                        val pos = (low.ts - night.start).toDouble() / (night.end - night.start)
                        Note(
                            if (pos < 0.5) "Your heart rate reached its lowest before the middle of the night, which usually goes with a well-recovered morning."
                            else "Your heart rate reached its lowest late in the night. A late meal, alcohol or a late workout can cause that."
                        )
                    }
                }
            }
            item {
                Text(
                    "Halo's own estimates, not medical advice.",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}
