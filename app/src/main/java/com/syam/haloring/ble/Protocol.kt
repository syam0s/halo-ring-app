package com.syam.haloring.ble

import com.syam.haloring.data.Act
import com.syam.haloring.data.Point
import com.syam.haloring.data.SleepNight
import com.syam.haloring.data.Spo2
import com.syam.haloring.data.Stage
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** Bluetooth addresses of the ring's services (QRing / Colmi family, as used by the Ravoz RZ02). */
object Uuids {
    val SERVICE_V1: UUID = UUID.fromString("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e")
    val WRITE: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val NOTIFY_V1: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    val SERVICE_V2: UUID = UUID.fromString("de5bf728-d711-4e47-af26-65e3012a5dc7")
    val COMMAND_V2: UUID = UUID.fromString("de5bf72a-d711-4e47-af26-65e3012a5dc7")
    val NOTIFY_V2: UUID = UUID.fromString("de5bf729-d711-4e47-af26-65e3012a5dc7")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val DEVICE_INFO: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val FIRMWARE: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    val HARDWARE: UUID = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")
}

object Cmd {
    const val SET_TIME = 0x01
    const val BATTERY = 0x03
    const val PHONE_NAME = 0x04
    const val POWER_OFF = 0x08
    const val SYNC_HR = 0x15
    const val HR_PREF = 0x16
    const val SPO2_PREF = 0x2c
    const val STRESS_PREF = 0x36
    const val SYNC_STRESS = 0x37
    const val HRV_PREF = 0x38
    const val SYNC_HRV = 0x39
    const val SYNC_ACTIVITY = 0x43
    const val FIND = 0x50
    const val REALTIME = 0x69
    const val REALTIME_STOP = 0x6a
    const val NOTIFY = 0x73
    const val BIG_DATA = 0xbc
    const val BIG_SLEEP = 0x27
    const val BIG_SPO2 = 0x2a

    const val LIVE_HR = 1
    const val LIVE_SPO2 = 3
}

fun ByteArray.u(i: Int): Int = if (i in indices) this[i].toInt() and 0xff else 0
fun ByteArray.u16(i: Int): Int = u(i) or (u(i + 1) shl 8)

object Packets {
    /** 16-byte packet: command, data, checksum in the last byte. */
    fun make(vararg b: Int): ByteArray {
        val p = ByteArray(16)
        b.forEachIndexed { i, v -> if (i < 15) p[i] = v.toByte() }
        var sum = 0
        for (i in 0 until 15) sum += p[i].toInt() and 0xff
        p[15] = (sum and 0xff).toByte()
        return p
    }

    private fun bcd(v: Int) = ((v / 10) shl 4) or (v % 10)

    fun setTime(t: LocalDateTime) = make(
        Cmd.SET_TIME, bcd(t.year % 100), bcd(t.monthValue), bcd(t.dayOfMonth),
        bcd(t.hour), bcd(t.minute), bcd(t.second), 1
    )

    fun phoneName() = make(Cmd.PHONE_NAME, 0x02, 0x0a, 'S'.code, 'Y'.code)
    fun battery() = make(Cmd.BATTERY)
    fun activity(daysAgo: Int) = make(Cmd.SYNC_ACTIVITY, daysAgo, 0x0f, 0x00, 0x5f, 0x01)

    fun hrHistory(day: LocalDate): ByteArray {
        val ts = day.atStartOfDay().toEpochSecond(ZoneOffset.UTC).toInt()
        return make(Cmd.SYNC_HR, ts and 0xff, (ts shr 8) and 0xff, (ts shr 16) and 0xff, (ts ushr 24) and 0xff)
    }

    fun stress() = make(Cmd.SYNC_STRESS)
    fun hrv(daysAgo: Int) = make(Cmd.SYNC_HRV, daysAgo, 0, 0, 0)
    fun bigData(type: Int) = byteArrayOf(
        Cmd.BIG_DATA.toByte(), type.toByte(), 0x01, 0x00, 0xff.toByte(), 0x00, 0xff.toByte()
    )

    fun prefRead(cmd: Int) = make(cmd, 0x01)
    fun hrPrefWrite(enabled: Boolean, minutes: Int) = make(Cmd.HR_PREF, 0x02, if (enabled) 1 else 2, minutes)
    fun togglePrefWrite(cmd: Int, enabled: Boolean) = make(cmd, 0x02, if (enabled) 1 else 0)
    fun find() = make(Cmd.FIND, 0x55, 0xaa)
    fun liveStart(type: Int) = make(Cmd.REALTIME, type, 1)
    fun liveContinue(type: Int) = make(Cmd.REALTIME, type, 3)
    fun liveStop(type: Int) = make(Cmd.REALTIME_STOP, type, 0, 0)
    fun powerOff() = make(Cmd.POWER_OFF, 0x01)
}

