package com.syam.haloring.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syam.haloring.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------- palette ("Obsidian")

val Bg = Color(0xFF07080B)
val CardColor = Color(0xFF101217)
val CardHi = Color(0xFF181B22)
val Border = Color(0x14FFFFFF)
val TextMain = Color(0xFFF3F4F7)
val Muted = Color(0xFF8B90A0)
val Faint = Color(0xFF5A5F6C)
val Grid = Color(0xFF22252D)

val Teal = Color(0xFF4FE3B5)    // readiness / optimal
val Coral = Color(0xFFFF6B6B)   // heart / attention
val Violet = Color(0xFF9C8CFF)  // sleep
val Sky = Color(0xFF5AB8FF)     // blood oxygen
val Indigo = Color(0xFF6E7BFF)
val Amber = Color(0xFFFFC25A)   // stress / fair
val Lime = Color(0xFFA8E05F)    // HRV / good
val Rose = Color(0xFFFF8FB1)
val Orange = Color(0xFFFF9F5A)  // activity

// Score pillars: two-tone gradients
val ReadinessGrad = listOf(Color(0xFF2FD6C6), Color(0xFF8CF5A0))
val SleepGrad = listOf(Color(0xFF6E7BFF), Color(0xFFC59CFF))
val ActivityGrad = listOf(Color(0xFFFF6A5C), Color(0xFFFFB45A))

// Sleep stage colours
val DeepColor = Indigo
val LightColor = Sky
val RemColor = Violet
val AwakeColor = Amber

// ---------------------------------------------------------------- type

val Manrope = FontFamily(
    Font(R.font.manrope_light, FontWeight.Light),
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)

private fun Typography.withFont(f: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = f),
    displayMedium = displayMedium.copy(fontFamily = f),
    displaySmall = displaySmall.copy(fontFamily = f),
    headlineLarge = TextStyle(fontFamily = f, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = TextMain, letterSpacing = (-0.5).sp),
    headlineMedium = headlineMedium.copy(fontFamily = f),
    headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = TextStyle(fontFamily = f, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextMain),
    titleMedium = titleMedium.copy(fontFamily = f),
    titleSmall = titleSmall.copy(fontFamily = f),
    bodyLarge = bodyLarge.copy(fontFamily = f),
    bodyMedium = bodyMedium.copy(fontFamily = f),
    bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f, fontWeight = FontWeight.SemiBold),
    labelMedium = labelMedium.copy(fontFamily = f),
    labelSmall = labelSmall.copy(fontFamily = f),
)

@Composable
fun RingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Teal,
            onPrimary = Color(0xFF00241C),
            secondary = Violet,
            background = Bg,
            onBackground = TextMain,
            surface = CardColor,
            onSurface = TextMain,
            surfaceVariant = CardHi,
            onSurfaceVariant = Muted,
            surfaceContainer = CardColor,
            surfaceContainerHigh = CardHi,
            outline = Grid,
        ),
        typography = Typography().withFont(Manrope),
        content = content,
    )
}

// ---------------------------------------------------------------- score status

data class Status(val label: String, val color: Color)

fun statusOf(score: Int?): Status = when {
    score == null -> Status("No data", Muted)
    score >= 85 -> Status("Optimal", Teal)
    score >= 70 -> Status("Good", Lime)
    score >= 60 -> Status("Fair", Amber)
    else -> Status("Pay attention", Coral)
}

// ---------------------------------------------------------------- surfaces

private val cardShape = RoundedCornerShape(26.dp)
private val cardBrush = Brush.verticalGradient(listOf(CardHi, CardColor))

/** Glass card used everywhere. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(cardShape)
            .background(cardBrush)
            .border(BorderStroke(1.dp, Border), cardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(18.dp),
        content = content,
    )
}

/** Soft coloured glow behind the top of a screen. */
@Composable
fun GlowBackdrop(color: Color, modifier: Modifier = Modifier.fillMaxWidth().height(420.dp)) {
    Box(
        modifier.background(
            Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0.06f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(540f, -40f),
                radius = 1100f,
            )
        )
    )
}

@Composable
fun PanelTitle(text: String, color: Color, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Spacer(Modifier.weight(1f))
        if (trailing != null) Text(trailing, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, color = TextMain, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, color = Muted, fontSize = 12.sp)
    }
}

@Composable
fun BigValue(value: String, unit: String = "", color: Color = TextMain, size: Int = 34) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, color = color, fontSize = size.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp)
        if (unit.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            Text(unit, color = Muted, fontSize = (size * 0.42f).sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = (size * 0.13f).dp))
        }
    }
}

@Composable
fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label.uppercase(), color = Faint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun StatRow(vararg items: Pair<String, String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        items.forEach { (l, v) -> MiniStat(l, v, Modifier.weight(1f)) }
    }
}

@Composable
fun Chip(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
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
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(CardColor).border(1.dp, Border, shape).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onChange(date.minusDays(1)) }) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous day", tint = TextMain)
        }
        Text(label(date), color = TextMain, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        val canNext = date.isBefore(LocalDate.now())
        IconButton(onClick = { if (canNext) onChange(date.plusDays(1)) }, enabled = canNext) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next day", tint = if (canNext) TextMain else Grid)
        }
    }
}

fun fmtMinutes(m: Int): String = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"

fun fmtNumber(n: Int): String = "%,d".format(n)
