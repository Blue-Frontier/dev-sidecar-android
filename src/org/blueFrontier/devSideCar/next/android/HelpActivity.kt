package org.blueFrontier.devSideCar.next.android

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Static explanation of what the app does and how the pieces fit together. */
class HelpActivity : Activity() {

    private val bg = Color.parseColor("#0F1115")
    private val ink = Color.parseColor("#E6E6E6")
    private val muted = Color.parseColor("#8A8A8A")

    private val sections = listOf(
        "这是什么" to "dev-sidecar 的 Android 移植版：把桌面版的 Go 内核跑在手机里，" +
            "对内置浏览器的请求做拦截、改写与加速。不用 VPN，也不改系统代理。",
        "拦截怎么生效" to "浏览器通过应用内的 HTTP 代理把请求交给本机内核（127.0.0.1:31191），" +
            "命中规则时由内核直接应答或改写，未命中则照常转发到源站；POST 与登录也一样。",
        "证书" to "内核用自签 CA 给访问的站点临时签发证书，信任链在应用内完成：" +
            "浏览器的请求由本应用转发，不需要安装系统证书。只有让其它应用也走这个内核时才需要装。",
        "两种模式" to "默认：按规则拦截与改写；\n" +
            "安全：关闭规则匹配，用于排查问题是不是规则导致的。",
        "配置层级" to "官方远程配置 → 个人远程配置 → 本地配置，后者覆盖前者。" +
            "对象做深合并、数组整体替换、null 表示删除该键。",
        "规则" to "「规则」页按域名列出全部规则，右侧开关控制是否启用；" +
            "点一行可以编辑该域名的规则 JSON，长按可恢复默认（删除本地覆盖）。",
        "流量与诊断" to "「流量明细」按域名统计请求数、大小、平均延迟与错误数；" +
            "「GitHub 状态」逐个探测 GitHub 相关端点，用来判断是规则问题还是网络问题。",
        "常见问题" to "页面打不开：先切到「安全」模式，若恢复正常则是规则问题；\n" +
            "部分资源失败：看「流量明细」里哪个域名在报错；\n" +
            "配置改坏了：设置页的「本地配置」清空即可回到官方配置。",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "帮助"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(40, 48, 40, 48)
        }

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(
            TextView(this).apply {
                text = "帮助"
                textSize = 20f
                setTextColor(ink)
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        head.addView(
            Button(this).apply {
                text = "返回"
                setOnClickListener { finish() }
            },
        )
        root.addView(head)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((sectionTitle, text) in sections) {
            body.addView(
                TextView(this).apply {
                    this.text = sectionTitle
                    textSize = 15f
                    setTextColor(ink)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 20, 0, 6)
                },
            )
            body.addView(
                TextView(this).apply {
                    this.text = text
                    textSize = 13f
                    setTextColor(muted)
                    setLineSpacing(6f, 1f)
                },
            )
        }
        root.addView(ScrollView(this).apply { addView(body) })

        setContentView(root)
    }
}


