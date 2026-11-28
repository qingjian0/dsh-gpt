@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gpt.hook

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.gpt.hook.api.ApiService
import com.gpt.hook.api.ChatGptPlanProfileStore
import com.gpt.hook.api.RelayConfig
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.max

private val Green = Color(0xFF10A37F)
private val Ink = Color(0xFF0D0D0D)
private val Muted = Color(0xFF8E8E93)
private val SurfaceLight = Color(0xFFF7F7F8)
private val DarkSurface = Color(0xFF303030)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = getSharedPreferences("dsh_gpt", MODE_PRIVATE)
        val relayConfig = RelayConfig.load(this)

        setContent {
            val dark = remember {
                mutableStateOf(prefs.getBoolean("dark", false))
            }

            DshTheme(dark.value) {
                DshApp(
                    context = this,
                    baseUrl = prefs.getString("base_url", "http://127.0.0.1:8766") ?: "",
                    initialKey = prefs.getString("api_key", "") ?: "",
                    initialRelayConfig = relayConfig,
                    onConfigChange = { url, key ->
                        prefs.edit()
                            .putString("base_url", url)
                            .putString("api_key", key)
                            .apply()
                    },
                    onRelayConfigChange = { next ->
                        RelayConfig.save(this, next)
                        ApiService.start(this)
                    },
                    dark = dark.value,
                    onDarkChange = {
                        dark.value = it
                        prefs.edit().putBoolean("dark", it).apply()
                    }
                )
            }
        }
    }
}

@Composable
private fun DshTheme(
    dark: Boolean,
    content: @Composable () -> Unit
) {
    val scheme = if (dark) {
        darkColorScheme(
            primary = Green,
            background = Color(0xFF212121),
            surface = Color(0xFF2B2B2B),
            onBackground = Color.White,
            onSurface = Color.White,
            surfaceVariant = Color(0xFF383838)
        )
    } else {
        lightColorScheme(
            primary = Green,
            background = Color.White,
            surface = Color.White,
            onBackground = Ink,
            onSurface = Ink,
            surfaceVariant = SurfaceLight
        )
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(
            headlineMedium = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold
            ),
            titleLarge = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold
            ),
            titleMedium = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold
            ),
            bodyLarge = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 16.sp
            ),
            bodyMedium = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp
            )
        ),
        content = content
    )
}

private data class ApiResponse(
    val code: Int,
    val body: String
)

private object AuthHeaders {
    fun apply(connection: HttpURLConnection, apiKey: String) {
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/json")
        if (apiKey.isNotBlank()) {
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("x-api-key", apiKey)
        }
    }
}

private class Backend(
    baseUrl: String,
    private val apiKey: String
) {
    private val base = baseUrl.trim().trimEnd('/')

    suspend fun get(path: String): ApiResponse =
        request("GET", path, null)

    suspend fun post(path: String, body: JSONObject): ApiResponse =
        request("POST", path, body.toString())

    suspend fun put(path: String, body: JSONObject): ApiResponse =
        request("PUT", path, body.toString())

    suspend fun delete(path: String): ApiResponse =
        request("DELETE", path, null)

    private suspend fun request(
        method: String,
        path: String,
        body: String?
    ): ApiResponse = withContext(Dispatchers.IO) {
        val connection = (URL(base + path).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true
            AuthHeaders.apply(connection, apiKey)

            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use {
                    it.write(body.toByteArray(Charsets.UTF_8))
                }
            }

            val code = connection.responseCode
            val stream = if (code in 200..399) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream?.bufferedReader()?.use {
                it.readText()
            } ?: ""

            ApiResponse(code, response)
        } finally {
            connection.disconnect()
        }
    }
}

private data class LatencyPoint(
    val label: String,
    val millis: Long
)

private data class ToolItem(
    val name: String,
    val description: String,
    val schema: JSONObject,
    val group: String
)

private data class ToolCallRecord(
    val name: String,
    val elapsed: Long,
    val success: Boolean,
    val result: String
)

