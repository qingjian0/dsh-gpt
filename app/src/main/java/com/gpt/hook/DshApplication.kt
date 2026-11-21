package com.gpt.hook

import android.app.Application
import com.gpt.hook.api.ApiService
import com.gpt.hook.api.RelayConfig
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/** Receives the framework service used by the module's own control app. */
class DshApplication : Application(), XposedServiceHelper.OnServiceListener {
    override fun onCreate() {
        super.onCreate()
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
