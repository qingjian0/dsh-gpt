package com.gpt.hook

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import com.gpt.hook.api.ApiService
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
        XposedServiceHelper.registerListener(this)
        if (RelayConfig.load(this).enabled) {
            runCatching { ApiService.start(this) }
        }
    }

    override fun onServiceBind(service: XposedService) {
        currentServiceState.value = service
    }

    override fun onServiceDied(service: XposedService) {
        if (currentServiceState.value === service) currentServiceState.value = null
    }

    companion object {
        val currentServiceState = mutableStateOf<XposedService?>(null)
    }
}
