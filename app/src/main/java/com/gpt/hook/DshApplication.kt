package com.gpt.hook

import android.app.Application
import com.gpt.hook.api.AndroidSecretBox
import com.gpt.hook.api.ApiService
import com.gpt.hook.api.ChatGptCredentialStore
import com.gpt.hook.api.ConversationRepository
import com.gpt.hook.api.RelayConfig
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/** Receives the framework service used by the module's own control app. */
class DshApplication : Application(), XposedServiceHelper.OnServiceListener {
    lateinit var conversationRepository: ConversationRepository
        private set

    override fun onCreate() {
        super.onCreate()
        conversationRepository = ConversationRepository(this)
        // OAuth 凭证加密存储：Keystore 包裹，缺失时退化为内存（绝不明文落盘）
        ChatGptCredentialStore.attach(AndroidSecretBox(this))
        XposedServiceHelper.registerListener(this)
        if (RelayConfig.load(this).enabled) {
            runCatching { ApiService.start(this) }
        }
    }

    override fun onServiceBind(service: XposedService) {
        currentService = service
    }

    override fun onServiceDied(service: XposedService) {
        if (currentService === service) currentService = null
    }

    companion object {
        @Volatile
        var currentService: XposedService? = null
            private set
    }
}
