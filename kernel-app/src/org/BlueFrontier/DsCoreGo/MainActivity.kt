package org.BlueFrontier.DsCoreGo

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var handleText: TextView
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            textSize = 16f
            text = "内核：未启动"
        }
        handleText = TextView(this).apply {
            textSize = 12f
            alpha = 0.7f
            text = "（无）"
        }
        toggle = Button(this).apply {
            setOnClickListener { onToggle() }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(handleText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 8 })
            addView(toggle, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 })
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(32, insets.getSystemWindowInsetTop() + 32, 32, insets.getSystemWindowInsetBottom())
            insets
        }
        setContentView(root)

        // Self-contained: bring the kernel up when the app opens.
        if (!KernelService.isRunning()) requestStart()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        window.decorView.postDelayed({ refresh() }, 1500)
    }

    private fun refresh() {
        val running = KernelService.isRunning()
        status.text = if (running) "内核运行中：127.0.0.1:31181" else "内核未启动"
        handleText.text = "handle: " + KernelService.lastJson()
        toggle.text = if (running) "停止内核" else "启动内核"
    }

    private fun onToggle() {
        if (KernelService.isRunning()) {
            val i = Intent(this, KernelService::class.java)
            i.action = KernelService.ACTION_STOP
            startService(i)
            status.text = "已请求停止"
        } else {
            requestStart()
        }
        window.decorView.postDelayed({ refresh() }, 1500)
    }

    private fun requestStart() {
        val i = Intent(this, KernelService::class.java)
        i.action = KernelService.ACTION_START
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        status.text = "正在启动…"
    }
}
