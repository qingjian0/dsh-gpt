package com.gpt.hook

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val Green = Color(0xFF10A37F)
private val Background = Color(0xFFF7F7F8)
private val CardColor = Color.White
private val Dark = Color(0xFF202123)
private val Gray = Color(0xFF8E8EA0)
private val JsonMedia = "application/json; charset=utf-8".toMediaType()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Green,
                    background = Background,
                    surface = CardColor,
                    onBackground = Dark,
                    onSurface = Dark
                )
            ) {
                DshApp(this)
            }
        }
    }
}

private class ApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    var baseUrl = "http://127.0.0.1:8080"

    fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        apiKey: String = ""
    ): JSONObject {
        val url = baseUrl.trimEnd('/') + path
        val builder = Request.Builder().url(url)

        if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }

        when (method.uppercase()) {
            "POST" -> builder.post(
                (body ?: JSONObject()).toString().toRequestBody(JsonMedia)
            )
            "PUT" -> builder.put(
                (body ?: JSONObject()).toString().toRequestBody(JsonMedia)
            )
            "DELETE" -> builder.delete(
                (body ?: JSONObject()).toString().toRequestBody(JsonMedia)
            )
            else -> builder.get()
        }

        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: $text")
            }
            return try {
                JSONObject(text)
            } catch (_: Exception) {
                JSONObject().put("raw", text)
            }
        }
    }

    fun array(
        path: String,
        apiKey: String = ""
    ): JSONArray {
        val url = baseUrl.trimEnd('/') + path
        val builder = Request.Builder().url(url)
        if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: $text")
            }
            return try {
                JSONArray(text)
            } catch (_: Exception) {
                JSONObject(text).optJSONArray("data") ?: JSONArray()
            }
        }
    }
}

