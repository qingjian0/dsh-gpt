package com.gpt.hook

import android.app.Activity
import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import com.gpt.hook.api.RelayConfig
import com.gpt.hook.api.ApiService
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var enableSwitch: Switch
    private lateinit var lanSwitch: Switch

    private lateinit var portInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var didInput: EditText
    private lateinit var tokenInput: EditText

    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    private var config = RelayConfig()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private val logs = ArrayDeque<String>()
    private var updating = false

    private val bgColor = Color.rgb(17, 24, 39)
    private val cardColor = Color.rgb(31, 41, 55)
    private val accentColor = Color.rgb(96, 165, 250)
    private val textColor = Color.rgb(243, 244, 246)
    private val secondaryColor = Color.rgb(156, 163, 175)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        config = RelayConfig.load(this)

        buildInterface()
        fillControls(config)
        refreshStatus()

        log("控制面板已启动")
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun buildInterface() {
        val outerScroll = ScrollView(this).apply {
            fillViewport = true
            setBackgroundColor(bgColor)
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        outerScroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(outerScroll)

        addTitle()
        addStatus()
        addSwitches()
        addInputs()
        addButtons()
        addLogs()
    }

    private fun addTitle() {
        val title = TextView(this).apply {
            text = "DSH GPT · 本地 API 网关"
            textSize = 23f
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
        }

        root.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        )
    }

    private fun addStatus() {
        statusText = TextView(this).apply {
            textSize = 13f
            setTextColor(secondaryColor)
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(cardColor, 10)
        }

        root.addView(
            statusText,
            matchWidth().apply {
                bottomMargin = dp(20)
            }
        )
    }

    private fun addSwitches() {
        enableSwitch = Switch(this).apply {
            text = "启用"
            textSize = 15f
            setTextColor(textColor)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            isChecked = config.enabled
        }

        lanSwitch = Switch(this).apply {
            text = "局域网监听 0.0.0.0"
            textSize = 15f
            setTextColor(textColor)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            isChecked = config.lan
        }

        root.addView(enableSwitch, matchWidth())
        root.addView(lanSwitch, matchWidth())

        enableSwitch.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener

            config = config.copy(enabled = checked)
            persistAndApply()
            log(if (checked) "已请求启动网关" else "已请求停止网关")
        }

        lanSwitch.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener

            config = config.copy(lan = checked)
            persistAndApply()
            log(
                if (checked) {
                    "已切换为局域网监听"
                } else {
                    "已切换为本机监听"
                }
            )
        }

        addDivider()
    }

    private fun addInputs() {
        portInput = createInput(
            "端口",
            InputType.TYPE_CLASS_NUMBER,
            "8766"
        )

        apiKeyInput = createInput(
            "客户端 API Key（可留空）",
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
            "留空则不设置客户端密钥"
        )

        didInput = createInput(
            "oai-did",
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            "设备标识"
        )

        tokenInput = createInput(
            "access_token",
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            "粘贴 access_token",
            multiline = true
        )

        tokenInput.setTypeface(Typeface.MONOSPACE)
        tokenInput.minLines = 4
        tokenInput.maxLines = 8
        tokenInput.gravity = Gravity.TOP or Gravity.START

        addDivider()
    }

    private fun createInput(
        label: String,
        inputType: Int,
        hint: String,
        multiline: Boolean = false
    ): EditText {
        val labelView = TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(secondaryColor)
        }

        root.addView(
            labelView,
            matchWidth().apply {
                bottomMargin = dp(6)
            }
        )

        val edit = EditText(this).apply {
            this.inputType = inputType
            this.hint = hint
            textSize = 14f
            setTextColor(textColor)
            setHintTextColor(Color.GRAY)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(cardColor, 8)

            if (multiline) {
                gravity = Gravity.TOP or Gravity.START
                isSingleLine = false
            } else {
                isSingleLine = true
            }
        }

        root.addView(
            edit,
            matchWidth().apply {
                bottomMargin = dp(16)
                height = if (multiline) dp(130) else dp(48)
            }
        )

        return edit
    }

    private fun addButtons() {
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        root.addView(
            row1,
            matchWidth().apply {
                bottomMargin = dp(8)
            }
        )

        root.addView(
            row2,
            matchWidth().apply {
                bottomMargin = dp(16)
            }
        )

        addButton(row1, "保存并重启", accentColor) {
            saveAndRestart()
        }

        addButton(row1, "导入 kit.json", cardColor) {
            importKit()
        }

        addButton(row2, "复制客户端配置", cardColor) {
            copyClientConfig()
        }

        addButton(row2, "测试 /health", cardColor) {
            testHealth()
        }
    }

    private fun addButton(
        parent: LinearLayout,
        title: String,
        color: Int,
        action: () -> Unit
    ) {
        val button = Button(this).apply {
            text = title
            textSize = 12f
            isAllCaps = false
            setTextColor(textColor)
            background = roundedBackground(color, 8)
            setPadding(dp(5), dp(4), dp(5), dp(4))
            minHeight = dp(44)
            minimumHeight = dp(44)
            stateListAnimator = null
        }

        parent.addView(
            button,
            LinearLayout.LayoutParams(
                0,
                dp(46),
                1f
            ).apply {
                marginEnd = dp(5)
            }
        )

        button.setOnClickListener {
            action()
        }
    }

    private fun addLogs() {
        val heading = TextView(this).apply {
            text = "运行日志"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
        }

        root.addView(
            heading,
            matchWidth().apply {
                bottomMargin = dp(10)
            }
        )

        logScroll = ScrollView(this).apply {
            isFillViewport = true
            background = roundedBackground(Color.rgb(10, 15, 25), 8)
        }

        logText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(134, 239, 172))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            text = "等待操作...\n"
        }

        logScroll.addView(
            logText,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            logScroll,
            matchWidth().apply {
                height = dp(240)
            }
        )
    }

    private fun fillControls(cfg: RelayConfig) {
        updating = true

        portInput.setText(cfg.port.toString())
        apiKeyInput.setText(cfg.apiKey)
        didInput.setText(cfg.oaiDid)
        tokenInput.setText(cfg.accessToken)

        enableSwitch.isChecked = cfg.enabled
        lanSwitch.isChecked = cfg.lan

        updating = false
    }

    private fun readConfig(): RelayConfig? {
        val port = portInput.text.toString().trim().toIntOrNull()

        if (port == null || port !in 1..65535) {
            log("端口无效：请输入 1～65535")
            portInput.error = "端口范围为 1～65535"
            return null
        }

        return config.copy(
            enabled = enableSwitch.isChecked,
            lan = lanSwitch.isChecked,
            port = port,
            apiKey = apiKeyInput.text.toString().trim(),
            oaiDid = didInput.text.toString().trim(),
            accessToken = tokenInput.text.toString().toString().trim()
        )
    }

    private fun persistAndApply() {
        val cfg = readConfig() ?: run {
            updating = true
            enableSwitch.isChecked = config.enabled
            lanSwitch.isChecked = config.lan
            updating = false
            return
        }

        config = cfg

        try {
            RelayConfig.save(this, config)

            if (config.enabled) {
                ApiService.start(this)
            } else {
                ApiService.stop(this)
            }

            refreshStatus()
        } catch (e: Exception) {
            log("应用配置失败：${e.javaClass.simpleName}")
        }
    }

    private fun saveAndRestart() {
        val cfg = readConfig() ?: return

        config = cfg

        try {
            RelayConfig.save(this, config)

            ApiService.stop(this)

            if (config.enabled) {
                ApiService.start(this)
            }

            refreshStatus()
            log("配置已保存并请求重启")
        } catch (e: Exception) {
            log("保存或重启失败：${e.javaClass.simpleName}")
        }
    }

    private fun importKit() {
        try {
            val clipboard = getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as ClipboardManager

            if (!clipboard.hasPrimaryClip()) {
                log("剪贴板为空")
                return
            }

            val json = clipboard.primaryClip
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
                ?.trim()

            if (json.isNullOrBlank()) {
                log("剪贴板没有有效 JSON")
                return
            }

            val imported = RelayConfig.importKit(this, json)

            config = imported.copy(
                enabled = config.enabled,
                port = config.port,
                lan = config.lan,
                apiKey = config.apiKey
            )

            RelayConfig.save(this, config)
            fillControls(config)
            refreshStatus()

            log("kit.json 导入成功")
        } catch (e: Exception) {
            log("导入失败：${e.javaClass.simpleName}")
        }
    }

    private fun copyClientConfig() {
        val cfg = readConfig() ?: return

        config = cfg

        val content = "base_url=http://127.0.0.1:${cfg.port}/v1\n" +
            "api_key=${cfg.apiKey}"

        val clipboard = getSystemService(
            Context.CLIPBOARD_SERVICE
        ) as ClipboardManager

        clipboard.setPrimaryClip(
            ClipData.newPlainText("客户端 API 配置", content)
        )

        log("客户端配置已复制到剪贴板")
    }

    private fun testHealth() {
        val cfg = readConfig() ?: return

        config = cfg

        log("正在测试 /health ...")

        executor.execute {
            var connection: HttpURLConnection? = null

            try {
                val url = URL(
                    "http://127.0.0.1:${cfg.port}/health"
                )

                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.useCaches = false

                val code = connection.responseCode

                val stream = if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val body = stream?.bufferedReader()?.use {
                    it.readText()
                }.orEmpty()

                mainHandler.post {
                    log("GET /health -> HTTP $code")
                    if (body.isNotBlank()) {
                        log("响应：${body.take(500)}")
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    log("健康检查失败：${e.javaClass.simpleName}")
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun refreshStatus() {
        if (!::statusText.isInitialized) return

        val current = RelayConfig.load(this)

        val running = current.enabled

        val address = if (current.lan) {
            "0.0.0.0"
        } else {
            "127.0.0.1"
        }

        val readiness = if (current.ready) {
            "已就绪"
        } else {
            "未就绪"
        }

        statusText.text =
            "${if (running) "运行中" else "已停止"} · " +
            "端口 ${current.port} · " +
            "$address · $readiness"
    }

    private fun log(message: String) {
        val timestamp = SimpleDateFormat(
            "HH:mm:ss",
            Locale.getDefault()
        ).format(Date())

        val line = "[$timestamp] $message"

        logs.addLast(line)

        while (logs.size > 200) {
            logs.removeFirst()
        }

        if (!::logText.isInitialized) return

        mainHandler.post {
            logText.text = logs.joinToString("\n")
            logScroll.post {
                logScroll.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun addDivider() {
        val divider = View(this).apply {
            setBackgroundColor(Color.rgb(55, 65, 81))
        }

        root.addView(
            divider,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(16)
                bottomMargin = dp(16)
            }
        )
    }

    private fun roundedBackground(
        color: Int,
        radius: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
    }

    private fun matchWidth(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
