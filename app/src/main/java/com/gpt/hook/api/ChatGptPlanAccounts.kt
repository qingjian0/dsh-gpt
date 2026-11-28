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
import kotlinx.coroutines.Dispatchers
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
    var pending by remember { mutableStateOf<ChatGptPlanLoginCoordinator.PendingLogin?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("尚未读取账号状态") }

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

    fun startLogin(selectedProfileId: String?, forceNewRegistration: Boolean = false) {
        try {
            val login = coordinator.begin(context, selectedProfileId, forceNewRegistration)
            pending = login
            busy = true
            status = "已打开系统浏览器，等待 ChatGPT 完成授权……"
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { login.complete() }
                    status = "ChatGPT 授权成功，凭证已加密保存"
                    reload()
                } catch (error: Throwable) {
                    status = "授权未完成：${error.message ?: error.javaClass.simpleName}"
                } finally {
                    pending = null
                    busy = false
                }
            }
        } catch (error: Throwable) {
            status = "无法启动授权：${error.message ?: error.javaClass.simpleName}"
            busy = false
        }
    }

    val activeProfile = profiles.firstOrNull { it.id == activeProfileId }

    LaunchedEffect(store) { reload() }
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
                "系统浏览器 OAuth · PKCE · Android Keystore 加密。独立于 Android DPoP / Web Cookie；当前用于账号授权与凭证保管，不会自动切换网关上游。",
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
