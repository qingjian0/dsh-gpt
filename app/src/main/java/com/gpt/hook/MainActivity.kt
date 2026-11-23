package com.gpt.hook

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gpt.hook.api.ApiService
import com.gpt.hook.api.RelayConfig
import com.gpt.hook.diagnostics.HookConfigParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DshTheme { DshApp() } }
    }
}

private enum class MainTab(val label: String, val icon: ImageVector) {
    HOME("首页", Icons.Default.Home),
    API("API", Icons.Default.Cloud),
    CONVERSATIONS("对话", Icons.Default.Chat),
    HOOK("Hook", Icons.Default.Code),
    AGENT("Agent", Icons.Default.SmartToy),
    CUSTOMIZER("定制", Icons.Default.Tune)
}

@Composable
private fun DshTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(
            primary = Color(0xFF72D9CB),
            onPrimary = Color(0xFF06201D),
            background = Color(0xFF101418),
            surface = Color(0xFF171D22),
            surfaceVariant = Color(0xFF222B31),
            onSurface = Color(0xFFE5ECEF),
            onSurfaceVariant = Color(0xFFB4C0C5)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF087F75),
            onPrimary = Color.White,
            background = Color(0xFFF7F9F8),
            surface = Color.White,
            surfaceVariant = Color(0xFFEEF3F1),
            onSurface = Color(0xFF172321),
            onSurfaceVariant = Color(0xFF5E6D69)
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DshApp() {
    var selected by rememberSaveable { mutableStateOf(MainTab.HOME.name) }
    val tab = MainTab.valueOf(selected)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("DSH-GPT", fontWeight = FontWeight.SemiBold)
                        Text(tab.label, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    TextButton(onClick = { selected = MainTab.HOME.name }) { Text("总览") }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = selected == item.name,
                        onClick = { selected = item.name },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label, maxLines = 1) },
                        alwaysShowLabel = true
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                MainTab.HOME -> HomeScreen(onNavigate = { selected = it.name })
                MainTab.API -> ApiScreen()
                MainTab.CONVERSATIONS -> ConversationsScreen()
                MainTab.HOOK -> HookScreen()
                MainTab.AGENT -> AgentScreen()
                MainTab.CUSTOMIZER -> CustomizerScreen()
            }
        }
    }
}

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content
    )
}

@Composable
private fun PageHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, positive: Boolean? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium,
            color = when (positive) {
                true -> MaterialTheme.colorScheme.primary
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurface
            })
    }
}

@Composable
private fun HomeScreen(onNavigate: (MainTab) -> Unit) {
    val context = LocalContext.current
    val relay = remember { RelayConfig.load(context) }
    val xposedConnected = DshApplication.currentService != null
    PageColumn {
        PageHeading("运行总览", "管理 AI 服务、运行时扩展与智能体工作流。")
        SectionCard("系统状态", "仅展示当前可读取的真实状态") {
            StatusLine("本地 API", if (relay.enabled && relay.ready) "已配置 / 已启用" else if (relay.enabled) "缺少上游凭据" else "已停止", relay.enabled && relay.ready)
            StatusLine("LSPosed 服务", if (xposedConnected) "已连接" else "未连接", xposedConnected)
            StatusLine("目标应用", "com.openai.chatgpt")
        }
        SectionCard("快捷入口") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onNavigate(MainTab.API) }, modifier = Modifier.weight(1f)) { Text("API 网关") }
                FilledTonalButton(onClick = { onNavigate(MainTab.HOOK) }, modifier = Modifier.weight(1f)) { Text("Hook 控制台") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onNavigate(MainTab.CONVERSATIONS) }, modifier = Modifier.weight(1f)) { Text("对话管理") }
                OutlinedButton(onClick = { onNavigate(MainTab.AGENT) }, modifier = Modifier.weight(1f)) { Text("Agent 中心") }
            }
        }
        SectionCard("平台模块") {
            StatusLine("API Gateway", "已接入")
            StatusLine("Hook 配置与观察", "已接入")
            StatusLine("会话持久化", "待接入")
            StatusLine("多智能体调度", "待接入")
            StatusLine("GPT UI 定制执行器", "待接入")
        }
    }
}

