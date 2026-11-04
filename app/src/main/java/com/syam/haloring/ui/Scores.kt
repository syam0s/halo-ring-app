package com.syam.haloring.ui

import com.syam.haloring.App
import com.syam.haloring.data.Db
import com.syam.haloring.data.SleepNight
import com.syam.haloring.data.dayEnd
import com.syam.haloring.data.dayStart
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/** One factor behind a score, itself scored 0–100. */
data class Contributor(val name: String, val score: Int, val value: String, val note: String, val weight: Double)

data class Score(val value: Int, val contributors: List<Contributor>)

private fun clamp(v: Double, lo: Double = 0.0, hi: Double = 100.0) = v.coerceIn(lo, hi)

private fun combine(cs: List<Contributor>, minCount: Int = 1): Score? {
    if (cs.size < minCount) return null
    val w = cs.sumOf { it.weight }
    if (w <= 0) return null
    return Score((cs.sumOf { it.score * it.weight } / w).roundToInt().coerceIn(0, 100), cs)
}

private fun eveningMins(ms: Long): Int {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime()
    return ((t.hour * 60 + t.minute) - 18 * 60 + 1440) % 1440
}

/**
 * Halo scores. These are this app's own estimates, built the way premium rings describe theirs:
 * each score blends a few contributors, and most contributors compare you with your own recent baseline.
 */
object Scores {

    // ------------------------------------------------------------------ helpers

    fun asleepMinutes(n: SleepNight): Int =
        if (n.stages.isEmpty()) ((n.end - n.start) / 60000).toInt() else n.asleepMinutes

    /** Resting heart rate: the calmest part of the night (average of the lowest third while asleep). */
    fun restingHr(app: App, date: LocalDate): Int? {
        val night = app.db.sleepFor(date)
        val pts = if (night != null) app.db.series(Db.HR, night.start, night.end) else emptyList()
        val use = if (pts.size >= 3) pts else app.db.series(Db.HR, dayStart(date), dayEnd(date)).takeIf { it.size >= 6 } ?: return null
        val sorted = use.map { it.value }.sorted()
        val n = maxOf(3, if (use === pts) sorted.size / 3 else sorted.size / 10)
        return sorted.take(n).average().roundToInt()
    }

    fun restingHrBaseline(app: App, date: LocalDate): Double? =
        (1..7).mapNotNull { restingHr(app, date.minusDays(it.toLong())) }.takeIf { it.isNotEmpty() }?.average()

    private fun hrvToday(app: App, date: LocalDate): Double? {
        val night = app.db.sleepFor(date)
        val from = night?.start ?: dayStart(date)
        val pts = app.db.series(Db.HRV, minOf(from, dayStart(date)), dayEnd(date))
        return if (pts.isEmpty()) null else pts.map { it.value }.average()
    }

    // ------------------------------------------------------------------ sleep