/** Turns the ring's raw packets into readings. */
object Parsers {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private fun at(day: LocalDate, minute: Int): Long =
        day.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()

    private fun fromBcd(b: Int) = ((b shr 4) and 0xf) * 10 + (b and 0xf)

    /** Heart-rate history for one day, 5-minute slots. */
    class HeartRate(private val day: LocalDate) {
        val out = mutableListOf<Point>()
        private var total = 0
        fun feed(p: ByteArray): Boolean {
            val sub = p.u(1)
            if (sub == 0xff) return true
            if (sub == 0) {
                total = p.u(2)
                return total <= 1
            }
            val start = if (sub == 1) 6 else 2
            val base = if (sub == 1) 0 else 9 + (sub - 2) * 13
            val now = System.currentTimeMillis()
            for (i in start until 15) {
                val v = p.u(i)
                if (v in 25..230) {
                    val m = (base + i - start) * 5
                    if (m < 1440) {
                        val ts = at(day, m)
                        if (ts <= now) out += Point(ts, v)
                    }
                }
            }
            return total > 0 && sub >= total - 1
        }
    }

    /** Steps / calories / distance in 15-minute slots. */
    class Activity {
        val out = mutableListOf<Act>()
        fun feed(p: ByteArray): Boolean {
            val b1 = p.u(1)
            if (b1 == 0xff) return true
            if (b1 == 0xf0) return false
            val date = runCatching {
                LocalDate.of(2000 + fromBcd(b1), fromBcd(p.u(2)), fromBcd(p.u(3)))
            }.getOrNull()
            val quarter = p.u(4)
            if (date != null && quarter < 96) {
                out += Act(at(date, quarter * 15), steps = p.u16(9), calories = p.u16(7), distance = p.u16(11))
            }
            return p.u(5) >= p.u(6) - 1
        }
    }

    /** Stress and HRV history: one value every 30 minutes. */
    class HalfHour(private val day: LocalDate) {
        val out = mutableListOf<Point>()
        var gotAny = false
        fun feed(p: ByteArray): Boolean {
            gotAny = true
            val sub = p.u(1)
            if (sub == 0xff) return true
            if (sub == 0) return false
            val start = if (sub == 1) 3 else 2
            val base = if (sub == 1) 0 else 12 + (sub - 2) * 13
            val now = System.currentTimeMillis()
            for (i in start until 15) {
                val v = p.u(i)
                if (v > 0) {
                    val m = (base + i - start) * 30
                    if (m < 1440) {
                        val ts = at(day, m)
                        if (ts <= now) out += Point(ts, v)
                    }
                }
            }
            return sub >= 4
        }
    }

    /** Blood-oxygen history: hourly min/max for several days. */
    fun spo2(v: ByteArray, today: LocalDate): List<Spo2> {
        val out = mutableListOf<Spo2>()
        val len = v.u16(2)
        var idx = 6
        val now = System.currentTimeMillis()
        while (idx - 6 < len && idx < v.size) {
            val daysAgo = v.u(idx)
            idx++
            val day = today.minusDays(daysAgo.toLong())
            for (hour in 0..23) {
                if (idx + 1 >= v.size || idx - 6 >= len) break
                val lo = v.u(idx)
                val hi = v.u(idx + 1)
                idx += 2
                if (lo in 70..100 && hi in 70..100) {
                    val ts = at(day, hour * 60)
                    if (ts <= now) out += Spo2(ts, (lo + hi + 1) / 2, lo, hi)
                }
            }
            if (daysAgo == 0) break
        }
        return out
    }

    /** Sleep history: one session per night with stages. */
    fun sleep(v: ByteArray, today: LocalDate): List<SleepNight> {
        val out = mutableListOf<SleepNight>()
        if (v.u16(2) < 2) return out
        val days = v.u(6)
        var idx = 7
        repeat(days) {
            if (idx + 6 > v.size) return out
            val daysAgo = v.u(idx)
            val dayBytes = v.u(idx + 1)
            val start = v.u16(idx + 2)
            val end = v.u16(idx + 4)
            idx += 6
            val wake = today.minusDays(daysAgo.toLong())
            val startTs = if (start > end) at(wake.minusDays(1), start) else at(wake, start)
            val endTs = at(wake, end)
            val stages = mutableListOf<Stage>()
            var cursor = startTs
            var j = 4
            while (j < dayBytes) {
                if (idx + 1 >= v.size) break
                val stage = v.u(idx)
                val mins = v.u(idx + 1)
                if (mins > 0) {
                    stages += Stage(cursor, stage, mins)
                    cursor += mins * 60_000L
                }
                idx += 2
                j += 2
            }
            if (endTs > startTs) out += SleepNight(startTs, endTs, stages)
        }
        return out
    }
}