@Composable
private fun ApiScreen() {
    val context = LocalContext.current
    var config by remember { mutableStateOf(RelayConfig.load(context)) }
    var port by remember(config) { mutableStateOf(config.port.toString()) }
    var apiKey by remember(config) { mutableStateOf(config.apiKey) }
    var did by remember(config) { mutableStateOf(config.oaiDid) }
    var token by remember(config) { mutableStateOf(config.accessToken) }
    var message by rememberSaveable { mutableStateOf("") }
    var runtimeStatus by rememberSaveable { mutableStateOf("等待检测") }

    LaunchedEffect(config.port, config.enabled) {
        while (true) {
            runtimeStatus = if (!config.enabled) {
                "已停止（配置开关关闭）"
            } else {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val connection = URL("http://127.0.0.1:${config.port}/health").openConnection() as HttpURLConnection
                        try {
                            connection.requestMethod = "GET"
                            connection.connectTimeout = 1500
                            connection.readTimeout = 1500
                            val code = connection.responseCode
                            if (code in 200..299) {
                                val body = connection.inputStream.bufferedReader().use { it.readText() }
                                val healthy = org.json.JSONObject(body).optBoolean("ok", false)
                                if (healthy) "运行中 · HTTP $code" else "服务响应异常 · HTTP $code"
                            } else "未正常响应 · HTTP $code"
                        } finally {
                            connection.disconnect()
                        }
                    }.getOrElse { "未检测到监听服务：${it.javaClass.simpleName}" }
                }
            }
            delay(3000)
        }
    }

    PageColumn {
        PageHeading("API Gateway", "本地反代服务与上游连接配置。")
        SectionCard("服务状态") {
            StatusLine("监听地址", (if (config.lan) "0.0.0.0" else "127.0.0.1") + ":" + config.port)
            StatusLine("上游凭据", if (config.ready) "已配置" else "未配置", config.ready)
            StatusLine("配置开关", if (config.enabled) "已启用" else "已停止", config.enabled)
            StatusLine("实际运行状态", runtimeStatus, runtimeStatus.startsWith("运行中"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (!config.ready) {
                            message = "请先填写 access_token 和 oai-did。"
                        } else {
                            val next = config.copy(enabled = true)
                            RelayConfig.save(context, next)
                            ApiService.start(context)
                            config = next
                            message = "已请求启动 API 服务。"
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("启动") }
                OutlinedButton(
                    onClick = {
                        val next = config.copy(enabled = false)
                        RelayConfig.save(context, next)
                        ApiService.stop(context)
                        config = next
                        message = "已停止 API 服务。"
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("停止") }
            }
        }
        SectionCard("连接配置", "保存后按当前配置重启服务。") {
            OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text("监听端口") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = apiKey, onValueChange = { apiKey = it },
                label = { Text("客户端 API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(value = did, onValueChange = { did = it },
                label = { Text("oai-did") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = token, onValueChange = { token = it },
                label = { Text("access_token") }, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(), minLines = 2, maxLines = 4)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("允许局域网访问", modifier = Modifier.weight(1f))
                Switch(checked = config.lan, onCheckedChange = { config = config.copy(lan = it) })
            }
            Button(onClick = {
                val parsedPort = port.toIntOrNull()
                if (parsedPort == null || parsedPort !in 1..65535) {
                    message = "端口必须在 1–65535 之间。"
                } else {
                    val next = config.copy(port = parsedPort, apiKey = apiKey.trim(),
                        oaiDid = did.trim(), accessToken = token.trim())
                    RelayConfig.save(context, next)
                    if (next.enabled && next.ready) ApiService.start(context) else if (!next.enabled) ApiService.stop(context)
                    config = next
                    message = "配置已保存。"
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("保存配置") }
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall)
        }
        SectionCard("Provider") {
            StatusLine("ChatGPT 上游", config.upstreamBase, true)
            StatusLine("第三方 OpenAI 兼容 API", "Provider 管理器待接入")
            StatusLine("DSH Tools", "工具路由待接入")
        }
        SectionCard("高级能力") {
            StatusLine("流式响应", "沿用当前网关实现")
            StatusLine("会话复用策略", "待接入会话存储层")
            StatusLine("思考模式与插件路由", "待接入能力协商")
        }
    }
}

@Composable
private fun ConversationsScreen() {
    PageColumn {
        PageHeading("对话", "统一管理主对话、子对话和 API 会话。")
        SectionCard("会话中心") {
            Text("当前 UI 分支尚未接入持久化会话列表。")
            Text("后续接入会话存储后，这里将展示标题、Conversation ID、最后活动时间、消息数和所属智能体。",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text("新建对话 · 会话引擎待接入")
            }
        }
        SectionCard("计划支持") {
            StatusLine("主对话 / 子对话", "待接入")
            StatusLine("搜索与筛选", "待接入")
            StatusLine("继续会话与消息详情", "待接入")
        }
    }
}

@Composable
private fun HookScreen() {
    val context = LocalContext.current
    val service = DshApplication.currentService
    val defaultConfig = """{
  "schemaVersion": 1,
  "targetPackage": "com.openai.chatgpt",
  "rules": []
}"""
    var configText by remember {
        mutableStateOf(runCatching {
            service?.getRemotePreferences("dsh")?.getString("hook_config", defaultConfig) ?: defaultConfig
        }.getOrDefault(defaultConfig))
    }
    var reportText by rememberSaveable { mutableStateOf("尚无观察报告。") }
    var status by rememberSaveable { mutableStateOf(if (service == null) "LSPosed 服务未连接" else "LSPosed 服务已连接") }
    val scope = rememberCoroutineScope()

    suspend fun readReport() {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.call(Uri.parse("content://" + ObservationProvider.AUTHORITY),
                    ObservationProvider.METHOD_READ, null, null)
            }.getOrNull()
        }
        reportText = result?.getString(ObservationProvider.KEY_REPORT) ?: "读取失败或尚无报告。"
        val updated = result?.getLong(ObservationProvider.KEY_UPDATED) ?: 0L
        status = if (updated == 0L) "尚无观察报告" else "报告更新时间（epoch ms）：" + updated
    }

    PageColumn {
        PageHeading("Hook Runtime", "查看配置、运行状态与元数据观察。")
        SectionCard("运行状态") {
            StatusLine("LSPosed 服务", if (service == null) "未连接" else service.frameworkName + " / API " + service.apiVersion,
                service != null)
            StatusLine("目标包", "com.openai.chatgpt")
            Text(status, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionCard("Hook 规则配置", "提交前先进行结构校验；规则是否安装成功仍以目标进程结果为准。") {
            OutlinedTextField(value = configText, onValueChange = { configText = it },
                modifier = Modifier.fillMaxWidth(), minLines = 8, maxLines = 16,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            Button(onClick = {
                val current = DshApplication.currentService
                if (current == null) {
                    status = "LSPosed 服务未连接，无法提交。"
                } else {
                    runCatching {
                        val parsed = HookConfigParser.parse(configText)
                        current.getRemotePreferences("dsh").edit().putString("hook_config", configText).apply()
                        status = "配置已提交：" + parsed.rules.size + " 条规则；等待目标进程验证。"
                    }.onFailure { status = "配置被拒绝：" + (it.message ?: it.javaClass.simpleName) }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("验证并提交规则") }
        }
        SectionCard("运行时观察", "这里只展示真实收到的元数据报告，不把发现的类算作已 Hook。") {
            Text(reportText, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            LaunchedEffect(Unit) { readReport() }
            OutlinedButton(onClick = { scope.launch { readReport() } },
                modifier = Modifier.fillMaxWidth()) { Text("刷新观察报告") }
        }
    }
}

@Composable
private fun AgentScreen() {
    var depth by rememberSaveable { mutableIntStateOf(3) }
    var mainMode by rememberSaveable { mutableStateOf("AI 自动选择或创建") }
    PageColumn {
        PageHeading("Agent 中心", "主对话调度子智能体，支持多层任务协作。")
        SectionCard("主智能体") {
            Text("主对话来源")
            listOf("选择已有对话", "创建新对话", "AI 自动选择或创建").forEach { option ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mainMode == option, onClick = { mainMode = option })
                    Text(option)
                }
            }
            StatusLine("当前策略", mainMode)
            Text("对话选择器和创建动作将在会话存储接入后启用。",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        SectionCard("层级与规模") {
            Text("最大层数：" + depth + " 层", fontWeight = FontWeight.SemiBold)
            Slider(value = depth.toFloat(), onValueChange = { depth = it.toInt().coerceIn(1, 6) },
                valueRange = 1f..6f, steps = 4)
            StatusLine("最大并发数", "待配置")
            StatusLine("最大子智能体数", "待配置")
        }
        SectionCard("协作模式") {
            StatusLine("层级委派", "计划支持")
            StatusLine("并行执行", "计划支持")
            StatusLine("交叉审查", "计划支持")
            StatusLine("动态创建与复用", "计划支持")
        }
        SectionCard("执行引擎") {
            Text("当前分支尚未接入 Agent 调度执行器。UI 与数据结构先行，接入 DSH 原生 subagent/workflow 能力后再启用执行操作。",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text("创建智能体团队 · 待接入")
            }
        }
    }
}

@Composable
private fun CustomizerScreen() {
    PageColumn {
        PageHeading("GPT UI 定制", "管理客户端菜单、按钮、布局和事件绑定。")
        SectionCard("菜单管理") {
            StatusLine("菜单结构识别", "待接入")
            StatusLine("显示 / 隐藏项目", "待接入")
            StatusLine("拖动排序", "待接入")
        }
        SectionCard("自定义按钮") {
            Text("添加名称、图标、插入位置和点击动作；动作可关联 DSH 页面或已授权工具。",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text("添加按钮 · Hook 绑定待接入")
            }
        }
        SectionCard("兼容性与安全") {
            StatusLine("Hook 绑定状态", "未扫描")
            StatusLine("客户端版本兼容性", "未验证")
            StatusLine("恢复默认布局", "待接入")
        }
    }
}
