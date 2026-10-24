package com.bluefrontier.dscore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * Concentrates the proxy on this app only.
 *
 * Two modes, selected by [EXTRA_MODE]:
 *
 *  - [MODE_PROXY] (default): the VPN publishes an HTTP proxy and adds no route,
 *    so the tun carries no packets; the proxy setting is the only effect.
 *    Whether Android applies a proxy to a routeless VPN network is exactly what
 *    we need to verify on a device. With the kernel on 127.0.0.1 the connect to
 *    the proxy stays on loopback either way.
 *
 *  - [MODE_ROUTE]: also routes 0.0.0.0/0 into the tun. Without a packet pump
 *    (netstack) this blackholes traffic, so it is only for the next milestone.
 */
class ProxyVpnService : VpnService() {

    companion object {
        private const val TAG = "ds-vpn"

        const val ACTION_START = "com.bluefrontier.dscore.START"
        const val ACTION_STOP = "com.bluefrontier.dscore.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_MODE = "mode"

        const val MODE_PROXY = "proxy"
        const val MODE_ROUTE = "route"

        private const val CHANNEL_ID = "ds-core-vpn"
        private const val NOTIFICATION_ID = 1001
        private const val VPN_ADDRESS = "10.111.222.1"

        /** Status sink used by the activity; the process is shared. */
        @Volatile
        var statusListener: ((String) -> Unit)? = null

        fun report(message: String) {
            Log.i(TAG, message)
            statusListener?.invoke(message)
        }
    }

    private var tun: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: "127.0.0.1"
                val port = intent.getIntExtra(EXTRA_PORT, 31181)
                val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_PROXY
                startForegroundCompat("proxy $host:$port")
                startTunnel(host, port, mode)
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        report("VPN revoked by the system")
        shutdown()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun startTunnel(host: String, port: Int, mode: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            report("需要 Android 10 及以上（setHttpProxy）")
            return
        }

        shutdown()

        val builder = Builder()
            .setSession("ds-core")
            .setMtu(1500)
            .addAddress(VPN_ADDRESS, 32)
            .setHttpProxy(ProxyInfo.buildDirectProxy(host, port))

        try {
            // Only this app is covered, so the rest of the device is untouched.
            builder.addAllowedApplication(packageName)
        } catch (e: Exception) {
            Log.w(TAG, "addAllowedApplication failed", e)
        }

        if (mode == MODE_ROUTE) {
            builder.addRoute("0.0.0.0", 0)
        }

        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            report("establish 失败: ${e.message}")
            null
        }
        if (fd == null) {
            report("VPN 未能建立（可能被系统拒绝）")
            return
        }
        tun = fd
        report("已建立：模式=$mode 代理=$host:$port")
    }

    private fun shutdown() {
        val fd = tun
        tun = null
        try {
            fd?.close()
        } catch (e: Exception) {
            Log.w(TAG, "closing tun failed", e)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ds-core proxy",
                NotificationManager.IMPORTANCE_LOW,
            )
            nm.createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat(text: String) {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ds-core")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setContentIntent(open)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                builder.build(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, builder.build())
        }
    }
}
