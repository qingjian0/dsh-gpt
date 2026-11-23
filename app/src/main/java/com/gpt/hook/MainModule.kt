package com.gpt.hook

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import com.gpt.hook.diagnostics.EventBuffer
import com.gpt.hook.diagnostics.ClassLoadWatcher
import com.gpt.hook.diagnostics.HookConfigParser
import com.gpt.hook.diagnostics.HookRuntime
import com.gpt.hook.diagnostics.ModuleLogger
import com.gpt.hook.diagnostics.RemoteConfigLoader
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** libxposed entry point for the DSH declarative diagnostics runtime. */
class MainModule : XposedModule() {
    private val configExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dsh-config-apply").apply { isDaemon = true }
    }
    private val providerConfigExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dsh-provider-config").apply { isDaemon = true }
    }
    private val discoveryPoller = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dsh-discovery-poll").apply { isDaemon = true }
    }
    private val discoveryExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dsh-discovery-worker").apply { isDaemon = true }
    }
    private val reportExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dsh-report-publish").apply { isDaemon = true }
    }
    private val reportQueued = AtomicBoolean(false)
    private val providerPollStarted = AtomicBoolean(false)
    private val discoveryPollStarted = AtomicBoolean(false)
    private val discoveryWorkQueued = AtomicBoolean(false)
    private val startupDiscoveryStarted = AtomicBoolean(false)
    private val eventBuffer = EventBuffer(onChanged = ::scheduleReportPublish)
    private val classLoadWatcher = ClassLoadWatcher(this, eventBuffer)
    private var configListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var targetContextForIpc: Context? = null
    @Volatile private var activeRuntime: HookRuntime? = null
    @Volatile private var lastProviderConfigVersion: Long = -1L

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
                ?: DEFAULT_CONFIG
            configExecutor.execute {
                try {
                    val config = HookConfigParser.parse(source)
                    updateClassLoadWatcher(config.observeClassLoads)
                    val report = hookRuntime.applyWithReport(config)
                    logApplyReport(config.rules.size, report.added, report.replaced, report.retained, report.removed, report.failed)
                    scheduleReportPublish()
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

    /** Capture the target app Context at Application startup and start narrow Provider polling. */
    private fun installContextBootstrap() {
        try {
            val callback = Instrumentation::class.java.getDeclaredMethod(
                "callApplicationOnCreate", Application::class.java
            )
            this.hook(callback).setId("dsh.context.bootstrap").intercept { chain: XposedInterface.Chain ->
                val application = chain.getArg(0) as? Application
                val result = chain.proceed()
                if (targetContextForIpc == null && application != null) {
                    targetContextForIpc = application.baseContext?.applicationContext
                        ?: application.baseContext
                    startProviderPolling()
                }
                result
            }
        } catch (t: Throwable) {
            ModuleLogger.w("context bootstrap hook failed: ${t.javaClass.simpleName}")
        }
    }

    private fun startProviderPolling() {
        scheduleReportPublish()
        startStartupDiscovery()
        if (providerPollStarted.compareAndSet(false, true)) {
            providerConfigExecutor.scheduleWithFixedDelay({
                pollProviderConfig()
            }, 0, CONFIG_POLL_SECONDS, TimeUnit.SECONDS)
        }
        if (discoveryPollStarted.compareAndSet(false, true)) {
            discoveryPoller.scheduleWithFixedDelay({
                pollDiscoveryRequest()
            }, 0, DISCOVERY_POLL_SECONDS, TimeUnit.SECONDS)
        }
    }

    /** One bounded metadata-only DEX scan per target-process start; never runs on the UI thread. */
    private fun startStartupDiscovery() {
        if (!startupDiscoveryStarted.compareAndSet(false, true)) return
        discoveryExecutor.execute {
            try {
                val result = activeRuntime?.discover(
                    prefix = "com.openai.",
                    exactClassName = null,
                    limit = 100,
                    cursor = 0
                ) ?: JSONObject().put("ok", false).put("error", "hook_runtime_unavailable")
                if (!result.optBoolean("ok", false)) {
                    ModuleLogger.w("startup DEX discovery incomplete; error=${result.optString("error", "unknown")}")
                    return@execute
                }
                val ctx = targetContextForIpc ?: return@execute
                val extras = Bundle().apply {
                    putString(ObservationProvider.KEY_STARTUP_DISCOVERY, result.toString())
                }
                val accepted = ctx.contentResolver.call(
                    PROVIDER_URI,
                    ObservationProvider.METHOD_TARGET_PUBLISH_STARTUP_DISCOVERY,
                    null,
                    extras
                )?.getBoolean(ObservationProvider.KEY_ACCEPTED) == true
                if (accepted) {
                    ModuleLogger.i(
                        "startup DEX discovery published; classes=${result.optInt("classesInspected")}; " +
                            "records=${result.optInt("recordCount")}; truncated=${result.optBoolean("scanTruncated")}"
                    )
                } else {
                    ModuleLogger.w("startup DEX discovery snapshot was not accepted")
                }
            } catch (t: Throwable) {
                ModuleLogger.w("startup DEX discovery failed; reason=${t.javaClass.simpleName}")
            }
        }
    }

    private fun pollProviderConfig() {
        val ctx = targetContextForIpc ?: return
        try {
            val state = ctx.contentResolver.call(
                PROVIDER_URI, ObservationProvider.METHOD_TARGET_CONFIG, null, null
            ) ?: return
            val version = state.getLong(ObservationProvider.KEY_CONFIG_UPDATED, 0L)
            if (version == lastProviderConfigVersion) return
            val source = state.getString(ObservationProvider.KEY_CONFIG) ?: return
            val enabled = state.getBoolean(ObservationProvider.KEY_ENABLED, true)
            lastProviderConfigVersion = version
            configExecutor.execute {
                val runtime = activeRuntime ?: return@execute
                if (!enabled) {
                    updateClassLoadWatcher(false)
                    val removed = runtime.clear()
                    ModuleLogger.i("GPT API disabled Hook runtime; removed=$removed; revision=$version")
                    scheduleReportPublish()
                    return@execute
                }
                try {
                    val config = HookConfigParser.parse(source)
                    updateClassLoadWatcher(config.observeClassLoads)
                    val report = runtime.applyWithReport(config)
                    logApplyReport(config.rules.size, report.added, report.replaced, report.retained, report.removed, report.failed, version)
                    scheduleReportPublish()
                } catch (t: Throwable) {
                    ModuleLogger.w("DSH config rejected; revision=$version; reason=${t.javaClass.simpleName}")
                }
            }
        } catch (t: Throwable) {
            ModuleLogger.w("DSH config poll failed; reason=${t.javaClass.simpleName}")
        }
    }

    /** Provider leases the request before returning it; a restarted process can reclaim it after expiry. */
    private fun pollDiscoveryRequest() {
        if (!discoveryWorkQueued.compareAndSet(false, true)) return
        val ctx = targetContextForIpc
        if (ctx == null) {
            discoveryWorkQueued.set(false)
            return
        }
        try {
            val pending = ctx.contentResolver.call(
                PROVIDER_URI, ObservationProvider.METHOD_TARGET_DISCOVERY_POLL, null, null
            ) ?: run {
                discoveryWorkQueued.set(false)
                return
            }
            val raw = pending.getString(ObservationProvider.KEY_DISCOVERY_REQUEST)
            val taskRaw = pending.getString(ObservationProvider.KEY_DISCOVERY_TASK)
            if (raw.isNullOrBlank() || taskRaw.isNullOrBlank()) {
                discoveryWorkQueued.set(false)
                return
            }
            val task = JSONObject(taskRaw)
            val request = JSONObject(raw)
            val requestId = request.optString("requestId")
            val revision = pending.getLong(ObservationProvider.KEY_DISCOVERY_REVISION, -1L)
            if (requestId.isBlank() || revision < 0L) {
                discoveryWorkQueued.set(false)
                ModuleLogger.w("Provider returned an invalid discovery lease")
                return
            }

            discoveryExecutor.execute {
                try {
                    val prefix = request.optString("prefix", "com.openai.")
                    val className = request.optString("className").takeIf { it.isNotBlank() && it != "null" }
                    val limit = request.optInt("limit", 100).coerceIn(1, 500)
                    val cursor = request.optInt("cursor", 0).coerceAtLeast(0)
                    val result = activeRuntime?.discover(prefix, className, limit, cursor)
                        ?: JSONObject().put("ok", false).put("error", "hook_runtime_unavailable")
                    val extras = Bundle().apply {
                        putString(ObservationProvider.KEY_DISCOVERY_ID, requestId)
                        putLong(ObservationProvider.KEY_DISCOVERY_REVISION, revision)
                        putString(ObservationProvider.KEY_DISCOVERY_RESULT, result.toString())
                    }
                    val accepted = ctx.contentResolver.call(
                        PROVIDER_URI, ObservationProvider.METHOD_TARGET_DISCOVERY_RESULT, null, extras
                    )?.getBoolean(ObservationProvider.KEY_ACCEPTED) == true
                    if (!accepted) ModuleLogger.w("Discovery result was not accepted; request=$requestId")
                    else ModuleLogger.i("Discovery attempt completed; request=$requestId; attempt=${task.optInt("attempt")}; ok=${result.optBoolean("ok")}")
                } catch (t: Throwable) {
                    ModuleLogger.w("Discovery worker failed; request=$requestId; reason=${t.javaClass.simpleName}")
                    runCatching {
                        val failure = JSONObject().put("ok", false).put("error", "discovery_failed")
                        val extras = Bundle().apply {
                            putString(ObservationProvider.KEY_DISCOVERY_ID, requestId)
                            putLong(ObservationProvider.KEY_DISCOVERY_REVISION, revision)
                            putString(ObservationProvider.KEY_DISCOVERY_RESULT, failure.toString())
                        }
                        ctx.contentResolver.call(
                            PROVIDER_URI, ObservationProvider.METHOD_TARGET_DISCOVERY_RESULT, null, extras
                        )
                    }
                } finally {
                    discoveryWorkQueued.set(false)
                }
            }
        } catch (t: Throwable) {
            discoveryWorkQueued.set(false)
            ModuleLogger.w("Discovery request poll failed; reason=${t.javaClass.simpleName}")
        }
    }

    private fun updateClassLoadWatcher(enabled: Boolean) {
        try {
            if (enabled) {
                if (classLoadWatcher.enable()) ModuleLogger.i("ClassLoader metadata watcher enabled")
            } else {
                if (classLoadWatcher.disable()) ModuleLogger.i("ClassLoader metadata watcher disabled")
            }
        } catch (t: Throwable) {
            ModuleLogger.w("ClassLoader metadata watcher update failed; reason=${t.javaClass.simpleName}")
        }
    }

    private fun logApplyReport(
        rules: Int,
        added: Int,
        replaced: Int,
        retained: Int,
        removed: Int,
        failed: Int,
        revision: Long? = null
    ) {
        ModuleLogger.i(
            "Hook config applied; rules=$rules; added=$added; replaced=$replaced; " +
                "retained=$retained; removed=$removed; failed=$failed" +
                (revision?.let { "; revision=$it" } ?: "")
        )
    }

    private fun scheduleReportPublish() {
        if (!reportQueued.compareAndSet(false, true)) return
        reportExecutor.schedule({
            reportQueued.set(false)
            publishReport()
        }, REPORT_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }

    private fun publishReport() {
        val report = JSONObject(eventBuffer.toJson())
            .put("runtime", activeRuntime?.runtimeSnapshot() ?: JSONObject()
                .put("state", "unavailable")
                .put("activeHookCount", 0)
                .put("hooks", org.json.JSONArray()))
            .put("classLoadWatcherEnabled", classLoadWatcher.isEnabled())
            .put("generatedAtMs", System.currentTimeMillis())
        val json = report.toString(2)
        ModuleLogger.i("observation-report: $json")
        val ctx = targetContextForIpc ?: return
        try {
            val extras = Bundle().apply { putString(ObservationProvider.KEY_REPORT, json) }
            val result = ctx.contentResolver.call(
                PROVIDER_URI, ObservationProvider.METHOD_PUBLISH, null, extras
            )
            if (result?.getBoolean(ObservationProvider.KEY_ACCEPTED) != true) {
                ModuleLogger.w("observation report relay not accepted; logcat channel in use")
            }
        } catch (t: Throwable) {
            ModuleLogger.w("observation report relay unavailable (${t.javaClass.simpleName}); logcat channel in use")
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "com.openai.chatgpt"
        const val DEFAULT_CONFIG =
            """{"schemaVersion":1,"targetPackage":"com.openai.chatgpt","rules":[],"observeClassLoads":true}"""
        const val CONFIG_POLL_SECONDS = 3L
        const val DISCOVERY_POLL_SECONDS = 1L
        const val REPORT_DEBOUNCE_MS = 1500L
        val PROVIDER_URI: Uri = Uri.parse("content://${ObservationProvider.AUTHORITY}")
    }
}
