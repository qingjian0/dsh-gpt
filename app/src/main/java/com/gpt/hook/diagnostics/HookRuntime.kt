package com.gpt.hook.diagnostics

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Applies declarative observation and explicitly configured runtime rules. */
data class HookApplyReport(
    val added: Int,
    val replaced: Int,
    val retained: Int,
    val removed: Int,
    val failed: Int
) {
    val activeCount: Int get() = added + replaced + retained
}

class HookRuntime(
    private val framework: XposedInterface,
    private val classLoader: ClassLoader,
    private val events: EventBuffer
) {
    private data class Installed(val handle: XposedInterface.HookHandle, val fingerprint: String)
    private val installed = ConcurrentHashMap<String, Installed>()
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val discoveryEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DexDiscoveryEngine(classLoader)
    }

    fun apply(config: HookConfig): Pair<Int, Int> {
        val report = applyWithReport(config)
        return report.activeCount to report.failed
    }

    @Synchronized
    fun applyWithReport(config: HookConfig): HookApplyReport {
        val activeKeys = HashSet<String>()
        var added = 0
        var replaced = 0
        var retained = 0
        var removed = 0
        var failed = 0

        for (rule in config.rules) {
            if (!rule.enabled) continue
            val candidates = try {
                val type = Class.forName(rule.className, false, classLoader)
                type.declaredMethods.filter { methodMatches(it, rule) }
                    .sortedBy { it.toGenericString() }.take(8)
            } catch (t: Throwable) {
                failed++
                val oldKeys = installed.keys.filter { it.startsWith("${rule.id}:") }
                activeKeys.addAll(oldKeys)
                retained += oldKeys.size
                ModuleLogger.w("rule lookup failed: id=${rule.id}; reason=${t.javaClass.simpleName}")
                continue
            }

            if (candidates.isEmpty()) {
                failed++
                val oldKeys = installed.keys.filter { it.startsWith("${rule.id}:") }
                activeKeys.addAll(oldKeys)
                retained += oldKeys.size
                ModuleLogger.w("rule has no matching methods: id=${rule.id}")
                continue
            }

            for (method in candidates) {
                val signature = method.toGenericString()
                val key = "${rule.id}:$signature"
                activeKeys += key
                val fingerprint = listOf(
                    rule.className, rule.methodName, rule.parameterTypes.joinToString(","),
                    rule.sampleEvery, rule.mode, rule.result, rule.argPatch.toSortedMap(), rule.dumpStack
                ).joinToString("|")
                val existing = installed[key]
                if (existing?.fingerprint == fingerprint) {
                    retained++
                    continue
                }
                try {
                    val hookId = stableHookId(rule.id, signature)
                    val handle = framework.hook(method).setId(hookId).intercept { chain ->
                        val count = counters.getOrPut(key) { AtomicLong() }.incrementAndGet()
                        if ((count - 1) % rule.sampleEvery != 0L) {
                            chain.proceed()
                        } else {
                            val started = System.nanoTime()
                            var failure: Throwable? = null
                            try {
                                when (rule.mode) {
                                    "return" -> coerceValue(rule.result, method.returnType)
                                    "noop" -> defaultValue(method.returnType)
                                    "throw" -> throw IllegalStateException("DSH rule: ${rule.id}")
                                    "args" -> {
                                        val args = Array(method.parameterCount) { chain.getArg(it) }
                                        rule.argPatch.forEach { (index, value) ->
                                            require(index < method.parameterCount) { "Argument index exceeds method arity" }
                                            args[index] = coerceValue(value, method.parameterTypes[index])
                                        }
                                        chain.proceed(args)
                                    }
                                    else -> {
                                        if (rule.mode == "dump" || rule.mode == "inspect") {
                                            ModuleLogger.i(
                                                "hook ${rule.mode}: id=${rule.id}; method=${method.name}; " +
                                                    "args=${method.parameterCount}; return=${method.returnType.name}" +
                                                    if (rule.dumpStack) "\n${shortStack()}" else ""
                                            )
                                        }
                                        chain.proceed()
                                    }
                                }
                            } catch (t: Throwable) {
                                failure = t
                                throw t
                            } finally {
                                events.record(rule, signature, System.nanoTime() - started, failure)
                            }
                        }
                    }
                    installed[key] = Installed(handle, fingerprint)
                    if (existing == null) added++ else replaced++
                } catch (t: Throwable) {
                    failed++
                    if (existing != null) retained++
                    ModuleLogger.w(
                        "method hook failed: id=${rule.id}; signature=${signature.take(180)}; " +
                            "reason=${t.javaClass.simpleName}"
                    )
                }
            }
        }

        installed.entries.toList().forEach { (key, value) ->
            if (key !in activeKeys) {
                runCatching { value.handle.unhook() }
                installed.remove(key)
                counters.remove(key)
                removed++
            }
        }
        return HookApplyReport(added, replaced, retained, removed, failed)
    }

    fun discover(prefix: String, exactClassName: String? = null, limit: Int = 100, cursor: Int = 0): JSONObject =
        discoveryEngine.discover(prefix, exactClassName, limit, cursor)

    @Synchronized
    fun clear(): Int {
        val removed = installed.size
        installed.values.forEach { runCatching { it.handle.unhook() } }
        installed.clear()
        counters.clear()
        return removed
    }

    private fun methodMatches(method: Method, rule: HookRule): Boolean {
        if (method.name != rule.methodName) return false
        if (rule.parameterTypes.isEmpty()) return true
        return method.parameterTypes.map { it.name } == rule.parameterTypes
    }

    private fun coerceValue(value: Any?, type: Class<*>): Any? {
        if (value == null) {
            require(!type.isPrimitive) { "Null cannot be used for primitive ${type.name}" }
            return null
        }
        return when (type) {
            java.lang.Void.TYPE -> throw IllegalArgumentException("void methods cannot return a value")
            java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> value as? Boolean
                ?: throw IllegalArgumentException("Expected Boolean")
            java.lang.Byte.TYPE, java.lang.Byte::class.java -> (value as? Number)?.toByte()
                ?: throw IllegalArgumentException("Expected Number for byte")
            java.lang.Short.TYPE, java.lang.Short::class.java -> (value as? Number)?.toShort()
                ?: throw IllegalArgumentException("Expected Number for short")
            java.lang.Integer.TYPE, java.lang.Integer::class.java -> (value as? Number)?.toInt()
                ?: throw IllegalArgumentException("Expected Number for int")
            java.lang.Long.TYPE, java.lang.Long::class.java -> (value as? Number)?.toLong()
                ?: throw IllegalArgumentException("Expected Number for long")
            java.lang.Float.TYPE, java.lang.Float::class.java -> (value as? Number)?.toFloat()
                ?: throw IllegalArgumentException("Expected Number for float")
            java.lang.Double.TYPE, java.lang.Double::class.java -> (value as? Number)?.toDouble()
                ?: throw IllegalArgumentException("Expected Number for double")
            java.lang.Character.TYPE, java.lang.Character::class.java -> (value as? String)?.singleOrNull()
                ?: throw IllegalArgumentException("Expected one-character String")
            String::class.java -> value as? String ?: throw IllegalArgumentException("Expected String")
            else -> {
                if (type.isEnum) {
                    val name = value as? String ?: throw IllegalArgumentException("Expected enum name")
                    type.enumConstants.firstOrNull { (it as Enum<*>).name == name }
                        ?: throw IllegalArgumentException("Unknown enum value")
                } else {
                    require(type.isInstance(value)) { "Value is incompatible with ${type.name}" }
                    value
                }
            }
        }
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Void.TYPE -> null
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    private fun shortStack(): String = Thread.currentThread().stackTrace
        .drop(4).take(8).joinToString("\n") { "  at ${it.className}.${it.methodName}:${it.lineNumber}" }

    private fun stableHookId(ruleId: String, signature: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }
        return "dsh.$ruleId.$digest"
    }
}