@Composable
private fun DshApp(context: Context) {
    val scope = rememberCoroutineScope()
    val api = remember { ApiClient() }

    var selectedTab by remember { mutableIntStateOf(0) }
    var baseUrl by remember { mutableStateOf(api.baseUrl) }
    var apiKey by remember { mutableStateOf("") }

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    var health by remember { mutableStateOf(JSONObject()) }
    var status by remember { mutableStateOf(JSONObject()) }
    var config by remember { mutableStateOf(JSONObject()) }
    var models by remember { mutableStateOf(JSONArray()) }

    var conversations by remember { mutableStateOf(JSONArray()) }
    var selectedConversation by remember { mutableStateOf("") }
    var chatInput by remember { mutableStateOf("") }
    var chatMessages by remember { mutableStateOf(JSONArray()) }

    var upstreamConversations by remember { mutableStateOf(JSONArray()) }
    var upstreamDetail by remember { mutableStateOf(JSONObject()) }
    var upstreamId by remember { mutableStateOf("") }

    var tools by remember { mutableStateOf(JSONArray()) }
    var toolSearch by remember { mutableStateOf("") }
    var selectedTool by remember { mutableStateOf(JSONObject()) }
    var toolDialog by remember { mutableStateOf(false) }
    var toolArguments by remember { mutableStateOf("{}") }
    var toolResult by remember { mutableStateOf("") }

    var webUrl by remember { mutableStateOf("") }
    var webContent by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(listOf<JSONObject>()) }

    var hookStatus by remember { mutableStateOf(JSONObject()) }
    var hookEvents by remember { mutableStateOf(JSONArray()) }
    var hookReport by remember { mutableStateOf(JSONObject()) }
    var hookReportExpanded by remember { mutableStateOf(false) }
    var hookRules by remember { mutableStateOf(JSONArray()) }

    var scanning by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableFloatStateOf(0f) }
    var scanResult by remember { mutableStateOf("") }

    var ruleClass by remember { mutableStateOf("") }
    var ruleMethod by remember { mutableStateOf("") }
    var ruleMode by remember { mutableStateOf("BEFORE") }
    var ruleParams by remember { mutableStateOf("[]") }

    var localIp by remember { mutableStateOf("127.0.0.1") }
    var lanEnabled by remember { mutableStateOf(false) }
    var temporaryChat by remember { mutableStateOf(false) }

    fun call(
        method: String,
        path: String,
        body: JSONObject? = null,
        success: ((JSONObject) -> Unit)? = null
    ) {
        if (loading && path == "/v1/start") return
        scope.launch {
            loading = true
            error = ""
            try {
                api.baseUrl = baseUrl
                val result = kotlinx.coroutines.withContext(
                    kotlinx.coroutines.Dispatchers.IO
                ) {
                    api.request(method, path, body, apiKey)
                }
                success?.invoke(result)
                message = result.optString("message", "操作成功")
            } catch (e: Exception) {
                error = e.message ?: "请求失败"
            } finally {
                loading = false
            }
        }
    }

    fun fetchArray(
        path: String,
        success: (JSONArray) -> Unit
    ) {
        scope.launch {
            try {
                api.baseUrl = baseUrl
                val result = kotlinx.coroutines.withContext(
                    kotlinx.coroutines.Dispatchers.IO
                ) {
                    api.array(path, apiKey)
                }
                success(result)
            } catch (e: Exception) {
                error = e.message ?: "请求失败"
            }
        }
    }

    fun refreshAll() {
        call("GET", "/health") { health = it }
        call("GET", "/v1/status") { status = it }
        call("GET", "/v1/config") {
            config = it
            lanEnabled = it.optBoolean("allow_lan", false)
            temporaryChat = it.optBoolean("temporary_chat", false)
            localIp = it.optString("lan_ip", localIp)
        }
        fetchArray("/v1/models") { models = it }
    }

    fun refreshConversations() {
        fetchArray("/v1/conversations") { conversations = it }
    }

    fun refreshUpstream() {
        fetchArray("/backend-api/conversations") {
            upstreamConversations = it
        }
    }

    fun refreshTools() {
        fetchArray("/v1/mcp/tools") { tools = it }
    }

    fun refreshHook() {
        call("GET", "/v1/hook/status") { hookStatus = it }
        fetchArray("/v1/hook/events") { hookEvents = it }
        call("GET", "/v1/hook/config") {
            hookRules = it.optJSONArray("rules") ?: JSONArray()
        }
    }

    LaunchedEffect(Unit) {
        refreshAll()
        refreshConversations()
        refreshTools()
        refreshHook()
    }

    Scaffold(
        containerColor = Background,
        topBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .background(Green, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("D", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            "DSH-GPT",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Personal AI Gateway",
                            fontSize = 11.sp,
                            color = Gray
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (loading) {
                        CircularProgressIndicator(
                            Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Green
                        )
                    } else {
                        IconButton(onClick = { refreshAll() }) {
                            Icon(Icons.Default.Refresh, null)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val tabs = listOf(
                        "状态", "API", "对话", "工具", "Hook"
                    )
                    tabs.forEachIndexed { index, name ->
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedTab = index }
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                name,
                                color = if (selectedTab == index) Green else Gray,
                                fontWeight = if (selectedTab == index)
                                    FontWeight.Bold else FontWeight.Normal,
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Box(
                                Modifier
                                    .width(28.dp)
                                    .height(3.dp)
                                    .background(
                                        if (selectedTab == index) Green
                                        else Color.Transparent,
                                        CircleShape
                                    )
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (error.isNotBlank() || message.isNotBlank()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (error.isNotBlank()) error else message,
                        modifier = Modifier.weight(1f),
                        color = if (error.isNotBlank()) Color.Red else Green,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = {
                        error = ""
                        message = ""
                    }) {
                        Text("关闭")
                    }
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (selectedTab) {
                0 -> StatusPage(
                    health, status, models, loading,
                    onRefresh = { refreshAll() },
                    onCopy = {
                        copyText(context, baseUrl)
                        message = "接入地址已复制"
                    }
                )

                1 -> ApiPage(
                    status = status,
                    config = config,
                    baseUrl = baseUrl,
                    onBaseUrl = { baseUrl = it },
                    apiKey = apiKey,
                    onApiKey = { apiKey = it },
                    lanEnabled = lanEnabled,
                    onLan = {
                        lanEnabled = it
                        call(
                            "POST", "/v1/config",
                            JSONObject().put("allow_lan", it)
                        ) { config = it }
                    },
                    temporaryChat = temporaryChat,
                    onTemporaryChat = {
                        temporaryChat = it
                        call(
                            "POST", "/v1/config",
                            JSONObject().put("temporary_chat", it)
                        ) { config = it }
                    },
                    localIp = localIp,
                    onStart = { call("POST", "/v1/start") },
                    onStop = { call("POST", "/v1/stop") },
                    onSave = {
                        call("POST", "/v1/config", config)
                    },
                    onImport = {
                        val clipboard = context.getSystemService(
                            Context.CLIPBOARD_SERVICE
                        ) as ClipboardManager
                        val text = clipboard.primaryClip
                            ?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (text.isNullOrBlank()) {
                            error = "剪贴板没有 kit.json 内容"
                        } else {
                            try {
                                val json = JSONObject(text)
                                call("POST", "/v1/kit/import", json)
                            } catch (e: Exception) {
                                error = "JSON 格式错误：${e.message}"
                            }
                        }
                    },
                    onCopyCommand = {
                        copyText(
                            context,
                            "curl -X POST '${baseUrl.trimEnd('/')}/v1/chat/completions' " +
                                "-H 'Content-Type: application/json' " +
                                "-H 'Authorization: Bearer $apiKey' " +
                                "-d '{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}'"
                        )
                        message = "测试命令已复制"
                    }
                )

                2 -> ConversationPage(
                    conversations = conversations,
                    selectedConversation = selectedConversation,
                    chatMessages = chatMessages,
                    chatInput = chatInput,
                    onInput = { chatInput = it },
                    onRefresh = { refreshConversations() },
                    onNew = {
                        call(
                            "POST", "/v1/conversations",
                            JSONObject().put("title", "新对话")
                        ) {
                            selectedConversation = it.optString(
                                "id", it.optString("conversation_id")
                            )
                            refreshConversations()
                        }
                    },
                    onSelect = { id ->
                        selectedConversation = id
                        chatMessages = JSONArray()
                    },
                    onSend = {
                        val text = chatInput.trim()
                        if (text.isNotEmpty() && selectedConversation.isNotBlank()) {
                            val body = JSONObject()
                                .put("conversation_id", selectedConversation)
                                .put("model", "gpt-4o")
                                .put(
                                    "messages",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("role", "user")
                                            .put("content", text)
                                    )
                                )
                            call("POST", "/v1/chat/completions", body) {
                                val messages = JSONArray()
                                for (i in 0 until chatMessages.length()) {
                                    messages.put(chatMessages.get(i))
                                }
                                messages.put(
                                    JSONObject()
                                        .put("role", "user")
                                        .put("content", text)
                                )
                                val choices = it.optJSONArray("choices")
                                val response = choices?.optJSONObject(0)
                                    ?.optJSONObject("message")
                                messages.put(
                                    response ?: JSONObject()
                                        .put(
                                            "role", "assistant"
                                        )
                                        .put(
                                            "content",
                                            it.optString("content", it.toString())
                                        )
                                )
                                chatMessages = messages
                                chatInput = ""
                            }
                        } else {
                            error = "请先创建或选择一个本地会话"
                        }
                    },
                    upstreamConversations = upstreamConversations,
                    upstreamId = upstreamId,
                    upstreamDetail = upstreamDetail,
                    onUpstreamRefresh = { refreshUpstream() },
                    onUpstreamSelect = { id ->
                        upstreamId = id
                        call(
                            "GET",
                            "/backend-api/conversation/$id"
                        ) { upstreamDetail = it }
                    }
                )

                3 -> ToolsPage(
                    tools = tools,
                    search = toolSearch,
                    onSearch = { toolSearch = it },
                    selectedTool = selectedTool,
                    dialog = toolDialog,
                    onOpenTool = {
                        selectedTool = it
                        toolArguments = "{}"
                        toolResult = ""
                        toolDialog = true
                    },
                    onCloseTool = { toolDialog = false },
                    arguments = toolArguments,
                    onArguments = { toolArguments = it },
                    result = toolResult,
                    onExecute = {
                        try {
                            val body = JSONObject()
                                .put(
                                    "name",
                                    selectedTool.optString(
                                        "name",
                                        selectedTool.optString("tool_name")
                                    )
                                )
                                .put("arguments", JSONObject(toolArguments))
                            val start = System.currentTimeMillis()
                            call("POST", "/v1/mcp/call", body) {
                                toolResult = it.toString(2)
                                history = history + JSONObject()
                                    .put("name", body.optString("name"))
                                    .put(
                                        "duration",
                                        System.currentTimeMillis() - start
                                    )
                                    .put("success", true)
                            }
                        } catch (e: Exception) {
                            toolResult = e.message ?: "参数错误"
                        }
                    },
                    webUrl = webUrl,
                    onWebUrl = { webUrl = it },
                    webContent = webContent,
                    onWebOpen = {
                        call(
                            "POST", "/v1/web/open",
                            JSONObject().put("url", webUrl)
                        ) {
                            webContent = it.optString(
                                "content",
                                it.optString("text", it.toString(2))
                            )
                        }
                    },
                    history = history,
                    onRefresh = { refreshTools() }
                )

                4 -> HookPage(
                    status = hookStatus,
                    events = hookEvents,
                    report = hookReport,
                    reportExpanded = hookReportExpanded,
                    onToggleReport = {
                        hookReportExpanded = !hookReportExpanded
                    },
                    scanning = scanning,
                    progress = scanProgress,
                    scanResult = scanResult,
                    onScan = {
                        scanning = true
                        scanProgress = 0.05f
                        call(
                            "POST",
                            "/v1/hook/discover",
                            JSONObject().put("action", "start")
                        ) {
                            scanning = false
                            scanProgress = 1f
                            scanResult = it.toString(2)
                            hookReport = it
                            hookRules = it.optJSONArray("rules") ?: hookRules
                        }
                    },
                    onCancelScan = {
                        call(
                            "POST", "/v1/hook/discover",
                            JSONObject().put("action", "cancel")
                        ) {
                            scanning = false
                        }
                    },
                    onSaveScan = {
                        copyText(context, scanResult)
                        message = "扫描结果已复制到剪贴板"
                    },
                    ruleClass = ruleClass,
                    onRuleClass = { ruleClass = it },
                    ruleMethod = ruleMethod,
                    onRuleMethod = { ruleMethod = it },
                    ruleMode = ruleMode,
                    onRuleMode = { ruleMode = it },
                    ruleParams = ruleParams,
                    onRuleParams = { ruleParams = it },
                    onCreateRule = {
                        try {
                            val params = JSONArray(ruleParams)
                            val rule = JSONObject()
                                .put("class_name", ruleClass)
                                .put("method_name", ruleMethod)
                                .put("mode", ruleMode)
                                .put("parameter_types", params)
                                .put("enabled", true)
                            call("POST", "/v1/hook/config", rule) {
                                refreshHook()
                                ruleClass = ""
                                ruleMethod = ""
                            }
                        } catch (e: Exception) {
                            error = "参数类型必须是 JSON 数组"
                        }
                    },
                    rules = hookRules,
                    onRuleAction = { id, action ->
                        call(
                            "POST", "/v1/hook/config",
                            JSONObject()
                                .put("id", id)
                                .put("action", action)
                        ) { refreshHook() }
                    },
                    onRefresh = { refreshHook() }
                )
            }
        }
    }

    if (toolDialog) {
        AlertDialog(
            onDismissRequest = { toolDialog = false },
            title = {
                Text(
                    selectedTool.optString(
                        "name",
                        selectedTool.optString("tool_name", "工具调用")
                    )
                )
            },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text("参数 JSON", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = toolArguments,
                        onValueChange = { toolArguments = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 5,
                        maxLines = 10
                    )
                    if (toolResult.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text("执行结果", fontWeight = FontWeight.Bold)
                        Text(toolResult, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                Button(onClick = { 
                    try {
                        val body = JSONObject()
                            .put(
                                "name",
                                selectedTool.optString("name")
                            )
                            .put("arguments", JSONObject(toolArguments))
                        call("POST", "/v1/mcp/call", body) {
                            toolResult = it.toString(2)
                        }
                    } catch (e: Exception) {
                        toolResult = "JSON 错误：${e.message}"
                    }
                }) {
                    Text("执行")
                }
            },
            dismissButton = {
                TextButton(onClick = { toolDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
}

@Composable
private fun StatusPage(
    health: JSONObject,
    status: JSONObject,
    models: JSONArray,
    loading: Boolean,
    onRefresh: () -> Unit,
    onCopy: () -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionTitle("服务概览", "SERVICE OVERVIEW")
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val available = health.optBoolean(
                        "ok",
                        health.optBoolean(
                            "healthy",
                            status.optBoolean("running", false)
                        )
                    )
                    StatusDot(if (available) Green else Color.Red)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (available) "服务运行正常" else "服务不可用",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        Text(
                            status.optString(
                                "message",
                                health.optString("status", "等待状态")
                            ),
                            color = Gray,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, null)
                    }
                }
                HorizontalDivider(
                    Modifier.padding(vertical = 14.dp),
                    color = Background
                )
                Row(Modifier.fillMaxWidth()) {
                    Metric(
                        "当前模型",
                        status.optString("model", "未指定"),
                        Modifier.weight(1f)
                    )
                    Metric(
                        "延迟",
                        "${status.optLong("latency", 0)} ms",
                        Modifier.weight(1f)
                    )
                    Metric(
                        "额度倒计时",
                        status.optString(
                            "quota_countdown",
                            status.optString("reset_in", "--")
                        ),
                        Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onCopy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ContentCopy, null)
                    Spacer(Modifier.width(8.dp))
                    Text("复制接入地址")
                }
            }
        }

        item {
            SectionTitle("运行指标", "SYSTEM METRICS")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallMetricCard(
                    "Hook 状态",
                    status.optString("hook_status", "未知"),
                    Modifier.weight(1f)
                )
                SmallMetricCard(
                    "API 状态",
                    status.optString("api_status", "未知"),
                    Modifier.weight(1f)
                )
                SmallMetricCard(
                    "今日用量",
                    status.optString(
                        "today_usage",
                        status.optString("usage_today", "0")
                    ),
                    Modifier.weight(1f)
                )
            }
        }

        item {
            SectionTitle("模型档位", "AVAILABLE MODELS")
        }

        items(models.length()) { index ->
            val model = models.optJSONObject(index)
            if (model != null) {
                SurfaceCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .background(
                                    Color(0xFFE9F7F2),
                                    RoundedCornerShape(10.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                null,
                                tint = Green
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                model.optString(
                                    "id",
                                    model.optString("name", "Model")
                                ),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                model.optString(
                                    "description",
                                    model.optString("tier", "模型")
                                ),
                                fontSize = 11.sp,
                                color = Gray
                            )
                        }
                        if (model.optBoolean("active", false)) {
                            Text("当前", color = Green, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, null)
                Spacer(Modifier.width(6.dp))
                Text("刷新状态与模型")
            }
        }
    }
}

@Composable
private fun ApiPage(
    status: JSONObject,
    config: JSONObject,
    baseUrl: String,
    onBaseUrl: (String) -> Unit,
    apiKey: String,
    onApiKey: (String) -> Unit,
    lanEnabled: Boolean,
    onLan: (Boolean) -> Unit,
    temporaryChat: Boolean,
    onTemporaryChat: (Boolean) -> Unit,
    localIp: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSave: () -> Unit,
    onImport: () -> Unit,
    onCopyCommand: () -> Unit
) {
    var port by remember(config) {
        mutableStateOf(config.optString("port", "8080"))
    }
    var clientKey by remember(config) {
        mutableStateOf(config.optString("client_api_key", apiKey))
    }
    var did by remember(config) {
        mutableStateOf(config.optString("oai_did", ""))
    }
    var accessToken by remember(config) {
        mutableStateOf(config.optString("access_token", ""))
    }
    var connectorId by remember(config) {
        mutableStateOf(config.optString("connector_id", ""))
    }
    var upstreamCredential by remember(config) {
        mutableStateOf(
            config.optString("upstream_credential", "未配置")
        )
    }
    var listenAddress by remember(config) {
        mutableStateOf(
            config.optString("listen_address", "0.0.0.0")
        )
    }

    val running = status.optBoolean(
        "running",
        status.optString("status").equals("running", true)
    )

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionTitle("服务状态", "SERVICE CONTROL")
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (running) Green else Color.Red)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (running) "正在运行" else "已停止",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        Text(
                            status.optString("state", "API Gateway"),
                            color = Gray,
                            fontSize = 12.sp
                        )
                    }
                }
                InfoLine("监听地址", listenAddress)
                InfoLine("上游凭据", upstreamCredential)
                InfoLine(
                    "配置状态",
                    if (config.length() > 0) "已加载" else "未加载"
                )
                InfoLine(
                    "实际运行状态",
                    status.optString(
                        "runtime_status",
                        if (running) "RUNNING" else "STOPPED"
                    )
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onStart,
                        modifier = Modifier.weight(1f),
                        enabled = !running
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Text("启动")
                    }
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                        enabled = running
                    ) {
                        Icon(Icons.Default.Stop, null)
                        Text("停止")
                    }
                }
            }
        }

        item {
            SectionTitle("连接配置", "CONNECTION SETTINGS")
            SurfaceCard {
                ConfigField("服务地址", baseUrl, onBaseUrl)
                ConfigField("监听地址", listenAddress) { listenAddress = it }
                ConfigField("端口", port) { port = it }
                ConfigField(
                    "客户端 API Key",
                    clientKey,
                    {
                        clientKey = it
                        onApiKey(it)
                    }
                )
                ConfigField("oai-did", did) { did = it }
                ConfigField("access_token", accessToken) { accessToken = it }
                ConfigField("connector id", connectorId) {
                    connectorId = it
                }
                ConfigField("上游凭据", upstreamCredential) {
                    upstreamCredential = it
                }

                HorizontalDivider(
                    Modifier.padding(vertical = 10.dp),
                    color = Background
                )

                SettingSwitch(
                    "临时对话",
                    "不保留本地对话历史",
                    temporaryChat,
                    onTemporaryChat
                )
                SettingSwitch(
                    "允许局域网访问",
                    "允许同一网络内的设备连接",
                    lanEnabled,
                    onLan
                )

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val json = JSONObject()
                            .put("base_url", baseUrl)
                            .put("listen_address", listenAddress)
                            .put("port", port.toIntOrNull() ?: 8080)
                            .put("client_api_key", clientKey)
                            .put("oai_did", did)
                            .put("access_token", accessToken)
                            .put("connector_id", connectorId)
                            .put("upstream_credential", upstreamCredential)
                            .put("allow_lan", lanEnabled)
                            .put("temporary_chat", temporaryChat)
                        onSave()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Save, null)
                    Spacer(Modifier.width(6.dp))
                    Text("保存连接配置")
                }
                OutlinedButton(
                    onClick = onImport,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FileOpen, null)
                    Spacer(Modifier.width(6.dp))
                    Text("从剪贴板导入 kit.json")
                }
            }
        }

        item {
            SectionTitle("OpenAI 接入地址", "ENDPOINTS")
            SurfaceCard {
                Text("手机本机地址", fontWeight = FontWeight.SemiBold)
                Text(
                    "$baseUrl/v1",
                    color = Green,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(12.dp))
                Text("局域网地址", fontWeight = FontWeight.SemiBold)
                Text(
                    "http://$localIp:${port}/v1",
                    color = Green,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(12.dp))
                Text("完整 Chat Completions 地址", fontWeight = FontWeight.SemiBold)
                Text(
                    "${baseUrl.trimEnd('/')}/v1/chat/completions",
                    color = Gray,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onCopyCommand,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ContentCopy, null)
                    Spacer(Modifier.width(6.dp))
                    Text("复制测试命令")
                }
            }
        }
    }
}

