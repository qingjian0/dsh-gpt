package com.gpt.hook.diagnostics

import io.github.libxposed.api.XposedInterface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Polls the framework shared config and applies only validated content changes. */
class RemoteConfigWatcher(
    private val framework: XposedInterface,
    private val onConfig: (String) -> Unit
) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dsh-config-watcher").apply { isDaemon = true }
    }
    @Volatile private var lastContent: String? = null

    fun start() {
        executor.scheduleWithFixedDelay({
            val content = RemoteConfigLoader.read(framework) ?: return@scheduleWithFixedDelay
            if (content == lastContent) return@scheduleWithFixedDelay
            try {
                HookConfigParser.parse(content)
                onConfig(content)
                lastContent = content
            } catch (t: Throwable) {
                ModuleLogger.w("config update rejected; reason=${t.javaClass.simpleName}")
                lastContent = content
            }
        }, 0, 5, TimeUnit.SECONDS)
    }

    override fun close() {
        executor.shutdownNow()
    }
}
