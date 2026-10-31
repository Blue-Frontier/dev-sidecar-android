package org.blueFrontier.devSideCar.next.android

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

/** Per-host traffic counters, refreshed while the screen is visible. */
class TrafficActivity : Activity() {

    private val bg = Color.parseColor("#0F1115")
    private val ink = Color.parseColor("#E6E6E6")
    private val muted = Color.parseColor("#8A8A8A")
    private val warn = Color.parseColor("#E0A458")

    private lateinit var summary: TextView
    private lateinit var table: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var refreshing = false

    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 3000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "流量"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(40, 48, 40, 48)
        }

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(
            TextView(this).apply {
                text = "流量统计"
                textSize = 20f
                setTextColor(ink)
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        head.addView(Button(this).apply {
            text = "返回"
            setOnClickListener { finish() }
        })
        root.addView(head)

        summary = TextView(this).apply {
            textSize = 13f
            setTextColor(muted)
            setPadding(0, 12, 0, 12)
            text = "读取中…"
        }
        root.addView(summary)

        table = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(table) })

        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun render() {
        if (refreshing) return
        refreshing = true
        try {
            if (!KernelRuntime.isRunning()) {
                summary.text = "内核未运行"
                return
            }
            val json = runCatching { JSONObject(KernelRuntime.traffic()) }.getOrNull() ?: return
            val pool = json.optJSONObject("pool")
            summary.text = String.format(
                Locale.US,
                "请求 %d · 响应 %d · 错误 %d · 出站 %s\n复用中的上游连接 %d（分属 %d 个目标）",
                json.optInt("requests"),
                json.optInt("responses"),
                json.optInt("errors"),
                humanBytes(json.optLong("bytesOut")),
                pool?.optInt("idle") ?: 0,
                pool?.optInt("keys") ?: 0,
            )

            table.removeAllViews()
            val perHost = json.optJSONObject("perHost")
            if (perHost == null || perHost.length() == 0) {
                table.addView(TextView(this).apply {
                    text = "还没有流量记录"
                    textSize = 12f
                    setTextColor(muted)
                })
                return
            }
            table.addView(headerRow())
            perHost.keys().asSequence()
                .map { it to perHost.optJSONObject(it) }
                .filter { it.second != null }
                .sortedByDescending { it.second?.optInt("requests") ?: 0 }
                .forEach { (host, row) ->
                    table.addView(
                        dataRow(
                            host = host,
                            requests = row?.optInt("requests") ?: 0,
                            bytes = row?.optLong("bytes") ?: 0,
                            millis = row?.optLong("ms") ?: 0,
                            errors = row?.optInt("errors") ?: 0,
                        ),
                    )
                }
        } finally {
            refreshing = false
        }
    }

    private fun headerRow(): LinearLayout {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        line.addView(cell("域名", 0.42f, muted, bold = true, mono = true))
        line.addView(cell("请求", 0.14f, muted, bold = true))
        line.addView(cell("大小", 0.16f, muted, bold = true))
        line.addView(cell("平均", 0.16f, muted, bold = true))
        line.addView(cell("错误", 0.12f, muted, bold = true))
        return line
    }

    private fun dataRow(host: String, requests: Int, bytes: Long, millis: Long, errors: Int): LinearLayout {
        val avg = if (requests > 0) millis / requests else 0L
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        line.addView(cell(host, 0.42f, ink, mono = true))
        line.addView(cell(requests.toString(), 0.14f, ink))
        line.addView(cell(humanBytes(bytes), 0.16f, ink))
        line.addView(cell("$avg ms", 0.16f, if (avg > 800) warn else muted))
        line.addView(cell(errors.toString(), 0.12f, if (errors > 0) warn else muted))
        return line
    }

    private fun cell(
        value: String,
        weight: Float,
        color: Int,
        bold: Boolean = false,
        mono: Boolean = false,
    ): TextView = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        if (mono) typeface = Typeface.MONOSPACE
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
        else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }
}