    fun sleep(app: App, date: LocalDate): Score? {
        val n = app.db.sleepFor(date) ?: return null
        val inBed = ((n.end - n.start) / 60000).toInt().coerceAtLeast(1)
        val asleep = asleepMinutes(n)
        val cs = mutableListOf<Contributor>()

        cs += Contributor(
            "Total sleep",
            clamp(if (asleep >= 450) 100.0 else 20.0 + (asleep - 240) * 80.0 / 210.0, 20.0).roundToInt(),
            fmtMinutes(asleep), "7½ hours or more scores best", 0.35,
        )
        if (n.stages.isNotEmpty()) {
            val eff = asleep.toDouble() / inBed
            cs += Contributor(
                "Efficiency",
                clamp(if (eff >= 0.9) 100.0 else 100 - (0.9 - eff) * 300, 20.0).roundToInt(),
                "${(eff * 100).roundToInt()}%", "Time asleep out of time in bed", 0.10,
            )
            val total = n.stages.sumOf { it.minutes }.coerceAtLeast(1)
            val deep = 100.0 * n.minutesOf(SleepNight.STAGE_DEEP) / total
            cs += Contributor(
                "Deep sleep",
                clamp(if (deep >= 13) 100.0 else 30 + deep * 70 / 13, 20.0).roundToInt(),
                "${fmtMinutes(n.minutesOf(SleepNight.STAGE_DEEP))} · ${deep.roundToInt()}%", "13–23% of the night is typical", 0.15,
            )
            val remMin = n.minutesOf(SleepNight.STAGE_REM)
            if (remMin > 0) {
                val rem = 100.0 * remMin / total
                cs += Contributor(
                    "REM sleep",
                    clamp(if (rem >= 20) 100.0 else 30 + rem * 70 / 20, 20.0).roundToInt(),
                    "${fmtMinutes(remMin)} · ${rem.roundToInt()}%", "20–25% of the night is typical", 0.15,
                )
            }
            val wakes = n.stages.count { it.stage == SleepNight.STAGE_AWAKE }
            val awake = n.minutesOf(SleepNight.STAGE_AWAKE)
            cs += Contributor(
                "Restfulness",
                clamp(100 - wakes * 6.0 - awake * 0.7, 20.0).roundToInt(),
                "$wakes wake-up${if (wakes == 1) "" else "s"} · ${fmtMinutes(awake)}", "Fewer interruptions is better", 0.10,
            )
        }
        val prev = (1..7).mapNotNull { app.db.sleepFor(date.minusDays(it.toLong())) }
        val bed = eveningMins(n.start)
        val timing = if (prev.isNotEmpty()) {
            val diff = abs(bed - prev.map { eveningMins(it.start) }.average())
            clamp(100 - maxOf(0.0, diff - 30) * 0.6, 30.0)
        } else {
            if (bed <= 390) 90.0 else clamp(90 - (bed - 390) * 0.4, 30.0)
        }
        cs += Contributor("Timing", timing.roundToInt(), "Bed at ${hhmm(n.start)}", "A regular bedtime before 00:30 is best", 0.15)
        return combine(cs)
    }

    // ------------------------------------------------------------------ readiness

    fun readiness(app: App, date: LocalDate): Score? {
        val cs = mutableListOf<Contributor>()

        restingHr(app, date)?.let { rhr ->
            val base = restingHrBaseline(app, date)
            val s = if (base != null) {
                val d = rhr - base
                if (d <= 0) 100.0 else clamp(100 - d * 9, 25.0)
            } else clamp(100 - (rhr - 55) * 2.5, 40.0)
            cs += Contributor(
                "Resting heart rate", s.roundToInt(), "$rhr bpm",
                if (base != null) "Your usual is ${base.roundToInt()} bpm. Lower than usual means well rested" else "Building your baseline",
                0.20,
            )
        }

        hrvToday(app, date)?.let { hrv ->
            val base = previousAverage(app, Metric.HRV, date)
            val s = if (base != null) {
                val r = hrv / base
                if (r >= 1.0) 100.0 else clamp(100 - (1 - r) * 220, 25.0)
            } else 75.0
            cs += Contributor(
                "HRV balance", s.roundToInt(), "${hrv.roundToInt()} ms",
                if (base != null) "Your usual is ${base.roundToInt()} ms. Higher than usual is a good sign" else "Building your baseline",
                0.25,
            )
        }

        sleep(app, date)?.let { sl ->
            cs += Contributor("Previous night", sl.value, "Sleep score ${sl.value}", "How well you slept last night", 0.20)
        }

        val nights = (0..2).mapNotNull { app.db.sleepFor(date.minusDays(it.toLong())) }
        if (nights.size >= 2) {
            val avg = nights.map { asleepMinutes(it) }.average()
            cs += Contributor(
                "Sleep balance", clamp(avg / 450 * 100, 25.0).roundToInt(), "${fmtMinutes(avg.roundToInt())} avg",
                "Your last ${nights.size} nights against a 7½ hour need", 0.15,
            )
        }

        val y = date.minusDays(1)
        val ySteps = app.db.activity(dayStart(y), dayEnd(y)).sumOf { it.steps }
        if (ySteps > 0) {
            val r = ySteps.toDouble() / app.prefs.stepGoal
            val s = when {
                r < 0.3 -> 60.0
                r < 0.6 -> 80.0
                r <= 1.8 -> 100.0
                r <= 2.5 -> 85.0
                else -> 65.0
            }
            cs += Contributor("Activity balance", s.roundToInt(), "${fmtNumber(ySteps)} steps yesterday", "Steady activity, not too little or too much", 0.10)
        }

        val stress = app.db.series(Db.STRESS, dayStart(y), dayEnd(date))
        if (stress.isNotEmpty()) {
            val avg = stress.map { it.value }.average()
            cs += Contributor("Stress load", clamp(110 - avg, 30.0).roundToInt(), "${avg.roundToInt()} avg", "Calmer recent days help recovery", 0.10)
        }
        return combine(cs, minCount = 2)
    }

