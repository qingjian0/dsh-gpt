package com.gpt.hook

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import com.gpt.hook.diagnostics.EventBuffer
import com.gpt.hook.diagnostics.HookConfigParser
import com.gpt.hook.diagnostics.HookRuntime
import com.gpt.hook.diagnostics.ModuleLogger
import com.gpt.hook.diagnostics.RemoteConfigLoader
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** libxposed entry point for the DSH declarative diagnostics runtime. */
class MainModule : XposedModule() {
    private val configExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dsh-config-apply").apply { isDaemon = true }
    }
    private val reportExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dsh-report-publish").apply { isDaemon = true }
    }
    private val providerConfigExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dsh-provider-config").apply { isDaemon = true }
    }
    private val discoveryPoller = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dsh-discovery-poll").apply { isDaemon = true }
    }
    private val reportQueued = AtomicBoolean(false)
    private val providerPollStarted = AtomicBoolean(false)
    private val discoveryPollStarted = AtomicBoolean(false)
    private val eventBuffer = EventBuffer(onChanged = ::scheduleReportPublish)
    private var configListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var targetContextForIpc: Context? = null
    @Volatile private var activeRuntime: HookRuntime? = null
    @Volatile private var lastProviderConfigVersion: Long = -1L
    @Volatile private var lastDiscoveryRequestId: String? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        ModuleLogger.framework = this
        ModuleLogger.i("module loaded; process=${param.processName}; api=${getApiVersion()}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        if (param.packageName != TARGET_PACKAGE) return
        ModuleLogger.i("target package ready; package=${param.packageName}; classLoader=${param.classLoader.javaClass.name}")
        installContextBootstrap()

        val hookRuntime = HookRuntime(this, param.classLoader, eventBuffer)
        activeRuntime = hookRuntime
        val prefs = getRemotePreferences("dsh")
        fun applyCurrentConfig() {
            val source = prefs.getString("hook_config", null)
                ?: RemoteConfigLoader.read(this)
                ?: return
            configExecutor.execute {
                try {
                    val config = HookConfigParser.parse(source)
                    val (applied, failed) = hookRuntime.apply(config)
                    ModuleLogger.i("configuration applied; rules=${config.rules.size}; hooks=$applied; failed=$failed")
                } catch (t: Throwable) {
                    ModuleLogger.w("configuration rejected; reason=${t.javaClass.simpleName}")
                }
            }
        }

        applyCurrentConfig()
        configListener?.let { prefs.unregisterOnSharedPreferenceChangeListener(it) }
        configListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "hook_config") applyCurrentConfig()
        }.also { prefs.registerOnSharedPreferenceChangeListener(it) }
    }

    /** Capture the target app Context and start polling DSH-authored configuration. */
    private fun installContextBootstrap() {
        try {
            val callback = Instrumentation::class.java.getDeclaredMethod(
                "callApplicationOnCreate", Application::class.java
            )
            this.hook(callback).setId("dsh.context.bootstrap").intercept { chain: XposedInterface.Chain ->
                val application = chain.getArg(0) as? Application
                val result = chain.proceed()
                if (targetContextForIpc == null && application != null) {
                    // Use the target app's own Context so Binder calls carry the target UID.
                    targetContextForIpc = application.baseContext?.applicationContext
                        ?: application.baseContext
                    startProviderConfigPolling()
                }
                result
            }
        } catch (t: Throwable) {
            ModuleLogger.w("context bootstrap hook failed: ${t.javaClass.simpleName}")
        }
    }

    /**
     * DSH writes a validated config snapshot through the module provider. The target
     * process polls with its own UID and applies changes on the config executor.
     */
    private fun startProviderConfigPolling() {
        if (!providerPollStarted.compareAndSet(false, true)) return
        providerConfigExecutor.scheduleWithFixedDelay({
            val ctx = targetContextForIpc ?: return@scheduleWithFixedDelay
            try {
                val uri = Uri.parse("content://${ObservationProvider.AUTHORITY}")
                val state = ctx.contentResolver.call(
                    uri, ObservationProvider.METHOD_TARGET_CONFIG, null, null
                ) ?: return@scheduleWithFixedDelay
                val version = state.getLong(ObservationProvider.KEY_CONFIG_UPDATED, 0L)
                if (version <= 0L || version == lastProviderConfigVersion) return@scheduleWithFixedDelay
                lastProviderConfigVersion = version
                val enabled = state.getBoolean(ObservationProvider.KEY_ENABLED, true)
                val source = state.getString(ObservationProvider.KEY_CONFIG) ?: return@scheduleWithFixedDelay
                configExecutor.execute {
                    val runtime = activeRuntime ?: return@execute
                    if (!enabled) {
                        runtime.clear()
                        ModuleLogger.i("DSH disabled Hook runtime")
                        return@execute
                    }
                    try {
                        val config = HookConfigParser.parse(source)
                        val (applied, failed) = runtime.apply(config)
                        ModuleLogger.i("DSH config applied; revision=$version; rules=${config.rules.size}; hooks=$applied; failed=$failed")
                    } catch (t: Throwable) {
                        ModuleLogger.w("DSH config rejected; revision=$version; reason=${t.javaClass.simpleName}")
                    }
                }
            } catch (t: Throwable) {
                ModuleLogger.w("DSH config poll failed; reason=${t.javaClass.simpleName}")
            }
        }, 0, 3, TimeUnit.SECONDS)

        if (discoveryPollStarted.compareAndSet(false, true)) {
            discoveryPoller.scheduleWithFixedDelay({
                pollDiscoveryRequest()
            }, 0, 1, TimeUnit.SECONDS)
        }
    }

    /** Runs metadata-only discovery in the target classloader and returns the result to DSH. */
    private fun pollDiscoveryRequest() {
        val ctx = targetContextForIpc ?: return
        try {
            val uri = Uri.parse("content://${ObservationProvider.AUTHORITY}")
            val pending = ctx.contentResolver.call(
                uri, ObservationProvider.METHOD_TARGET_DISCOVERY_POLL, null, null
            ) ?: return
            val raw = pending.getString(ObservationProvider.KEY_DISCOVERY_REQUEST) ?: return
            val request = org.json.JSONObject(raw)
            val requestId = request.optString("requestId")
            if (requestId.isBlank() || requestId == lastDiscoveryRequestId) return
            lastDiscoveryRequestId = requestId

            val prefix = request.optString("prefix", "com.openai.")
            val className = if (request.isNull("className")) null
                else request.optString("className").takeIf { it.isNotBlank() }
            val limit = request.optInt("limit", 100).coerceIn(1, 500)
            val result = activeRuntime?.discover(prefix, className, limit)
                ?: org.json.JSONObject().put("ok", false).put("error", "hook_runtime_unavailable")
            val extras = Bundle().apply {
                putString(ObservationProvider.KEY_DISCOVERY_ID, requestId)
                putString(ObservationProvider.KEY_DISCOVERY_RESULT, result.toString())
            }
            val accepted = ctx.contentResolver.call(
                uri, ObservationProvider.METHOD_TARGET_DISCOVERY_RESULT, null, extras
            )?.getBoolean(ObservationProvider.KEY_ACCEPTED) == true
            if (!accepted) ModuleLogger.w("discovery result was not accepted by provider")
        } catch (t: Throwable) {
            ModuleLogger.w("target class discovery failed; reason=${t.javaClass.simpleName}")
        }
    }

    private fun scheduleReportPublish() {
        if (!reportQueued.compareAndSet(false, true)) return
        reportExecutor.schedule({
            reportQueued.set(false)
            publishReport()
        }, REPORT_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }

    private fun publishReport() {
        val json = eventBuffer.toJson()

        // Primary channel: logcat. Always available even when MIUI blocks cross-app IPC.
        // The observation report is metadata-only and bounded, so it is safe to log.
        ModuleLogger.i("observation-report: $json")

        // Secondary channel: ContentProvider to the module app (best effort).
        val ctx = targetContextForIpc ?: return
        try {
            val uri = Uri.parse("content://${ObservationProvider.AUTHORITY}")
            val extras = Bundle().apply { putString(ObservationProvider.KEY_REPORT, json) }
            val result = ctx.contentResolver.call(uri, ObservationProvider.METHOD_PUBLISH, null, extras)
            if (result?.getBoolean(ObservationProvider.KEY_ACCEPTED) != true) {
                ModuleLogger.w("observation report relay not accepted; logcat channel in use")
            }
        } catch (t: Throwable) {
            ModuleLogger.w("observation report relay unavailable (${t.javaClass.simpleName}); logcat channel in use")
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "com.openai.chatgpt"
        const val REPORT_DEBOUNCE_MS = 1500L
    }
}
