package com.gpt.hook

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.gpt.hook.api.ApiService
import com.gpt.hook.api.RelayConfig
import com.gpt.hook.diagnostics.HookConfigParser

/** Unified DSH GPT Hook and local API dashboard. */
class MainActivity : Activity() {
    private lateinit var page: LinearLayout
    private lateinit var status: TextView
    private lateinit var reportStatus: TextView
    private lateinit var editor: EditText
    private lateinit var report: EditText
    private lateinit var relayStatus: TextView
    private lateinit var relayEnabled: Switch
    private lateinit var relayLan: Switch
    private lateinit var relayPort: EditText
    private lateinit var relayApiKey: EditText
    private lateinit var relayDid: EditText
    private lateinit var relayToken: EditText
    private var relayRefreshing = false

    private data class Palette(
        val bg: Int,
        val surface: Int,
        val surfaceAlt: Int,
        val stroke: Int,
        val primary: Int,
        val primaryText: Int,
        val white: Int,
        val muted: Int,
        val warning: Int,
        val switchTrack: Int,
        val secondaryButton: Int,
        val reportText: Int,
        val hint: Int,
        val lightSystemBars: Boolean
    )

    private lateinit var palette: Palette
    private val bg get() = palette.bg
    private val surface get() = palette.surface
    private val surfaceAlt get() = palette.surfaceAlt
    private val stroke get() = palette.stroke
    private val primary get() = palette.primary
    private val primaryText get() = palette.primaryText
    private val white get() = palette.white
    private val muted get() = palette.muted
    private val warning get() = palette.warning


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemTheme()

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            clipToPadding = false
        }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(30))
        }
        scroll.addView(page, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        setContentView(scroll)

        buildHeader()
        buildHookSection()
        buildObservationSection()
        buildRelaySection(page)
        refreshStatus()
        refreshReport()
    }

    private fun applySystemTheme() {
        val night = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        palette = if (night) {
            Palette(
                bg = 0xFF090E1B.toInt(),
                surface = 0xFF121B2D.toInt(),
                surfaceAlt = 0xFF0D1525.toInt(),
                stroke = 0xFF26354D.toInt(),
                primary = 0xFF63E6D3.toInt(),
                primaryText = 0xFF071A1C.toInt(),
                white = 0xFFF1F5FB.toInt(),
                muted = 0xFF91A0B7.toInt(),
                warning = 0xFFFFC978.toInt(),
                switchTrack = 0xFF42616B.toInt(),
                secondaryButton = 0xFF1B2940.toInt(),
                reportText = 0xFFB9C8DA.toInt(),
                hint = 0xFF66758D.toInt(),
                lightSystemBars = false
            )
        } else {
            Palette(
                bg = 0xFFF4F7FB.toInt(),
                surface = 0xFFFFFFFF.toInt(),
                surfaceAlt = 0xFFF7F9FC.toInt(),
                stroke = 0xFFDCE4EE.toInt(),
                primary = 0xFF087F75.toInt(),
                primaryText = 0xFFFFFFFF.toInt(),
                white = 0xFF172338.toInt(),
                muted = 0xFF66758A.toInt(),
                warning = 0xFF9A5A00.toInt(),
                switchTrack = 0xFF9BBAB5.toInt(),
                secondaryButton = 0xFFE8EEF5.toInt(),
                reportText = 0xFF34445B.toInt(),
                hint = 0xFF8996A8.toInt(),
                lightSystemBars = true
            )
        }
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = if (palette.lightSystemBars) {
            android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else {
            0
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshReport()
        if (this::relayStatus.isInitialized) renderRelayStatus(RelayConfig.load(this))
    }

    private fun buildHeader() {
        val hero = panel()
        hero.addView(text("DSH  /  GPT BRIDGE", 11f, primary, true).apply {
            letterSpacing = 0.16f
        })
        hero.addView(text("GPT Runtime Console", 27f, white, true).apply {
            setPadding(0, dp(8), 0, dp(5))
        })
        hero.addView(text("LSPosed Hook  ·  Local API Gateway", 13f, muted))
        status = text("正在连接 LSPosed…", 13f, muted, true).apply {
            setPadding(0, dp(16), 0, 0)
        }
        hero.addView(status)
        page.addView(hero, sectionParams())
        page.addView(text("控制台", 12f, muted, true).apply {
            setPadding(dp(3), dp(5), 0, dp(10))
        })
    }

    private fun buildHookSection() {
        val card = sectionCard("GPT Hook", "配置目标应用中的 Hook 规则")
        card.addView(text("目标应用  ·  com.openai.chatgpt", 12f, primary, true))
        editor = EditText(this).apply {
            setText(DEFAULT_CONFIG)
            minLines = 7
            gravity = Gravity.TOP
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(white)
            setHintTextColor(muted)
            background = rounded(surfaceAlt, stroke, 12)
            setPadding(dp(13), dp(12), dp(13), dp(12))
            setHorizontallyScrolling(true)
        }
        card.addView(editor, fieldParams(0, dp(12)))
        card.addView(buttonRow(
            ("验证并应用" to true) to { saveConfig() },
            ("恢复默认" to false) to { editor.setText(DEFAULT_CONFIG) }
        ))
        page.addView(card, sectionParams())
    }

    private fun buildObservationSection() {
        val card = sectionCard("运行时观察", "查看 Hook 产生的元数据报告")
        reportStatus = text("等待观察报告…", 12f, muted)
        card.addView(reportStatus)
        report = EditText(this).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            minLines = 5
            maxLines = 10
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(palette.reportText)
            background = rounded(surfaceAlt, stroke, 12)
            setPadding(dp(13), dp(12), dp(13), dp(12))
            setText(EMPTY_REPORT)
        }
        card.addView(report, fieldParams(0, dp(12)))
        card.addView(buttonRow(
            ("刷新" to true) to { refreshReport() },
            ("复制 JSON" to false) to { copyReport() },
            ("分享给 AI" to false) to { shareReport() }
        ))
        page.addView(card, sectionParams())
    }

    private fun buildRelaySection(root: LinearLayout) {
        val card = sectionCard("本地 API 网关", "将 ChatGPT 会话能力通过本机 OpenAI 兼容接口提供")
        relayStatus = text("已停止", 13f, warning, true)
        card.addView(relayStatus)
        card.addView(text("http://127.0.0.1:8766/v1", 12f, primary, true).apply {
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = rounded(surfaceAlt, stroke, 10)
        }, fieldParams(0, dp(12)))

        relayEnabled = Switch(this).apply {
            text = "启用本地 API"
            textSize = 14f
            setTextColor(white)
            thumbTintList = ColorStateList.valueOf(primary)
            trackTintList = ColorStateList.valueOf(palette.switchTrack)
            setOnCheckedChangeListener { _, checked ->
                if (relayRefreshing) return@setOnCheckedChangeListener
                val cfg = RelayConfig.load(this@MainActivity)
                if (cfg.enabled == checked) return@setOnCheckedChangeListener
                val next = cfg.copy(enabled = checked)
                RelayConfig.save(this@MainActivity, next)
                if (checked) ApiService.start(this@MainActivity) else ApiService.stop(this@MainActivity)
                renderRelayStatus(next)
            }
        }
        card.addView(relayEnabled)

        relayLan = Switch(this).apply {
            text = "允许局域网访问"
            textSize = 14f
            setTextColor(white)
            thumbTintList = ColorStateList.valueOf(primary)
            trackTintList = ColorStateList.valueOf(0xFF42616B.toInt())
            setOnCheckedChangeListener { _, checked ->
                if (relayRefreshing) return@setOnCheckedChangeListener
                val cfg = RelayConfig.load(this@MainActivity)
                if (cfg.lan == checked) return@setOnCheckedChangeListener
                val next = cfg.copy(lan = checked)
                RelayConfig.save(this@MainActivity, next)
                if (next.enabled) restartRelayService()
                renderRelayStatus(next)
            }
        }
        card.addView(relayLan)

        relayPort = addInput(card, "监听端口", "8766", InputType.TYPE_CLASS_NUMBER)
        relayApiKey = addInput(
            card, "客户端 API Key", "留空表示不校验",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        )
        relayDid = addInput(card, "oai-did", "设备标识", InputType.TYPE_CLASS_TEXT)
        relayToken = addInput(
            card, "access_token", "粘贴 ChatGPT access_token",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        ).apply {
            minLines = 3
            maxLines = 6
            isSingleLine = false
            gravity = Gravity.TOP
            typeface = Typeface.MONOSPACE
        }

        card.addView(buttonRow(
            ("保存并重启" to true) to { saveRelayAndRestart() },
            ("导入 kit.json" to false) to { importRelayKit() }
        ))
        card.addView(buttonRow(
            ("复制 API 配置" to false) to { copyRelayCommand() },
            ("测试连接" to false) to { testRelayHealth() }
        ))
        root.addView(card, sectionParams())
        fillRelayControls(RelayConfig.load(this))
    }

    private fun sectionCard(title: String, subtitle: String): LinearLayout {
        val card = panel()
        card.addView(text(title, 18f, white, true))
        card.addView(text(subtitle, 12f, muted).apply {
            setPadding(0, dp(4), 0, dp(14))
        })
        return card
    }

    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(surface, stroke, 18)
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun addInput(parent: LinearLayout, label: String, hint: String, inputType: Int): EditText {
        parent.addView(text(label, 12f, muted, true).apply {
            setPadding(dp(1), dp(11), 0, dp(5))
        })
        return EditText(this).apply {
            this.hint = hint
            this.inputType = inputType
            textSize = 13f
            setTextColor(white)
            setHintTextColor(palette.hint)
            background = rounded(surfaceAlt, stroke, 11)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            if (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE == 0) isSingleLine = true
            parent.addView(this, fieldParams(0, dp(2)))
        }
    }

    private fun buttonRow(vararg items: Pair<Pair<String, Boolean>, () -> Unit>) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
            items.forEachIndexed { index, item ->
                val (label, isPrimary) = item.first
                val action = item.second
                val button = TextView(this@MainActivity).apply {
                    text = label
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(if (isPrimary) primaryText else white)
                    background = rounded(
                        if (isPrimary) primary else palette.secondaryButton,
                        if (isPrimary) primary else stroke, 11
                    )
                    setPadding(dp(8), dp(13), dp(8), dp(13))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { action() }
                }
                addView(button, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    if (index > 0) leftMargin = dp(7)
                })
            }
        }

    private fun rounded(color: Int, border: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), border)
        }

    private fun sectionParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(14) }

    private fun fieldParams(top: Int, bottom: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        topMargin = top
        bottomMargin = bottom
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun fillRelayControls(cfg: RelayConfig) {
        relayRefreshing = true
        try {
            relayEnabled.isChecked = cfg.enabled
            relayLan.isChecked = cfg.lan
            relayPort.setText(cfg.port.toString())
            relayApiKey.setText(cfg.apiKey)
            relayDid.setText(cfg.oaiDid)
            relayToken.setText(cfg.accessToken)
        } finally {
            relayRefreshing = false
        }
        renderRelayStatus(cfg)
    }

    private fun relayStatusLine(cfg: RelayConfig): String {
        val state = if (cfg.enabled) "运行中" else "已停止"
        val host = if (cfg.lan) "0.0.0.0" else "127.0.0.1"
        val ready = if (cfg.ready) "凭据就绪" else "等待 access_token / oai-did"
        return "$state  ·  $host:${cfg.port}  ·  $ready"
    }

    private fun renderRelayStatus(cfg: RelayConfig) {
        relayStatus.text = relayStatusLine(cfg)
        relayStatus.setTextColor(if (cfg.enabled && cfg.ready) primary else warning)
    }

    private fun currentRelayConfig(base: RelayConfig): RelayConfig {
        val typed = relayPort.text.toString().trim().toIntOrNull()
        val port = if (typed != null && typed in 1..65535) typed else base.port
        return base.copy(
            port = port,
            apiKey = relayApiKey.text.toString().trim(),
            oaiDid = relayDid.text.toString().trim(),
            accessToken = relayToken.text.toString().trim()
        )
    }

    private fun restartRelayService() {
        ApiService.stop(this)
        ApiService.start(this)
    }

    private fun saveRelayAndRestart() {
        val next = currentRelayConfig(RelayConfig.load(this))
        RelayConfig.save(this, next)
        if (next.enabled) restartRelayService()
        fillRelayControls(next)
        toast(if (next.enabled) "配置已保存并请求重启" else "配置已保存")
    }

    private fun importRelayKit() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val json = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this).toString()
        } else ""
        if (json.isBlank()) {
            toast("剪贴板为空，请先复制 kit.json 内容")
            return
        }
        try {
            val next = RelayConfig.importKit(this, json)
            fillRelayControls(next)
            toast("kit.json 导入成功")
        } catch (t: Throwable) {
            toast("导入失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun copyRelayCommand() {
        val cfg = currentRelayConfig(RelayConfig.load(this))
        val content = "base_url=http://127.0.0.1:${cfg.port}/v1\napi_key=${cfg.apiKey}"
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("DSH GPT API", content))
        toast("客户端 API 配置已复制")
    }

    private fun testRelayHealth() {
        val cfg = currentRelayConfig(RelayConfig.load(this))
        val url = "http://127.0.0.1:${cfg.port}/health"
        relayStatus.text = "正在测试 $url"
        Thread {
            val result = try {
                val connection = (java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection)
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                connection.disconnect()
                "HTTP $code  ·  ${body.take(240)}"
            } catch (t: Throwable) {
                "连接失败：${t.javaClass.simpleName}"
            }
            runOnUiThread {
                relayStatus.text = result
                relayStatus.setTextColor(if (result.startsWith("HTTP 2")) primary else warning)
            }
        }.start()
    }

    private fun refreshStatus() {
        val service = DshApplication.currentService
        status.text = if (service == null) {
            "●  LSPosed 未连接  ·  请启用模块并重新打开"
        } else {
            "●  LSPosed 已连接  ·  ${service.frameworkName} / API ${service.apiVersion}"
        }
        status.setTextColor(if (service == null) warning else primary)
    }

    private fun saveConfig() {
        val service = DshApplication.currentService
        if (service == null) {
            status.text = "LSPosed 服务未连接，无法应用配置"
            status.setTextColor(warning)
            return
        }
        try {
            val source = editor.text.toString()
            val parsed = HookConfigParser.parse(source)
            service.getRemotePreferences("dsh").edit()
                .putString("hook_config", source)
                .apply()
            status.text = "●  配置已提交  ·  ${parsed.rules.size} 条规则"
            status.setTextColor(primary)
        } catch (t: Throwable) {
            status.text = "配置被拒绝：${t.message ?: t.javaClass.simpleName}"
            status.setTextColor(warning)
        }
    }

    private fun refreshReport() {
        try {
            val result = contentResolver.call(
                Uri.parse("content://${ObservationProvider.AUTHORITY}"),
                ObservationProvider.METHOD_READ, null, null
            )
            val json = result?.getString(ObservationProvider.KEY_REPORT) ?: EMPTY_REPORT
            report.setText(json)
            val updated = result?.getLong(ObservationProvider.KEY_UPDATED) ?: 0L
            reportStatus.text = if (updated == 0L) {
                "尚无报告 · 在目标应用触发已配置的方法后刷新"
            } else {
                "已收到报告 · ${updated} · ${json.length} 字符"
            }
        } catch (t: Throwable) {
            reportStatus.text = "读取失败：${t.javaClass.simpleName}"
        }
    }

    private fun copyReport() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("DSH observations", report.text))
        toast("观察报告已复制")
    }

    private fun shareReport() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "DSH GPT Hook observations")
            putExtra(Intent.EXTRA_TEXT, report.text.toString())
        }
        startActivity(Intent.createChooser(send, "分享观察报告"))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val EMPTY_REPORT =
            """{"schemaVersion":1,"kind":"dsh-hook-observations","events":[]}"""
        private const val DEFAULT_CONFIG = """{
  "schemaVersion": 1,
  "targetPackage": "com.openai.chatgpt",
  "rules": []
}"""
    }
}
