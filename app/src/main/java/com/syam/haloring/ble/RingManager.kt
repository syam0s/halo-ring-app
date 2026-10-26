package com.syam.haloring.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import com.syam.haloring.data.Db
import com.syam.haloring.data.Point
import com.syam.haloring.data.Prefs
import com.syam.haloring.data.Spo2
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Talks to the ring directly over Bluetooth: no cloud, no middleman.
 * Every operation connects, does its job and disconnects, which keeps the ring's battery happy.
 */
@SuppressLint("MissingPermission")
class RingManager(private val ctx: Context, private val db: Db, private val prefs: Prefs) {

    enum class Conn { Disconnected, Connecting, Connected }

    data class Status(
        val conn: Conn = Conn.Disconnected,
        val battery: Int? = null,
        val charging: Boolean = false,
        val busy: Boolean = false,
        val syncing: Boolean = false,
        val step: String = "",
        val progress: Float = 0f,
        val message: String? = null,
        val lastSync: Long = 0L,
    )

    data class RingSettings(
        val hrEnabled: Boolean = true,
        val hrInterval: Int = 10,
        val spo2: Boolean = false,
        val stress: Boolean = false,
        val hrv: Boolean = false,
    )

    data class Live(val type: Int, val value: Int?, val running: Boolean, val note: String)
    data class Found(val name: String, val address: String, val rssi: Int, val likely: Boolean)

    val status = MutableStateFlow(
        Status(
            lastSync = prefs.lastSync,
            battery = prefs.batteryLevel.takeIf { it >= 0 },
            charging = prefs.batteryCharging,
        )
    )
    val settings = MutableStateFlow(decodeSettings(prefs.ringSettings))
    val live = MutableStateFlow<Live?>(null)
    val scanResults = MutableStateFlow<List<Found>>(emptyList())
    val scanning = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = Mutex()
    private val gattLock = Mutex()
    private val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager.adapter

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var ready: CompletableDeferred<Boolean>? = null
    @Volatile private var op: CompletableDeferred<ByteArray?>? = null
    @Volatile private var stopLive = false

