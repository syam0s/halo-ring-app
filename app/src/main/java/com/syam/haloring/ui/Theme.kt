package com.syam.haloring.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

val Bg = Color(0xFF0B0E13)
val CardColor = Color(0xFF151A22)
val CardHi = Color(0xFF1D232D)
val TextMain = Color(0xFFEDF1F7)
val Muted = Color(0xFF8A93A3)
val Grid = Color(0xFF262D38)

val Teal = Color(0xFF3DDBC4)
val Coral = Color(0xFFFF6B6B)
val Violet = Color(0xFFA48CFF)
val Sky = Color(0xFF5AB4FF)
val Indigo = Color(0xFF5662F6)
val Amber = Color(0xFFFFC857)
val Lime = Color(0xFF9BE15D)
val Rose = Color(0xFFFF8FB1)

// Sleep stage colours
val DeepColor = Indigo
val LightColor = Sky
val RemColor = Violet
val AwakeColor = Amber

@Composable
fun RingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Teal,
            onPrimary = Color(0xFF00201C),
            secondary = Coral,
            background = Bg,
            onBackground = TextMain,
            surface = CardColor,
            onSurface = TextMain,
            surfaceVariant = CardHi,
            onSurfaceVariant = Muted,
            surfaceContainer = CardColor,
            outline = Grid,
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, color = TextMain),
            titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = TextMain),
        ),
        content = content,
    )
}

/** A rounded card used everywhere. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .clip(shape)
            .background(CardColor)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun PanelTitle(text: String, color: Color, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Spacer(Modifier.weight(1f))
        if (trailing != null) Text(trailing, color = Muted, fontSize = 12.sp)
    }
}

@Composable
fun BigValue(value: String, unit: String = "", color: Color = TextMain, size: Int = 34) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, color = color, fontSize = size.sp, fontWeight = FontWeight.Bold)
        if (unit.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            Text(unit, color = Muted, fontSize = (size * 0.45f).sp, modifier = Modifier.padding(bottom = (size * 0.12f).dp))
        }
    }
}

@Composable
fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun StatRow(vararg items: Pair<String, String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        items.forEach { (l, v) -> MiniStat(l, v, Modifier.weight(1f)) }
    }
}

fun dayLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(DateTimeFormatter.ofPattern("EEE, d MMM"))
    }
}

@Composable
fun DayPicker(date: LocalDate, onChange: (LocalDate) -> Unit, label: (LocalDate) -> String = ::dayLabel) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardColor).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onChange(date.minusDays(1)) }) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous day", tint = TextMain)
        }
        Text(label(date), color = TextMain, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        val canNext = date.isBefore(LocalDate.now())
        IconButton(onClick = { if (canNext) onChange(date.plusDays(1)) }, enabled = canNext) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next day", tint = if (canNext) TextMain else Grid)
        }
    }
}

fun fmtMinutes(m: Int): String = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"

fun fmtNumber(n: Int): String = "%,d".format(n)