    // ------------------------------------------------------------------ activity

    fun activity(app: App, date: LocalDate): Score? {
        val acts = app.db.activity(dayStart(date), dayEnd(date))
        val steps = acts.sumOf { it.steps }
        if (steps == 0) return null
        val goal = app.prefs.stepGoal
        val zone = ZoneId.systemDefault()
        val hourly = IntArray(24)
        acts.forEach { hourly[Instant.ofEpochMilli(it.ts).atZone(zone).hour] += it.steps }
        val cs = mutableListOf<Contributor>()

        cs += Contributor(
            "Daily steps", clamp(steps * 100.0 / goal).roundToInt(),
            "${fmtNumber(steps)} / ${fmtNumber(goal)}", "Your daily step goal", 0.40,
        )
        val lastHour = if (date == LocalDate.now()) LocalTime.now().hour - 1 else 20
        val window = (8..minOf(20, lastHour)).toList()
        if (window.isNotEmpty()) {
            val active = window.count { hourly[it] >= 250 }
            val target = minOf(10, window.size)
            cs += Contributor(
                "Stay active", clamp(active * 100.0 / target).roundToInt(),
                "$active active hour${if (active == 1) "" else "s"}", "250+ steps in an hour, ideally 10 hours a day", 0.25,
            )
            var run = 0; var best = 0
            for (h in window) { if (hourly[h] < 100) { run++; best = maxOf(best, run) } else run = 0 }
            val s = when (best) { 0, 1 -> 100; 2 -> 85; 3 -> 70; 4 -> 55; else -> 40 }
            cs += Contributor(
                "Sitting time", s,
                if (best <= 1) "No long still stretch" else "$best hours still in a row", "Get up and move at least once an hour", 0.20,
            )
        }
        val week = app.db.dailySteps(date, 7)
        val hit = week.count { it.second >= goal * 0.8 }
        cs += Contributor("Weekly consistency", clamp(hit * 100.0 / 4).roundToInt(), "$hit of 7 days near goal", "Four or more good days a week", 0.15)
        return combine(cs)
    }

    // ------------------------------------------------------------------ words

    fun readinessHeadline(score: Int?): String = when {
        score == null -> "Wear your ring through the night and sync in the morning to see your readiness."
        score >= 85 -> "You're well recovered. A great day to push a little harder."
        score >= 70 -> "You're ready for a normal day. Keep a steady pace."
        score >= 60 -> "Recovery is a bit low. Take it easier and get to bed on time."
        else -> "Your body is asking for rest. Keep today light and sleep early."
    }

    fun sleepHeadline(score: Int?): String = when {
        score == null -> "No sleep recorded for this night."
        score >= 85 -> "Excellent night. Deep, long and restful."
        score >= 70 -> "A good night's sleep."
        score >= 60 -> "A fair night. There's room for more or deeper sleep."
        else -> "A short or restless night. Try an earlier, regular bedtime."
    }

    fun activityHeadline(score: Int?): String = when {
        score == null -> "No activity recorded yet."
        score >= 85 -> "Great job: goal met and you kept moving through the day."
        score >= 70 -> "Good activity. A short walk would round it off."
        score >= 60 -> "Getting there. Try to break up long sitting stretches."
        else -> "A quiet day so far. Small walks every hour add up."
    }
}