private data class AppState(
    val online: Boolean = false,
    val health: JSONObject = JSONObject(),
    val models: JSONArray = JSONArray(),
    val tools: List<ToolItem> = emptyList(),
    val resources: JSONArray = JSONArray(),
    val hook: JSONObject = JSONObject(),
    val events: JSONArray = JSONArray(),
    val requestDump: JSONObject = JSONObject(),
    val usage: JSONObject = JSONObject(),
    val latency: List<LatencyPoint> = emptyList(),
    val history: List<ToolCallRecord> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

@Composable
private fun DshApp(
    context: Context,
    baseUrl: String,
    initialKey: String,
    initialRelayConfig: RelayConfig,
    onConfigChange: (String, String) -> Unit,
    onRelayConfigChange: (RelayConfig) -> Unit,
    dark: Boolean,
    onDarkChange: (Boolean) -> Unit
) {
    var endpoint by remember { mutableStateOf(baseUrl) }
    var apiKey by remember { mutableStateOf(initialKey) }
    var relayConfig by remember { mutableStateOf(initialRelayConfig) }
    var currentTab by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf(AppState()) }
    var configExpanded by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val backend = remember(endpoint, relayConfig.apiKey) {
        Backend(endpoint, relayConfig.apiKey)
    }
    val modelIds = remember(state.models) {
        (0 until state.models.length()).mapNotNull { index ->
            val item = state.models.optJSONObject(index)
            item?.optString("id")?.takeIf { it.isNotBlank() }
                ?: state.models.optString(index).takeIf { it.isNotBlank() }
        }.distinct()
    }

    fun notify(message: String) {
        toast = message
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    suspend fun refresh() {
        state = state.copy(loading = true, error = null)

        try {
            val health = backend.get("/health")
            val models = backend.get("/v1/models")
            val hook = backend.get("/v1/hook/status")
            val requestDump = backend.get("/v1/request-dump")
            val usage = backend.get("/v1/usage")

            state = state.copy(
                online = health.code in 200..299,
                health = JSONObjectOrEmpty(health.body),
                models = if (models.code in 200..299) extractArray(models.body, "data", "models") else JSONArray(),
                hook = if (hook.code in 200..299) JSONObjectOrEmpty(hook.body) else JSONObject().put("statusUnavailable", true).put("error", "HTTP ${hook.code}"),
                requestDump = if (requestDump.code in 200..299) JSONObjectOrEmpty(requestDump.body) else JSONObject().put("available", false).put("error", "HTTP ${requestDump.code}"),
                usage = JSONObjectOrEmpty(usage.body),
                loading = false
            )
        } catch (e: Exception) {
            state = state.copy(
                online = false,
                loading = false,
                error = readableError(e)
            )
        }
    }

    suspend fun loadTools() {
        val result = backend.get("/v1/mcp/tools")
        if (result.code !in 200..299) {
            throw Exception("工具列表获取失败：HTTP ${result.code}")
        }

        val array = extractArray(result.body, "tools", "data")
        val tools = mutableListOf<ToolItem>()

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.optString("name").ifBlank {
                item.optString("title")
            }
            if (name.isBlank()) continue

            val group = item.optString("server")
                .ifBlank { item.optString("group") }
                .ifBlank {
                    when {
                        name.contains("github", true) -> "GitHub"
                        name.contains("web", true) -> "网页读取"
                        else -> "其他工具"
                    }
                }

            tools.add(
                ToolItem(
                    name = name,
                    description = item.optString("description"),
                    schema = item.optJSONObject("inputSchema")
                        ?: item.optJSONObject("schema")
                        ?: JSONObject(),
                    group = group
                )
            )
        }

        state = state.copy(tools = tools)
    }

    LaunchedEffect(endpoint, apiKey) {
        refresh()
        try {
            loadTools()
            val resources = backend.get("/v1/mcp/resources")
            state = state.copy(
                resources = extractArray(resources.body, "resources", "data")
            )
        } catch (_: Exception) {
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "DSH-GPT",
                            fontSize = 23.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.8).sp
                        )
                        Text(
                            "OpenAI API Gateway",
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }

                    IconButton(onClick = { onDarkChange(!dark) }) {
                        Icon(
                            if (dark) Icons.Outlined.LightMode
                            else Icons.Outlined.DarkMode,
                            contentDescription = "切换主题"
                        )
                    }

                    IconButton(
                        onClick = {
                            scope.launch {
                                refresh()
                                notify(if (state.online) "状态已更新" else "连接失败")
                            }
                        }
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = "刷新"
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                listOf(
                    Triple("概览", Icons.Outlined.Dashboard, 0),
                    Triple("API", Icons.Outlined.Api, 1),
                    Triple("工具", Icons.Outlined.Build, 2),
                    Triple("Hook", Icons.Outlined.Extension, 3)
                ).forEach { (label, icon, index) ->
                    NavigationBarItem(
                        selected = currentTab == index,
                        onClick = { currentTab = index },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(label) }
                    )
                }
            }
        },
        snackbarHost = {
            SnackbarHost(remember { SnackbarHostState() })
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (currentTab) {
                0 -> DashboardScreen(
                    online = state.online,
                    loading = state.loading,
                    endpoint = endpoint,
                    models = (0 until state.models.length()).mapNotNull { index ->
                        val item = state.models.optJSONObject(index)
                        item?.optString("id")?.takeIf { it.isNotBlank() }
                            ?: state.models.optString(index).takeIf { it.isNotBlank() }
                    },
                    hookEnabled = state.hook.optBoolean("enabled", false),
                    error = state.error,
                    onCopy = {
                        copyText(context, endpoint)
                        notify("接入地址已复制")
                    },
                    onRefresh = {
                        scope.launch {
                            refresh()
                            notify("状态刷新完成")
                        }
                    }
                )

                1 -> ApiPage(
                    endpoint = endpoint,
                    apiKey = apiKey,
                    expanded = configExpanded,
                    latency = state.latency,
                    usage = state.usage,
                    initialRelayConfig = relayConfig,
                    modelIds = modelIds,
                    onRelayConfigSave = { next ->
                        relayConfig = next
                        onRelayConfigChange(next)
                        notify("ChatGPT 配置已保存，网关已重启")
                    },
                    onImportKit = {
                        try {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val raw = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                            require(raw.isNotBlank()) { "剪贴板为空" }
                            val imported = RelayConfig.importKit(context, raw)
                            relayConfig = imported
                            onRelayConfigChange(imported)
                            notify(if (imported.ready) "ChatGPT 配置导入成功" else "Kit 已导入，请补齐当前通道凭证")
                        } catch (e: Exception) {
                            notify("导入失败：${e.message ?: "无效 kit.json"}")
                        }
                    },
                    onExpand = { configExpanded = !configExpanded },
                    onEndpointChange = { endpoint = it },
                    onKeyChange = { apiKey = it },
                    onSave = {
                        onConfigChange(endpoint, apiKey)
                        notify("配置已保存")
                    },
                    onCopy = {
                        copyText(context, it)
                        notify("已复制")
                    },
                    onTest = {
                        scope.launch {
                            val start = System.currentTimeMillis()
                            try {
                                val response = backend.get("/health")
                                val elapsed = System.currentTimeMillis() - start
                                state = state.copy(
                                    latency = (
                                        state.latency + LatencyPoint(
                                            System.currentTimeMillis().toString().takeLast(5),
                                            elapsed
                                        )
                                    ).takeLast(10)
                                )
                                notify(
                                    if (response.code in 200..299) {
                                        "连接成功 · ${elapsed}ms"
                                    } else {
                                        "连接失败 · HTTP ${response.code}"
                                    }
                                )
                            } catch (e: Exception) {
                                notify(readableError(e))
                            }
                        }
                    }
                )

                2 -> ToolsPage(
                    tools = state.tools,
                    history = state.history,
                    backend = backend,
                    onCall = { name, arguments ->
                        scope.launch {
                            val start = System.currentTimeMillis()
                            try {
                                val result = backend.post(
                                    "/v1/mcp/call",
                                    JSONObject().apply {
                                        put("name", name)
                                        put("arguments", arguments)
                                    }
                                )
                                val elapsed = System.currentTimeMillis() - start
                                val success = result.code in 200..299
                                val record = ToolCallRecord(
                                    name,
                                    elapsed,
                                    success,
                                    result.body
                                )
                                state = state.copy(
                                    history = (listOf(record) + state.history).take(50)
                                )
                                notify(
                                    if (success) "调用成功 · ${elapsed}ms"
                                    else "调用失败 · HTTP ${result.code}"
                                )
                            } catch (e: Exception) {
                                val record = ToolCallRecord(
                                    name,
                                    System.currentTimeMillis() - start,
                                    false,
                                    readableError(e)
                                )
                                state = state.copy(
                                    history = (listOf(record) + state.history).take(50)
                                )
                                notify(record.result)
                            }
                        }
                    },
                    onReadWeb = { url ->
                        val start = System.currentTimeMillis()
                        try {
                            val response = backend.post(
                                "/v1/web/open",
                                JSONObject().put("url", url)
                            )
                            val elapsed = System.currentTimeMillis() - start
                            val record = ToolCallRecord(
                                "web.open",
                                elapsed,
                                response.code in 200..299,
                                response.body
                            )
                            state = state.copy(
                                history = (listOf(record) + state.history).take(50)
                            )
                            record
                        } catch (e: Exception) {
                            ToolCallRecord(
                                "web.open",
                                System.currentTimeMillis() - start,
                                false,
                                readableError(e)
                            )
                        }
                    },
                    onReload = {
                        scope.launch {
                            try {
                                loadTools()
                                notify("工具列表已更新")
                            } catch (e: Exception) {
                                notify(readableError(e))
                            }
                        }
                    }
                )

                3 -> HookPage(
                    hook = state.hook,
                    events = state.events,
                    requestDump = state.requestDump,
                    onSetDumpEnabled = { enabled ->
                        scope.launch {
                            try {
                                val result = backend.put("/v1/request-dump", JSONObject().put("enabled", enabled))
                                if (result.code !in 200..299) throw Exception("请求抓取设置失败：HTTP ${result.code}")
                                state = state.copy(requestDump = JSONObjectOrEmpty(result.body))
                                notify(if (enabled) "请求抓取已启用" else "请求抓取已关闭")
                            } catch (e: Exception) {
                                notify(e.message ?: "请求抓取设置失败")
                            }
                        }
                    },
                    onClearDump = {
                        scope.launch {
                            try {
                                val result = backend.delete("/v1/request-dump")
                                if (result.code !in 200..299) throw Exception("清理请求记录失败：HTTP ${result.code}")
                                state = state.copy(requestDump = JSONObjectOrEmpty(result.body))
                                notify("请求记录已清理")
                            } catch (e: Exception) {
                                notify(e.message ?: "清理失败")
                            }
                        }
                    },
                    onRefresh = {
                        scope.launch {
                            try {
                                val result = backend.get("/v1/hook/status")
                                val eventResult = backend.get("/v1/hook/events")
                                val dumpResult = backend.get("/v1/request-dump")
                                state = state.copy(
                                    hook = if (result.code in 200..299) JSONObjectOrEmpty(result.body) else JSONObject().put("statusUnavailable", true).put("error", "HTTP ${result.code}"),
                                    events = extractArray(eventResult.body, "events", "data"),
                                    requestDump = if (dumpResult.code in 200..299) JSONObjectOrEmpty(dumpResult.body) else JSONObject().put("available", false).put("error", "HTTP ${dumpResult.code}")
                                )
                                notify("Hook 状态已更新")
                            } catch (e: Exception) {
                                notify(readableError(e))
                            }
                        }
                    },
                    onSaveRule = { rule ->
                        scope.launch {
                            try {
                                val current = backend.get("/v1/hook/config")
                                if (current.code !in 200..299) throw Exception("读取 Hook 配置失败：HTTP ${current.code}")
                                val config = JSONObject(current.body).optJSONObject("config")
                                    ?: throw Exception("后端未返回有效 Hook 配置")
                                val rules = config.optJSONArray("rules") ?: JSONArray()
                                val id = rule.optString("id")
                                if (id.isBlank()) throw Exception("规则 ID 不能为空")
                                val delete = rule.optBoolean("_delete", false)
                                val nextRules = JSONArray()
                                var found = false
                                for (i in 0 until rules.length()) {
                                    val existing = rules.optJSONObject(i) ?: continue
                                    if (existing.optString("id") == id) {
                                        found = true
                                        if (!delete) nextRules.put(rule)
                                    } else {
                                        nextRules.put(existing)
                                    }
                                }
                                if (!found && !delete) {
                                    if (nextRules.length() >= 64) throw Exception("规则数量已达到上限（64）")
                                    nextRules.put(rule)
                                }
                                config.put("rules", nextRules)
                                val response = backend.put("/v1/hook/config", config)
                                if (response.code !in 200..299) throw Exception("规则提交失败：HTTP ${response.code}")
                                val status = backend.get("/v1/hook/status")
                                state = state.copy(hook = JSONObjectOrEmpty(status.body))
                                notify("规则已保存；实际安装状态以目标进程回报为准")
                            } catch (e: Exception) {
                                notify(e.message ?: "规则保存失败")
                            }
                        }
                    },
                    onSetEnabled = { enabled ->
                        scope.launch {
                            try {
                                val result = backend.put("/v1/hook/enabled", JSONObject().put("enabled", enabled))
                                if (result.code !in 200..299) throw Exception("Hook 状态修改失败：HTTP ${result.code}")
                                val status = backend.get("/v1/hook/status")
                                state = state.copy(hook = JSONObjectOrEmpty(status.body))
                            } catch (e: Exception) {
                                notify(e.message ?: "操作失败")
                            }
                        }
                    },
                    onDiscover = { request ->
                        scope.launch {
                            try {
                                val route = if (request.optString("className").isNotBlank()) "/v1/hook/inspect" else "/v1/hook/discover"
                                val submitted = backend.post(route, request)
                                if (submitted.code !in 200..299) throw Exception("扫描任务提交失败：HTTP ${submitted.code}")
                                val status = backend.get("/v1/hook/status")
                                state = state.copy(hook = JSONObjectOrEmpty(status.body))
                                notify("扫描任务已提交；等待目标进程执行")
                            } catch (e: Exception) {
                                notify(e.message ?: "扫描任务提交失败")
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun StatusPage(
    state: AppState,
    endpoint: String,
    onCopy: () -> Unit,
    onRefresh: () -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "运行状态",
                fontSize = 27.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "服务、模型和连接状态",
                color = Muted,
                fontSize = 14.sp
            )
        }

        item {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(21.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(
                                    if (state.online) Green else Color(0xFFE05252),
                                    CircleShape
                                )
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            if (state.online) "正常运行" else "服务不可用",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.weight(1f))
                        if (state.loading) {
                            CircularProgressIndicator(
                                Modifier.size(19.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    val model = state.health.optString("model")
                        .ifBlank { "gpt-6-mini" }
                    val latency = state.health.optString("latency")
                        .ifBlank { "--" }
                    val remaining = state.health.optLong(
                        "remaining_seconds",
                        state.health.optLong("quota_remaining", -1)
                    )

                    StatusValue("模型", model)
                    StatusValue("延迟", latency)
                    StatusValue(
                        "额度",
                        if (remaining >= 0) "还剩 ${remaining} 秒" else "服务端未提供"
                    )

                    Spacer(Modifier.height(23.dp))

                    Button(
                        onClick = onCopy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Green
                        )
                    ) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            null,
                            Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("复制接入地址")
                    }

                    Text(
                        endpoint,
                        color = Muted,
                        fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }

        item {
            Text(
                "服务概览",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStatusCard(
                    "Hook",
                    if (state.hook.optBoolean("enabled", false)) "已启用" else "状态未知",
                    Icons.Outlined.Extension,
                    Modifier.weight(1f)
                )
                MiniStatusCard(
                    "API",
                    if (state.online) "已连接" else "未连接",
                    Icons.Outlined.CloudDone,
                    Modifier.weight(1f)
                )
                MiniStatusCard(
                    "今日用量",
                    state.health.optString("today_usage")
                        .ifBlank { "--" },
                    Icons.Outlined.Bolt,
                    Modifier.weight(1f)
                )
            }
        }

        item {
            CardBlock {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("模型档位", fontWeight = FontWeight.Bold)
                        Text(
                            "${state.models.length()} 个模型",
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Outlined.Refresh, null)
                    }
                }

                for (i in 0 until state.models.length()) {
                    val model = state.models.optJSONObject(i)
                    val name = model?.optString("id")
                        ?: model?.optString("name")
                        ?: state.models.optString(i)
                    if (name.isNotBlank()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Memory,
                                null,
                                tint = Green,
                                modifier = Modifier.size(19.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(name, fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        item {
            state.error?.let {
                Text(it, color = Color(0xFFE05252))
            }
        }
    }
}

@Composable
private fun StatusValue(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Muted, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun MiniStatusCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(115.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Icon(icon, null, tint = Green, modifier = Modifier.size(21.dp))
            Text(title, color = Muted, fontSize = 11.sp)
            Text(
                value,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ApiPage(
    endpoint: String,
    apiKey: String,
    expanded: Boolean,
    latency: List<LatencyPoint>,
    usage: JSONObject,
    initialRelayConfig: RelayConfig,
    modelIds: List<String>,
    onRelayConfigSave: (RelayConfig) -> Unit,
    onImportKit: () -> Unit,
    onExpand: () -> Unit,
    onEndpointChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    onCopy: (String) -> Unit,
    onTest: () -> Unit
) {
    var showKey by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(17.dp)
    ) {
        item {
            Text("API 接入", fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text("连接配置与实际请求性能", color = Muted)
        }

        item {
            CardBlock {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("接入信息", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("API 地址和访问密钥", color = Muted, fontSize = 12.sp)
                    }
                    IconButton(onClick = onExpand) {
                        Icon(
                            if (expanded) Icons.Outlined.ExpandLess
                            else Icons.Outlined.ExpandMore,
                            null
                        )
                    }
                }

                AnimatedVisibility(expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FieldLabel("Base URL")
                        AppTextField(
                            endpoint,
                            onEndpointChange,
                            "http://127.0.0.1:8766"
                        )

                        FieldLabel("API Key")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                AppTextField(
                                    if (showKey) apiKey else "•".repeat(apiKey.length),
                                    onKeyChange,
                                    "sk-..."
                                )
                            }
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    if (showKey) Icons.Outlined.VisibilityOff
                                    else Icons.Outlined.Visibility,
                                    null
                                )
                            }
                            IconButton(onClick = { onCopy(apiKey) }) {
                                Icon(Icons.Outlined.ContentCopy, null)
                            }
                        }

                        Button(
                            onClick = onSave,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("保存配置")
                        }
                    }
                }

                OutlinedButton(
                    onClick = onTest,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Outlined.NetworkCheck, null)
                    Spacer(Modifier.width(8.dp))
                    Text("测试连接并记录延迟")
                }
            }
        }

        item {
            CardBlock {
                Text("真实用量与耗时", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("来自网关请求记录；Token 为字符数估算", color = Muted, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("今日请求", usage.optInt("requests").toString())
                    Metric("成功率", "${(usage.optDouble("success_rate") * 100).toInt()}%")
                    Metric("平均总耗时", "${usage.optLong("avg_ms")}ms")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("平均首字", "${usage.optLong("avg_ttft_ms")}ms")
                    Metric("最近首字", "${usage.optLong("last_ttft_ms")}ms")
                    Metric("最近总耗时", "${usage.optLong("last_ms")}ms")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("输入 Token", usage.optLong("prompt_tokens").toString())
                    Metric("输出 Token", usage.optLong("completion_tokens").toString())
                    Metric("总 Token", usage.optLong("total_tokens").toString())
                }
                val recent = usage.optJSONArray("recent") ?: JSONArray()
                if (recent.length() == 0) {
                    Text("完成一次 ChatGPT 请求后，这里会显示最近记录", color = Muted, fontSize = 12.sp)
                } else {
                    Text("最近请求", fontWeight = FontWeight.SemiBold)
                    for (i in (recent.length() - 20).coerceAtLeast(0) until recent.length()) {
                        val item = recent.optJSONObject(i) ?: continue
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("${item.optString("model", "auto")} · ${item.optString("channel", "chatgpt")}", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Text(if (item.optBoolean("ok")) "成功" else "失败", color = if (item.optBoolean("ok")) Green else Color(0xFFE05252), fontSize = 11.sp)
                            }
                            Text("首字 ${item.optLong("ttft_ms")}ms · 总计 ${item.optLong("total_ms")}ms", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        item {
            CardBlock {
                Text("请求延迟", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "最近 10 次实际连接测试 · 毫秒",
                    color = Muted,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(18.dp))

                if (latency.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "点击测试连接，记录真实请求耗时",
                            color = Muted,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LatencyChart(latency)
                }

                if (latency.isNotEmpty()) {
                    val avg = latency.map { it.millis }.average().toLong()
                    val maxValue = latency.maxOf { it.millis }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Metric("平均延迟", "${avg}ms")
                        Metric("最高延迟", "${maxValue}ms")
                        Metric("请求次数", "${latency.size}")
                    }
                }
            }
        }

        item {
            UpstreamConfig(
                initialConfig = initialRelayConfig,
                modelIds = modelIds,
                onSave = onRelayConfigSave,
                onImportKit = onImportKit
            )
        }
    }
}

@Composable
private fun LatencyChart(data: List<LatencyPoint>) {
    val maxValue = max(1L, data.maxOf { it.millis })
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(170.dp)
    ) {
        val chartBottom = size.height - 24.dp.toPx()
        val chartTop = 8.dp.toPx()
        val chartHeight = chartBottom - chartTop
        val count = data.size
        val gap = 9.dp.toPx()
        val barWidth = (size.width - gap * (count + 1)) / count

        repeat(4) { i ->
            val y = chartTop + chartHeight * i / 3f
            drawLine(
                Color.Gray.copy(alpha = 0.15f),
                Offset(0f, y),
                Offset(size.width, y),
                strokeWidth = 1.dp.toPx()
            )
        }

        data.forEachIndexed { index, point ->
            val height = chartHeight * point.millis.toFloat() / maxValue
            val left = gap + index * (barWidth + gap)
            val top = chartBottom - height

            drawRoundRect(
                color = Green.copy(alpha = 0.25f),
                topLeft = Offset(left, chartTop),
                size = androidx.compose.ui.geometry.Size(
                    barWidth,
                    chartHeight
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx())
            )

            drawRoundRect(
                color = Green,
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(
                    barWidth,
                    height.coerceAtLeast(2.dp.toPx())
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx())
            )

            drawLine(
                Color.Gray.copy(alpha = 0.3f),
                Offset(left, chartBottom),
                Offset(left + barWidth, chartBottom),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}

@Composable
private fun UpstreamConfig(
    initialConfig: RelayConfig,
    modelIds: List<String>,
    onSave: (RelayConfig) -> Unit,
    onImportKit: () -> Unit
) {
    val appContext = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var proxyMode by remember(initialConfig) { mutableStateOf(initialConfig.proxyMode) }
    var hookToken by remember(initialConfig) { mutableStateOf(initialConfig.accessToken) }
    var did by remember(initialConfig) { mutableStateOf(initialConfig.oaiDid) }
    var sessionId by remember(initialConfig) { mutableStateOf(initialConfig.sessionId) }
    var webToken by remember(initialConfig) { mutableStateOf(initialConfig.webAccessToken) }
    var webSession by remember(initialConfig) { mutableStateOf(initialConfig.webSessionToken) }
    var connector by remember(initialConfig) { mutableStateOf(initialConfig.connectorId) }
    var model by remember(initialConfig) { mutableStateOf(initialConfig.model) }
    var fastMode by remember(initialConfig) { mutableStateOf(initialConfig.fastMode) }
    var effort by remember(initialConfig) { mutableStateOf(RelayConfig.normalizeEffort(initialConfig.reasoningEffort)) }
    var search by remember(initialConfig) { mutableStateOf(initialConfig.searchEnabled) }
    var temporary by remember(initialConfig) { mutableStateOf(initialConfig.temporaryChat) }
    var revealSecrets by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var effortExpanded by remember { mutableStateOf(false) }
    var importStatus by remember { mutableStateOf("") }



    CardBlock {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("ChatGPT 上游与模型", fontWeight = FontWeight.Bold)
                Text("唯一 AI 通道 · 网页版 / Android Hook 接入", color = Muted, fontSize = 12.sp)
            }
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        Text(
            if (initialConfig.ready) "当前通道已配置" else "当前通道缺少必要凭证",
            color = if (initialConfig.ready) Green else Color(0xFFE0A43C),
            fontSize = 12.sp
        )
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = proxyMode == "hook",
                        onClick = { proxyMode = "hook" },
                        label = { Text("Android Hook") },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = proxyMode == "oauth",
                        onClick = { proxyMode = "oauth" },
                        label = { Text("账号授权") },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (proxyMode == "hook") {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            com.gpt.hook.api.ChatGptOAuthLogin.start(appContext) { _, msg ->
                                importStatus = msg
                            }
                        },
                    ) { Text("账号授权登录（推荐）") }
                    if (importStatus.isNotBlank()) {
                        Text(importStatus, fontSize = 11.sp, color = Color(0xFF2F6FEB))
                    }
                    FieldLabel("DPoP Access Token（可选，手填旧方式）")
                    OutlinedTextField(
                        value = hookToken, onValueChange = { hookToken = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = if (revealSecrets) VisualTransformation.None else PasswordVisualTransformation(),
                        placeholder = { Text("ChatGPT Android access token") }
                    )
                    FieldLabel("设备 DID")
                    OutlinedTextField(value = did, onValueChange = { did = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    FieldLabel("Session ID（可选）")
                    OutlinedTextField(value = sessionId, onValueChange = { sessionId = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                } else if (proxyMode == "oauth") {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            com.gpt.hook.api.ChatGptOAuthLogin.start(appContext) { _, msg ->
                                importStatus = msg
                            }
                        },
                    ) { Text("账号授权登录（Chrome 内完成）") }
                    if (importStatus.isNotBlank()) {
                        Text(importStatus, fontSize = 11.sp, color = Color(0xFF2F6FEB))
                    }
                    Text(
                        "在 Chrome 里完成一次授权即可，凭证会自动保存并用于 chatgpt.com/backend-api。",
                        color = Muted, fontSize = 12.sp,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("显示凭证", Modifier.weight(1f), color = Muted, fontSize = 12.sp)
                    Switch(checked = revealSecrets, onCheckedChange = { revealSecrets = it })
                }
                FieldLabel("Connector ID（可选）")
                AppTextField(connector, { connector = it }, "Connector ID")
                FieldLabel("默认模型")
                ExposedDropdownMenuBox(expanded = modelExpanded, onExpandedChange = { modelExpanded = !modelExpanded }) {
                    OutlinedTextField(
                        value = model, onValueChange = {}, readOnly = true,
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelExpanded) }
                    )
                    ExposedDropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                        modelIds.distinct().forEach { id ->
                            DropdownMenuItem(text = { Text(id) }, onClick = { model = id; modelExpanded = false })
                        }
                    }
                }
                if (modelIds.isEmpty()) {
                    Text("尚未获取模型列表。请确认网关已启动，然后点击页面右上角刷新。", color = Muted, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("快速模式", fontWeight = FontWeight.Medium)
                        Text("不注入 reason 和推理强度", color = Muted, fontSize = 11.sp)
                    }
                    Switch(checked = fastMode, onCheckedChange = { fastMode = it })
                }
                if (!fastMode) {
                    FieldLabel("推理强度")
                    ExposedDropdownMenuBox(expanded = effortExpanded, onExpandedChange = { effortExpanded = !effortExpanded }) {
                        OutlinedTextField(
                            value = effort, onValueChange = {}, readOnly = true,
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(effortExpanded) }
                        )
                        ExposedDropdownMenu(expanded = effortExpanded, onDismissRequest = { effortExpanded = false }) {
                            listOf("low", "medium", "high").forEach { value ->
                                DropdownMenuItem(text = { Text(value) }, onClick = { effort = value; effortExpanded = false })
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("联网检索", Modifier.weight(1f))
                    Switch(checked = search, onCheckedChange = { search = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("临时对话", Modifier.weight(1f))
                    Switch(checked = temporary, onCheckedChange = { temporary = it })
                }
                Text("快速模式不会发送推理提示；思考模式才会发送 reasoning_effort。Token 仅保存在本机。", color = Muted, fontSize = 11.sp)
                Button(
                    onClick = {
                        onSave(initialConfig.copy(
                            proxyMode = proxyMode,
                            siwcProfileId = if (proxyMode == "siwc")
                                ChatGptPlanProfileStore(appContext).snapshot().activeProfileId.orEmpty()
                            else initialConfig.siwcProfileId,
                            accessToken = hookToken.trim(), oaiDid = did.trim(), sessionId = sessionId.trim(),
                            webAccessToken = webToken.trim(), webSessionToken = webSession.trim(),
                            connectorId = connector.trim(), model = model,
                            fastMode = fastMode, reasoningEffort = effort,
                            searchEnabled = search, temporaryChat = temporary
                        ))
                    },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
                ) { Text("保存并重启网关") }
                OutlinedButton(onClick = onImportKit, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Outlined.ContentPaste, null)
                    Spacer(Modifier.width(8.dp))
                    Text("从剪贴板导入 ChatGPT kit.json")
                }
            }
        }
    }
}

@Composable
private fun ToolsPage(
    tools: List<ToolItem>,
    history: List<ToolCallRecord>,
    backend: Backend,
    onCall: (String, JSONObject) -> Unit,
    onReadWeb: suspend (String) -> ToolCallRecord,
    onReload: () -> Unit
) {
    var subTab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("工具列表", "网页读取", "调用历史").forEachIndexed { index, title ->
                FilterChip(
                    selected = subTab == index,
                    onClick = { subTab = index },
                    label = { Text(title) },
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }

        when (subTab) {
            0 -> ToolListPage(tools, onCall, onReload)
            1 -> WebReaderPage(onReadWeb)
            2 -> ToolHistoryPage(history)
        }
    }
}

@Composable
private fun ToolListPage(
    tools: List<ToolItem>,
    onCall: (String, JSONObject) -> Unit,
    onReload: () -> Unit
) {
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<ToolItem?>(null) }

    val filtered = tools.filter {
        it.name.contains(search, true) ||
            it.group.contains(search, true) ||
            it.description.contains(search, true)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
                AppTextField(search, { search = it }, "搜索工具名称或描述")
            }
            IconButton(onClick = onReload) {
                Icon(Icons.Outlined.Refresh, null)
            }
        }

        Row(
            Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${filtered.size} 个工具",
                color = Muted,
                fontSize = 12.sp
            )
            Spacer(Modifier.weight(1f))
            Text("来自 MCP", color = Muted, fontSize = 12.sp)
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            val groups = filtered.groupBy { it.group }
            groups.forEach { (group, itemsInGroup) ->
                item(key = "group_$group") {
                    Row(
                        Modifier.padding(top = 10.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(group, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.weight(1f))
                        Text("${itemsInGroup.size}", color = Muted)
                    }
                }

                items(itemsInGroup, key = { it.name }) { tool ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = tool },
                        shape = RoundedCornerShape(13.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(1.dp)
                    ) {
                        Row(
                            Modifier.padding(15.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    tool.name,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                if (tool.description.isNotBlank()) {
                                    Text(
                                        tool.description,
                                        color = Muted,
                                        fontSize = 12.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Icon(
                                Icons.Outlined.ChevronRight,
                                null,
                                tint = Muted
                            )
                        }
                    }
                }
            }

            if (filtered.isEmpty()) {
                item {
                    Text(
                        if (tools.isEmpty()) "正在等待后端工具数据，或接口暂不可用"
                        else "没有匹配的工具",
                        color = Muted,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }
        }
    }

    selected?.let { tool ->
        ToolCallDialog(
            tool = tool,
            onDismiss = { selected = null },
            onCall = { args ->
                onCall(tool.name, args)
                selected = null
            }
        )
    }
}

@Composable
private fun ToolCallDialog(
    tool: ToolItem,
    onDismiss: () -> Unit,
    onCall: (JSONObject) -> Unit
) {
    var arguments by remember {
        mutableStateOf(
            tool.schema.optJSONObject("properties")?.let { properties ->
                val obj = JSONObject()
                for (key in properties.keys()) {
                    val property = properties.optJSONObject(key)
                    if (property?.has("default") == true) {
                        obj.put(key, property.opt("default"))
                    }
                }
                obj.toString(2)
            } ?: "{}"
        )
    }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(tool.name, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    tool.description.ifBlank { "MCP 工具调用" },
                    color = Muted,
                    fontSize = 13.sp
                )
                Text("Arguments · JSON", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = arguments,
                    onValueChange = {
                        arguments = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 9,
                    shape = RoundedCornerShape(12.dp)
                )
                error?.let {
                    Text(it, color = Color.Red, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                try {
                    onCall(JSONObject(arguments))
                } catch (_: Exception) {
                    error = "JSON 格式错误，请检查参数"
                }
            }) {
                Text("执行调用")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun WebReaderPage(
    onReadWeb: suspend (String) -> ToolCallRecord
) {
    var url by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ToolCallRecord?>(null) }
    var readerText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Text("网页读取器", fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text(
            "通过后端读取网页内容并提取正文",
            color = Muted,
            fontSize = 13.sp
        )

        Spacer(Modifier.height(17.dp))

        AppTextField(
            url,
            { url = it },
            "https://example.com"
        )

        Spacer(Modifier.height(10.dp))

        Button(
            onClick = {
                if (url.isBlank()) return@Button
                loading = true
                result = null
                readerText = ""
                scope.launch {
                    val record = onReadWeb(url.trim())
                    result = record
                    loading = false
                    if (record.success) {
                        readerText = extractReadableText(record.result)
                    }
                }
            },
            enabled = !loading && url.startsWith("https://") || (
                !loading && url.startsWith("http://")
            ),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (loading) {
                CircularProgressIndicator(
                    Modifier.size(18.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Outlined.Language, null)
                Spacer(Modifier.width(8.dp))
                Text("读取网页")
            }
        }

        result?.let {
            Spacer(Modifier.height(13.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            if (it.success) Green else Color.Red,
                            CircleShape
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (it.success) "读取成功 · ${it.elapsed}ms" else "读取失败",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        if (readerText.isNotBlank()) {
            Text(
                "正文",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Spacer(Modifier.height(10.dp))
            Surface(
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp),
                shadowElevation = 2.dp
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(13.dp)
                ) {
                    val lines = readerText.lines()
                    items(lines.size) { index ->
                        Row(
                            Modifier.padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "${index + 1}",
                                color = Muted,
                                fontSize = 11.sp,
                                modifier = Modifier.width(25.dp)
                            )
                            Text(
                                lines[index],
                                fontSize = 14.sp,
                                lineHeight = 23.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        } else if (result != null) {
            Text(
                result!!.result,
                fontSize = 12.sp,
                color = Muted
            )
        } else {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.Article,
                        null,
                        tint = Muted,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("输入网址开始阅读", color = Muted)
                }
            }
        }
    }
}

@Composable
private fun ToolHistoryPage(history: List<ToolCallRecord>) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("调用历史", fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Text("本次运行期间的工具调用记录", color = Muted, fontSize = 13.sp)
        }

        if (history.isEmpty()) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("还没有工具调用记录", color = Muted)
                }
            }
        }

        items(history) { record ->
            var expanded by remember { mutableStateOf(false) }
            Card(
                shape = RoundedCornerShape(13.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(15.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(9.dp)
                                .background(
                                    if (record.success) Green else Color.Red,
                                    CircleShape
                                )
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            record.name,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text("${record.elapsed}ms", color = Muted, fontSize = 12.sp)
                    }

                    AnimatedVisibility(expanded) {
                        Column {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                record.result,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HookPage(
    hook: JSONObject,
    events: JSONArray,
    requestDump: JSONObject,
    onSetDumpEnabled: (Boolean) -> Unit,
    onClearDump: () -> Unit,
    onRefresh: () -> Unit,
    onSaveRule: (JSONObject) -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onDiscover: (JSONObject) -> Unit
) {
    var section by remember { mutableStateOf("总览") }
    var search by remember { mutableStateOf("") }
    var prefix by remember { mutableStateOf("com.openai.") }
    var showEditor by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<JSONObject?>(null) }
    var editClass by remember { mutableStateOf("") }
    var editMethod by remember { mutableStateOf("") }
    var editParameters by remember { mutableStateOf("[]") }
    var editorError by remember { mutableStateOf<String?>(null) }
    var expandedRuleId by remember { mutableStateOf<String?>(null) }

    val unavailable = hook.optBoolean("statusUnavailable", false)
    val rules = hook.optJSONArray("configuredHooks") ?: hook.optJSONArray("configuredRules") ?: JSONArray()
    val installed = hook.optJSONArray("installedHooks") ?: JSONArray()
    val enabled = hook.optBoolean("enabled", false)
    val reportAt = hook.optLong("targetReportUpdatedAtMs", 0L)
    val ageMs = if (reportAt > 0L) (System.currentTimeMillis() - reportAt).coerceAtLeast(0L) else Long.MAX_VALUE
    val targetOnline = reportAt > 0L && ageMs <= 30_000L
    val activeCount = hook.optInt("activeHookCount", 0)
    val observedRuleIds = buildSet {
        for (i in 0 until installed.length()) installed.optJSONObject(i)?.optString("ruleId")?.takeIf { it.isNotBlank() }?.let(::add)
    }
    val ruleItems = (0 until rules.length()).mapNotNull { rules.optJSONObject(it) }
    val eventItems = (0 until events.length()).mapNotNull { events.optJSONObject(it) }
    val filteredRules = ruleItems.filter { search.isBlank() || it.optString("className").contains(search, true) || it.optString("methodName").contains(search, true) || it.optString("id").contains(search, true) }
    val filteredEvents = eventItems.filter { search.isBlank() || it.optString("class").contains(search, true) || it.optString("method").contains(search, true) || it.optString("ruleId").contains(search, true) }
    val task = hook.optJSONObject("discoveryTask") ?: JSONObject()
    val discoveryResult = hook.optJSONObject("discoveryResult") ?: JSONObject()
    val applyReport = hook.optJSONObject("lastApplyReport") ?: JSONObject()
    val builtInGroups = listOf(
        Triple("设备信息", "Build 字段", "android.os.Build · MANUFACTURER / BRAND / MODEL / DEVICE / PRODUCT / HARDWARE / FINGERPRINT / BOOTLOADER / TAGS / TYPE / HOST"),
        Triple("系统属性", "属性读取", "android.os.SystemProperties.get(String) · get(String, String)"),
        Triple("文件检测", "文件状态", "java.io.File.exists() · isFile() · canRead() · canExecute()"),
        Triple("命令执行", "命令检测", "java.lang.Runtime.exec(String) · exec(String[])"),
        Triple("包查询", "安装包查询", "android.app.ApplicationPackageManager.getPackageInfo(String, int)"),
        Triple("调试检测", "调试状态", "android.os.Debug.isDebuggerConnected() · waitingForDebugger()"),
        Triple("证书校验", "TLS 校验路径", "okhttp3.CertificatePinner.check(...) · Conscrypt TrustManagerImpl.verifyChain(...)"),
        Triple("遥测调用", "SDK 调用", "Firebase · Crashlytics · Statsig · Amplitude · Segment · Sentry · Mixpanel · AppsFlyer · Adjust")
    )
    fun beginEdit(rule: JSONObject? = null) {
        editingRule = rule
        editClass = rule?.optString("className").orEmpty()
        editMethod = rule?.optString("methodName").orEmpty()
        editParameters = rule?.optJSONArray("parameterTypes")?.toString() ?: "[]"
        editorError = null
        showEditor = true
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Hook 控制台", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Text("目标应用 · com.openai.chatgpt", color = Muted, fontSize = 12.sp)
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, "刷新") }
            }
        }
        if (unavailable) item { CardBlock { Text("Hook 状态暂不可用", fontWeight = FontWeight.Bold); Text(hook.optString("error", "无法读取 Hook 控制接口"), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) } }
        item {
            CardBlock {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (targetOnline) "目标进程在线" else "目标进程未确认在线", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(if (reportAt == 0L) "尚未收到目标进程报告" else if (targetOnline) "最近报告 ${ageMs / 1000} 秒前" else "最近报告已过期 · ${ageMs / 1000} 秒前", color = Muted, fontSize = 12.sp)
                    }
                    StatusPill(if (targetOnline) "在线" else "未知 / 离线")
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("动态 Hook 总开关", fontWeight = FontWeight.SemiBold)
                        Text("只控制动态规则，不控制内置 Hook", color = Muted, fontSize = 11.sp)
                    }
                    Switch(checked = enabled, onCheckedChange = onSetEnabled)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("已配置", ruleItems.size.toString())
                    Metric("已安装方法", activeCount.toString())
                    Metric("事件", eventItems.size.toString())
                    Metric("Revision", hook.optLong("configRevision", 0L).toString())
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                listOf("总览", "内置 Hook", "动态 Hook", "类发现", "事件", "网关请求", "诊断").forEach { name ->
                    FilterChip(selected = section == name, onClick = { section = name }, label = { Text(name, fontSize = 10.sp) })
                }
            }
        }
        if (section == "总览") {
            item {
                CardBlock {
                    Text("运行概况", fontWeight = FontWeight.Bold)
                    StatusValue("模块注入报告", if (reportAt > 0L) "已收到" else "尚无报告")
                    StatusValue("运行时状态", hook.optString("runtimeState", "unavailable"))
                    StatusValue("动态规则安装方法", activeCount.toString())
                    StatusValue("类加载监听", if (hook.optBoolean("classLoadWatcherEnabled", false)) "已启用" else "未启用 / 未报告")
                    StatusValue("最近应用结果", applyReport.optString("state", "unavailable"))
                    if (hook.optBoolean("hookInventoryTruncated", false)) Text("运行时清单已截断，数量不是完整总数。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
            item { CardBlock { Text("控制台分区", fontWeight = FontWeight.Bold); Text("内置 Hook 清单、动态规则管理、DEX 类发现、事件检索和运行诊断已拆分为独立页面。", color = Muted, fontSize = 12.sp); Text("内置 Hook 尚无逐项安装回报，未确认状态不会显示成已安装。", color = Muted, fontSize = 12.sp) } }
        }
        if (section == "内置 Hook") {
            item { CardBlock { Text("内置 Hook 能力清单", fontWeight = FontWeight.Bold, fontSize = 17.sp); Text("以下是当前 APK Hardening 实现中的目标分类。当前协议没有逐项安装结果，因此明确标注未逐项确认。", color = Muted, fontSize = 12.sp) } }
            items(builtInGroups) { group ->
                CardBlock {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(group.first, fontWeight = FontWeight.SemiBold); Text(group.second, color = Muted, fontSize = 11.sp) }
                        StatusPill("未逐项确认")
                    }
                    Text(group.third, fontSize = 11.sp, color = Muted)
                }
            }
            item { CardBlock { Text("状态边界", fontWeight = FontWeight.SemiBold); Text("需要目标进程逐项上报安装成功、类未找到、方法未匹配或安装失败，才能显示真实运行状态。当前页面不推测安装结果。", color = Muted, fontSize = 12.sp) } }
        }
        if (section == "动态 Hook") {
            item { Row(verticalAlignment = Alignment.CenterVertically) { Text("动态规则 · ${ruleItems.size}", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f)); TextButton(onClick = { beginEdit() }) { Icon(Icons.Outlined.Add, null); Text("新增") } } }
            item { OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索类名、方法名或规则 ID") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }) }
            if (filteredRules.isEmpty()) item { CardBlock { Text(if (ruleItems.isEmpty()) "暂无动态规则" else "没有匹配的规则"); Text("配置会立即显示；实际安装状态以目标进程回报为准。", color = Muted, fontSize = 12.sp) } }
            else items(filteredRules, key = { it.optString("id") }) { rule ->
                val id = rule.optString("id")
                val isEnabled = rule.optBoolean("enabled", true)
                val actualCount = (0 until installed.length()).count { installed.optJSONObject(it)?.optString("ruleId") == id }
                CardBlock {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(rule.optString("methodName", "未命名方法"), fontWeight = FontWeight.SemiBold)
                            Text(rule.optString("className"), color = Muted, fontSize = 11.sp)
                            Text("模式 ${rule.optString("mode", "observe")} · 安装方法 ${actualCount} · ${if (!enabled) "总开关关闭" else if (!isEnabled) "规则禁用" else if (!targetOnline) "等待新报告" else if (id in observedRuleIds) "已上报安装" else "尚未确认安装"}", color = Muted, fontSize = 11.sp)
                        }
                        Switch(checked = isEnabled, onCheckedChange = { value -> onSaveRule(JSONObject(rule.toString()).put("enabled", value)) })
                    }
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { expandedRuleId = if (expandedRuleId == id) null else id }) { Text(if (expandedRuleId == id) "收起详情" else "详情") }
                        TextButton(onClick = { beginEdit(rule) }) { Icon(Icons.Outlined.Edit, null); Text("编辑") }
                        TextButton(onClick = { onSaveRule(JSONObject().put("id", id).put("_delete", true)) }) { Icon(Icons.Outlined.Delete, null); Text("删除") }
                    }
                    if (expandedRuleId == id) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        Text("实际匹配方法", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        val actualMethods = (0 until installed.length()).mapNotNull { installed.optJSONObject(it) }.filter { it.optString("ruleId") == id }
                        if (actualMethods.isEmpty()) Text("目标进程尚未回报安装方法。", color = Muted, fontSize = 11.sp)
                        actualMethods.forEach { actual ->
                            Text(actual.optString("signature"), fontSize = 10.sp)
                            Text("调用 ${actual.optLong("invocations", 0L)} 次", color = Muted, fontSize = 10.sp)
                        }
                        val relatedEvents = eventItems.filter { it.optString("ruleId") == id || it.optString("rule_id") == id }
                        Text("关联事件 · ${relatedEvents.size}", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        relatedEvents.takeLast(10).reversed().forEach { event ->
                            Text("${event.optString("method")} · ${event.optString("exception", "无异常")}", fontSize = 10.sp, color = Muted)
                        }
                    }
                }
            }
            item { CardBlock { Text("数据边界", fontWeight = FontWeight.SemiBold); Text("规则观察只展示类 / 方法元数据、耗时和异常，不采集参数、返回值、Cookie、Token、请求头或请求体。", color = Muted, fontSize = 12.sp) } }
        }
        if (section == "类发现") {
            item {
                CardBlock {
                    Text("DEX 类与方法发现", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("提交目标进程扫描任务；结果可能受 DEX 数量、类数量、耗时和响应预算限制。", color = Muted, fontSize = 12.sp)
                    OutlinedTextField(value = prefix, onValueChange = { prefix = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("类名前缀") })
                    Button(onClick = { onDiscover(JSONObject().put("prefix", prefix.trim()).put("limit", 100).put("cursor", 0)) }, enabled = prefix.startsWith("com.openai.") && prefix.length <= 180, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Search, null); Text("开始扫描") }
                }
            }
            item {
                CardBlock {
                    Text("启动时发现", fontWeight = FontWeight.SemiBold)
                    StatusValue("更新时间", hook.optLong("startupDiscoveryUpdatedAtMs", 0L).toString())
                    StatusValue("发现类数", hook.optInt("startupDiscoveryClassCount", 0).toString())
                    if (hook.optBoolean("startupDiscoveryAvailable", false)) Text("启动发现结果已上报，可通过启动发现接口读取详情。", color = Muted, fontSize = 11.sp)
                    else Text("尚无可用的启动发现结果。", color = Muted, fontSize = 11.sp)
                }
            }
            item {
                CardBlock {
                    Text("最近扫描任务", fontWeight = FontWeight.SemiBold)
                    StatusValue("任务 ID", task.optString("requestId", "暂无"))
                    StatusValue("状态", task.optString("status", "尚未提交"))
                    StatusValue("更新时间", task.optLong("updatedAtMs", 0L).toString())
                    if (task.has("error")) Text(task.optString("error"), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    Text("扫描结果不等于已安装 Hook；刷新状态可读取最近一次扫描结果。", color = Muted, fontSize = 12.sp)
                }
            }
            if (discoveryResult.optBoolean("ok", false)) {
                item {
                    CardBlock {
                        Text("扫描结果", fontWeight = FontWeight.Bold)
                        StatusValue("扫描类数", discoveryResult.optInt("classesScanned", 0).toString())
                        StatusValue("发现类数", discoveryResult.optJSONArray("classes")?.length()?.toString() ?: "0")
                        StatusValue("成员记录", discoveryResult.optInt("recordCount", 0).toString())
                        StatusValue("DEX 数", discoveryResult.optInt("dexScanned", 0).toString())
                        StatusValue("耗时", "${discoveryResult.optLong("elapsedMs", 0L)} ms")
                        StatusValue("分页", if (discoveryResult.optBoolean("hasMore", false)) "还有下一页" else "已完成")
                        if (discoveryResult.optBoolean("timedOut", false) || discoveryResult.optBoolean("scanTruncated", false) || discoveryResult.optBoolean("responseTruncated", false) || discoveryResult.optBoolean("classMetadataTruncated", false)) {
                            Text("结果受到扫描时间、数量或响应预算限制，可能不完整。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                        if (discoveryResult.optBoolean("hasMore", false)) {
                            TextButton(onClick = {
                                onDiscover(JSONObject().put("prefix", prefix.trim()).put("limit", 100).put("cursor", discoveryResult.optInt("nextCursor", 0)))
                            }) { Text("加载下一页") }
                        }
                    }
                }
                val classes = discoveryResult.optJSONArray("classes") ?: JSONArray()
                items(classes.length()) { index ->
                    val item = classes.optJSONObject(index) ?: JSONObject()
                    val className = item.optString("className")
                    val methods = item.optJSONArray("methods") ?: JSONArray()
                    val inherited = item.optJSONArray("inheritedMethods") ?: JSONArray()
                    val constructors = item.optJSONArray("constructors") ?: JSONArray()
                    val fields = item.optJSONArray("fields") ?: JSONArray()
                    CardBlock {
                        Text(className, fontWeight = FontWeight.SemiBold)
                        StatusValue("声明方法", methods.length().toString())
                        StatusValue("继承方法", inherited.length().toString())
                        StatusValue("构造器", constructors.length().toString())
                        StatusValue("字段", fields.length().toString())
                        if (methods.length() > 0) {
                            Text("声明方法签名", fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                            for (methodIndex in 0 until minOf(methods.length(), 20)) Text(methods.optString(methodIndex), fontSize = 10.sp, color = Muted)
                        }
                        if (inherited.length() > 0) {
                            Text("继承方法", fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                            for (methodIndex in 0 until minOf(inherited.length(), 10)) Text(inherited.optString(methodIndex), fontSize = 10.sp, color = Muted)
                        }
                        if (constructors.length() > 0) {
                            Text("构造器", fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                            for (methodIndex in 0 until minOf(constructors.length(), 10)) Text(constructors.optString(methodIndex), fontSize = 10.sp, color = Muted)
                        }
                        if (fields.length() > 0) {
                            Text("字段", fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                            for (fieldIndex in 0 until minOf(fields.length(), 20)) Text(fields.optString(fieldIndex), fontSize = 10.sp, color = Muted)
                        }
                        if (item.optBoolean("metadataTruncated", false)) Text("此类的成员元数据已截断。", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                        TextButton(onClick = { onDiscover(JSONObject().put("className", className).put("limit", 100).put("cursor", 0)) }) { Text("重新检查该类") }
                    }
                }
                val candidates = discoveryResult.optJSONArray("candidateProposals") ?: JSONArray()
                if (candidates.length() > 0) item {
                    CardBlock {
                        Text("候选规则 · ${candidates.length()}", fontWeight = FontWeight.SemiBold)
                        for (index in 0 until minOf(candidates.length(), 12)) {
                            val candidate = candidates.optJSONObject(index) ?: continue
                            Text(candidate.optString("runtimeClassName") + "." + candidate.optString("methodName"), fontSize = 11.sp)
                            Text(candidate.optString("jvmDescriptor"), fontSize = 10.sp, color = Muted)
                            TextButton(onClick = {
                                val candidateId = candidate.optString("candidateId").take(64)
                                if (candidateId.isNotBlank()) {
                                    onSaveRule(JSONObject()
                                        .put("id", candidateId)
                                        .put("className", candidate.optString("runtimeClassName"))
                                        .put("methodName", candidate.optString("methodName"))
                                        .put("methodDescriptor", candidate.optString("jvmDescriptor"))
                                        .put("mode", "observe")
                                        .put("enabled", true)
                                        .put("sampleEvery", 1))
                                }
                            }) { Text("生成观察规则") }
                        }
                        Text("候选项仅供检查；点击生成后才会加入动态规则，仍需目标进程确认安装。", color = Muted, fontSize = 11.sp)
                    }
                }
            }
        }
        if (section == "事件") {
            item { Text("事件中心 · ${eventItems.size}", fontSize = 20.sp, fontWeight = FontWeight.Bold); OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索规则、类名或方法") }) }
            if (filteredEvents.isEmpty()) item { CardBlock { Text("暂无匹配事件"); Text("事件仅包含规则、类 / 方法元数据、耗时和异常，不包含参数和凭证。", color = Muted, fontSize = 12.sp) } }
            else items(filteredEvents.takeLast(100).reversed()) { event ->
                CardBlock {
                    Text(event.optString("method", "未知方法"), fontWeight = FontWeight.SemiBold)
                    Text(event.optString("class", "未知类"), color = Muted, fontSize = 11.sp)
                    StatusValue("规则", event.optString("ruleId", event.optString("rule_id", "—")))
                    StatusValue("耗时", "${event.optLong("elapsedMs", event.optLong("durationMs", 0L))} ms")
                    StatusValue("异常", event.optString("exception", "无"))
                    StatusValue("时间", event.optString("timestamp", "—"))
                }
            }
        }
        if (section == "网关请求") {
            val dumpEnabled = requestDump.optBoolean("enabled", false)
            val dumpRecords = requestDump.optJSONArray("records") ?: JSONArray()
            item {
                CardBlock {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("网关请求抓取", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("默认关闭 · 仅记录网关请求，不采集认证请求头", color = Muted, fontSize = 11.sp)
                        }
                        Switch(checked = dumpEnabled, onCheckedChange = onSetDumpEnabled)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    StatusValue("当前状态", if (dumpEnabled) "已启用" else "已关闭")
                    StatusValue("记录数量", "${dumpRecords.length()} / ${requestDump.optInt("maxRecords", 50)}")
                    StatusValue("保留时间", "${requestDump.optInt("retentionHours", 24)} 小时")
                    Text("只抓取进入本地 DSH 网关的 /v1/chat/completions、/v1/responses 和 /backend-api/ 请求。请求体会递归脱敏凭证字段并截断；不记录 Authorization、Cookie 等请求头。", color = Muted, fontSize = 11.sp)
                    Text("这不是 ChatGPT 目标进程内部 OkHttp / Cronet 流量抓取，也不包含上游响应正文。", color = Muted, fontSize = 11.sp)
                    Text("提示：请求正文可能包含你的对话内容。仅在需要排查问题时开启，完成后建议关闭并清理。", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    TextButton(onClick = onClearDump, enabled = dumpRecords.length() > 0) { Text("清空全部记录") }
                }
            }
            if (dumpRecords.length() == 0) {
                item { CardBlock { Text(if (dumpEnabled) "等待下一次网关请求" else "暂无请求记录"); Text("启用后，新进入的请求会出现在这里。", color = Muted, fontSize = 12.sp) } }
            } else {
                items((0 until dumpRecords.length()).mapNotNull { dumpRecords.optJSONObject(it) }.reversed()) { record ->
                    CardBlock {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(record.optString("method", "POST"), fontWeight = FontWeight.Bold)
                            Text(record.optString("path", ""), color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        }
                        StatusValue("时间", record.optLong("timestamp", 0L).toString())
                        StatusValue("类型", record.optString("contentType", "未知"))
                        if (record.optBoolean("bodyTruncated", false)) Text("正文预览已截断", color = MaterialTheme.colorScheme.error, fontSize = 10.sp)
                        Text(record.optString("body", "").ifBlank { "（空请求体）" }, fontSize = 10.sp, color = Muted)
                    }
                }
            }
        }
        if (section == "诊断") {
            item {
                CardBlock {
                    Text("目标进程诊断", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    StatusValue("目标包", hook.optString("targetPackage", "com.openai.chatgpt"))
                    StatusValue("报告状态", if (targetOnline) "新鲜报告" else if (reportAt > 0L) "报告过期" else "无报告")
                    StatusValue("报告时间", reportAt.toString())
                    StatusValue("运行时状态", hook.optString("runtimeState", "unavailable"))
                    StatusValue("运行时快照", hook.optLong("runtimeSnapshotAtMs", 0L).toString())
                    StatusValue("配置 revision", hook.optLong("configRevision", 0L).toString())
                    StatusValue("安装清单", "${installed.length()} 条")
                    StatusValue("清单截断", if (hook.optBoolean("hookInventoryTruncated", false)) "是" else "否")
                }
            }
            item { CardBlock { Text("最后一次规则应用", fontWeight = FontWeight.SemiBold); Text(applyReport.toString(2), fontSize = 10.sp, color = Muted) } }
            item {
                CardBlock {
                    Text("高级能力", fontWeight = FontWeight.SemiBold)
                    StatusValue("类加载监听", if (hook.optBoolean("classLoadWatcherEnabled", false)) "已启用" else "未启用 / 未报告")
                    StatusValue("网关请求抓取", if (requestDump.optBoolean("enabled", false)) "已启用" else "已关闭")
                    Text("独立的请求抓取页面提供开关、记录列表与清理；默认关闭，受本地网关 API Key 保护。", color = Muted, fontSize = 12.sp)
                }
            }
            item { CardBlock { Text("旧版未迁移能力", fontWeight = FontWeight.SemiBold); Text("旧版 gpt-hook 独立 Xposed 实现不属于当前 APK 运行能力；未迁移功能不会列为当前可用。", color = Muted, fontSize = 12.sp) } }
        }
    }

    if (showEditor) {
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text(if (editingRule == null) "新增动态规则" else "编辑动态规则") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = editClass, onValueChange = { editClass = it }, label = { Text("类名") }, singleLine = true)
                    OutlinedTextField(value = editMethod, onValueChange = { editMethod = it }, label = { Text("方法名") }, singleLine = true)
                    OutlinedTextField(value = editParameters, onValueChange = { editParameters = it }, label = { Text("参数类型 JSON 数组") }, minLines = 2)
                    Text("仅允许 observe / dump 观察模式；目标类必须位于 com.openai.*。", color = Muted, fontSize = 11.sp)
                    editorError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        require(editClass.startsWith("com.openai.") && editClass.length <= 240) { "类名必须位于 com.openai.*" }
                        require(editMethod.isNotBlank()) { "方法名不能为空" }
                        val args = JSONArray(editParameters)
                        val next = JSONObject(editingRule?.toString() ?: "{}")
                            .put("id", editingRule?.optString("id")?.takeIf { it.isNotBlank() } ?: "ui-" + java.util.UUID.randomUUID())
                            .put("className", editClass.trim())
                            .put("methodName", editMethod.trim())
                            .put("mode", "observe")
                            .put("parameterTypes", args)
                            .put("enabled", editingRule?.optBoolean("enabled", true) ?: true)
                            .put("sampleEvery", editingRule?.optInt("sampleEvery", 1) ?: 1)
                        onSaveRule(next)
                        showEditor = false
                    } catch (t: Throwable) {
                        editorError = t.message ?: "规则无效"
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showEditor = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun StatusPill(value: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(50)) {
        Text(value, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 11.sp)
    }
}

@Composable
private fun CardBlock(
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            Modifier.padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(label, color = Muted, fontSize = 11.sp)
        Text(
            value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FieldLabel(value: String) {
    Text(
        value,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp
    )
}

@Composable
private fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = {
            Text(placeholder, color = Muted, fontSize = 13.sp)
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        textStyle = MaterialTheme.typography.bodyMedium
    )
}

private fun JSONObjectOrEmpty(value: String): JSONObject =
    try {
        JSONObject(value)
    } catch (_: Exception) {
        JSONObject()
    }

private fun extractArray(
    value: String,
    vararg keys: String
): JSONArray {
    return try {
        val root = JSONObject(value)
        for (key in keys) {
            val array = root.optJSONArray(key)
            if (array != null) return array
        }
        JSONArray()
    } catch (_: Exception) {
        try {
            JSONArray(value)
        } catch (_: Exception) {
            JSONArray()
        }
    }
}

private fun extractReadableText(raw: String): String {
    return try {
        val json = JSONObject(raw)
        val candidates = listOf(
            "content",
            "text",
            "markdown",
            "body",
            "article",
            "readable_text"
        )
        for (key in candidates) {
            val value = json.optString(key)
            if (value.isNotBlank() && value != "null") return value
        }

        val result = json.optJSONObject("result")
        if (result != null) {
            for (key in candidates) {
                val value = result.optString(key)
                if (value.isNotBlank() && value != "null") return value
            }
        }
        json.toString(2)
    } catch (_: Exception) {
        raw
    }
}

private fun readableError(e: Exception): String {
    val message = e.localizedMessage ?: "未知错误"
    return when {
        message.contains("timeout", true) -> "连接超时，请检查服务是否运行"
        message.contains("refused", true) -> "连接被拒绝，请检查服务地址和端口"
        message.contains("UnknownHost", true) -> "无法解析服务器地址"
        else -> "请求失败：$message"
    }
}

private fun copyText(context: Context, value: String) {
    val clipboard = context.getSystemService(
        Context.CLIPBOARD_SERVICE
    ) as ClipboardManager
    clipboard.setPrimaryClip(
        ClipData.newPlainText("DSH-GPT", value)
    )
}
