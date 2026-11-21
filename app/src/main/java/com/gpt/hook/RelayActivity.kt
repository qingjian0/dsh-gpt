package com.gpt.hook

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.gpt.hook.api.ApiService
import com.gpt.hook.api.RelayConfig
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class RelayActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var enabledSwitch: Switch
    private lateinit var lanSwitch: Switch

    private lateinit var portInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var didInput: EditText
    private lateinit var tokenInput: EditText

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val logs = ArrayDeque<String>()

    private var updating = false

    private val horizontalPadding = 20

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        buildLayout()
        loadConfig()
        refreshStatus()
        appendLog("本地 API 网关设置界面已初始化")
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun buildLayout() {
        val outerScroll = ScrollView(this).apply {
            isFillViewport = true
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(horizontalPadding), dp(20), dp(horizontalPadding), dp(24))
        }

        outerScroll.addView(
            root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(outerScroll)

        val title = TextView(this).apply {
            text = "DSH GPT · 本地 API 网关"
            textSize = 23f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF202124.toInt())
        }
        root.addView(title)

        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setPadding(0, dp(12), 0, dp(18))
        }
        root.addView(statusView)

        addDivider()

        enabledSwitch = Switch(this).apply {
            text = "启用"
            textSize = 16f
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, checked ->
                if (!updating) {
                    saveAndApply()
                    appendLog(if (checked) "已请求启动服务" else "已请求停止服务")
                }
            }
        }
        root.addView(enabledSwitch)

        lanSwitch = Switch(this).apply {
            text = "局域网监听 0.0.0.0"
            textSize = 16f
            setPadding(0, dp(8), 0, dp(12))
            setOnCheckedChangeListener { _, _ ->
                if (!updating) {
                    saveAndApply()
                    appendLog("监听地址设置已更新")
                }
            }
        }
        root.addView(lanSwitch)

        addDivider()

        addSectionTitle("网关配置")

        portInput = addInput(
            label = "端口",
            hint = "8766",
            inputType = InputType.TYPE_CLASS_NUMBER
        )

        apiKeyInput = addInput(
            label = "客户端 API Key",
            hint = "留空表示不设置客户端密钥",
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
        )

        didInput = addInput(
            label = "oai-did",
            hint = "输入 oai-did",
            inputType = InputType.TYPE_CLASS_TEXT
        )

        tokenInput = addInput(
            label = "access_token",
            hint = "输入 access_token",
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        ).apply {
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
            setHorizontallyScrolling(false)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        addDivider()

        addSectionTitle("操作")

        val buttonRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        buttonRow1.addView(
            createButton("保存并重启") {
                saveAndRestart()
            },
            weightedParams()
        )

        buttonRow1.addView(
            createButton("导入 kit.json") {
                importKit()
            },
            weightedParams()
        )

        root.addView(buttonRow1)

        val buttonRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        buttonRow2.addView(
            createButton("复制客户端配置") {
                copyClientConfig()
            },
            weightedParams()
        )

        buttonRow2.addView(
            createButton("测试 /health") {
                testHealth()
            },
            weightedParams()
        )

        root.addView(buttonRow2)

        addDivider()

        addSectionTitle("运行日志")

        logScroll = ScrollView(this).apply {
            isFillViewport = false
            setBackgroundColor(0xFFF3F4F6.toInt())
            isVerticalScrollBarEnabled = true
        }

        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF303030.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            text = ""
        }

        logScroll.addView(
            logView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            logScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(220)
            )
        )
    }

    private fun addSectionTitle(value: String) {
        val view = TextView(this).apply {
            text = value
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF303030.toInt())
            setPadding(0, dp(14), 0, dp(10))
        }
        root.addView(view)
    }

    private fun addDivider() {
        val divider = View(this).apply {
            setBackgroundColor(0xFFE0E0E0.toInt())
        }
        root.addView(
            divider,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        )
    }

    private fun addInput(
        label: String,
        hint: String,
        inputType: Int
    ): EditText {
        val labelView = TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(0xFF555555.toInt())
            setPadding(0, dp(8), 0, dp(5))
        }
        root.addView(labelView)

        val editText = EditText(this).apply {
            this.hint = hint
            this.inputType = inputType
            textSize = 14f
            setSingleLine(
                inputType == InputType.TYPE_CLASS_NUMBER ||
                    inputType == InputType.TYPE_CLASS_TEXT ||
                    inputType == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        root.addView(
            editText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        return editText
    }

    private fun createButton(
        text: String,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            this.text = text
            textSize = 12f
            isAllCaps = false
            setOnClickListener {
                action()
            }
        }
    }

    private fun weightedParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            setMargins(dp(3), dp(4), dp(3), dp(4))
        }
    }

    private fun loadConfig() {
        try {
            val cfg = RelayConfig.load(this)

            updating = true

            enabledSwitch.isChecked = cfg.enabled
            lanSwitch.isChecked = cfg.lan

            portInput.setText(cfg.port.toString())
            apiKeyInput.setText(cfg.apiKey)
            didInput.setText(cfg.oaiDid)
            tokenInput.setText(cfg.accessToken)

            updating = false

            appendLog("配置加载成功")
        } catch (e: Exception) {
            updating = false
            appendLog("配置加载失败：${e.javaClass.simpleName}")
        }
    }

    private fun buildConfig(): RelayConfig {
        val old = RelayConfig.load(this)

        val port = portInput.text.toString()
            .trim()
            .toIntOrNull()
            ?: old.port

        return old.copy(
            enabled = enabledSwitch.isChecked,
            port = port.coerceIn(1, 65535),
            lan = lanSwitch.isChecked,
            apiKey = apiKeyInput.text.toString().trim(),
            oaiDid = didInput.text.toString().trim(),
            accessToken = tokenInput.text.toString().trim()
        )
    }

    private fun saveConfig(): RelayConfig {
        val cfg = buildConfig()
        RelayConfig.save(this, cfg)
        refreshStatus()
        return cfg
    }

    private fun saveAndApply() {
        try {
            val cfg = saveConfig()

            if (cfg.enabled) {
                ApiService.start(this)
                appendLog("配置已保存，服务启动请求已发送")
            } else {
                ApiService.stop(this)
                appendLog("配置已保存，服务停止请求已发送")
            }

            refreshStatus()
        } catch (e: Exception) {
            appendLog("保存配置失败：${e.javaClass.simpleName}")
        }
    }

    private fun saveAndRestart() {
        try {
            val cfg = saveConfig()

            ApiService.stop(this)

            if (cfg.enabled) {
                ApiService.start(this)
                appendLog("配置已保存，服务已请求重启")
            } else {
                appendLog("配置已保存，当前为关闭状态")
            }

            refreshStatus()
        } catch (e: Exception) {
            appendLog("重启失败：${e.javaClass.simpleName}")
        }
    }

    private fun refreshStatus() {
        try {
            val cfg = RelayConfig.load(this)

            val running = cfg.enabled
            val address = if (cfg.lan) "0.0.0.0" else "127.0.0.1"
            val ready = if (cfg.ready) "已就绪" else "未就绪"

            statusView.text =
                "${if (running) "运行中" else "已停止"} · " +
                "端口 ${cfg.port} · $address · $ready"

        } catch (e: Exception) {
            statusView.text = "状态读取失败"
        }
    }

    private fun importKit() {
        try {
            val clipboard = getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as ClipboardManager

            if (!clipboard.hasPrimaryClip()) {
                toast("剪贴板为空")
                appendLog("导入失败：剪贴板为空")
                return
            }

            val clip = clipboard.primaryClip
                ?: throw IllegalArgumentException("剪贴板内容为空")

            val json = clip.getItemAt(0)
                .coerceToText(this)
                .toString()

            if (json.isBlank()) {
                toast("剪贴板内容为空")
                return
            }

            val imported = RelayConfig.importKit(this, json)

            updating = true

            portInput.setText(imported.port.toString())
            apiKeyInput.setText(imported.apiKey)
            didInput.setText(imported.oaiDid)
            tokenInput.setText(imported.accessToken)
            enabledSwitch.isChecked = imported.enabled
            lanSwitch.isChecked = imported.lan

            updating = false

            RelayConfig.save(this, imported)

            if (imported.enabled) {
                ApiService.start(this)
            } else {
                ApiService.stop(this)
            }

            refreshStatus()
            appendLog("kit.json 导入成功")
            toast("导入成功")

        } catch (e: Exception) {
            updating = false
            appendLog("kit.json 导入失败：${e.javaClass.simpleName}")
            toast("导入失败：${e.javaClass.simpleName}")
        }
    }

    private fun copyClientConfig() {
        try {
            val cfg = saveConfig()

            val key = cfg.apiKey

            val content =
                "base_url=http://127.0.0.1:${cfg.port}/v1\n" +
                "api_key=$key"

            val clipboard = getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as ClipboardManager

            clipboard.setPrimaryClip(
                ClipData.newPlainText("客户端配置", content)
            )

            appendLog("客户端配置已复制到剪贴板")
            toast("已复制客户端配置")

        } catch (e: Exception) {
            appendLog("复制失败：${e.javaClass.simpleName}")
        }
    }

    private fun testHealth() {
        val port = portInput.text.toString()
            .trim()
            .toIntOrNull()
            ?: run {
                toast("请输入有效端口")
                return
            }

        if (port !in 1..65535) {
            toast("端口范围应为 1～65535")
            return
        }

        appendLog("开始测试 /health，端口 $port")

        thread(name = "relay-health-test") {
            var connection: HttpURLConnection? = null

            try {
                connection = URL(
                    "http://127.0.0.1:$port/health"
                ).openConnection() as HttpURLConnection

                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.useCaches = false

                val responseCode = connection.responseCode

                val result = if (responseCode in 200..299) {
                    "健康检查成功：HTTP $responseCode"
                } else {
                    "健康检查失败：HTTP $responseCode"
                }

                mainHandler.post {
                    appendLog(result)
                }

            } catch (e: Exception) {
                mainHandler.post {
                    appendLog("健康检查异常：${e.javaClass.simpleName}")
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun appendLog(message: String) {
        val timestamp = SimpleDateFormat(
            "HH:mm:ss",
            Locale.getDefault()
        ).format(Date())

        logs.addLast("[$timestamp] $message")

        while (logs.size > 200) {
            logs.removeFirst()
        }

        logView.text = logs.joinToString("\n")

        logScroll.post {
            logScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}
