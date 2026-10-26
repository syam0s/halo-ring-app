package com.syam.haloring.data

import com.syam.haloring.ble.RingManager.RingSettings

/**
 * Estimates how long the ring battery will last.
 *
 * Starts from a typical-use model (how much each kind of automatic measurement costs per day),
 * then calibrates it against how fast YOUR ring actually drained between syncs.
 * Changing a setting changes the model straight away, so the estimate follows your settings.
 */
object BatteryEstimator {
    // Typical drain in % of a full charge per day.
    private const val BASE = 8.0          // Bluetooth, steps, sleep tracking
    private const val HR_PER_DAY_AT_1MIN = 70.0 // heart-rate cost scales with 1 / interval
    private const val SPO2 = 3.0
    private const val STRESS = 1.5
    private const val HRV = 1.5

    data class Estimate(
        val level: Int?,            // estimated level right now
        val charging: Boolean,
        val hoursLeft: Double?,     // from now, on the current charge
        val fullChargeDays: Double, // a full 100% charge at these settings
        val learned: Boolean,       // true once calibrated against real drain
    )

    fun modelDrainPerDay(s: RingSettings): Double {
        var d = BASE
        if (s.hrEnabled && s.hrInterval > 0) d += HR_PER_DAY_AT_1MIN / s.hrInterval
        if (s.spo2) d += SPO2
        if (s.stress) d += STRESS
        if (s.hrv) d += HRV
        return d
    }

    private fun BatteryReading.settings() = RingSettings(hrOn, hrInterval, spo2, stress, hrv)

    /**
     * How much faster (>1) or slower (<1) your ring drains than the model, learned from
     * stretches between syncs where it wasn't charging.
     */
    fun calibration(readings: List<BatteryReading>): Double? {
        var observed = 0.0
        var expected = 0.0
        for (i in 1 until readings.size) {
            val a = readings[i - 1]
            val b = readings[i]
            if (a.charging || b.charging || b.level > a.level) continue // charged in between
            val days = (b.ts - a.ts) / 86_400_000.0
            if (days <= 0 || days > 4) continue
            observed += (a.level - b.level)
            expected += modelDrainPerDay(a.settings()) * days
        }
        // Need a meaningful amount of drain before trusting it.
        if (expected < 6.0 || observed < 4.0) return null
        return (observed / expected).coerceIn(0.4, 2.5)
    }

    fun estimate(prefs: Prefs, db: Db, settings: RingSettings): Estimate {
        val now = System.currentTimeMillis()
        val factor = calibration(db.batteryReadings(now - 21L * 86_400_000L))
        val perDay = modelDrainPerDay(settings) * (factor ?: 1.0)
        val fullDays = 100.0 / perDay
        val known = prefs.batteryLevel
        if (known < 0) return Estimate(null, false, null, fullDays, factor != null)
        if (prefs.batteryCharging) return Estimate(known, true, null, fullDays, factor != null)
        val elapsedDays = ((now - prefs.batteryTs).coerceAtLeast(0L)) / 86_400_000.0
        val levelNow = (known - perDay * elapsedDays).coerceAtLeast(0.0)
        return Estimate(levelNow.toInt(), false, levelNow / perDay * 24.0, fullDays, factor != null)
    }

    fun formatHours(h: Double): String {
        val total = h.toInt()
        val d = total / 24
        val r = total % 24
        return when {
            total < 1 -> "under an hour"
            d == 0 -> "$r h"
            r == 0 -> if (d == 1) "1 day" else "$d days"
            else -> "${d}d ${r}h"
        }
    }

    fun formatDays(d: Double): String = if (d < 10) "%.1f days".format(d) else "${d.toInt()} days"
}
