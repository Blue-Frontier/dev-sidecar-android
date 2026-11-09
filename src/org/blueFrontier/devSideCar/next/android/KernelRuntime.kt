package org.blueFrontier.devSideCar.next.android

import android.content.Context
import mobile.Mobile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.io.copyTo as copyStream

/**
 * Process-wide handle to the in-process kernel. The kernel itself lives in Go
 * (gomobile); this keeps the handle, the listen address and the paths of the
 * configuration layers the UI edits.
 */
object KernelRuntime {

    const val DEFAULT_PORT = 31191
    private const val PREFS = "ds-kernel"

    /**
     * The CA that older builds shipped in the APK. Its private key was public,
     * so an install that still has it must drop it: the kernel then mints a
     * fresh authority for this device.
     */
    private const val LEGACY_CA_SHA256 =
        "5a2efbad2d1927b35bb566cd5b8f8f3f46b950039695011f8a6da02eac57dde8"

    /**
     * Which CA scheme this install uses. Anything below 2 predates the
     * device-minted CA, so its authority is discarded on the first start after
     * the upgrade and replaced by one random pair that this device keeps.
     * Bump the constant to rotate every install's CA, exactly once, later.
     */
    private const val CA_EPOCH = 2
    private const val KEY_CA_EPOCH = "caEpoch"

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

    /**
     * The debug flavour is a separate package and can run next to the release
     * app, so it must not fight it for the same port.
     */
    fun defaultPort(ctx: Context): Int =
        if (ctx.packageName.endsWith(".debug")) DEFAULT_PORT + 1 else DEFAULT_PORT

    fun savedPort(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("port", defaultPort(ctx))

    fun savedListen(ctx: Context): String = "127.0.0.1:${savedPort(ctx)}"

    @Synchronized
    fun start(ctx: Context, addr: String): String {
        if (running) return "already running on $listen"
        listen = addr
        return try {
            val config = ensureConfig(ctx)
            val caDir = ensureOwnCa(ctx)
            val logFile = File(ctx.filesDir, "kernel.log").absolutePath
              // Pinned shared ECH config: fetching it live needs a resolver
              // that publishes ECH parameters, which a blocked network cannot reach.
              runCatching { ctx.assets.open("ech_shared.b64").bufferedReader().use { it.readText() } }
                  .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                  ?.let { b64 -> runCatching { Mobile.setSharedECH(b64) } }
            runCatching { Mobile.setSystemDNS(systemDnsServers(ctx).joinToString(",")) }
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

      /** DNS servers of the network the device is actually using. */
      private fun systemDnsServers(ctx: Context): Array<String> {
          val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return emptyArray()
          val network = cm.activeNetwork ?: return emptyArray()
          val props = cm.getLinkProperties(network) ?: return emptyArray()
          return props.dnsServers.mapNotNull { it.hostAddress }.toTypedArray()
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

    /** The CA the kernel minted on this device (absent until the first start). */
    fun caCertFile(ctx: Context): File = File(ctx.filesDir, "ca/ca.crt")

    /**
     * Hands the kernel a CA directory and decides whether it gets to keep what
     * is in there. On a fresh install the directory is empty, so
     * `certcache.Load` mints the authority (RSA 2048, ten years, CN
     * `dev-sidecar-core CA`) before the proxy starts listening; afterwards the
     * pair is left alone, so it is generated exactly once per install.
     */
    private fun ensureOwnCa(ctx: Context): String {
        val dir = File(ctx.filesDir, "ca")
        val cert = File(dir, "ca.crt")
        val key = File(dir, "ca.key")
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // First start after the upgrade: whatever CA an older build left in
        // files/ca is discarded, so the kernel has to mint a new random one.
        val upgrade = prefs.getInt(KEY_CA_EPOCH, 0) < CA_EPOCH
        // Fingerprint check as well: a downgrade puts the shared, publicly known
        // key back, and the epoch alone would then wave it through.
        val sharedCa = cert.isFile && cert.sha256() == LEGACY_CA_SHA256
        // A pair missing one half (killed between the two writes) cannot be
        // loaded; dropping it lets the kernel regenerate instead of failing.
        val halfWritten = cert.exists() != key.exists()

        if (upgrade || sharedCa || halfWritten) {
            val certGone = !cert.exists() || cert.delete()
            val keyGone = !key.exists() || key.delete()
            // Only a fully discarded pair earns the new epoch; a failed delete
            // is retried on the next start instead of pinning a stale CA.
            if (upgrade && certGone && keyGone) {
                prefs.edit().putInt(KEY_CA_EPOCH, CA_EPOCH).commit()
            }
        }
        dir.mkdirs()
        return dir.absolutePath
    }

    private fun File.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}