    private val v1 = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1024)
    private val v2 = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var bigBuf: ByteArray? = null
    private var bigLen = 0

    // ---------- Bluetooth callbacks ----------

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && st == BluetoothGatt.GATT_SUCCESS) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || st != BluetoothGatt.GATT_SUCCESS) {
                try { g.close() } catch (_: Exception) {}
                if (gatt === g) gatt = null
                ready?.complete(false)
                op?.complete(null)
                setConn(Conn.Disconnected)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            ready?.complete(st == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, st: Int) { op?.complete(ByteArray(0)) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) { op?.complete(ByteArray(0)) }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) { op?.complete(ByteArray(0)) }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            op?.complete(c.value?.copyOf())
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, st: Int) {
            op?.complete(value.copyOf())
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            onNotify(c.uuid, v.copyOf())
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onNotify(c.uuid, value.copyOf())
        }
    }

    private fun onNotify(uuid: UUID, v: ByteArray) {
        if (uuid == Uuids.NOTIFY_V1) {
            when (v.u(0)) {
                Cmd.BATTERY -> setBattery(v.u(1), v.u(2) == 1)
                Cmd.NOTIFY -> if (v.u(1) == 0x0c) setBattery(v.u(2), v.u(3) == 1)
            }
            v1.tryEmit(v)
        } else if (uuid == Uuids.NOTIFY_V2) {
            var data = v
            val buf = bigBuf
            if (buf != null) {
                data = buf + v
                if (data.size < bigLen + 6) { bigBuf = data; return }
                bigBuf = null
            } else if (data.u(0) == Cmd.BIG_DATA && data.size >= 4) {
                val len = data.u16(2)
                if (data.size < len + 6) { bigBuf = data; bigLen = len; return }
            }
            v2.tryEmit(data)
        }
    }

    private fun setConn(c: Conn) = status.update { it.copy(conn = c) }

    private fun setBattery(level: Int, charging: Boolean) {
        if (level !in 0..100) return
        prefs.batteryLevel = level
        prefs.batteryCharging = charging
        prefs.batteryTs = System.currentTimeMillis()
        status.update { it.copy(battery = level, charging = charging) }
    }

    /** Log battery + settings so the time-left estimate can learn how fast this ring drains. */
    private fun recordBattery() {
        val s = settings.value ?: return
        val level = prefs.batteryLevel
        if (level < 0) return
        db.insertBattery(
            com.syam.haloring.data.BatteryReading(
                System.currentTimeMillis(), level, prefs.batteryCharging,
                s.hrEnabled, s.hrInterval, s.spo2, s.stress, s.hrv,
            )
        )
        db.bump()
    }

    private fun encodeSettings(s: RingSettings) =
        listOf(if (s.hrEnabled) 1 else 0, s.hrInterval, if (s.spo2) 1 else 0, if (s.stress) 1 else 0, if (s.hrv) 1 else 0)
            .joinToString(",")

    private fun decodeSettings(v: String?): RingSettings? {
        val p = v?.split(",")?.mapNotNull { it.toIntOrNull() } ?: return null
        if (p.size != 5) return null
        return RingSettings(p[0] == 1, p[1], p[2] == 1, p[3] == 1, p[4] == 1)
    }

    private fun saveSettings(s: RingSettings) {
        settings.value = s
        prefs.ringSettings = encodeSettings(s)
    }

    // ---------- Low-level Bluetooth operations ----------

    private suspend fun gattOp(timeout: Long = 4000, start: () -> Boolean): ByteArray? = gattLock.withLock {
        val d = CompletableDeferred<ByteArray?>()
        op = d
        val started = try { start() } catch (_: Exception) { false }
        val result = if (started) withTimeoutOrNull(timeout) { d.await() } else null
        op = null
        result
    }

    @Suppress("DEPRECATION")
    private suspend fun write(service: UUID, char: UUID, data: ByteArray): Boolean {
        val g = gatt ?: return false
        val ch = g.getService(service)?.getCharacteristic(char) ?: return false
        val withResponse = (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return gattOp {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(ch, data, type) == BluetoothStatusCodes.SUCCESS
            } else {
                ch.writeType = type
                ch.value = data
                g.writeCharacteristic(ch)
            }
        } != null
    }

    private suspend fun send(packet: ByteArray) = write(Uuids.SERVICE_V1, Uuids.WRITE, packet)

    @Suppress("DEPRECATION")
    private suspend fun enableNotify(g: BluetoothGatt, service: UUID, char: UUID): Boolean {
        val ch = g.getService(service)?.getCharacteristic(char) ?: return false
        g.setCharacteristicNotification(ch, true)
        val desc = ch.getDescriptor(Uuids.CCCD) ?: return true
        return gattOp {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(desc)
            }
        } != null
    }

    private suspend fun readString(g: BluetoothGatt, service: UUID, char: UUID): String? {
        val ch = g.getService(service)?.getCharacteristic(char) ?: return null
        val bytes = gattOp(2500) { g.readCharacteristic(ch) } ?: return null
        return String(bytes).trim { it <= ' ' }.ifBlank { null }
    }

    /** Send [packet] and feed every reply with command [cmd] to [onPacket] until it returns true. */
    private suspend fun request(
        cmd: Int,
        packet: ByteArray,
        timeout: Long = 6000,
        onPacket: (ByteArray) -> Boolean,
    ): Boolean = coroutineScope {
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(timeout) { v1.first { it.u(0) == cmd && onPacket(it) } } != null
        }
        if (!send(packet)) {
            waiter.cancel()
            false
        } else {
            waiter.await()
        }
    }

    private suspend fun requestBig(type: Int, timeout: Long = 10000): ByteArray? = coroutineScope {
        bigBuf = null
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(timeout) { v2.first { it.u(0) == Cmd.BIG_DATA && it.u(1) == type } }
        }
        if (!write(Uuids.SERVICE_V2, Uuids.COMMAND_V2, Packets.bigData(type))) {
            waiter.cancel()
            null
        } else {
            waiter.await()
        }
    }

    // ---------- Connect / disconnect ----------

    private suspend fun connect(): Boolean {
        if (gatt != null && status.value.conn == Conn.Connected) return true
        val addr = prefs.address ?: return false
        val ad = adapter ?: return false
        if (!ad.isEnabled) {
            status.update { it.copy(message = "Bluetooth is turned off. Switch it on and try again.") }
            return false
        }
        setConn(Conn.Connecting)
        repeat(3) {
            val d = CompletableDeferred<Boolean>()
            ready = d
            val device = ad.getRemoteDevice(addr)
            val g = withContext(Dispatchers.Main) {
                device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
            }
            gatt = g
            val ok = withTimeoutOrNull(12000) { d.await() } ?: false
            if (ok && setup(g)) {
                setConn(Conn.Connected)
                return true
            }
            try { g.disconnect(); g.close() } catch (_: Exception) {}
            if (gatt === g) gatt = null
            delay(700)
        }
        setConn(Conn.Disconnected)
        return false
    }

    private suspend fun setup(g: BluetoothGatt): Boolean {
        gattOp(3000) { g.requestMtu(247) }
        val okMain = enableNotify(g, Uuids.SERVICE_V1, Uuids.NOTIFY_V1)
        enableNotify(g, Uuids.SERVICE_V2, Uuids.NOTIFY_V2)
        readString(g, Uuids.DEVICE_INFO, Uuids.FIRMWARE)?.let { prefs.firmware = it }
        readString(g, Uuids.DEVICE_INFO, Uuids.HARDWARE)?.let { prefs.hardware = it }
        delay(400) // let the ring settle
        return okMain
    }

    private suspend fun disconnect() {
        val g = gatt ?: return
        try { g.disconnect() } catch (_: Exception) {}
        delay(300)
        try { g.close() } catch (_: Exception) {}
        if (gatt === g) gatt = null
        setConn(Conn.Disconnected)
    }

    private suspend inline fun <T> withRing(busyNote: String, crossinline block: suspend () -> T): T? = session.withLock {
        status.update { it.copy(busy = true, message = null, step = busyNote) }
        try {
            if (!connect()) {
                status.update { it.copy(message = "Couldn't reach the ring. Keep it close to the phone (or on its charger) and try again.") }
                null
            } else {
                block()
            }
        } catch (e: Exception) {
            status.update { it.copy(message = "Something went wrong: ${e.message}") }
            null
        } finally {
            disconnect()
            status.update { it.copy(busy = false, syncing = false, step = "", progress = 0f) }
        }
    }

    // ---------- Public actions ----------

    /** Pull everything new from the ring. Only fetches the days since the last sync, so it's quick. */
    suspend fun sync(): Boolean {
        status.update { it.copy(syncing = true, progress = 0f) }
        val ok = withRing("Connecting") {
            send(Packets.setTime(LocalDateTime.now()))
            send(Packets.phoneName())
            request(Cmd.BATTERY, Packets.battery(), 2500) { true }
            readSettings()
            recordBattery()

            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            val last = prefs.lastSync
            val daysBack = if (last == 0L) 6 else
                ChronoUnit.DAYS.between(Instant.ofEpochMilli(last).atZone(zone).toLocalDate(), today).toInt().coerceIn(0, 6)
            val total = 6f
            fun stage(i: Int, name: String) = status.update { it.copy(step = name, progress = i / total) }

            stage(0, "Steps")
            for (d in daysBack downTo 0) {
                val p = Parsers.Activity()
                request(Cmd.SYNC_ACTIVITY, Packets.activity(d)) { p.feed(it) }
                db.insertActivity(p.out)
            }

            stage(1, "Heart rate")
            for (d in daysBack downTo 0) {
                val day = today.minusDays(d.toLong())
                val p = Parsers.HeartRate(day)
                request(Cmd.SYNC_HR, Packets.hrHistory(day)) { p.feed(it) }
                db.insertSeries(Db.HR, p.out)
            }
            db.bump()

            stage(2, "Stress")
            Parsers.HalfHour(today).let { p ->
                request(Cmd.SYNC_STRESS, Packets.stress(), 4000) { p.feed(it) }
                db.insertSeries(Db.STRESS, p.out)
            }

            stage(3, "HRV")
            for (d in daysBack downTo 0) {
                val p = Parsers.HalfHour(today.minusDays(d.toLong()))
                request(Cmd.SYNC_HRV, Packets.hrv(d), 4000) { p.feed(it) }
                db.insertSeries(Db.HRV, p.out)
                if (!p.gotAny) break // ring didn't answer: no HRV support, don't keep waiting
            }

            stage(4, "Blood oxygen")
            requestBig(Cmd.BIG_SPO2)?.let { db.insertSpo2(Parsers.spo2(it, today)) }

            stage(5, "Sleep")
            requestBig(Cmd.BIG_SLEEP)?.let { db.insertSleep(Parsers.sleep(it, today)) }

            prefs.lastSync = System.currentTimeMillis()
            status.update { it.copy(lastSync = prefs.lastSync, progress = 1f) }
            db.bump()
            true
        }
        return ok == true
    }

    private suspend fun readSettings() {
        var s = settings.value ?: RingSettings()
        request(Cmd.HR_PREF, Packets.prefRead(Cmd.HR_PREF), 2000) {
            if (it.u(1) == 2) false else { s = s.copy(hrEnabled = it.u(2) == 1, hrInterval = it.u(3)); true }
        }
        request(Cmd.SPO2_PREF, Packets.prefRead(Cmd.SPO2_PREF), 2000) {
            if (it.u(1) == 2) false else { s = s.copy(spo2 = it.u(2) == 1); true }
        }
        request(Cmd.STRESS_PREF, Packets.prefRead(Cmd.STRESS_PREF), 2000) {
            if (it.u(1) == 2) false else { s = s.copy(stress = it.u(2) == 1); true }
        }
        request(Cmd.HRV_PREF, Packets.prefRead(Cmd.HRV_PREF), 2000) {
            if (it.u(1) == 2) false else { s = s.copy(hrv = it.u(2) == 1); true }
        }
        saveSettings(s)
    }

    suspend fun loadSettings() { withRing("Reading ring settings") { readSettings() } }

    suspend fun applySettings(s: RingSettings) {
        saveSettings(s)
        withRing("Saving settings") {
            send(Packets.hrPrefWrite(s.hrEnabled, s.hrInterval))
            send(Packets.togglePrefWrite(Cmd.SPO2_PREF, s.spo2))
            send(Packets.togglePrefWrite(Cmd.STRESS_PREF, s.stress))
            send(Packets.togglePrefWrite(Cmd.HRV_PREF, s.hrv))
            delay(300)
            request(Cmd.BATTERY, Packets.battery(), 2500) { true }
            readSettings()
            recordBattery()
        }
    }

    suspend fun findRing() { withRing("Flashing the ring") { send(Packets.find()); delay(500) } }

    suspend fun powerOff() { withRing("Turning the ring off") { send(Packets.powerOff()); delay(500) } }

    fun cancelLive() { stopLive = true }

    /** Live heart-rate (type 1) or blood-oxygen (type 3) measurement. */
    suspend fun measure(type: Int) {
        stopLive = false
        live.value = Live(type, null, true, "Connecting to the ring…")
        val result = withRing("Measuring") {
            live.value = Live(type, null, true, "Measuring… keep your hand still")
            val readings = mutableListOf<Int>()
            coroutineScope {
                val listener = launch(start = CoroutineStart.UNDISPATCHED) {
                    v1.collect { p ->
                        if (p.u(0) == Cmd.REALTIME && p.u(1) == type) {
                            val err = p.u(2)
                            val value = p.u(3)
                            if (err == 0 && value > 0) {
                                readings += value
                                live.value = Live(type, value, true, "Measuring… keep your hand still")
                            } else if (err == 1) {
                                live.value = Live(type, null, true, "The ring isn't sitting right. Push it snug on your finger.")
                            }
                        }
                    }
                }
                send(Packets.liveStart(type))
                val started = System.currentTimeMillis()
                var lastNudge = started
                val needed = if (type == Cmd.LIVE_HR) 10 else 5
                while (!stopLive && System.currentTimeMillis() - started < 50_000 && readings.size < needed) {
                    delay(500)
                    if (System.currentTimeMillis() - lastNudge > 10_000) {
                        send(Packets.liveContinue(type))
                        lastNudge = System.currentTimeMillis()
                    }
                }
                send(Packets.liveStop(type))
                listener.cancel()
            }
            readings.takeLast(4).takeIf { it.isNotEmpty() }?.average()?.roundToInt()
        }
        val now = System.currentTimeMillis()
        if (result != null) {
            if (type == Cmd.LIVE_HR) db.insertSeries(Db.HR, listOf(Point(now, result)))
            else db.insertSpo2(listOf(Spo2(now, result, result, result)))
            db.bump()
        }
        live.value = Live(
            type, result, false,
            when {
                result != null -> "Saved"
                stopLive -> "Stopped"
                else -> "No reading. Make sure the ring is snug and try again."
            }
        )
    }

    fun clearLive() { live.value = null }

    // ---------- Scanning for the ring ----------

    private var scanCallback: ScanCallback? = null

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        stopScan()
        scanResults.value = emptyList()
        scanning.value = true
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val name = r.scanRecord?.deviceName ?: r.device.name ?: return
                val f = Found(name, r.device.address, r.rssi, looksLikeRing(name))
                scanResults.update { list ->
                    (list.filter { it.address != f.address } + f)
                        .sortedWith(compareByDescending<Found> { it.likely }.thenByDescending { it.rssi })
                }
            }
        }
        scanCallback = cb
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        } catch (_: Exception) {
            scanning.value = false
            return
        }
        scope.launch {
            delay(15_000)
            stopScan()
        }
    }

    fun stopScan() {
        scanCallback?.let { cb -> try { adapter?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {} }
        scanCallback = null
        scanning.value = false
    }

    private fun looksLikeRing(n: String) =
        Regex("^(RZ|R|COLMI R)\\d{2}.*", RegexOption.IGNORE_CASE).matches(n) || n.contains("ring", ignoreCase = true)

    fun clearMessage() = status.update { it.copy(message = null) }
}
