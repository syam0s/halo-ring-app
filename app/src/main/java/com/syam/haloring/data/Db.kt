package com.syam.haloring.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class Point(val ts: Long, val value: Int)
data class Act(val ts: Long, val steps: Int, val calories: Int, val distance: Int)
data class Spo2(val ts: Long, val value: Int, val min: Int, val max: Int)
data class BatteryReading(
    val ts: Long, val level: Int, val charging: Boolean,
    val hrOn: Boolean, val hrInterval: Int, val spo2: Boolean, val stress: Boolean, val hrv: Boolean,
)
data class Stage(val ts: Long, val stage: Int, val minutes: Int)
data class SleepNight(val start: Long, val end: Long, val stages: List<Stage>) {
    val asleepMinutes: Int get() = stages.filter { it.stage != STAGE_AWAKE }.sumOf { it.minutes }
    fun minutesOf(stage: Int) = stages.filter { it.stage == stage }.sumOf { it.minutes }

    companion object {
        const val STAGE_LIGHT = 2
        const val STAGE_DEEP = 3
        const val STAGE_REM = 4
        const val STAGE_AWAKE = 5
    }
}

fun dayStart(d: LocalDate): Long = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
fun dayEnd(d: LocalDate): Long = dayStart(d.plusDays(1))
fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

