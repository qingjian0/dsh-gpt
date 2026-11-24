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
import com.gpt.hook.api.UpstreamConversations
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


private fun findLanIpv4(): String {
    return try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val network = interfaces.nextElement()
            if (!network.isUp || network.isLoopback) continue
            val addresses = network.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is java.net.Inet4Address &&
                    !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                    return address.hostAddress ?: continue
                }
            }
        }
        "未连接"
    } catch (_: Throwable) {
        "未连接"
    }
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
            StatusLine("会话持久化", "SQLite 已接入")
            StatusLine("多智能体调度", "等待 DSH 引擎")
            StatusLine("GPT UI 定制执行器", "待接入")
        }
    }
}

@Composable
private fun ApiScreen() {
    val context = LocalContext.current
    var config by remember { mutableStateOf(RelayConfig.load(context)) }
    var connector by remember { mutableStateOf(config.connectorId) }
    var port by remember(config) { mutableStateOf(config.port.toString()) }
    var apiKey by remember(config) { mutableStateOf(config.apiKey) }
    var did by remember(config) { mutableStateOf(config.oaiDid) }
    var token by remember(config) { mutableStateOf(config.accessToken) }
    var message by rememberSaveable { mutableStateOf("") }
    var runtimeStatus by rememberSaveable { mutableStateOf("等待检测") }
    val lanIp = remember { findLanIpv4() }

    LaunchedEffect(config.port, config.upstreamBase) {
        while (true) {
            runtimeStatus = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = URL("http://127.0.0.1:${config.port}/health").openConnection() as HttpURLConnection
                    try {
                        connection.requestMethod = "GET"
                        connection.connectTimeout = 1500
                        connection.readTimeout = 1500
                        val code = connection.responseCode
                        if (code in 200..299) {
                            val body = connection.inputStream.bufferedReader().use { it.readText() }
                            val health = org.json.JSONObject(body)
                            val healthy = health.optBoolean("ok", false) &&
                                health.optInt("port", -1) == config.port &&
                                health.optString("upstream") == config.upstreamBase
                            if (healthy) "运行中 · HTTP $code" else "端口有响应，但不是当前网关"
                        } else "未正常响应 · HTTP $code"
                    } finally {
                        connection.disconnect()
                    }
                }.getOrElse { "未检测到监听服务：${it.javaClass.simpleName}" }
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
            OutlinedTextField(value = connector, onValueChange = { connector = it.trim() },
                label = { Text("已授权 connector id（用于 gpt-6-tools）") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    val json = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    runCatching {
                        require(json.isNotBlank()) { "剪贴板为空，请先复制 kit.json 内容。" }
                        RelayConfig.importKit(context, json)
                    }.onSuccess { imported ->
                        config = imported
                        message = "已从剪贴板导入 kit.json。"
                    }.onFailure { message = "导入失败：${it.message ?: it.javaClass.simpleName}" }
                }, modifier = Modifier.weight(1f)) { Text("导入 kit.json") }
                OutlinedButton(onClick = {
                    if (config.apiKey.isBlank()) {
                        message = "请先配置客户端 API Key。"
                    } else {
                        val command = "curl -H \"Authorization: Bearer ${config.apiKey}\" http://127.0.0.1:${config.port}/v1/models"
                        context.getSystemService(android.content.ClipboardManager::class.java)
                            ?.setPrimaryClip(android.content.ClipData.newPlainText("local api curl", command))
                        message = "已复制 /v1/models 测试命令。"
                    }
                }, modifier = Modifier.weight(1f)) { Text("复制测试命令") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("允许局域网访问", modifier = Modifier.weight(1f))
                Switch(checked = config.lan, onCheckedChange = { config = config.copy(lan = it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("临时对话")
                    Text("不保存历史、不进训练、不带记忆（history_and_training_disabled）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = config.temporaryChat,
                    onCheckedChange = { config = config.copy(temporaryChat = it) })
            }
            Button(onClick = {
                val parsedPort = port.toIntOrNull()
                if (parsedPort == null || parsedPort !in 1..65535) {
                    message = "端口必须在 1–65535 之间。"
                } else {
                    val next = config.copy(port = parsedPort, apiKey = apiKey.trim(),
                        oaiDid = did.trim(), accessToken = token.trim(), connectorId = connector)
                    RelayConfig.save(context, next)
                    if (next.enabled && next.ready) ApiService.start(context) else if (!next.enabled) ApiService.stop(context)
                    config = next
                    message = "配置已保存。"
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("保存配置") }
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall)
        }
        SectionCard("通用 OpenAI API", "任何支持 OpenAI API 的客户端都可以直接连接，不需要 DSH 专用请求头。") {
            StatusLine("手机本机地址", "http://127.0.0.1:${config.port}/v1")
            StatusLine("电脑连接地址", if (config.lan && lanIp != "未连接")
                "http://${lanIp}:${config.port}/v1" else "请开启局域网访问并保存")
            StatusLine("认证方式", "Bearer API Key")
            StatusLine("模型列表", "auto · gpt-6 · gpt-6-mini · research")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = {
                    val value = "http://127.0.0.1:${config.port}/v1"
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("OpenAI Base URL", value))
                    message = "已复制手机本机 Base URL。"
                }, modifier = Modifier.weight(1f)) { Text("复制手机地址") }
                OutlinedButton(onClick = {
                    val value = if (config.lan && lanIp != "未连接")
                        "http://${lanIp}:${config.port}/v1" else ""
                    if (value.isNotBlank()) {
                        context.getSystemService(android.content.ClipboardManager::class.java)
                            ?.setPrimaryClip(android.content.ClipData.newPlainText("OpenAI LAN Base URL", value))
                        message = "已复制电脑连接地址。"
                    } else message = "先开启局域网访问并保存配置。"
                }, modifier = Modifier.weight(1f)) { Text("复制电脑地址") }
            }
            OutlinedButton(onClick = {
                context.getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("OpenAI API Key", config.apiKey))
                message = "已复制客户端 API Key。"
            }, modifier = Modifier.fillMaxWidth()) { Text("复制 API Key") }
            Text("支持 /models、/chat/completions、/responses 和 SSE 流式输出。文本对话兼容；图像、音频及工具调用能力取决于后续上游适配。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val context = LocalContext.current
    val repository = remember { (context.applicationContext as DshApplication).conversationRepository }
    val scope = rememberCoroutineScope()
    val caller = "dsh-local-control-ui-profile-v1"
    var items by remember { mutableStateOf(emptyList<com.gpt.hook.api.ConversationRepository.Conversation>()) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedConversation by remember { mutableStateOf<com.gpt.hook.api.ConversationRepository.Conversation?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("正在加载") }
    var sending by rememberSaveable { mutableStateOf(false) }
    // 上游真实对话（来自 ChatGPT 账号，不是本地网关库）
    val upstreamCfg = remember { RelayConfig.load(context.applicationContext) }
    var upItems by remember { mutableStateOf(emptyList<UpstreamConversations.Summary>()) }
    var upDetail by remember { mutableStateOf<UpstreamConversations.Detail?>(null) }
    var upStatus by rememberSaveable { mutableStateOf("未加载") }
    var upLoading by rememberSaveable { mutableStateOf(false) }

    suspend fun reload() {
        val snapshot = withContext(Dispatchers.IO) { repository.list(caller) }
        items = snapshot
        val id = selectedId
        selectedConversation = if (id == null) null else withContext(Dispatchers.IO) {
            repository.get(caller, id)
        }
        if (id != null && selectedConversation == null) selectedId = null
        status = "共 ${snapshot.size} 个本地会话"
    }

    LaunchedEffect(Unit) {
        runCatching { reload() }.onFailure { status = "读取失败：${it.message ?: "unknown"}" }
    }

    PageColumn {
        PageHeading("对话", if (selectedConversation == null) "管理本地会话，选择会话后可直接通过本地 API 网关对话。" else "当前会话通过本地网关连接已配置的 ChatGPT 上游。")
        if (selectedConversation == null) {
            SectionCard("ChatGPT 真实对话") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(upStatus, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(enabled = !upLoading, onClick = {
                        upLoading = true
                        upStatus = "加载中…"
                        scope.launch {
                            runCatching {
                                val list = withContext(Dispatchers.IO) {
                                    UpstreamConversations(upstreamCfg).list(20)
                                }
                                upItems = list
                                upDetail = null
                                upStatus = "共 ${list.size} 个对话"
                            }.onFailure { upStatus = "加载失败：${it.message ?: "unknown"}" }
                            upLoading = false
                        }
                    }) { Text("拉取") }
                }
                if (upDetail != null) {
                    val d = upDetail!!
                    Text("标题：${d.title}", style = MaterialTheme.typography.bodySmall)
                    Text("默认模型：${d.model}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    d.turns.forEach { turn ->
                        Text(
                            (if (turn.role == "user") "你：" else "GPT：") + turn.text.take(400),
                            style = MaterialTheme.typography.bodySmall
                        )
                        val meta = buildList {
                            if (turn.model.isNotBlank()) add("模型 " + turn.model)
                            if (turn.reasoningSec > 0) add("思考 " + turn.reasoningSec + "s")
                            if (turn.hasTool) add("工具 " + turn.toolLabel)
                        }
                        if (meta.isNotEmpty()) Text(meta.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (upItems.isNotEmpty()) {
                    upItems.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.bodyMedium)
                                Text(item.updatedAt, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(enabled = !upLoading, onClick = {
                                upLoading = true
                                upStatus = "打开中…"
                                scope.launch {
                                    runCatching {
                                        upDetail = withContext(Dispatchers.IO) {
                                            UpstreamConversations(upstreamCfg).detail(item.id)
                                        }
                                        upStatus = "已打开"
                                    }.onFailure { upStatus = "打开失败：${it.message ?: "unknown"}" }
                                    upLoading = false
                                }
                            }) { Text("查看") }
                        }
                    }
                } else {
                    Text("点「拉取」读取账号里的真实对话，含模型、思考时长与工具调用记录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SectionCard("会话中心") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(status, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { reload() }.onFailure { status = "刷新失败：${it.message ?: "unknown"}" }
                        }
                    }) { Text("刷新") }
                }
                Button(
                    onClick = {
                        scope.launch {
                            runCatching {
                                val created = withContext(Dispatchers.IO) {
                                    repository.create(caller, "新对话", "auto")
                                }
                                selectedId = created.id
                                selectedConversation = created
                                reload()
                                status = "已创建新会话"
                            }.onFailure { status = "创建失败：${it.message ?: "unknown"}" }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("新建对话") }
            }
            if (items.isEmpty()) {
                SectionCard("暂无会话") {
                    Text("创建后会保存在本机数据库，应用重启后仍可读取。")
                }
            } else {
                items.forEach { item ->
                    SectionCard(item.title) {
                        Text("模型：${item.model} · 消息：${item.messages.length()}")
                        Text("更新时间：${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.updatedAt))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = {
                                selectedId = item.id
                                scope.launch {
                                    runCatching { reload() }.onFailure { status = "打开失败：${it.message ?: "unknown"}" }
                                }
                            }) { Text("打开对话") }
                            TextButton(onClick = {
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) { repository.delete(caller, item.id) }
                                        reload()
                                    }.onFailure { status = "删除失败：${it.message ?: "unknown"}" }
                                }
                            }) { Text("删除") }
                        }
                    }
                }
            }
        } else {
            val conversation = selectedConversation!!
            SectionCard("当前会话") {
                Text(conversation.title, fontWeight = FontWeight.SemiBold)
                Text("会话 ID：${conversation.id}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("上游会话：${if (conversation.upstreamConversationId.isNullOrBlank()) "尚未建立" else "已建立并可续接"}",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    selectedId = null
                    selectedConversation = null
                    draft = ""
                }) { Text("返回会话列表") }
            }
            SectionCard("消息记录") {
                if (conversation.messages.length() == 0) {
                    Text("发送第一条消息，开始此会话。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    for (index in 0 until conversation.messages.length()) {
                        val message = conversation.messages.optJSONObject(index) ?: continue
                        val role = message.optString("role", "unknown")
                        val content = message.optString("content", "")
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (role == "assistant") "GPT" else if (role == "user") "你" else role,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                            Text(content, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (index < conversation.messages.length() - 1) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
                Text(status, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionCard("发送消息") {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("发送消息给 GPT…") },
                    minLines = 2,
                    maxLines = 6,
                    enabled = !sending
                )
                Button(
                    onClick = {
                        val prompt = draft.trim()
                        val target = selectedConversation
                        if (prompt.isBlank() || target == null || sending) return@Button
                        sending = true
                        status = "正在请求上游…"
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    val config = RelayConfig.load(context)
                                    require(config.enabled) { "请先在 API 页面启动本地网关。" }
                                    require(config.ready) { "请先配置 ChatGPT 上游凭据。" }
                                    val connection = URL("http://127.0.0.1:${config.port}/v1/chat/completions")
                                        .openConnection() as HttpURLConnection
                                    try {
                                        connection.requestMethod = "POST"
                                        connection.connectTimeout = 5000
                                        connection.readTimeout = 120000
                                        connection.doOutput = true
                                        connection.setRequestProperty("Content-Type", "application/json")
                                        connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                                        connection.setRequestProperty("X-DSH-Client-Id", caller)
                                        connection.setRequestProperty("X-DSH-Conversation-Id", target.id)
                                        val payload = org.json.JSONObject()
                                            .put("model", target.model)
                                            .put("stream", false)
                                            .put("messages", org.json.JSONArray().put(
                                                org.json.JSONObject().put("role", "user").put("content", prompt)
                                            ))
                                        connection.outputStream.use {
                                            it.write(payload.toString().toByteArray(Charsets.UTF_8))
                                        }
                                        val code = connection.responseCode
                                        val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                                            ?.bufferedReader()?.use { it.readText() }.orEmpty()
                                        require(code in 200..299) {
                                            val upstreamMessage = runCatching {
                                                org.json.JSONObject(response).optJSONObject("error")?.optString("message")
                                            }.getOrNull().orEmpty()
                                            "本地 API 返回 HTTP $code：" + upstreamMessage.ifBlank { response.take(240) }
                                        }
                                        val root = org.json.JSONObject(response)
                                        val answer = root.optJSONArray("choices")?.optJSONObject(0)
                                            ?.optJSONObject("message")?.optString("content").orEmpty()
                                        require(answer.isNotBlank()) { "上游没有返回可显示的回复。" }
                                    } finally {
                                        connection.disconnect()
                                    }
                                }
                                draft = ""
                                reload()
                                status = "回复完成"
                            }.onFailure {
                                status = "发送失败：${it.message ?: it.javaClass.simpleName}"
                            }
                            sending = false
                        }
                    },
                    enabled = draft.isNotBlank() && !sending,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (sending) "正在发送…" else "发送") }
            }
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
    var discoveryPrefix by rememberSaveable { mutableStateOf("com.openai.") }
    var discoveryId by rememberSaveable { mutableStateOf("") }
    var discoveryTaskText by rememberSaveable { mutableStateOf("尚未扫描") }
    var discoveryResultText by rememberSaveable { mutableStateOf("") }
    var discoveryActive by rememberSaveable { mutableStateOf(false) }
    var discoveryNextCursor by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val providerUri = remember { Uri.parse("content://" + ObservationProvider.AUTHORITY) }

    suspend fun readReport() {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.call(providerUri, ObservationProvider.METHOD_READ, null, null)
            }.getOrNull()
        }
        reportText = result?.getString(ObservationProvider.KEY_REPORT) ?: "读取失败或尚无报告。"
        val updated = result?.getLong(ObservationProvider.KEY_UPDATED) ?: 0L
        status = if (updated == 0L) "尚无观察报告" else "报告更新时间（epoch ms）：" + updated
    }

    suspend fun submitDiscovery(cursor: Int = 0) {
        require(discoveryPrefix.startsWith("com.openai.") && discoveryPrefix.length <= 180) {
            "扫描范围必须限定在 com.openai.*"
        }
        val requestId = java.util.UUID.randomUUID().toString()
        val request = org.json.JSONObject()
            .put("requestId", requestId)
            .put("prefix", discoveryPrefix)
            .put("limit", 100)
            .put("cursor", cursor)
        val extras = android.os.Bundle().apply {
            putString(ObservationProvider.KEY_DISCOVERY_REQUEST, request.toString())
        }
        val response = withContext(Dispatchers.IO) {
            context.contentResolver.call(providerUri, ObservationProvider.METHOD_HOST_DISCOVER, null, extras)
        }
        val accepted = response ?: throw IllegalStateException("扫描请求没有返回状态。")
        require(accepted.getBoolean(ObservationProvider.KEY_ACCEPTED)) {
            "扫描请求未被接受；请检查是否已有任务正在运行。"
        }
        discoveryId = requestId
        discoveryTaskText = accepted.getString(ObservationProvider.KEY_DISCOVERY_TASK) ?: "已提交"
        discoveryResultText = ""
        discoveryNextCursor = null
        discoveryActive = true
    }

    LaunchedEffect(discoveryId) {
        val requestId = discoveryId
        if (requestId.isBlank()) return@LaunchedEffect
        while (true) {
            val extras = android.os.Bundle().apply {
                putString(ObservationProvider.KEY_DISCOVERY_ID, requestId)
            }
            val response = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.call(
                        providerUri, ObservationProvider.METHOD_HOST_DISCOVERY_RESULT, null, extras
                    )
                }.getOrNull()
            }
            val taskRaw = response?.getString(ObservationProvider.KEY_DISCOVERY_TASK)
            val resultRaw = response?.getString(ObservationProvider.KEY_DISCOVERY_RESULT)
            if (!taskRaw.isNullOrBlank()) {
                val task = runCatching { org.json.JSONObject(taskRaw) }.getOrNull()
                val taskStatus = task?.optString("status").orEmpty()
                discoveryTaskText = if (task == null) "任务状态解析失败" else
                    "状态：${taskStatus} · 尝试次数：${task.optInt("attempt")}"
                if (!resultRaw.isNullOrBlank()) {
                    val envelope = runCatching { org.json.JSONObject(resultRaw) }.getOrNull()
                    val result = envelope?.optJSONObject("result")
                    if (result != null) {
                        discoveryResultText = result.toString(2)
                        val next = result.opt("nextCursor")
                        discoveryNextCursor = if (next is Number) next.toInt() else null
                    } else {
                        discoveryResultText = resultRaw
                    }
                }
                if (taskStatus in setOf("SUCCEEDED", "FAILED", "EXPIRED", "CANCELLED")) {
                    discoveryActive = false
                    break
                }
            }
            delay(1000)
        }
    }

    PageColumn {
        PageHeading("Hook Runtime", "查看配置、扫描候选方法与运行时元数据观察。")
        SectionCard("运行状态") {
            StatusLine("LSPosed 服务", if (service == null) "未连接" else service.frameworkName + " / API " + service.apiVersion,
                service != null)
            StatusLine("目标包", "com.openai.chatgpt")
            Text(status, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionCard("类与方法扫描", "在目标进程中执行有界 DEX 元数据扫描；只读取类、方法和字段签名，不调用目标方法。") {
            OutlinedTextField(
                value = discoveryPrefix,
                onValueChange = { discoveryPrefix = it.take(180) },
                label = { Text("包名前缀") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !discoveryActive
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        scope.launch {
                            runCatching { submitDiscovery(0) }
                                .onFailure { discoveryTaskText = "提交失败：${it.message ?: it.javaClass.simpleName}" }
                        }
                    },
                    enabled = !discoveryActive,
                    modifier = Modifier.weight(1f)
                ) { Text(if (discoveryActive) "扫描中…" else "开始扫描") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            runCatching {
                                val extras = android.os.Bundle().apply {
                                    putString(ObservationProvider.KEY_DISCOVERY_ID, discoveryId)
                                }
                                withContext(Dispatchers.IO) {
                                    context.contentResolver.call(
                                        providerUri, ObservationProvider.METHOD_HOST_DISCOVERY_CANCEL, null, extras
                                    )
                                }
                                discoveryTaskText = "已请求取消"
                            }.onFailure { discoveryTaskText = "取消失败：${it.message ?: it.javaClass.simpleName}" }
                        }
                    },
                    enabled = discoveryActive && discoveryId.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("取消") }
            }
            Text(discoveryTaskText, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (discoveryResultText.isNotBlank()) {
                Text(
                    discoveryResultText.take(12000),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        context.getSystemService(android.content.ClipboardManager::class.java)
                            ?.setPrimaryClip(android.content.ClipData.newPlainText("DSH discovery result", discoveryResultText))
                        discoveryTaskText = "扫描结果已复制"
                    }, modifier = Modifier.weight(1f)) { Text("复制结果") }
                    OutlinedButton(
                        onClick = {
                            val cursor = discoveryNextCursor
                            if (cursor != null) scope.launch {
                                runCatching { submitDiscovery(cursor) }
                                    .onFailure { discoveryTaskText = "下一页失败：${it.message ?: it.javaClass.simpleName}" }
                            }
                        },
                        enabled = discoveryNextCursor != null && !discoveryActive,
                        modifier = Modifier.weight(1f)
                    ) { Text("下一页") }
                }
                if (discoveryResultText.length > 12000) {
                    Text("界面仅显示前 12,000 个字符；复制按钮可获取完整结果。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("发现的候选方法不等于已安装 Hook；请先审查候选证据，再通过规则配置提交。",
                style = MaterialTheme.typography.bodySmall,
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = {
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("DSH observations", reportText))
                    status = "观察报告已复制到剪贴板。"
                }, modifier = Modifier.weight(1f)) { Text("复制报告") }
                OutlinedButton(onClick = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "DSH metadata-only Hook observations")
                        putExtra(android.content.Intent.EXTRA_TEXT, reportText)
                    }
                    context.startActivity(android.content.Intent.createChooser(send, "分享脱敏观察报告"))
                }, modifier = Modifier.weight(1f)) { Text("分享给 AI") }
            }
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