@Composable
private fun ConversationPage(
    conversations: JSONArray,
    selectedConversation: String,
    chatMessages: JSONArray,
    chatInput: String,
    onInput: (String) -> Unit,
    onRefresh: () -> Unit,
    onNew: () -> Unit,
    onSelect: (String) -> Unit,
    onSend: () -> Unit,
    upstreamConversations: JSONArray,
    upstreamId: String,
    upstreamDetail: JSONObject,
    onUpstreamRefresh: () -> Unit,
    onUpstreamSelect: (String) -> Unit
) {
    var showLocal by remember { mutableStateOf(true) }
    var showUpstream by remember { mutableStateOf(true) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionTitle("本地会话中心", "LOCAL CONVERSATIONS")
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "本地会话",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, null)
                    }
                    IconButton(onClick = onNew) {
                        Icon(Icons.Default.Add, null, tint = Green)
                    }
                    IconButton(onClick = {
                        showLocal = !showLocal
                    }) {
                        Icon(
                            if (showLocal) Icons.Default.ExpandLess
                            else Icons.Default.ExpandMore,
                            null
                        )
                    }
                }

                if (showLocal) {
                    Text(
                        "${conversations.length()} 个会话",
                        color = Gray,
                        fontSize = 12.sp
                    )
                    for (i in 0 until conversations.length()) {
                        val item = conversations.optJSONObject(i) ?: continue
                        val id = item.optString(
                            "id",
                            item.optString("conversation_id")
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(id) }
                                .background(
                                    if (id == selectedConversation)
                                        Color(0xFFE9F7F2)
                                    else Color.Transparent,
                                    RoundedCornerShape(10.dp)
                                )
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.ChatBubbleOutline,
                                null,
                                tint = Green
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.optString("title", "新对话"),
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    id,
                                    color = Gray,
                                    fontSize = 10.sp,
                                    maxLines = 1
                                )
                            }
                            if (id == selectedConversation) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    null,
                                    tint = Green
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionTitle("本地消息", "CHAT")
            SurfaceCard {
                Text(
                    if (selectedConversation.isBlank())
                        "请选择一个会话或新建对话"
                    else "会话 ID：$selectedConversation",
                    color = Gray,
                    fontSize = 11.sp
                )
                for (i in 0 until chatMessages.length()) {
                    val item = chatMessages.optJSONObject(i) ?: continue
                    MessageBubble(
                        item.optString("role", "assistant"),
                        item.optString("content", item.toString())
                    )
                }
                OutlinedTextField(
                    value = chatInput,
                    onValueChange = onInput,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("输入消息…") },
                    minLines = 2,
                    maxLines = 5
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onSend,
                    enabled = selectedConversation.isNotBlank() &&
                        chatInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Send, null)
                    Spacer(Modifier.width(8.dp))
                    Text("发送消息")
                }
            }
        }

        item {
            SectionTitle("ChatGPT 真实对话", "UPSTREAM CONVERSATIONS")
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "账号对话列表",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onUpstreamRefresh) {
                        Icon(Icons.Default.Refresh, null)
                    }
                    IconButton(onClick = {
                        showUpstream = !showUpstream
                    }) {
                        Icon(
                            if (showUpstream) Icons.Default.ExpandLess
                            else Icons.Default.ExpandMore,
                            null
                        )
                    }
                }

                if (showUpstream) {
                    Text(
                        "${upstreamConversations.length()} 个账号对话",
                        color = Gray,
                        fontSize = 12.sp
                    )
                    for (i in 0 until upstreamConversations.length()) {
                        val item = upstreamConversations.optJSONObject(i)
                            ?: continue
                        val id = item.optString(
                            "id",
                            item.optString("conversation_id")
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onUpstreamSelect(id) }
                                .padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Cloud,
                                null,
                                tint = Green
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.optString(
                                        "title",
                                        "ChatGPT 对话"
                                    ),
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    id,
                                    fontSize = 10.sp,
                                    color = Gray
                                )
                            }
                            Icon(
                                Icons.Default.ChevronRight,
                                null,
                                tint = Gray
                            )
                        }
                        HorizontalDivider(color = Background)
                    }
                }
            }
        }

        if (upstreamId.isNotBlank()) {
            item {
                SectionTitle("真实对话详情", "CONVERSATION DETAIL")
                SurfaceCard {
                    Text("ID：$upstreamId", fontSize = 11.sp, color = Gray)
                    val messages = upstreamDetail.optJSONArray("messages")
                        ?: upstreamDetail.optJSONObject("mapping")
                            ?.let { mapping ->
                                JSONArray().also { arr ->
                                    val keys = mapping.keys()
                                    while (keys.hasNext()) {
                                        val node = mapping.optJSONObject(
                                            keys.next()
                                        ) ?: continue
                                        val msg = node.optJSONObject("message")
                                            ?: continue
                                        arr.put(msg)
                                    }
                                }
                            } ?: JSONArray()

                    for (i in 0 until messages.length()) {
                        val item = messages.optJSONObject(i) ?: continue
                        val author = item.optJSONObject("author")
                        val content = item.optJSONObject("content")
                        val parts = content?.optJSONArray("parts")
                        val text = if (parts != null) {
                            (0 until parts.length()).joinToString("\n") {
                                parts.optString(it)
                            }
                        } else {
                            item.optString(
                                "content",
                                content?.toString() ?: item.toString()
                            )
                        }
                        MessageBubble(
                            author?.optString("role", "assistant")
                                ?: item.optString("role", "assistant"),
                            text
                        )
                        InfoLine(
                            "模型",
                            item.optString(
                                "model",
                                item.optString("model_slug", "--")
                            )
                        )
                        InfoLine(
                            "思考时长",
                            item.optString(
                                "thinking_duration",
                                item.optString("duration", "--")
                            )
                        )
                        val metadata = item.optJSONObject("metadata")
                        if (metadata != null &&
                            (metadata.has("tool") ||
                                metadata.has("tool_name") ||
                                metadata.has("tool_calls"))
                        ) {
                            Text(
                                "⚙ 工具调用：${metadata.optString("tool_name", metadata.toString())}",
                                color = Green,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolsPage(
    tools: JSONArray,
    search: String,
    onSearch: (String) -> Unit,
    selectedTool: JSONObject,
    dialog: Boolean,
    onOpenTool: (JSONObject) -> Unit,
    onCloseTool: () -> Unit,
    arguments: String,
    onArguments: (String) -> Unit,
    result: String,
    onExecute: () -> Unit,
    webUrl: String,
    onWebUrl: (String) -> Unit,
    webContent: String,
    onWebOpen: () -> Unit,
    history: List<JSONObject>,
    onRefresh: () -> Unit
) {
    val filtered = (0 until tools.length()).mapNotNull {
        tools.optJSONObject(it)
    }.filter {
        it.toString().contains(search, ignoreCase = true)
    }

    val groups = filtered.groupBy {
        it.optString(
            "namespace",
            it.optString("server", "default")
        )
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionTitle("工具目录", "MCP TOOL DIRECTORY")
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${filtered.size} 个工具",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, null)
                    }
                }
                OutlinedTextField(
                    value = search,
                    onValueChange = onSearch,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("搜索工具名称或命名空间") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, null)
                    }
                )
            }
        }

        groups.forEach { (namespace, entries) ->
            item(key = "group_$namespace") {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        namespace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${entries.size}",
                        color = Green,
                        fontSize = 12.sp
                    )
                }
            }

            items(entries) { tool ->
                SurfaceCard(
                    modifier = Modifier.clickable {
                        onOpenTool(tool)
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .background(
                                    Color(0xFFE9F7F2),
                                    RoundedCornerShape(10.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Extension,
                                null,
                                tint = Green
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                tool.optString(
                                    "name",
                                    tool.optString("tool_name")
                                ),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                tool.optString(
                                    "description",
                                    tool.optString("title", "")
                                ),
                                color = Gray,
                                fontSize = 11.sp,
                                maxLines = 2
                            )
                        }
                        Icon(
                            Icons.Default.ChevronRight,
                            null,
                            tint = Gray
                        )
                    }
                }
            }
        }

        item {
            SectionTitle("网页读取器", "WEB READER")
            SurfaceCard {
                OutlinedTextField(
                    value = webUrl,
                    onValueChange = onWebUrl,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("https://example.com") },
                    leadingIcon = {
                        Icon(Icons.Default.Language, null)
                    }
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onWebOpen,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = webUrl.startsWith("http")
                ) {
                    Icon(Icons.Default.Download, null)
                    Spacer(Modifier.width(6.dp))
                    Text("读取网页")
                }
                if (webContent.isNotBlank()) {
                    HorizontalDivider(
                        Modifier.padding(vertical = 10.dp),
                        color = Background
                    )
                    Text(
                        "网页正文",
                        fontWeight = FontWeight.Bold
                    )
                    webContent.lines().forEachIndexed { index, line ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                "${index + 1}",
                                color = Gray,
                                fontSize = 10.sp
                            )
                            Text(line, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        item {
            SectionTitle("调用历史", "CALL HISTORY")
            SurfaceCard {
                if (history.isEmpty()) {
                    Text("暂无调用记录", color = Gray, fontSize = 12.sp)
                } else {
                    history.reversed().forEach { item ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.optString("name"),
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "${item.optLong("duration")} ms",
                                    color = Gray,
                                    fontSize = 11.sp
                                )
                            }
                            Text(
                                if (item.optBoolean("success")) "成功" else "失败",
                                color = if (item.optBoolean("success"))
                                    Green else Color.Red,
                                fontSize = 12.sp
                            )
                        }
                        HorizontalDivider(color = Background)
                    }
                }
            }
        }
    }
}

