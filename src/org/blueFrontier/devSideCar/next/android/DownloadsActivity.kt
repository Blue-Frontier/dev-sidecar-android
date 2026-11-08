package org.blueFrontier.devSideCar.next.android

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Lists the download queue and lets a transfer be paused, resumed or dropped. */
class DownloadsActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, if (Downloads.active) 500L else 1500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "下载"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dpx(16), dpx(12), dpx(16), dpx(12))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "下载"
            textSize = 20f
            setTextColor(TEXT)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = "返回"
            setOnClickListener { finish() }
        })
        root.addView(header)

        empty = TextView(this).apply {
            text = "还没有下载任务。\n在页面里点一个下载链接，任务会出现在这里。"
            textSize = 13f
            setTextColor(MUTED)
            setPadding(0, dpx(24), 0, 0)
        }
        root.addView(empty)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        setContentView(root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(dpx(16), insets.systemWindowInsetTop + dpx(12), dpx(16), insets.systemWindowInsetBottom + dpx(12))
            insets
        }
        // `am start -S -n <pkg>/.DownloadsActivity --es downloadUrl <url>` queues
        // a transfer without going through the browser; the WebView otherwise
        // aborts the load that would have produced it. The activity is exported
        // so it must not let another app queue cookie-bearing requests, and only
        // a web URL is ever handed to the downloader.
        if (launchedFromShell()) {
            intent.getStringExtra("downloadUrl")?.let { raw ->
                val scheme = android.net.Uri.parse(raw).scheme?.lowercase()
                if (scheme == "http" || scheme == "https") {
                    Downloads.start(this, raw, UA, null, null)
                }
            }
        }
        handler.post(ticker)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    private fun render() {
        val tasks = Downloads.tasks
        empty.visibility = if (tasks.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        list.removeAllViews()
        for (task in tasks) list.addView(row(task))
    }

    private fun row(task: Downloads.Task): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CARD)
            setPadding(dpx(14), dpx(12), dpx(14), dpx(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dpx(10) }
        }

        card.addView(TextView(this).apply {
            text = task.name
            textSize = 14f
            setTextColor(TEXT)
            maxLines = 2
        })

        // A bar is only meaningful while bytes are moving: once the transfer is
        // done or has failed it would just take up space.
        if (task.state == Downloads.State.RUNNING || task.state == Downloads.State.PAUSED) {
            val known = task.total > 0 && task.percent >= 0
            card.addView(
                android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    isIndeterminate = !known
                    progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                    if (known) progress = task.percent.coerceIn(0, 100)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dpx(6),
                    ).apply { topMargin = dpx(8) }
                },
            )
        }

        val percent = task.percent
        val detail = buildString {
            if (percent >= 0) append("$percent%  ") else if (task.state == Downloads.State.DONE) append("100%  ")
            append(size(task.bytes))
            if (task.total > 0) append(" / ").append(size(task.total))
            when (task.state) {
                Downloads.State.RUNNING -> append("  · 下载中")
                Downloads.State.PAUSED -> append("  · 已暂停")
                Downloads.State.DONE -> append("  · 已完成")
                Downloads.State.FAILED -> append("  · 失败")
            }
        }
        card.addView(TextView(this).apply {
            text = detail
            textSize = 12f
            setTextColor(if (task.state == Downloads.State.FAILED) FAILED else MUTED)
            setPadding(0, dpx(4), 0, dpx(8))
        })

        task.error?.let { message ->
            card.addView(TextView(this).apply {
                text = message
                textSize = 11f
                setTextColor(FAILED)
                setPadding(0, 0, 0, dpx(8))
            })
        }

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        when (task.state) {
            Downloads.State.RUNNING -> actions.addView(action("暂停") { Downloads.pause(task) })
            Downloads.State.PAUSED, Downloads.State.FAILED ->
                actions.addView(action("继续") { Downloads.resume(this, task) })
            Downloads.State.DONE -> actions.addView(action("打开") {
                runCatching {
                    task.uri?.let {
                        startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW)
                                .setDataAndType(it, task.mime)
                                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        )
                    }
                }
            })
        }
        if (task.state != Downloads.State.DONE || task.uri == null) {
            actions.addView(action("取消") { Downloads.cancel(this, task) })
        } else {
            actions.addView(action("移除") { Downloads.tasks.remove(task); render() })
        }
        card.addView(actions)
        return card
    }

    private fun action(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dpx(8)
            }
        }

    private fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }

    private fun dpx(v: Int): Int = Math.round(v * resources.displayMetrics.density)

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private val BG = Color.parseColor("#0F1115")
        private val CARD = Color.parseColor("#181B21")
        private val TEXT = Color.parseColor("#E6E8EB")
        private val MUTED = Color.parseColor("#9AA4B2")
        private val FAILED = Color.parseColor("#FF7A7A")
private val ACCENT = Color.parseColor("#4C8DFF")
    }
}

