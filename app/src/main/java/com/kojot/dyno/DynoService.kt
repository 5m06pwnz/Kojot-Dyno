package com.kojot.dyno

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground Service that keeps the Dyno engine alive across screen-off, Doze, and background
 * restrictions. Android 14 requires a service type; we use specialUse with a manifest declaration.
 *
 * Lifecycle:
 *  - onCreate: open DB, recover mid-flight turns, create notification channel
 *  - onStartCommand: promote to foreground, acquire a partial wake lock (released on destroy)
 *  - onDestroy: kill llama-server, flush writer, release wake lock
 *
 * Clients bind via [LocalBinder] and talk to [engine] / [NativeBridge].
 */
class DynoService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    lateinit var engine: DynoEngine
        private set
    private var wakeLock: PowerManager.WakeLock? = null
    private var startedAsFg = false

    inner class LocalBinder : Binder() {
        fun getService(): DynoService = this@DynoService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        engine = DynoEngine(applicationContext, scope)
        scope.launch(Dispatchers.IO) {
            try {
                val summary = engine.recover()
                if (summary.any) {
                    Log.i(TAG, "Boot recovery: $summary")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "recover failed", t)
            }
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    runCatching { engine.stopRun("NOTIFICATION_STOP") }
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            else -> {
                promoteForeground()
                acquireWakeLock()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        try {
            engine.killNow()
        } catch (_: Throwable) {}
        // Flush synchronously before cancelling the scope; a fire-and-forget launch races
        // with job.cancel() and often never commits the last WAL frames.
        try {
            kotlinx.coroutines.runBlocking {
                runCatching { engine.dao.flush() }
            }
        } catch (_: Throwable) {}
        releaseWakeLock()
        job.cancel()
        super.onDestroy()
    }

    private fun promoteForeground() {
        if (startedAsFg) {
            // Refresh the notification text
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification("Dyno idle"))
            return
        }
        val notif = buildNotification("Dyno starting…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        startedAsFg = true
    }

    fun updateNotification(text: String) {
        if (!startedAsFg) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, DynoService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Launch the host activity if present; otherwise just a no-op content intent.
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kojot Dyno")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launch)
            .addAction(0, "Stop", stop)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Dyno Engine",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps llama-server and telemetry alive during stress runs"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kojot:dyno").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L) // 6h safety cap; service destroy releases earlier
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {}
        wakeLock = null
    }

    companion object {
        private const val TAG = "DynoService"
        const val CHANNEL_ID = "dyno_engine"
        const val NOTIF_ID = 42
        const val ACTION_STOP = "com.kojot.dyno.STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, DynoService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, DynoService::class.java).setAction(ACTION_STOP))
        }
    }
}