@Composable
private fun HookPage(
    status: JSONObject,
    events: JSONArray,
    report: JSONObject,
    reportExpanded: Boolean,
    onToggleReport: () -> Unit,
    scanning: Boolean,
    progress: Float,
    scanResult: String,
    onScan: () -> Unit,
    onCancelScan: () -> Unit,
    onSaveScan: () -> Unit,
    ruleClass: String,
    onRuleClass: (String) -> Unit,
    ruleMethod: String,
    onRuleMethod: (String) -> Unit,
    ruleMode: String,
    onRuleMode: (String) -> Unit,
    ruleParams: String,
    onRuleParams: (String) -> Unit,
    onCreateRule: () -> Unit,
    rules: JSONArray,
    onRuleAction: (String, String) -> Unit,
    onRefresh: () -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionTitle("Hook 运行状态", "RUNTIME STATUS")
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(
                        if (status.optBoolean("running", true))
                            Green else Color.Red
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        status.optString("status", "Hook Runtime"),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, null)
                    }
                }
                InfoLine(
                    "LSPosed 版本",
                    status.optString("lsposed_version", "--")
                )
                InfoLine(
                    "API 版本",
                    status.optString("api_version", "--")
                )
                InfoLine(
                    "已安装方法数",
                    status.optString(
                        "installed_methods",
                        status.optString("method_count", "0")
                    )
                )
                InfoLine(
                    "已加载类数",
                    status.optString("class_count", "--")
                )
            }
        }

        item {
            SectionTitle("类扫描", "CLASS DISCOVERY")
            SurfaceCard {
                Text(
                    "扫描运行时类与可用方法",
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(12.dp))
                if (scanning) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = Green
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${(progress * 100).toInt()}%",
                        color = Gray,
                        fontSize = 11.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onScan,
                        modifier = Modifier.weight(1f),
                        enabled = !scanning
                    ) {
                        Icon(Icons.Default.Search, null)
                        Text("开始扫描")
                    }
                    OutlinedButton(
                        onClick = onCancelScan,
                        modifier = Modifier.weight(1f),
                        enabled = scanning
                    ) {
                        Icon(Icons.Default.Cancel, null)
                        Text("取消")
                    }
                }
                OutlinedButton(
                    onClick = onSaveScan,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = scanResult.isNotBlank()
                ) {
                    Icon(Icons.Default.SaveAlt, null)
                    Text("导出扫描结果")
                }
                if (scanResult.isNotBlank()) {
                    Text(
                        scanResult,
                        fontSize = 10.sp,
                        color = Gray
                    )
                }
            }
        }

        item {
            SectionTitle("运行时观察报告", "RUNTIME OBSERVATION")
            SurfaceCard {
                Text(
                    report.optString(
                        "summary",
                        report.optString(
                            "human_summary",
                            "运行时报告"
                        )
                    ),
                    fontSize = 13.sp
                )
                TextButton(onClick = onToggleReport) {
                    Icon(
                        if (reportExpanded) Icons.Default.ExpandLess
                        else Icons.Default.ExpandMore,
                        null
                    )
                    Text(
                        if (reportExpanded) "收起原始 JSON"
                        else "查看原始 JSON"
                    )
                }
                if (reportExpanded) {
                    Text(
                        report.toString(2),
                        fontSize = 10.sp,
                        color = Gray
                    )
                }
            }
        }

        item {
            SectionTitle("动态规则创建", "DYNAMIC RULES")
            SurfaceCard {
                ConfigField("类名", ruleClass, onRuleClass)
                ConfigField("方法名", ruleMethod, onRuleMethod)
                Text(
                    "匹配模式",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(ruleMode)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        listOf(
                            "BEFORE",
                            "AFTER",
                            "REPLACE",
                            "HOOK",
                            "ALL"
                        ).forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode) },
                                onClick = {
                                    onRuleMode(mode)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "参数类型 JSON 数组",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = ruleParams,
                    onValueChange = onRuleParams,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onCreateRule,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = ruleClass.isNotBlank() &&
                        ruleMethod.isNotBlank()
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("提交动态规则")
                }
            }
        }

        item {
            SectionTitle("规则列表", "ACTIVE RULES")
            SurfaceCard {
                if (rules.length() == 0) {
                    Text("暂无规则", color = Gray, fontSize = 12.sp)
                }
                for (i in 0 until rules.length()) {
                    val rule = rules.optJSONObject(i) ?: continue
                    val id = rule.optString(
                        "id",
                        rule.optString("rule_id", i.toString())
                    )
                    val enabled = rule.optBoolean("enabled", true)
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    rule.optString("class_name"),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    rule.optString("method_name"),
                                    color = Gray,
                                    fontSize = 11.sp
                                )
                                Text(
                                    rule.optString("mode"),
                                    color = Green,
                                    fontSize = 10.sp
                                )
                            }
                            Switch(
                                checked = enabled,
                                onCheckedChange = {
                                    onRuleAction(
                                        id,
                                        if (it) "enable" else "disable"
                                    )
                                }
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TextButton(
                                onClick = {
                                    onRuleAction(id, "delete")
                                }
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    null,
                                    tint = Color.Red
                                )
                                Text("删除", color = Color.Red)
                            }
                        }
                        HorizontalDivider(color = Background)
                    }
                }
            }
        }

        item {
            SectionTitle("最近事件流", "RECENT EVENTS")
            SurfaceCard {
                if (events.length() == 0) {
                    Text("暂无 Hook 事件", color = Gray, fontSize = 12.sp)
                }
                for (i in 0 until events.length()) {
                    val event = events.optJSONObject(i) ?: continue
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        StatusDot(Green)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                event.optString(
                                    "name",
                                    event.optString(
                                        "method",
                                        event.optString("type", "Hook Event")
                                    )
                                ),
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp
                            )
                            Text(
                                event.optString(
                                    "timestamp",
                                    event.optString("time", "")
                                ),
                                color = Gray,
                                fontSize = 10.sp
                            )
                            Text(
                                event.optString(
                                    "message",
                                    event.optString("class_name", "")
                                ),
                                color = Gray,
                                fontSize = 11.sp
                            )
                        }
                    }
                    HorizontalDivider(
                        Modifier.padding(vertical = 8.dp),
                        color = Background
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    subtitle: String
) {
    Column(Modifier.padding(top = 4.dp, bottom = 2.dp)) {
        Text(
            title,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = Dark
        )
        Text(
            subtitle,
            fontSize = 9.sp,
            letterSpacing = 1.4.sp,
            color = Gray
        )
    }
}

