package org.blueFrontier.devSideCar.next.android

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything configurable: kernel state, modes, the three config layers, stats. */
class KernelSettingsActivity : Activity() {

    private lateinit var statusLine: TextView
    private lateinit var statusDetail: TextView
    private lateinit var summaryBox: TextView
    private lateinit var configSource: TextView
    private lateinit var updateStatus: TextView
    private lateinit var personalUrlField: EditText
    private lateinit var personalStatus: TextView
    private lateinit var localEditor: EditText
    private lateinit var localStatus: TextView
    private lateinit var modeStatus: TextView
    private lateinit var homeField: EditText
    private lateinit var homeStatus: TextView
    private lateinit var statsBox: TextView
    private lateinit var logBox: TextView
    private lateinit var portField: EditText
    private lateinit var toggle: Button

    private var hasPersonal = false
    private var personalBusy = false

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            if (prefs.getBoolean("autoRefresh", true)) handler.postDelayed(this, 3000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "内核设置"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(40, 48, 40, 48)
        }

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@KernelSettingsActivity).apply {
                    text = "内核设置"
                    textSize = 20f
                    setTextColor(TEXT)
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@KernelSettingsActivity).apply {
                    text = "Go MITM 内核 · 与桌面 dev-sidecar 同源规则"
                    textSize = 12f
                    setTextColor(MUTED)
                })
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        head.addView(Button(this).apply {
            text = "返回"
            setOnClickListener { finish() }
        })
        root.addView(head)

        // 运行状态
        val statusCard = card("运行状态")
        statusLine = TextView(this).apply {
            textSize = 16f
            setTextColor(ACCENT)
            text = "…"
        }
        statusDetail = TextView(this).apply {
            textSize = 11f
            setTextColor(MUTED)
            typeface = Typeface.MONOSPACE
        }
        statusCard.addView(statusLine)
        statusCard.addView(statusDetail)
        statusCard.addView(buildActions())
        root.addView(statusCard)

        // 模式
        val modeCard = card("模式")
        modeStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(MUTED)
            text = "当前模式：默认"
        }
        modeCard.addView(modeStatus)
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((id, label) in listOf("default" to "默认", "safe" to "安全")) {
            modeRow.addView(Button(this).apply {
                text = label
                setOnClickListener { applyMode(id) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dpx(4)
            })
        }
        modeCard.addView(modeRow)
        modeCard.addView(TextView(this).apply {
            text = "默认：按规则拦截与改写；安全：关闭规则匹配，用于排查是不是规则导致的问题"
            textSize = 11f
            setTextColor(MUTED)
            setPadding(0, dpx(6), 0, 0)
        })
        root.addView(modeCard)

        // 主页
        val homeCard = card("主页")
        homeField = EditText(this).apply {
            setText(BrowserPrefs.home(this@KernelSettingsActivity))
            isSingleLine = true
            setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            textSize = 14f
            setPadding(dpx(12), dpx(8), dpx(12), dpx(8))
            backgroundTintList = android.content.res.ColorStateList.valueOf(STROKE)
        }
        homeCard.addView(homeField)
        homeStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(MUTED)
            setPadding(0, dpx(6), 0, dpx(6))
            text = "浏览器启动和 ⌂ 按钮打开的地址"
        }
        homeCard.addView(homeStatus)
        homeCard.addView(Button(this).apply {
            text = "保存"
            setOnClickListener {
                BrowserPrefs.setHome(this@KernelSettingsActivity, homeField.text.toString())
                homeField.setText(BrowserPrefs.home(this@KernelSettingsActivity))
                homeStatus.text = "已保存，返回浏览器点 ⌂ 生效"
            }
        })
        root.addView(homeCard)

        // 配置概览
        val summaryCard = card("配置概览")
        summaryBox = TextView(this).apply {
            textSize = 11f
            setTextColor(TEXT)
            typeface = Typeface.MONOSPACE
        }
        summaryCard.addView(summaryBox)
        root.addView(summaryCard)

        // 配置更新
        val updateCard = card("配置更新")
        configSource = TextView(this).apply {
            textSize = 11f
            setTextColor(MUTED)
            typeface = Typeface.MONOSPACE
        }
        updateCard.addView(configSource)
        updateCard.addView(TextView(this).apply {
            text = "下载的配置会保存到 app 私有目录，几秒内自动生效"
            textSize = 11f
            setTextColor(MUTED)
            setPadding(0, dpx(6), 0, dpx(6))
        })
        updateStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(MUTED)
        }
        updateCard.addView(updateStatus)
        val updateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        updateRow.addView(Button(this).apply {
            text = "检查更新"
            setOnClickListener { checkUpdate() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        updateRow.addView(Button(this).apply {
            text = "恢复内置"
            setOnClickListener { resetToBundled() }
        })
        updateCard.addView(updateRow)
        root.addView(updateCard)

        // 个人配置
        val personalCard = card("个人配置")
        personalUrlField = EditText(this).apply {
            hint = "https://…/remote_config_personal.json5"
            setText(KernelRuntime.savedPersonalUrl(this@KernelSettingsActivity))
            setTextColor(TEXT)
            textSize = 12f
            isSingleLine = true
            setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        }
        personalCard.addView(personalUrlField)
        personalStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(MUTED)
            setPadding(0, dpx(6), 0, dpx(6))
        }
        personalCard.addView(personalStatus)
        val personalRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        personalRow.addView(Button(this).apply {
            text = "下载并应用"
            setOnClickListener { applyPersonal() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        personalRow.addView(Button(this).apply {
            text = "清除"
            setOnClickListener { clearPersonal() }
        })
        personalCard.addView(personalRow)
        personalCard.addView(TextView(this).apply {
            text = "个人层会覆盖官方配置的同名项（对象深合并、数组整体替换、null 删除）"
            textSize = 11f
            setTextColor(MUTED)
            setPadding(0, dpx(6), 0, 0)
        })
        root.addView(personalCard)

        // 本地配置
        val localCard = card("本地配置")
        localCard.addView(TextView(this).apply {
            text = "优先级最高的一层，覆盖官方与个人配置；保存后自动生效"
            textSize = 11f
            setTextColor(MUTED)
        })
        localEditor = EditText(this).apply {
            setText(readUserConfig())
            setTextColor(TEXT)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            minLines = 5
            maxLines = 10
        }
        localCard.addView(localEditor)
        localStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(MUTED)
            setPadding(0, dpx(6), 0, dpx(6))
            text = "空表示不启用本地层"
        }
        localCard.addView(localStatus)
        val localRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        localRow.addView(Button(this).apply {
            text = "保存"
            setOnClickListener { saveUserConfig() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        localRow.addView(Button(this).apply {
            text = "清空"
            setOnClickListener { clearUserConfig() }
        })
        localRow.addView(Button(this).apply {
            text = "规则列表"
            setOnClickListener {
                startActivity(android.content.Intent(this@KernelSettingsActivity, RulesActivity::class.java))
            }
        })
        localCard.addView(localRow)
        root.addView(localCard)

        // 监听端口
        val portCard = card("监听端口")
        val portRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        portField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("port", KernelRuntime.DEFAULT_PORT).toString())
            setTextColor(TEXT)
            textSize = 15f
        }
        portRow.addView(portField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        portRow.addView(Button(this).apply {
            text = "保存"
            setOnClickListener { savePort() }
        })
        portCard.addView(portRow)
        portCard.addView(TextView(this).apply {
            text = "修改后需要重启内核才会重新绑定（默认 31191）"
            textSize = 11f
            setTextColor(MUTED)
        })
        root.addView(portCard)

        // 行为
        val behaviorCard = card("行为")
        behaviorCard.addView(switchRow("打开应用时自动启动内核", "autoStart", true) {})
        behaviorCard.addView(switchRow("运行时每 3 秒刷新统计", "autoRefresh", true) {})
        root.addView(behaviorCard)

        // 流量统计
        val statsCard = card("流量统计")
        statsBox = TextView(this).apply {
            textSize = 11f
            setTextColor(TEXT)
            typeface = Typeface.MONOSPACE
        }
        statsCard.addView(statsBox)
        val statsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statsRow.addView(Button(this).apply {
            text = "流量明细"
            setOnClickListener {
                startActivity(android.content.Intent(this@KernelSettingsActivity, TrafficActivity::class.java))
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statsRow.addView(Button(this).apply {
            text = "GITHUB 状态"
            setOnClickListener {
                startActivity(android.content.Intent(this@KernelSettingsActivity, GithubStatusActivity::class.java))
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statsRow.addView(Button(this).apply {
            text = "帮助"
            setOnClickListener {
                startActivity(android.content.Intent(this@KernelSettingsActivity, HelpActivity::class.java))
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statsCard.addView(statsRow)
        root.addView(statsCard)

        // 内核日志
        val logCard = card("内核日志（最近 12 行）")
        logBox = TextView(this).apply {
            textSize = 10f
            setTextColor(MUTED)
            typeface = Typeface.MONOSPACE
        }
        logCard.addView(logBox)
        root.addView(logCard)

        val scroll = ScrollView(this).apply { addView(root) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)

        // The mode row deliberately omits 增强; MainActivity carries the hidden
        // `--es mode ow` switch because this activity is not exported.
        refresh()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun buildActions(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toggle = Button(this).apply {
            text = "启动内核"
            setOnClickListener { onToggle() }
        }
        row.addView(toggle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "重启"
            setOnClickListener { restartKernel() }
        })
        return row
    }

    private fun onToggle() {
        if (KernelRuntime.isRunning()) {
            statusDetail.text = "正在停止…"
            KernelRuntime.stop()
            KernelService.stop(this)
        } else {
            statusDetail.text = "正在启动…"
            val listen = listenAddr()
            KernelRuntime.start(this, listen)
            KernelService.start(this, listen)
        }
        refresh()
    }

    private fun restartKernel() {
        KernelRuntime.stop()
        val listen = listenAddr()
        KernelRuntime.start(this, listen)
        KernelService.start(this, listen)
        refresh()
    }

    private fun savePort() {
        val port = portField.text.toString().toIntOrNull()
        if (port == null || port !in 1024..65535) {
            statusDetail.text = "端口需在 1024–65535 之间"
            return
        }
        prefs.edit().putInt("port", port).apply()
        statusDetail.text = "已保存 $port，点「重启」生效"
    }

    private fun listenAddr(): String = "127.0.0.1:" + prefs.getInt("port", KernelRuntime.DEFAULT_PORT)

    private fun checkUpdate() {
        val url = runCatching {
            JSONObject(KernelRuntime.summary()).optString("configUrl")
        }.getOrDefault("")
        if (url.isEmpty()) {
            updateStatus.text = "配置未声明更新源"
            return
        }
        updateStatus.text = "检查中…"
        Thread {
            val raw = KernelRuntime.updateConfig(this, url)
            handler.post {
                val json = runCatching { JSONObject(raw) }.getOrNull()
                if (json == null) {
                    updateStatus.text = "检查失败：内核返回无法解析"
                    return@post
                }
                updateStatus.text = when (json.optString("status")) {
                    "updated" -> "已更新到 ${json.optString("version")}，规则将自动生效"
                    "current" -> "已是最新（${json.optString("version")}）"
                    else -> "失败：${json.optString("error")}"
                }
                refresh()
            }
        }.start()
    }

    private fun resetToBundled() {
        KernelRuntime.resetConfig(this)
        updateStatus.text = "已恢复内置配置，规则将自动生效"
        refresh()
    }

    private fun applyPersonal() {
        if (personalBusy) return
        val url = personalUrlField.text.toString().trim()
        if (url.isEmpty()) {
            personalStatus.text = "先填写个人配置地址"
            return
        }
        KernelRuntime.savePersonalUrl(this, url)
        personalBusy = true
        personalStatus.text = "下载中…"
        Thread {
            val raw = KernelRuntime.updatePersonalConfig(this, url)
            handler.post {
                personalBusy = false
                val json = runCatching { JSONObject(raw) }.getOrNull()
                personalStatus.text = when {
                    json == null -> "无法解析内核返回"
                    json.optString("status") == "ok" -> "已应用个人配置，规则将自动生效"
                    else -> "失败：${json.optString("error")}"
                }
                refresh()
            }
        }.start()
    }

    private fun clearPersonal() {
        KernelRuntime.resetPersonalConfig(this)
        personalStatus.text = "已清除个人配置，规则将自动生效"
        refresh()
    }

    private fun applyMode(mode: String) {
        val ok = KernelRuntime.setMode(this, mode)
        if (!ok) {
            modeStatus.text = "切换失败，请检查本地配置是否可写"
            return
        }
        modeStatus.text = "当前模式：${modeLabel(mode)}（约 2 秒内生效）"
        statusDetail.text = "正在重载配置…"
        handler.postDelayed({ refresh() }, 2000L)
    }

    private fun modeLabel(mode: String): String = when (mode) {
        "safe" -> "安全"
        "ow" -> "增强"
        else -> "默认"
    }

    private fun userConfigFile(): File = File(filesDir, "user_config.json5")

    private fun readUserConfig(): String =
        runCatching { userConfigFile().takeIf { it.exists() }?.readText() }.getOrNull()
            ?: "{\n  // 本地层示例：覆盖某条规则（保存前可删）\n" +
            "  // server: { intercepts: { \"example.com\": { \".*\": { sni: \"baidu.com\" } } } }\n}"

    private fun saveUserConfig() {
        val text = localEditor.text.toString()
        if (text.isBlank()) {
            clearUserConfig()
            return
        }
        val checked = KernelRuntime.validateConfig(text)
        if (!checked.contains("\"ok\":true")) {
            localStatus.text = "格式错误：$checked"
            return
        }
        runCatching { userConfigFile().writeText(text) }
            .onSuccess { localStatus.text = "已保存，约 2 秒内自动生效" }
            .onFailure { localStatus.text = "保存失败：${it.message}" }
    }

    private fun clearUserConfig() {
        runCatching { userConfigFile().delete() }
        localEditor.setText("")
        localStatus.text = "已清空本地层，约 2 秒内自动生效"
    }

    private fun refresh() {
        val running = KernelRuntime.isRunning()
        statusLine.text = if (running) "运行中 · ${KernelRuntime.listenAddr()}" else "未启动"
        statusLine.setTextColor(if (running) ACCENT else MUTED)
        statusDetail.text = if (running) "handle=${KernelRuntime.currentHandle()}" else KernelRuntime.lastJson
        toggle.text = if (running) "停止内核" else "启动内核"
        modeStatus.text = "当前模式：${modeLabel(KernelRuntime.currentMode(this))}"
        summaryBox.text = renderSummary()
        configSource.text = runCatching {
            val j = JSONObject(KernelRuntime.summary())
            "源  " + j.optString("configUrl").ifEmpty { "未声明" }
        }.getOrDefault("源  未声明")
        if (!personalBusy) {
            hasPersonal = File(KernelRuntime.personalConfigPath(this)).exists()
            personalStatus.text = if (hasPersonal) "当前已启用个人配置" else "当前未启用个人配置"
        }
        statsBox.text = renderStats()
        logBox.text = renderLog()
    }

    private fun renderSummary(): String {
        val j = runCatching { JSONObject(KernelRuntime.summary()) }.getOrNull() ?: return "内核未运行"
        if (j.has("error")) return "内核未运行"

        val version = j.optLong("configVersion")
        val id = j.optString("configId")
        val meta = buildString {
            if (version > 0) append(fmtVersion(version))
            if (id.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(id)
            }
        }.ifEmpty { "（未声明）" }

        val updateRaw = j.optString("configUpdate")
        val update = if (updateRaw.length > 28) updateRaw.take(28) + "…" else updateRaw

        val lines = mutableListOf<String>()
        lines += "监听地址   " + j.optString("listen")
        lines += "配置版本   " + meta
        if (j.optBoolean("hasPersonal")) {
            val pid = j.optString("personalId").ifEmpty { "personal" }
            val pv = j.optLong("personalVersion", 0L)
            lines += "个人层     " + pid + if (pv > 0) " · " + fmtVersion(pv) else ""
        }
        if (update.isNotEmpty()) lines += "配置更新   " + update
        lines += "拦截域名   ${j.optInt("rules")} 个"
        lines += "白名单     ${j.optInt("whiteList")} 个"
        lines += "排除 IP    ${j.optInt("excludeIp")} 个"
        if (j.optInt("rulesRewritten") > 0) {
            lines += "改写规则   ${j.optInt("rulesRewritten")} 条（断言→排除项）"
        }
        if (j.optInt("rulesSkipped") > 0) {
            lines += "跳过规则   ${j.optInt("rulesSkipped")} 条（RE2 不支持）"
        }
        return lines.joinToString("\n")
    }

    private fun fmtVersion(v: Long): String =
        if (v <= 0) "（未声明）" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(v))

    private fun renderStats(): String {
        val j = runCatching { JSONObject(KernelRuntime.traffic()) }.getOrNull() ?: return "（不可用）"
        val sb = StringBuilder()
        sb.append("请求 ${j.optInt("requests")}   响应 ${j.optInt("responses")}\n")
        sb.append("注入 ${j.optInt("injected")}   隧道 ${j.optInt("bypassed")}   升级 ${j.optInt("upgrades")}\n")
        sb.append("停滞 ${j.optInt("stalls")}   错误 ${j.optInt("errors")}\n")
        sb.append("出站 ${human(j.optLong("bytesOut"))}")
        val perHost = j.optJSONObject("perHost")
        if (perHost != null && perHost.length() > 0) {
            sb.append("\n热门主机\n")
            perHost.keys().asSequence()
                .map { it to (perHost.optJSONObject(it)?.optInt("requests") ?: 0) }
                .sortedByDescending { it.second }
                .take(5)
                .forEach { (host, count) -> sb.append("  $host  $count\n") }
        }
        return sb.toString().trimEnd()
    }

    private fun renderLog(): String {
        val file = File(filesDir, "kernel.log")
        if (!file.exists()) return "（暂无日志）"
        val text = tail(file, 24 * 1024)
        if (text.isBlank()) return "（暂无日志）"
        return text.lines().filter { it.isNotBlank() }.takeLast(12).joinToString("\n") { line ->
            if (line.length > 200) line.substring(0, 200) + "…" else line
        }
    }

    private fun tail(file: File, maxBytes: Int): String = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val start = (length - maxBytes).coerceAtLeast(0)
            raf.seek(start)
            val buf = ByteArray((length - start).toInt())
            raf.readFully(buf)
            String(buf, Charsets.UTF_8)
        }
    }.getOrDefault("")

    private fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun card(title: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpx(14), dpx(12), dpx(14), dpx(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dpx(8).toFloat()
                setColor(CARD)
                setStroke(dpx(1), STROKE)
            }
        }
        box.addView(TextView(this).apply {
            text = title
            textSize = 12f
            setTextColor(ACCENT)
            setPadding(0, 0, 0, dpx(6))
        })
        return box
    }

    private fun switchRow(
        label: String,
        key: String,
        def: Boolean,
        onChange: (Boolean) -> Unit,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dpx(6), 0, dpx(6))
        }
        row.addView(
            TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(TEXT)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(Switch(this).apply {
            isChecked = prefs.getBoolean(key, def)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                onChange(checked)
            }
        })
        return row
    }

    private fun dpx(v: Int): Int = Math.round(v * resources.displayMetrics.density)

    companion object {
        private const val PREFS = "ds-kernel"
        private val BG = Color.parseColor("#0F1115")
        private val CARD = Color.parseColor("#181B21")
        private val STROKE = Color.parseColor("#262B33")
        private val TEXT = Color.parseColor("#E6E8EB")
        private val MUTED = Color.parseColor("#9AA4B2")
        private val ACCENT = Color.parseColor("#2FD8A6")
    }
}



