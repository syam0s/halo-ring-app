package com.syam.haloring.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syam.haloring.App
import com.syam.haloring.SyncWorker
import com.syam.haloring.ble.Cmd
import com.syam.haloring.ble.RingManager
import com.syam.haloring.data.BatteryEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal val screenPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp)

internal fun ago(ms: Long): String {
    if (ms == 0L) return "never"
    val m = ((System.currentTimeMillis() - ms) / 60000).toInt()
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 60 * 24 -> "${m / 60} h ago"
        else -> "${m / 1440} days ago"
    }
}

internal fun metricColor(m: Metric) = when (m) {
    Metric.HEART -> Coral
    Metric.SPO2 -> Sky
    Metric.HRV -> Lime
    Metric.STRESS -> Amber
    Metric.STEPS -> Orange
    Metric.SLEEP -> Violet
    Metric.READINESS -> Teal
}

// ---------------------------------------------------------------- buttons

/** Premium pill button (filled or outlined). */
@Composable
fun PillButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val c = if (enabled) color else Faint
    Box(
        modifier
            .clip(shape)
            .then(
                if (filled) Modifier.background(Brush.horizontalGradient(listOf(c, c.copy(alpha = 0.8f))))
                else Modifier.background(c.copy(alpha = 0.10f)).border(1.dp, c.copy(alpha = 0.35f), shape)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (filled) Bg else c, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun SegmentedPills(options: List<String>, selected: Int, color: Color, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(Modifier.fillMaxWidth().clip(shape).background(CardColor).border(1.dp, Border, shape).padding(4.dp)) {
        options.forEachIndexed { i, o ->
            val sel = i == selected
            Box(
                Modifier.weight(1f).clip(shape)
                    .background(if (sel) color.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) { Text(o, color = if (sel) color else Muted, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        }
    }
}

// ---------------------------------------------------------------- live measurement

@Composable
internal fun SpotCheckPanel(app: App) {
    val st by app.ring.status.collectAsState()
    val live by app.ring.live.collectAsState()
    Panel(Modifier.fillMaxWidth()) {
        PanelTitle("Spot check", Coral, trailing = "about 30 seconds")
        Spacer(Modifier.height(6.dp))
        Text("Take a reading right now. Sit still with the ring snug on your finger.", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("♥  Heart rate", Coral, Modifier.weight(1f), enabled = !st.busy) {
                app.scope.launch(Dispatchers.Default) { app.ring.measure(Cmd.LIVE_HR) }
            }
            PillButton("◉  Blood oxygen", Sky, Modifier.weight(1f), enabled = !st.busy) {
                app.scope.launch(Dispatchers.Default) { app.ring.measure(Cmd.LIVE_SPO2) }
            }
        }
        live?.let { l ->
            val isHr = l.type == Cmd.LIVE_HR
            val color = if (isHr) Coral else Sky
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(color.copy(alpha = 0.09f)).padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (isHr) "LIVE HEART RATE" else "LIVE BLOOD OXYGEN", color = color, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        BigValue(l.value?.toString() ?: "--", if (isHr) "bpm" else "%", size = 46)
                    }
                    if (l.running) CircularProgressIndicator(Modifier.size(30.dp), color = color, strokeWidth = 3.dp)
                }
                Text(l.note, color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { if (l.running) app.ring.cancelLive() else app.ring.clearLive() }) {
                    Text(if (l.running) "Stop" else "Close", color = color, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- ring illustration

@Composable
private fun RingArt(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        // glow
        drawCircle(Brush.radialGradient(listOf(Teal.copy(alpha = 0.25f), Color.Transparent), Offset(cx, cy), w / 2f), w / 2f, Offset(cx, cy))
        val ow = w * 0.62f
        val oh = h * 0.40f
        val thick = h * 0.13f
        // band (back)
        drawOval(
            Brush.linearGradient(listOf(Color(0xFF2A2E37), Color(0xFF0E1014)), Offset(cx - ow / 2, cy), Offset(cx + ow / 2, cy)),
            Offset(cx - ow / 2, cy - oh / 2 - thick / 2), Size(ow, oh), style = Stroke(thick),
        )
        // band (front, metallic)
        drawOval(
            Brush.linearGradient(
                listOf(Color(0xFF6B717E), Color(0xFFE9ECF2), Color(0xFF8D93A0), Color(0xFF2B2F38)),
                Offset(cx - ow / 2, cy), Offset(cx + ow / 2, cy),
            ),
            Offset(cx - ow / 2, cy - oh / 2 + thick / 2), Size(ow, oh), style = Stroke(thick * 0.9f),
        )
        // sensor glints
        drawCircle(Lime.copy(alpha = 0.9f), h * 0.018f, Offset(cx - ow * 0.08f, cy + oh / 2 + thick * 0.45f))
        drawCircle(Coral.copy(alpha = 0.9f), h * 0.018f, Offset(cx + ow * 0.08f, cy + oh / 2 + thick * 0.45f))
    }
}

// ---------------------------------------------------------------- RING tab

@Composable
fun RingScreen(app: App, onForget: () -> Unit) {
    val ctx = LocalContext.current
    val st by app.ring.status.collectAsState()
    val ringSettings by app.ring.settings.collectAsState()
    val ver by app.db.version.collectAsState()
    val current = ringSettings ?: RingManager.RingSettings()
    val est = remember(ver, ringSettings, st.battery, st.charging) { BatteryEstimator.estimate(app.prefs, app.db, current) }
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

    Box {
        GlowBackdrop(Teal)
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    RingArt(Modifier.size(200.dp))
                    Text(app.prefs.name ?: "Halo Ring", color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(
                            when (st.conn) {
                                RingManager.Conn.Connected -> "Connected"
                                RingManager.Conn.Connecting -> "Connecting…"
                                RingManager.Conn.Disconnected -> if (st.lastSync > 0) "Standby" else "Not synced yet"
                            },
                            if (st.conn == RingManager.Conn.Disconnected) Muted else Teal,
                        )
                        est.level?.let { Chip("$it%" + if (est.charging) " · charging" else "", if (it <= 15) Coral else Lime) }
                        Chip("Synced ${ago(st.lastSync)}", Muted)
                    }
                    if (st.busy) {
                        Spacer(Modifier.height(8.dp))
                        Text("${st.step}…", color = Teal, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Sync now", Teal, Modifier.weight(1f), filled = true, enabled = !st.busy) {
                            app.scope.launch { app.ring.sync() }
                        }
                        PillButton("Find my ring", Teal, Modifier.weight(1f), enabled = !st.busy) {
                            app.scope.launch { app.ring.findRing() }
                        }
                    }
                }
            }

            item { BatteryPanel(est, current) }

            item {
                Panel(Modifier.fillMaxWidth()) {
                    PanelTitle("Automatic measurements", Coral)
                    Spacer(Modifier.height(6.dp))
                    val s = ringSettings
                    if (s == null) {
                        Text("Settings load during a sync. Tap below to read them now.", color = Muted)
                        Spacer(Modifier.height(10.dp))
                        PillButton("Read ring settings", Coral, enabled = !st.busy) { app.scope.launch { app.ring.loadSettings() } }
                    } else {
                        fun save(n: RingManager.RingSettings) { app.scope.launch { app.ring.applySettings(n) } }
                        ToggleRow("Heart rate", "Automatic readings day and night", s.hrEnabled, !st.busy) { save(s.copy(hrEnabled = it)) }
                        if (s.hrEnabled) {
                            Text("Measure every", color = Muted, fontSize = 13.sp)
                            Spacer(Modifier.height(6.dp))
                            val opts = listOf(5, 10, 15, 30, 60)
                            SegmentedPills(opts.map { "${it}m" }, opts.indexOf(s.hrInterval), Coral) { i ->
                                if (!st.busy) save(s.copy(hrInterval = opts[i]))
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("Shorter means more detail, but the battery runs down faster.", color = Faint, fontSize = 12.sp)
                            Spacer(Modifier.height(6.dp))
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
                    Spacer(Modifier.height(6.dp))
                    Text("Daily step goal", color = TextMain, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    val goals = listOf(6000, 8000, 10000, 12000)
                    SegmentedPills(goals.map { "${it / 1000}k" }, goals.indexOf(goal), Orange) { i ->
                        goal = goals[i]; app.prefs.stepGoal = goals[i]; app.db.bump()
                    }
                    Spacer(Modifier.height(14.dp))
                    PillButton("Export all data (CSV)", Sky, Modifier.fillMaxWidth()) {
                        exporter.launch("ring-data-${LocalDate.now()}.csv")
                    }
                    exported?.let { Spacer(Modifier.height(6.dp)); Text(it, color = Muted, fontSize = 12.sp) }
                }
            }

            item {
                Panel(Modifier.fillMaxWidth()) {
                    PanelTitle("Ring info", Muted)
                    Spacer(Modifier.height(10.dp))
                    StatRow("Firmware" to (app.prefs.firmware ?: "--"), "Hardware" to (app.prefs.hardware ?: "--"))
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Turn ring off", Coral, Modifier.weight(1f), enabled = !st.busy) { confirmOff = true }
                        PillButton("Forget ring", Coral, Modifier.weight(1f)) { confirmForget = true }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Forgetting the ring keeps all your saved data.", color = Faint, fontSize = 12.sp)
                }
            }
        }
    }

    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            containerColor = CardHi,
            title = { Text("Turn the ring off?") },
            text = { Text("It stops tracking until you put it back on the charger.") },
            confirmButton = { TextButton(onClick = { confirmOff = false; app.scope.launch { app.ring.powerOff() } }) { Text("Turn off", color = Coral) } },
            dismissButton = { TextButton(onClick = { confirmOff = false }) { Text("Cancel", color = Muted) } },
        )
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            containerColor = CardHi,
            title = { Text("Forget this ring?") },
            text = { Text("You'll go back to the connect screen. Your saved readings stay on the phone.") },
            confirmButton = { TextButton(onClick = { confirmForget = false; onForget() }) { Text("Forget", color = Coral) } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel", color = Muted) } },
        )
    }
}

@Composable
private fun BatteryPanel(est: BatteryEstimator.Estimate, s: RingManager.RingSettings) {
    Panel(Modifier.fillMaxWidth()) {
        PanelTitle("Battery", Lime, trailing = if (est.learned) "learned from your ring" else "rough estimate")
        Spacer(Modifier.height(12.dp))
        val level = est.level
        if (level == null) {
            Text("Sync once to read the battery.", color = Muted)
            return@Panel
        }
        val c = if (level <= 15 && !est.charging) Coral else Lime
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreDial(level, listOf(c.copy(alpha = 0.7f), c), 96.dp, 8.dp, 26, caption = "%")
            Spacer(Modifier.width(18.dp))
            Column {
                Text(
                    when {
                        est.charging -> "Charging"
                        est.hoursLeft != null -> BatteryEstimator.formatHours(est.hoursLeft)
                        else -> "--"
                    },
                    color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
                )
                Text(if (est.charging) "on the charger now" else "left on this charge", color = Muted, fontSize = 13.sp)
                if (!est.charging && est.hoursLeft != null) {
                    val emptyAt = java.time.LocalDateTime.now().plusMinutes((est.hoursLeft * 60).toLong())
                    Spacer(Modifier.height(4.dp))
                    Text("Runs out around " + emptyAt.format(DateTimeFormatter.ofPattern("EEE d MMM, h a")), color = c, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("A full charge lasts about ${BatteryEstimator.formatDays(est.fullChargeDays)} with your current settings.", color = Muted, fontSize = 13.sp)
        if (s.hrEnabled) {
            Spacer(Modifier.height(14.dp))
            Text("FULL CHARGE BY HEART-RATE INTERVAL", color = Faint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            val factor = est.fullChargeDays * BatteryEstimator.modelDrainPerDay(s) / 100.0
            Row(Modifier.fillMaxWidth()) {
                listOf(5, 10, 15, 30, 60).forEach { m ->
                    val days = 100.0 / (BatteryEstimator.modelDrainPerDay(s.copy(hrInterval = m)) / factor)
                    val selected = s.hrInterval == m
                    Column(
                        Modifier.weight(1f).padding(horizontal = 3.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (selected) Lime.copy(alpha = 0.16f) else CardColor)
                            .border(1.dp, if (selected) Lime.copy(alpha = 0.4f) else Border, RoundedCornerShape(14.dp))
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("${m}m", color = if (selected) Lime else Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("%.1fd".format(days), color = TextMain, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    }
                }
            }
        }
        if (!est.learned) {
            Spacer(Modifier.height(10.dp))
            Text("Based on typical use for now. After a day or two of syncs it learns your ring's real drain.", color = Faint, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Teal, checkedThumbColor = Bg,
                uncheckedTrackColor = CardHi, uncheckedThumbColor = Muted, uncheckedBorderColor = Border,
            ),
        )
    }
}
