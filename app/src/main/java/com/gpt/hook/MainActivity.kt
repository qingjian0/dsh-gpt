package com.gpt.hook

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.gpt.hook.api.ApiService
import com.gpt.hook.api.RelayConfig
import com.gpt.hook.diagnostics.HookConfigParser

/** On-device configuration and metadata-only observation console. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var reportStatus: TextView
    private lateinit var editor: EditText
    private lateinit var report: EditText

    // 本地 API 网关控件
    private lateinit var relayStatus: TextView
    private lateinit var relayEnabled: Switch
    private lateinit var relayLan: Switch
    private lateinit var relayPort: EditText
    private lateinit var relayApiKey: EditText
    private lateinit var relayDid: EditText
    private lateinit var relayToken: EditText
    private var relayRefreshing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        status = TextView(this).apply { text = "Connecting to LSPosed service…" }
        editor = EditText(this).apply {
            setText(DEFAULT_CONFIG)
            minLines = 10
            gravity = android.view.Gravity.TOP
            typeface = android.graphics.Typeface.MONOSPACE
            setHorizontallyScrolling(true)
        }
        root.addView(status)
        root.addView(TextView(this).apply { text = "Hook configuration (JSON)" })
        root.addView(ScrollView(this).apply {
            addView(editor)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f
            )
        })
        root.addView(Button(this).apply {
            text = "Validate and apply configuration"
            setOnClickListener { saveConfig() }
        })
        root.addView(Button(this).apply {
            text = "Restore empty safe configuration"
            setOnClickListener { editor.setText(DEFAULT_CONFIG) }
        })
        root.addView(reportStatusText().also { reportStatus = it })
        root.addView(TextView(this).apply { text = "Latest metadata-only observations" })
        report = EditText(this).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            minLines = 6
            typeface = android.graphics.Typeface.MONOSPACE
            setText(EMPTY_REPORT)
        }
        root.addView(ScrollView(this).apply {
            addView(report)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f
            )
        })
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = "Refresh"
                setOnClickListener { refreshReport() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Copy JSON"
                setOnClickListener { copyReport() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Share to AI"
                setOnClickListener { shareReport() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        buildRelaySection(root)
        setContentView(root)
        refreshStatus()
        refreshReport()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshReport()
        if (this::relayStatus.isInitialized) renderRelayStatus(RelayConfig.load(this))
    }

    private fun reportStatusText() = TextView(this).apply { text = "Observation transport: waiting" }

    // ---------------------------------------------------------------- 本地 API 网关

    private fun buildRelaySection(root: LinearLayout) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 32, 0, 0)
        }
        box.addView(TextView(this).apply {
            text = "本地 API 网关"
            textSize = 18f
            setPadding(0, 0, 0, 8)
        })
        relayStatus = TextView(this).apply { text = "已停止" }
        box.addView(relayStatus)

        relayEnabled = Switch(this).apply {
            text = "启用"
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
        box.addView(relayEnabled)

        relayLan = Switch(this).apply {
            text = "局域网监听（0.0.0.0）"
            setOnCheckedChangeListener { _, checked ->
                if (relayRefreshing) return@setOnCheckedChangeListener
                val cfg = RelayConfig.load(this@MainActivity)
                if (cfg.lan == checked) return@setOnCheckedChangeListener
                val next = cfg.copy(lan = checked)
                RelayConfig.save(this@MainActivity, next)
                // ApiService.restart() 是实例方法，这里用 stop + start 让它按新配置重建监听
                if (next.enabled) restartRelayService()
                renderRelayStatus(next)
            }
        }
        box.addView(relayLan)

        box.addView(labelView("端口（1-65535）"))
        relayPort = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = RelayConfig().port.toString()
        }
        box.addView(relayPort)

        box.addView(labelView("client apiKey（留空表示不校验）"))
        relayApiKey = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            hint = "可留空"
        }
        box.addView(relayApiKey)

        box.addView(labelView("oai-did"))
        relayDid = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            hint = "oai-did"
        }
        box.addView(relayDid)

        box.addView(labelView("access_token"))
        relayToken = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = false
            minLines = 3
            maxLines = 8
            gravity = android.view.Gravity.TOP
            typeface = android.graphics.Typeface.MONOSPACE
            hint = "Bearer 后面的 token"
        }
        box.addView(relayToken)

        box.addView(
            buttonRow(
                "保存并重启" to { saveRelayAndRestart() },
                "导入 kit.json（粘贴）" to { importRelayKit() },
            )
        )
        box.addView(
            buttonRow(
                "复制启动命令" to { copyRelayCommand() },
                "测试 /health" to { testRelayHealth() },
            )
        )

        root.addView(ScrollView(this).apply {
            addView(box)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f
            )
        })
        fillRelayControls(RelayConfig.load(this))
    }

    private fun labelView(text: String) = TextView(this).apply {
        this.text = text
        setPadding(0, 12, 0, 0)
    }

    private fun buttonRow(vararg items: Pair<String, () -> Unit>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((label, action) in items) {
            addView(Button(this@MainActivity).apply {
                text = label
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /** 回填控件；期间屏蔽 Switch 监听，避免写盘 + 启停服务的递归触发。 */
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
        val ready = if (cfg.ready) "就绪" else "未就绪（缺 access_token / oai-did）"
        return "$state · 端口 ${cfg.port} · $host · $ready"
    }

    private fun renderRelayStatus(cfg: RelayConfig) {
        relayStatus.text = relayStatusLine(cfg)
    }

    /** 读控件 + 校验端口，合并到已有配置上。 */
    private fun currentRelayConfig(base: RelayConfig): RelayConfig {
        val typed = relayPort.text.toString().trim().toIntOrNull()
        val port = if (typed != null && typed in 1..65535) typed else base.port
        return base.copy(
            port = port,
            apiKey = relayApiKey.text.toString().trim(),
            oaiDid = relayDid.text.toString().trim(),
            accessToken = relayToken.text.toString().trim(),
        )
    }

    private fun restartRelayService() {
        ApiService.stop(this)
        ApiService.start(this)
    }

    private fun saveRelayAndRestart() {
        val base = RelayConfig.load(this)
        val next = currentRelayConfig(base)
        RelayConfig.save(this, next)
        if (next.enabled) restartRelayService()
        fillRelayControls(next)
        val hint = if (next.enabled) "已保存并重启" else "已保存（未启用，未启动服务）"
        relayStatus.text = hint + "\n" + relayStatusLine(next)
    }

    private fun importRelayKit() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val json = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this).toString()
        } else ""
        if (json.isBlank()) {
            relayStatus.text = "剪贴板为空：先复制 kit.json 内容再点这里"
            return
        }
        try {
            val next = RelayConfig.importKit(this, json)
            fillRelayControls(next)
            relayStatus.text = "已导入 kit.json\n" + relayStatusLine(next)
        } catch (t: Throwable) {
            relayStatus.text = "导入失败: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    private fun copyRelayCommand() {
        val cfg = currentRelayConfig(RelayConfig.load(this))
        val cmd = "curl -H \"Authorization: Bearer ${cfg.apiKey}\" " +
            "http://127.0.0.1:${cfg.port}/v1/models"
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("local api curl", cmd))
        relayStatus.text = "已复制启动命令\n$cmd"
    }

    /** 后台线程请求 /health，结果回主线程写状态。 */
    private fun testRelayHealth() {
        val cfg = currentRelayConfig(RelayConfig.load(this))
        val url = "http://127.0.0.1:${cfg.port}/health"
        relayStatus.text = "测试中… $url"
        Thread {
            val body = try {
                val conn = (java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 3000
                    readTimeout = 3000
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                conn.disconnect()
                "HTTP $code\n" + text.take(600)
            } catch (t: Throwable) {
                "请求失败: ${t.javaClass.simpleName}: ${t.message ?: ""}"
            }
            runOnUiThread { relayStatus.text = "$url\n$body" }
        }.start()
    }

    private fun refreshStatus() {
        val service = DshApplication.currentService
        status.text = if (service == null) {
            "LSPosed service unavailable. Enable the module and open it again."
        } else {
            "Connected: ${service.frameworkName} / API ${service.apiVersion}\n" +
                "Scope: ${service.scope.joinToString()}"
        }
    }

    private fun saveConfig() {
        val service = DshApplication.currentService
        if (service == null) {
            status.text = "Cannot save: LSPosed service is not connected."
            return
        }
        try {
            val source = editor.text.toString()
            val parsed = HookConfigParser.parse(source)
            service.getRemotePreferences("dsh").edit()
                .putString("hook_config", source)
                .apply()
            status.text = "Saved: ${parsed.rules.size} rule(s). The target process validates and applies the update."
        } catch (t: Throwable) {
            status.text = "Rejected: ${t.message ?: t.javaClass.simpleName}"
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
                "No observation report received yet. Trigger a configured method in the target app."
            } else {
                "Report received: ${updated} (epoch ms), ${json.length} characters"
            }
        } catch (t: Throwable) {
            reportStatus.text = "Report read failed: ${t.javaClass.simpleName}"
        }
    }

    private fun copyReport() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("DSH observations", report.text))
        reportStatus.text = "Observation JSON copied to clipboard."
    }

    private fun shareReport() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "DSH metadata-only Hook observations")
            putExtra(Intent.EXTRA_TEXT, report.text.toString())
        }
        startActivity(Intent.createChooser(send, "Send sanitized report to an AI assistant"))
    }

    companion object {
        private const val EMPTY_REPORT = """{"schemaVersion":1,"kind":"dsh-hook-observations","events":[]}"""
        private const val DEFAULT_CONFIG = """{
  "schemaVersion": 1,
  "targetPackage": "com.openai.chatgpt",
  "rules": []
}"""
    }
}
