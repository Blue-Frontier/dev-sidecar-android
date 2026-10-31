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
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/** Probes the GitHub endpoints through the kernel so the failing layer is obvious. */
class GithubStatusActivity : Activity() {

    private data class Target(val label: String, val url: String)

    private val targets = listOf(
        Target("github.com", "https://github.com/"),
        Target("api.github.com", "https://api.github.com/"),
        Target("raw.githubusercontent.com", "https://raw.githubusercontent.com/octocat/Hello-World/master/README"),
        Target("github.githubassets.com", "https://github.githubassets.com/favicons/favicon.svg"),
        Target("codeload.github.com", "https://codeload.github.com/octocat/Hello-World/tar.gz/refs/heads/master"),
        Target("objects.githubusercontent.com", "https://objects.githubusercontent.com/"),
    )

    private val bg = Color.parseColor("#0F1115")
    private val ink = Color.parseColor("#E6E6E6")
    private val muted = Color.parseColor("#8A8A8A")
    private val ok = Color.parseColor("#2FD8A6")
    private val bad = Color.parseColor("#D9534F")
    private val slow = Color.parseColor("#E0A458")

    private lateinit var table: LinearLayout
    private lateinit var status: TextView

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastError = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "GitHub 状态"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(40, 48, 40, 48)
        }

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(
            TextView(this).apply {
                text = "GitHub 连通性"
                textSize = 20f
                setTextColor(ink)
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        head.addView(Button(this).apply {
            text = "重新检测"
            setOnClickListener { runChecks() }
        })
        head.addView(Button(this).apply {
            text = "返回"
            setOnClickListener { finish() }
        })
        root.addView(head)

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(muted)
            setPadding(0, 12, 0, 12)
            text = "通过内核代理逐个探测，点击「重新检测」刷新"
        }
        root.addView(status)

        table = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(table) })

        setContentView(root)
        runChecks()
    }

    private fun runChecks() {
        if (!KernelRuntime.isRunning()) {
            status.text = "内核未运行，先启动内核再检测"
            return
        }
        table.removeAllViews()
        status.text = "检测中…"
        val port = KernelRuntime.savedPort(this)
        Thread {
            val results = targets.map { it to probe(it.url, port) }
            handler.post { render(results) }
        }.start()
    }

    private fun probe(url: String, port: Int): Pair<Int, Long> {
        val started = System.nanoTime()
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
                as HttpURLConnection
            if (conn is HttpsURLConnection) {
                val (factory, verifier) = KernelTrust.setup()
                conn.sslSocketFactory = factory
                conn.hostnameVerifier = verifier
            }
            conn.connectTimeout = 12_000
            conn.readTimeout = 12_000
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            conn.setRequestProperty("Range", "bytes=0-0")
            val code = conn.responseCode
            code to (System.nanoTime() - started) / 1_000_000
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
            0 to (System.nanoTime() - started) / 1_000_000
        } finally {
            conn?.disconnect()
        }
    }

    private fun render(results: List<Pair<Target, Pair<Int, Long>>>) {
        table.removeAllViews()
        var healthy = 0
        for ((target, result) in results) {
            val code = result.first
            if (code in 200..399) healthy += 1
            table.addView(row(target.label, code, result.second))
        }
        status.text = if (healthy == 0 && lastError.isNotEmpty()) {
            "全部失败：$lastError"
        } else {
            "可达 $healthy/${results.size}"
        }
    }

    private fun row(label: String, code: Int, ms: Long): LinearLayout {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 10)
        }
        line.addView(
            TextView(this).apply {
                text = label
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(ink)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.54f),
        )
        line.addView(
            TextView(this).apply {
                text = if (code == 0) "失败" else code.toString()
                textSize = 12f
                setTextColor(if (code in 200..399) ok else bad)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.23f),
        )
        line.addView(
            TextView(this).apply {
                text = String.format(Locale.US, "%d ms", ms)
                textSize = 12f
                setTextColor(if (ms > 2000) slow else muted)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.23f),
        )
        return line
    }
}

