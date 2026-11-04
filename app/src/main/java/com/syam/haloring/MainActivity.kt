package com.syam.haloring

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syam.haloring.ble.RingManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.syam.haloring.ui.Border
import com.syam.haloring.ui.CardHi
import com.syam.haloring.ui.RingScreen
import com.syam.haloring.ui.TrendsScreen
import com.syam.haloring.ui.VitalsScreen
import com.syam.haloring.ui.DetailScreen
import com.syam.haloring.ui.Metric
import java.time.LocalDate
import com.syam.haloring.ui.Bg
import com.syam.haloring.ui.CardColor
import com.syam.haloring.ui.Muted
import com.syam.haloring.ui.RingTheme
import com.syam.haloring.ui.Teal
import com.syam.haloring.ui.TextMain
import com.syam.haloring.ui.TodayScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RingTheme { Root() } }
    }
}

@Composable
private fun Root() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as App
    var granted by remember { mutableStateOf(hasBlePermissions(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasBlePermissions(ctx)
    }
    var address by remember { mutableStateOf(app.prefs.address) }

    Surface(Modifier.fillMaxSize(), color = Bg) {
        when {
            !granted -> PermissionScreen { launcher.launch(blePermissions()) }
            address == null -> ConnectScreen(app) { found ->
                app.ring.stopScan()
                app.prefs.address = found.address
                app.prefs.name = found.name
                app.prefs.lastSync = 0L
                address = found.address
                SyncWorker.schedule(app, app.prefs.autoSync)
                app.scope.launch { app.ring.sync() }
            }
            else -> MainScreen(app, onForget = {
                app.prefs.address = null
                SyncWorker.schedule(app, false)
                address = null
            })
        }
    }
}

@Composable
private fun PermissionScreen(onAsk: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Bluetooth, null, tint = Teal, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(20.dp))
        Text("Let the app talk to your ring", color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(
            "Android needs your OK to use Bluetooth to find and read your ring. " +
                "Your data stays on this phone. Nothing is sent anywhere.",
            color = Muted, fontSize = 16.sp,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onAsk, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Allow Bluetooth", fontSize = 16.sp) }
    }
}

@Composable
private fun ConnectScreen(app: App, onPick: (RingManager.Found) -> Unit) {
    val results by app.ring.scanResults.collectAsState()
    val scanning by app.ring.scanning.collectAsState()
    LaunchedEffect(Unit) { app.ring.startScan() }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Connect your ring", color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Put the ring on its charger next to the phone. Close the Halo Ravoz app first, " +
                "because the ring hides while another app is connected.",
            color = Muted, fontSize = 15.sp,
        )
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (scanning) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Teal)
                Spacer(Modifier.width(10.dp))
                Text("Looking for rings…", color = Muted)
            } else {
                Button(onClick = { app.ring.startScan() }) {
                    Icon(Icons.Rounded.Search, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Search again")
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(results, key = { it.address }) { f ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(if (f.likely) Teal.copy(alpha = 0.14f) else CardColor)
                        .clickable { onPick(f) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(if (f.likely) Teal else Muted))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Text(if (f.likely) "This looks like your ring. Tap to connect" else f.address, color = Muted, fontSize = 13.sp)
                    }
                    Text("${f.rssi} dBm", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

private data class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
private fun MainScreen(app: App, onForget: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detail by rememberSaveable { mutableStateOf<Metric?>(null) }
    var detailDate by rememberSaveable { mutableStateOf(LocalDate.now()) }
    val openDetail: (Metric, LocalDate) -> Unit = { m, d -> detailDate = d; detail = m }
    val tabs = listOf(
        Tab("Today", Icons.Rounded.Home),
        Tab("Vitals", Icons.Rounded.MonitorHeart),
        Tab("Trends", Icons.Rounded.BarChart),
        Tab("Ring", Icons.Rounded.Tune),
    )
    LaunchedEffect(Unit) {
        if (System.currentTimeMillis() - app.prefs.lastSync > 5 * 60 * 1000L) app.scope.launch { app.ring.sync() }
    }
    Box(Modifier.fillMaxSize().background(Bg)) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            val d = detail
            if (d != null) {
                DetailScreen(app, d, detailDate) { detail = null }
            } else when (tab) {
                0 -> TodayScreen(app, { m -> openDetail(m, LocalDate.now()) }, { tab = it })
                1 -> VitalsScreen(app, openDetail)
                2 -> TrendsScreen(app, openDetail)
                else -> RingScreen(app, onForget)
            }
        }
        // bottom fade so content slides under the floating bar
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(130.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Bg.copy(alpha = 0.92f), Bg)))
        )
        FloatingBar(
            tabs, tab,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
        ) { tab = it; detail = null }
    }
}

@Composable
private fun FloatingBar(tabs: List<Tab>, selected: Int, modifier: Modifier, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.fillMaxWidth().height(66.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(CardHi, CardColor)))
            .border(1.dp, Border, shape).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { i, t ->
            val sel = i == selected
            val bg by animateColorAsState(if (sel) Teal.copy(alpha = 0.16f) else Color.Transparent, label = "tab")
            Row(
                Modifier.weight(if (sel) 1.5f else 1f).fillMaxHeight().clip(shape).background(bg)
                    .clickable(remember { MutableInteractionSource() }, null) { onSelect(i) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(t.icon, t.label, tint = if (sel) Teal else Muted, modifier = Modifier.size(22.dp))
                if (sel) {
                    Spacer(Modifier.width(6.dp))
                    Text(t.label, color = Teal, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                }
            }
        }
    }
}
