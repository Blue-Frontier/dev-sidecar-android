package org.blueFrontier.devSideCar.next.android

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.Switch
import android.widget.TextView
import org.json.JSONArray

/** Lists every configured host and lets each rule block be toggled or edited. */
class RulesActivity : Activity() {

    private val bg = Color.parseColor("#0F1115")
    private val ink = Color.parseColor("#E6E6E6")
    private val muted = Color.parseColor("#8A8A8A")
    private val accent = Color.parseColor("#2FD8A6")

    private lateinit var container: LinearLayout
    private lateinit var status: TextView

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "规则"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(40, 48, 40, 48)
        }

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(
            TextView(this).apply {
                text = "规则列表"
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

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(muted)
            setPadding(0, 12, 0, 12)
            text = "开关改动约 2 秒后生效（热更新，不断线）"
        }
        root.addView(status)

        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(container) })

        setContentView(root)
        render()
    }

    private fun render() {
        container.removeAllViews()
        val array = runCatching { JSONArray(KernelRuntime.rules()) }.getOrNull()
        if (array == null) {
            container.addView(label("内核未运行或规则不可用", muted))
            return
        }

        val byHost = LinkedHashMap<String, MutableList<Pair<String, Boolean>>>()
        val descs = HashMap<String, String>()
        val sources = HashMap<String, String>()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val host = o.optString("host")
            byHost.getOrPut(host) { mutableListOf() }
                .add(o.optString("pattern") to o.optBoolean("enabled", true))
            o.optString("desc").takeIf { it.isNotEmpty() }?.let { descs[host] = it }
            o.optString("source").takeIf { it.isNotEmpty() }?.let { sources[host] = it }
        }

        val total = byHost.values.sumOf { it.size }
        status.text = "共 ${byHost.size} 个域名、$total 条规则"

        for ((host, patterns) in byHost) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 10, 0, 10)
            }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            top.addView(
                TextView(this).apply {
                    text = host
                    textSize = 13f
                    setTextColor(ink)
                    typeface = Typeface.MONOSPACE
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            val enabled = patterns.any { it.second }
            top.addView(Switch(this).apply {
                isChecked = enabled
                setOnCheckedChangeListener { _, checked ->
                    var ok = true
                    for ((pattern, _) in patterns) {
                        ok = KernelRuntime.setRuleEnabled(this@RulesActivity, host, pattern, checked) && ok
                    }
                    status.text = if (ok) {
                        "已${if (checked) "启用" else "停用"} $host（约 2 秒生效）"
                    } else {
                        "写入失败：$host"
                    }
                }
            })
            row.addView(top)

            val meta = buildString {
                val src = sources[host] ?: "official"
                append("来源 $src · ${patterns.size} 条规则")
                descs[host]?.let { append(" · ").append(it) }
            }
            row.addView(label(meta, muted))
            // Tap edits the host block, long-press drops the local override.
            row.isClickable = true
            row.setOnClickListener { editHost(host) }
            row.setOnLongClickListener {
                confirmReset(host)
                true
            }
            container.addView(row)
            container.addView(Space(this).apply { layoutParams = LinearLayout.LayoutParams(1, 1) })
        }
    }

    private fun editHost(host: String) {
        val editor = EditText(this).apply {
            setText(KernelRuntime.ruleText(this@RulesActivity, host))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            minLines = 6
            maxLines = 14
        }
        AlertDialog.Builder(this)
            .setTitle("编辑 $host")
            .setView(ScrollView(this).apply { addView(editor) })
            .setPositiveButton("保存") { _, _ ->
                val ok = KernelRuntime.setRuleText(this, host, editor.text.toString())
                status.text = if (ok) "已保存 $host" else "保存失败：JSON 格式不正确"
                status.setTextColor(if (ok) accent else Color.parseColor("#D9534F"))
                render()
            }
            .setNeutralButton("恢复默认") { _, _ ->
                val ok = KernelRuntime.resetRuleText(this, host)
                status.text = if (ok) "已恢复 $host 的默认规则" else "恢复失败"
                status.setTextColor(if (ok) accent else Color.parseColor("#D9534F"))
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmReset(host: String) {
        AlertDialog.Builder(this)
            .setTitle("恢复默认")
            .setMessage("将删除本地层里 $host 的覆盖配置，回到官方/个人层的规则。")
            .setPositiveButton("恢复") { _, _ ->
                val ok = KernelRuntime.resetRuleText(this, host)
                status.text = if (ok) "已恢复 $host 的默认规则" else "恢复失败"
                status.setTextColor(if (ok) accent else Color.parseColor("#D9534F"))
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun label(value: String, color: Int): TextView = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(color)
    }
}
