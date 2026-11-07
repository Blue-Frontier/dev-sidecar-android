package org.blueFrontier.devSideCar.next.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast

/**
 * Keeps the process alive while the kernel serves the browser: a foreground
 * notice plus a wake lock, so screen-off does not suspend the listener.
 */
class KernelService : Service() {

    private var foregroundStarted = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            releaseWakeLock()
            KernelRuntime.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        // A ROM that refuses the specialUse type would otherwise kill the app in
        // onStartCommand and the kernel would never start; fall back to an ordinary
        // notice so the proxy still runs.
        foregroundStarted = runCatching {
            startForeground(NOTICE_ID, notice("内核 ${KernelRuntime.listenAddr()}"))
        }.isSuccess
        if (!foregroundStarted) postNotice("内核 ${KernelRuntime.listenAddr()}")
        holdWakeLock()
        val addr = intent?.getStringExtra(EXTRA_LISTEN) ?: KernelRuntime.savedListen(this)
        val msg = KernelRuntime.start(this, addr)
        postNotice(msg)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        return START_STICKY
    }

    private fun holdWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService("power") as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ds-next:kernel").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun postNotice(text: String) {
        if (foregroundStarted) {
            runCatching { startForeground(NOTICE_ID, notice(text)) }
        } else {
            runCatching {
                (getSystemService("notification") as NotificationManager).notify(NOTICE_ID, notice(text))
            }
        }
    }

    private fun notice(text: String): Notification {
        val b = Notification.Builder(this, CHANNEL)
            .setContentTitle("DS NEXT")
            .setContentText(text)
            // android.R.drawable.stat_sys_download, kept numeric so it cannot
            // drift with the SDK's resource table.
            .setSmallIcon(0x01080088)
        if (Build.VERSION.SDK_INT >= 34) b.setOngoing(true)
        return b.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService("notification") as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "DS NEXT 内核", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "org.blueFrontier.devSideCar.next.android.START"
        const val ACTION_STOP = "org.blueFrontier.devSideCar.next.android.STOP"
        const val EXTRA_LISTEN = "org.blueFrontier.devSideCar.next.android.LISTEN"

        private const val CHANNEL = "ds-kernel"
        private const val NOTICE_ID = 1

        fun start(ctx: Context, addr: String) {
            val i = Intent(ctx, KernelService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LISTEN, addr)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, KernelService::class.java).setAction(ACTION_STOP)
            ctx.startService(i)
        }
    }
}

