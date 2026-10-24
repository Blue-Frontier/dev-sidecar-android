package com.bluefrontier.dscore.kernel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import org.json.JSONObject
import java.io.File

class KernelService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopKernel()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startKernel()
                return START_STICKY
            }
        }
    }

    private fun startKernel() {
        if (running) {
            postNotice("Kernel already running")
            return
        }
        createChannel()
        startForeground(1, notice("Starting kernel…"))
        val msg = try {
            val configPath = copyAsset("remote_config.json5")
            // CA + key are stored as TOP-LEVEL assets (aapt2 -A subdirectory
            // assets are not openable); write them into files/ca/ for the kernel.
            copyAssetFile("ca_ca.crt", "ca/ca.crt")
            copyAssetFile("ca_ca.key", "ca/ca.key")
            val caDir = File(filesDir, "ca").absolutePath
            val logFile = File(filesDir, "kernel.log").absolutePath
            mobile.Mobile.touch()
            val json = mobile.Mobile.startKernel(configPath, LISTEN, caDir, logFile)
            lastJson = json
            val h = try { JSONObject(json).getLong("handle") } catch (e: Exception) { 0L }
            handle = h
            running = h != 0L
            if (running) "Kernel listening on $LISTEN (handle=$h)" else "Kernel start failed: $json"
        } catch (e: Throwable) {
            lastJson = "exception: " + e
            running = false
            "Kernel error: ${e.message}"
        }
        postNotice(msg)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun stopKernel() {
        if (handle != 0L) {
            val err = mobile.Mobile.stopKernel(handle)
            if (err.isNotEmpty()) lastJson = "stop: " + err
        }
        handle = 0
        running = false
        postNotice("Kernel stopped")
        Toast.makeText(this, "Kernel stopped", Toast.LENGTH_SHORT).show()
    }

    private fun postNotice(text: String) {
        startForeground(1, notice(text))
    }

    private fun notice(text: String): Notification {
        val b = Notification.Builder(this, CH)
            .setContentTitle("ds-kernel")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
        if (Build.VERSION.SDK_INT >= 34) b.setOngoing(true)
        return b.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(NotificationChannel(CH, "ds-kernel", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    private fun copyAsset(name: String): String {
        val out = File(filesDir, name)
        assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out.absolutePath
    }

    private fun copyAssetFile(assetPath: String, outFile: String) {
        val dest = File(filesDir, outFile)
        dest.parentFile?.mkdirs()
        try {
            assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "copy asset $assetPath failed: $e")
        }
    }

    companion object {
        const val ACTION_START = "com.bluefrontier.dscore.kernel.START"
        const val ACTION_STOP = "com.bluefrontier.dscore.kernel.STOP"
        private const val CH = "ds-kernel"
        private const val TAG = "ds-kernel"
        private const val LISTEN = "127.0.0.1:31181"

        @Volatile private var running = false
        @Volatile private var handle: Long = 0
        @Volatile private var lastJson = "(none)"

        fun isRunning(): Boolean = running
        fun lastJson(): String = lastJson
    }
}