/** All readings live here, on the phone only. */
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "ring.db", null, 2) {

    /** Bumped after every change so screens refresh. */
    val version = MutableStateFlow(0)
    fun bump() { version.value = version.value + 1 }

    override fun onCreate(db: SQLiteDatabase) {
        for (t in listOf(HR, STRESS, HRV)) {
            db.execSQL("CREATE TABLE $t (ts INTEGER PRIMARY KEY, value INTEGER NOT NULL)")
        }
        db.execSQL("CREATE TABLE spo2 (ts INTEGER PRIMARY KEY, value INTEGER, min INTEGER, max INTEGER)")
        db.execSQL("CREATE TABLE activity (ts INTEGER PRIMARY KEY, steps INTEGER, calories INTEGER, distance INTEGER)")
        db.execSQL("CREATE TABLE sleep_session (start INTEGER PRIMARY KEY, finish INTEGER)")
        db.execSQL("CREATE TABLE sleep_stage (ts INTEGER PRIMARY KEY, stage INTEGER, minutes INTEGER)")
        createBattery(db)
    }

    private fun createBattery(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS battery (ts INTEGER PRIMARY KEY, level INTEGER, charging INTEGER, " +
                "hr_on INTEGER, hr_interval INTEGER, spo2 INTEGER, stress INTEGER, hrv INTEGER)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createBattery(db)
    }

    fun insertBattery(r: BatteryReading) {
        val cv = ContentValues()
        cv.put("ts", r.ts)
        cv.put("level", r.level)
        cv.put("charging", if (r.charging) 1 else 0)
        cv.put("hr_on", if (r.hrOn) 1 else 0)
        cv.put("hr_interval", r.hrInterval)
        cv.put("spo2", if (r.spo2) 1 else 0)
        cv.put("stress", if (r.stress) 1 else 0)
        cv.put("hrv", if (r.hrv) 1 else 0)
        writableDatabase.insertWithOnConflict("battery", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun batteryReadings(from: Long): List<BatteryReading> {
        val out = mutableListOf<BatteryReading>()
        readableDatabase.rawQuery(
            "SELECT ts, level, charging, hr_on, hr_interval, spo2, stress, hrv FROM battery WHERE ts >= ? ORDER BY ts",
            arrayOf(from.toString())
        ).use { c ->
            while (c.moveToNext()) out += BatteryReading(
                c.getLong(0), c.getInt(1), c.getInt(2) == 1, c.getInt(3) == 1, c.getInt(4),
                c.getInt(5) == 1, c.getInt(6) == 1, c.getInt(7) == 1,
            )
        }
        return out
    }

    private inline fun tx(block: SQLiteDatabase.() -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun insertSeries(table: String, pts: List<Point>) {
        if (pts.isEmpty()) return
        tx {
            for (p in pts) {
                val cv = ContentValues()
                cv.put("ts", p.ts)
                cv.put("value", p.value)
                insertWithOnConflict(table, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    fun insertActivity(list: List<Act>) {
        if (list.isEmpty()) return
        tx {
            for (a in list) {
                val cv = ContentValues()
                cv.put("ts", a.ts)
                cv.put("steps", a.steps)
                cv.put("calories", a.calories)
                cv.put("distance", a.distance)
                insertWithOnConflict("activity", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    fun insertSpo2(list: List<Spo2>) {
        if (list.isEmpty()) return
        tx {
            for (s in list) {
                val cv = ContentValues()
                cv.put("ts", s.ts)
                cv.put("value", s.value)
                cv.put("min", s.min)
                cv.put("max", s.max)
                insertWithOnConflict("spo2", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    fun insertSleep(nights: List<SleepNight>) {
        if (nights.isEmpty()) return
        tx {
            for (n in nights) {
                val wake = n.end.toLocalDate()
                val from = dayStart(wake)
                val to = dayEnd(wake)
                // Replace whatever we had for that night (it may have grown since the last sync).
                val old = rawQuery(
                    "SELECT start, finish FROM sleep_session WHERE finish >= ? AND finish < ?",
                    arrayOf(from.toString(), to.toString())
                )
                old.use { c ->
                    while (c.moveToNext()) {
                        delete("sleep_stage", "ts >= ? AND ts < ?", arrayOf(c.getLong(0).toString(), c.getLong(1).toString()))
                    }
                }
                delete("sleep_session", "finish >= ? AND finish < ?", arrayOf(from.toString(), to.toString()))
                delete("sleep_stage", "ts >= ? AND ts < ?", arrayOf(n.start.toString(), n.end.toString()))
                val cv = ContentValues()
                cv.put("start", n.start)
                cv.put("finish", n.end)
                insertWithOnConflict("sleep_session", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                for (s in n.stages) {
                    val sv = ContentValues()
                    sv.put("ts", s.ts)
                    sv.put("stage", s.stage)
                    sv.put("minutes", s.minutes)
                    insertWithOnConflict("sleep_stage", null, sv, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
        }
    }

    fun series(table: String, from: Long, to: Long): List<Point> {
        val out = mutableListOf<Point>()
        readableDatabase.rawQuery(
            "SELECT ts, value FROM $table WHERE ts >= ? AND ts < ? ORDER BY ts",
            arrayOf(from.toString(), to.toString())
        ).use { c -> while (c.moveToNext()) out += Point(c.getLong(0), c.getInt(1)) }
        return out
    }

    fun activity(from: Long, to: Long): List<Act> {
        val out = mutableListOf<Act>()
        readableDatabase.rawQuery(
            "SELECT ts, steps, calories, distance FROM activity WHERE ts >= ? AND ts < ? ORDER BY ts",
            arrayOf(from.toString(), to.toString())
        ).use { c -> while (c.moveToNext()) out += Act(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3)) }
        return out
    }

    fun spo2(from: Long, to: Long): List<Spo2> {
        val out = mutableListOf<Spo2>()
        readableDatabase.rawQuery(
            "SELECT ts, value, min, max FROM spo2 WHERE ts >= ? AND ts < ? ORDER BY ts",
            arrayOf(from.toString(), to.toString())
        ).use { c -> while (c.moveToNext()) out += Spo2(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3)) }
        return out
    }

    /** The night that ended on [wake] (the morning you woke up). */
    fun sleepFor(wake: LocalDate): SleepNight? = sleepNights(dayStart(wake), dayEnd(wake)).lastOrNull()

    fun sleepNights(from: Long, to: Long): List<SleepNight> {
        val sessions = mutableListOf<Pair<Long, Long>>()
        readableDatabase.rawQuery(
            "SELECT start, finish FROM sleep_session WHERE finish >= ? AND finish < ? ORDER BY finish",
            arrayOf(from.toString(), to.toString())
        ).use { c -> while (c.moveToNext()) sessions += c.getLong(0) to c.getLong(1) }
        return sessions.map { (s, e) ->
            val stages = mutableListOf<Stage>()
            readableDatabase.rawQuery(
                "SELECT ts, stage, minutes FROM sleep_stage WHERE ts >= ? AND ts < ? ORDER BY ts",
                arrayOf(s.toString(), e.toString())
            ).use { c -> while (c.moveToNext()) stages += Stage(c.getLong(0), c.getInt(1), c.getInt(2)) }
            SleepNight(s, e, stages)
        }
    }

    /** Steps per day for the [days] days ending on [last]. */
    fun dailySteps(last: LocalDate, days: Int): List<Pair<LocalDate, Int>> {
        val first = last.minusDays((days - 1).toLong())
        val acts = activity(dayStart(first), dayEnd(last))
        val byDay = acts.groupBy { it.ts.toLocalDate() }.mapValues { e -> e.value.sumOf { it.steps } }
        return (0 until days).map { i -> val d = first.plusDays(i.toLong()); d to (byDay[d] ?: 0) }
    }

    fun exportCsv(os: OutputStream) {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val zone = ZoneId.systemDefault()
        fun t(ts: Long) = Instant.ofEpochMilli(ts).atZone(zone).format(fmt)
        val w = os.bufferedWriter()
        w.write("type,time,value,extra1,extra2\n")
        val all = 0L to Long.MAX_VALUE
        series(HR, all.first, all.second).forEach { w.write("heart_rate,${t(it.ts)},${it.value},,\n") }
        series(HRV, all.first, all.second).forEach { w.write("hrv,${t(it.ts)},${it.value},,\n") }
        series(STRESS, all.first, all.second).forEach { w.write("stress,${t(it.ts)},${it.value},,\n") }
        spo2(all.first, all.second).forEach { w.write("spo2,${t(it.ts)},${it.value},${it.min},${it.max}\n") }
        activity(all.first, all.second).forEach { w.write("steps,${t(it.ts)},${it.steps},${it.calories},${it.distance}\n") }
        sleepNights(all.first, all.second).forEach { n ->
            w.write("sleep_session,${t(n.start)},${n.asleepMinutes},${t(n.end)},\n")
            n.stages.forEach { s -> w.write("sleep_stage,${t(s.ts)},${s.stage},${s.minutes},\n") }
        }
        w.flush()
    }

    companion object {
        const val HR = "hr"
        const val STRESS = "stress"
        const val HRV = "hrv"
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("ring", Context.MODE_PRIVATE)

    var address: String?
        get() = sp.getString("address", null)
        set(v) = sp.edit().putString("address", v).apply()
    var name: String?
        get() = sp.getString("name", null)
        set(v) = sp.edit().putString("name", v).apply()
    var firmware: String?
        get() = sp.getString("fw", null)
        set(v) = sp.edit().putString("fw", v).apply()
    var hardware: String?
        get() = sp.getString("hw", null)
        set(v) = sp.edit().putString("hw", v).apply()
    var lastSync: Long
        get() = sp.getLong("lastSync", 0L)
        set(v) = sp.edit().putLong("lastSync", v).apply()
    var autoSync: Boolean
        get() = sp.getBoolean("autoSync", true)
        set(v) = sp.edit().putBoolean("autoSync", v).apply()
    var stepGoal: Int
        get() = sp.getInt("stepGoal", 8000)
        set(v) = sp.edit().putInt("stepGoal", v).apply()

    // Last known battery state, so the estimate works between syncs.
    var batteryLevel: Int
        get() = sp.getInt("batt", -1)
        set(v) = sp.edit().putInt("batt", v).apply()
    var batteryTs: Long
        get() = sp.getLong("battTs", 0L)
        set(v) = sp.edit().putLong("battTs", v).apply()
    var batteryCharging: Boolean
        get() = sp.getBoolean("battChg", false)
        set(v) = sp.edit().putBoolean("battChg", v).apply()

    /** Ring measurement settings as "hrOn,interval,spo2,stress,hrv" (empty = not read yet). */
    var ringSettings: String?
        get() = sp.getString("ringSettings", null)
        set(v) = sp.edit().putString("ringSettings", v).apply()
}
