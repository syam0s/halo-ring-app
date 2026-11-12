package com.syam.haloring

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.workDataOf
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.syam.haloring.ble.RingManager
import com.syam.haloring.data.Db
import com.syam.haloring.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

class App : Application() {
    lateinit var db: Db
    lateinit var prefs: Prefs
    lateinit var ring: RingManager
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        db = Db(this)
        prefs = Prefs(this)
        ring = RingManager(this, db, prefs)
        SyncWorker.schedule(this, prefs.autoSync && prefs.address != null)
    }
}

fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

fun hasBlePermissions(ctx: Context): Boolean = blePermissions().all {
    ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
}

/** Quietly syncs the ring every hour in the background. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        if (app.prefs.address == null || !hasBlePermissions(app)) return Result.success()
        // Skip if the app synced very recently.
        if (System.currentTimeMillis() - app.prefs.lastSync < 20 * 60 * 1000L) return Result.success()
        withTimeoutOrNull(150_000) { app.ring.sync() }
        return Result.success()
    }

    companion object {
        private const val NAME = "ring-sync"
        fun schedule(ctx: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            if (enabled) {
                wm.enqueueUniquePeriodicWork(
                    NAME, ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).build()
                )
            } else {
                wm.cancelUniqueWork(NAME)
            }
        }
    }
}

/** One-off "time to wind down" notification, set from the Tonight tab. */
class BedtimeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bedtime reminder", NotificationManager.IMPORTANCE_DEFAULT))
        val bed = inputData.getString("bed") ?: ""
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Time to wind down")
            .setContentText(if (bed.isEmpty()) "Your planned bedtime is close." else "Aim to be in bed by $bed tonight.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(1001, n)
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "bedtime"
        private const val NAME = "bedtime-reminder"

        /** Show the reminder [minutesFromNow] minutes from now. Replaces any reminder already waiting. */
        fun schedule(ctx: Context, minutesFromNow: Int, bed: String) {
            val req = OneTimeWorkRequestBuilder<BedtimeWorker>()
                .setInitialDelay(minutesFromNow.toLong(), TimeUnit.MINUTES)
                .setInputData(workDataOf("bed" to bed))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