@Composable
private fun SurfaceCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = CardColor,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 0.dp
    ) {
        Column(
            Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        Modifier
            .size(9.dp)
            .background(color, CircleShape)
    )
}

@Composable
private fun Metric(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(end = 4.dp)) {
        Text(label, color = Gray, fontSize = 10.sp)
        Text(
            value,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SmallMetricCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color.White,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                title,
                color = Gray,
                fontSize = 10.sp
            )
            Spacer(Modifier.height(7.dp))
            Text(
                value,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun InfoLine(
    label: String,
    value: String
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = Gray,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2
        )
    }
}

@Composable
private fun ConfigField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(
            label,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(5.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 13.sp
            )
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, color = Gray, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun MessageBubble(
    role: String,
    content: String
) {
    val user = role.equals("user", true)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = if (user)
            Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (user) Color(0xFFE9F7F2) else Background,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 310.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    if (user) "你" else role,
                    fontSize = 10.sp,
                    color = Green,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    content,
                    fontSize = 13.sp
                )
            }
        }
    }
}

private fun copyText(
    context: Context,
    text: String
) {
    val clipboard = context.getSystemService(
        Context.CLIPBOARD_SERVICE
    ) as ClipboardManager
    clipboard.setPrimaryClip(
        ClipData.newPlainText("DSH-GPT", text)
    )
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}
