package com.gpt.hook.api

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Standalone SIWC account vault. It does not silently change the active gateway transport. */
@Composable
internal fun ChatGptPlanAccounts(context: Context) {
    val scope = rememberCoroutineScope()
    val store = remember(context) { ChatGptPlanProfileStore(context.applicationContext) }
    val coordinator = remember { ChatGptPlanLoginCoordinator() }
    val http = remember { ChatGptPlanHttp() }
    val sessionManager = remember { ChatGptPlanSessionManager(http) }
    var profiles by remember { mutableStateOf(emptyList<ChatGptPlanProfile>()) }
    var activeProfileId by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(ChatGptPlanLoginRegistry.pending) }
    var busy by remember { mutableStateOf(ChatGptPlanLoginRegistry.inFlight) }
    var status by remember { mutableStateOf(ChatGptPlanLoginRegistry.lastStatus ?: "尚未读取账号状态") }

    suspend fun reload() {
        try {
            val snapshot = withContext(Dispatchers.IO) { store.snapshot() }
            profiles = snapshot.profiles
            activeProfileId = snapshot.activeProfileId
            status = if (profiles.isEmpty()) "尚未绑定 ChatGPT 账号" else "已安全保存 ${profiles.size} 个账号"
        } catch (error: Throwable) {
            status = "账号读取失败：${error.message ?: error.javaClass.simpleName}"
        }
    }

    suspend fun syncGatewayProfile() {
        val activeId = withContext(Dispatchers.IO) {
            val snapshot = store.snapshot()
            snapshot.profiles.firstOrNull {
                it.id == snapshot.activeProfileId && it.canUseChatGptPlan
            }?.id.orEmpty()
        }
        val current = withContext(Dispatchers.IO) { RelayConfig.load(context) }
        if (current.proxyMode == "siwc") {
            withContext(Dispatchers.IO) {
                RelayConfig.save(context, current.copy(siwcProfileId = activeId))
            }
            if (current.enabled) ApiService.start(context)
        }
    }

    fun startLogin(selectedProfileId: String?, forceNewRegistration: Boolean = false) {
        if (ChatGptPlanLoginRegistry.inFlight) {
            pending = ChatGptPlanLoginRegistry.pending
            busy = true
            status = ChatGptPlanLoginRegistry.lastStatus ?: "正在等待授权…"
            return
        }
        try {
            val login = coordinator.begin(context, selectedProfileId, forceNewRegistration)
            pending = login
            busy = true
            status = "已打开系统浏览器，等待 ChatGPT 完成授权……"
            // 关键：MIUI 切到外部浏览器后约 3 秒把本应用降到 adj 700（idle）并冻结进程，
            // 冻结后回环 socket 不再 accept，浏览器回调必然失败。前台服务让进程保持可见级 adj。
            ChatGptPlanLoginWaitService.start(context.applicationContext)
            // 关键：等待协程跑在进程级作用域，否则打开浏览器导致 composable 销毁时
            // 协程被取消，回环 socket 随之关闭，浏览器回调只会看到 ERR_CONNECTION_REFUSED。
            ChatGptPlanLoginRegistry.launchWait(context, login) { outcome, message ->
                pending = null
                busy = false
                status = message
                if (outcome) {
                    ChatGptPlanLoginRegistry.scope.launch {
                        reload()
                        syncGatewayProfile()
                    }
                }
            }
        } catch (error: Throwable) {
            status = "无法启动授权：${error.message ?: error.javaClass.simpleName}"
            busy = false
        }
    }

    val activeProfile = profiles.firstOrNull { it.id == activeProfileId }

    LaunchedEffect(store) {
        reload()
        // 从浏览器返回后重新同步（等待可能仍在进程级协程里进行）
        pending = ChatGptPlanLoginRegistry.pending
        busy = ChatGptPlanLoginRegistry.inFlight
        ChatGptPlanLoginRegistry.lastStatus?.let { status = it }
    }
    DisposableEffect(pending) {
        onDispose { pending?.close() }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ChatGPT 官方账号授权", fontWeight = FontWeight.Bold)
            Text(
                "系统浏览器 OAuth · PKCE · Android Keystore 加密。独立于 Android DPoP / Web Cookie；选择账号后，若网关处于「ChatGPT 订阅授权」模式，会同步切换网关账号。",
                color = Color.Gray,
                fontSize = 12.sp,
            )
            Text(status, fontSize = 12.sp)
            profiles.forEach { profile ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(profile.name?.takeIf { it.isNotBlank() } ?: profile.email ?: "ChatGPT 账号")
                        Text(
                            buildString {
                                append(if (profile.canUseChatGptPlan) "具备 API 授权范围" else "缺少 API 使用授权范围")
                                if (profile.email != null && profile.name != null) append(" · ${profile.email}")
                            },
                            color = Color.Gray,
                            fontSize = 11.sp,
                        )
                        if (activeProfileId == profile.id) {
                            Text("当前选中", color = Color(0xFF10A37F), fontSize = 11.sp)
                        }
                    }
                    Column {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { startLogin(profile.id) },
                        ) { Text("重新授权") }
                        if (activeProfileId != profile.id) {
                            OutlinedButton(
                                enabled = !busy,
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        try {
                                            withContext(Dispatchers.IO) { store.setActive(profile.id) }
                                            reload()
                                            syncGatewayProfile()
                                        } finally { busy = false }
                                    }
                                },
                            ) { Text("选中") }
                        }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    try {
                                        val revoked = withContext(Dispatchers.IO) {
                                            sessionManager.signOut(store, profile.id)
                                        }
                                        reload()
                                        syncGatewayProfile()
                                        status = if (revoked) {
                                            "已退出账号，OpenAI 已确认撤销会话"
                                        } else {
                                            "本地凭证已清除；远程撤销未确认，可在 ChatGPT 设置中断开此应用"
                                        }
                                    } finally { busy = false }
                                }
                            },
                        ) { Text("退出账号") }
                    }
                }
            }
            OutlinedButton(
                enabled = !busy && activeProfile?.canUseChatGptPlan == true,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val selected = activeProfile ?: return@OutlinedButton
                    scope.launch {
                        busy = true
                        try {
                            val usable = withContext(Dispatchers.IO) {
                                sessionManager.getUsableProfile(store, selected.id)
                            }
                            val models = withContext(Dispatchers.IO) {
                                http.listModels(requireNotNull(usable.accessToken))
                            }
                            status = "API 已连通 · 当前账号可用模型 ${models.size} 个"
                        } catch (error: Throwable) {
                            status = "模型目录请求失败：${error.message ?: error.javaClass.simpleName}"
                        } finally {
                            busy = false
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
            ) { Text("测试 ChatGPT API 并刷新模型") }
            OutlinedButton(
                enabled = !busy && activeProfile?.canUseChatGptPlan == true,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val selected = activeProfile ?: return@OutlinedButton
                    scope.launch {
                        busy = true
                        try {
                            val usable = withContext(Dispatchers.IO) {
                                sessionManager.getUsableProfile(store, selected.id)
                            }
                            val model = withContext(Dispatchers.IO) {
                                http.listModels(requireNotNull(usable.accessToken)).firstOrNull()
                            } ?: throw IllegalStateException("该账号没有可用的可见模型")
                            val output = withContext(Dispatchers.IO) {
                                http.streamResponse(
                                    requireNotNull(usable.accessToken),
                                    model.slug,
                                    "请只回复：DSH GPT SIWC inference OK",
                                )
                            }
                            status = "真实推理成功 · ${model.displayName} · ${output.take(120)}"
                        } catch (error: Throwable) {
                            status = "真实推理失败：${error.message ?: error.javaClass.simpleName}"
                        } finally {
                            busy = false
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
            ) { Text("执行一次真实 Responses API 测试") }
            Button(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = { startLogin(null, forceNewRegistration = true) },
                shape = RoundedCornerShape(12.dp),
            ) { Text(if (busy) "正在等待授权…" else "添加另一个 ChatGPT 账号") }
            Spacer(Modifier.height(2.dp))
        }
    }

}

/**
 * 进程级登录注册表。
 *
 * 为什么需要它：SIWC 登录要先绑一个 127.0.0.1 的回环端口，再把用户送去系统浏览器。
 * 如果等待回调的协程绑在 Composable 上（rememberCoroutineScope），应用退到后台、
 * Composable 被销毁时协程会被取消，finally 里 close() 掉 socket，浏览器回调就变成
 * ERR_CONNECTION_REFUSED。把等待放到进程级作用域后，Activity 重建也不影响监听。
 */
internal object ChatGptPlanLoginRegistry {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    var pending: ChatGptPlanLoginCoordinator.PendingLogin? = null
        private set

    @Volatile
    var inFlight: Boolean = false
        private set

    @Volatile
    var lastStatus: String? = null

    /** 授权完成后把主界面拉回前台，避免用户以为“卡在浏览器”。 */
    private fun bringToFront(context: android.content.Context) {
        runCatching {
            val intent = android.content.Intent(context, com.gpt.hook.MainActivity::class.java).apply {
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            context.startActivity(intent)
        }
    }

    fun launchWait(
        context: android.content.Context,
        login: ChatGptPlanLoginCoordinator.PendingLogin,
        onDone: (success: Boolean, message: String) -> Unit,
    ) {
        pending = login
        inFlight = true
        lastStatus = "已打开系统浏览器，等待 ChatGPT 完成授权……"
        scope.launch {
            var success = false
            val message = try {
                withContext(Dispatchers.IO) { login.complete() }
                success = true
                "ChatGPT 授权成功，凭证已加密保存"
            } catch (error: Throwable) {
                "授权未完成：${error.message ?: error.javaClass.simpleName}"
            }
            pending = null
            inFlight = false
            lastStatus = message
            ChatGptPlanLoginWaitService.stop(context.applicationContext)
            // 浏览器不会自己切回 App：http 回调页没有能力把后台应用拉到前台，
            // 必须由 App 主动启动 MainActivity（网关前台服务在跑时允许后台启动）。
            if (success) bringToFront(context)
            onDone(success, message)
        }
    }
}
