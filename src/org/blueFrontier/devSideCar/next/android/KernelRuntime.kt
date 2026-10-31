package org.blueFrontier.devSideCar.next.android

import android.content.Context
import mobile.Mobile
import org.json.JSONObject
import java.io.File
import kotlin.io.copyTo as copyStream

/**
 * Process-wide handle to the in-process kernel. The kernel itself lives in Go
 * (gomobile); this keeps the handle, the listen address and the paths of the
 * configuration layers the UI edits.
 */
object KernelRuntime {

    const val DEFAULT_PORT = 31191
    private const val PREFS = "ds-kernel"

    @Volatile
    private var running = false

    @Volatile
    private var handle = 0L

    @Volatile
    var lastJson: String = "(none)"
        private set

    @Volatile
    private var listen = "127.0.0.1:31191"

    fun isRunning(): Boolean = running

    fun currentHandle(): Long = handle

    fun listenAddr(): String = listen

    fun savedPort(ctx: Context): Int = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("port", DEFAULT_PORT)

    fun savedListen(ctx: Context): String = "127.0.0.1:${savedPort(ctx)}"

    @Synchronized
    fun start(ctx: Context, addr: String): String {
        if (running) return "already running on $listen"
        listen = addr
        return try {
            val config = ensureConfig(ctx)
            copyAsset(ctx, "ca_ca.crt", "ca/ca.crt")
            copyAsset(ctx, "ca_ca.key", "ca/ca.key")
            val caDir = File(ctx.filesDir, "ca").absolutePath
            val logFile = File(ctx.filesDir, "kernel.log").absolutePath
            Mobile.touch()
            val json = Mobile.startKernel(config, addr, caDir, logFile)
            lastJson = json
            handle = runCatching { JSONObject(json).getLong("handle") }.getOrDefault(0L)
            running = handle != 0L
            if (running) "listening on $addr (handle=$handle)" else "start failed: $json"
        } catch (e: Throwable) {
            lastJson = "exception: $e"
            running = false
            "error: ${e.message}"
        }
    }

    @Synchronized
    fun stop() {
        if (handle != 0L) {
            val err = runCatching { Mobile.stopKernel(handle) }.getOrDefault("")
            if (err.isNotEmpty()) lastJson = "stop: $err"
        }
        handle = 0L
        running = false
    }

    fun summary(): String =
        if (running) Mobile.kernelSummary(handle) else "{\"error\":\"kernel not running\"}"

    fun configPath(ctx: Context): String = File(ctx.filesDir, "remote_config.json5").absolutePath

    fun updateConfig(ctx: Context, url: String): String {
        if (url.isEmpty()) return "{\"status\":\"error\",\"error\":\"no config url\"}"
        return Mobile.updateConfig(url, configPath(ctx))
    }

    fun resetConfig(ctx: Context): Boolean {
        File(ctx.filesDir, "remote_config.json5").delete()
        ensureConfig(ctx)
        return true
    }

    fun personalConfigPath(ctx: Context): String =
        File(ctx.filesDir, "remote_config_personal.json5").absolutePath


    fun ruleText(ctx: Context, host: String): String =
        runCatching { Mobile.ruleText(configPath(ctx), host) }.getOrDefault("{}")

    fun setRuleText(ctx: Context, host: String, text: String): Boolean =
        runCatching { Mobile.setRuleText(configPath(ctx), host, text) }
            .getOrDefault("")
            .contains("\"ok\":true")

    fun resetRuleText(ctx: Context, host: String): Boolean =
        runCatching { Mobile.resetRuleText(configPath(ctx), host) }
            .getOrDefault("")
            .contains("\"ok\":true")

    fun setMode(ctx: Context, mode: String): Boolean =
        runCatching { Mobile.setMode(configPath(ctx), mode) }
            .getOrDefault("")
            .contains("\"ok\":true")

    fun currentMode(ctx: Context): String =
        runCatching { Mobile.currentMode(configPath(ctx)) }.getOrDefault("default")

    fun traffic(): String = if (running) Mobile.kernelStats(handle) else "{}"

    fun rules(): String =
        if (running) Mobile.kernelRules(handle) else "{\"error\":\"kernel not running\"}"

    fun setRuleEnabled(ctx: Context, host: String, pattern: String, enabled: Boolean): Boolean =
        runCatching { Mobile.setRuleEnabled(configPath(ctx), host, pattern, enabled) }
            .getOrDefault("")
            .contains("\"ok\":true")

    fun validateConfig(text: String): String =
        runCatching { Mobile.validateConfig(text) }.getOrDefault("validation failed")

    fun updatePersonalConfig(ctx: Context, url: String): String {
        if (url.isEmpty()) return "{\"status\":\"error\",\"error\":\"no personal config url\"}"
        return Mobile.updatePersonalConfig(url, personalConfigPath(ctx))
    }

    fun resetPersonalConfig(ctx: Context): Boolean =
        runCatching { File(personalConfigPath(ctx)).delete() }.getOrDefault(false)

    fun savedPersonalUrl(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("personalUrl", "").orEmpty()

    fun savePersonalUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("personalUrl", url).apply()
    }

    /** Copies the bundled official config out of assets on first run. */
    private fun ensureConfig(ctx: Context): String {
        val out = File(ctx.filesDir, "remote_config.json5")
        if (!out.exists()) {
            ctx.assets.open("remote_config.json5").use { input ->
                out.outputStream().use { input.copyStream(it) }
            }
        }
        return out.absolutePath
    }

    private fun copyAsset(ctx: Context, asset: String, dest: String): String {
        val out = File(ctx.filesDir, dest)
        out.parentFile?.mkdirs()
        ctx.assets.open(asset, android.content.res.AssetManager.ACCESS_STREAMING).use { input ->
            out.outputStream().use { input.copyStream(it) }
        }
        return out.absolutePath
    }
}

