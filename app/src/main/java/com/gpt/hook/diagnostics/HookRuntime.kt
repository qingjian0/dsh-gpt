package com.gpt.hook.diagnostics

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Applies live, replaceable Hook rules inside the target process. */
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
    private data class Installed(
        val handle: XposedInterface.HookHandle,
        val fingerprint: String,
        val ruleId: String,
        val signature: String,
        val sampleEvery: Int,
        val mode: String
    )

    private val installed = ConcurrentHashMap<String, Installed>()
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    @Volatile private var currentRules: List<HookRule> = emptyList()
    private val discoveryEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DexDiscoveryEngine(classLoader)
    }

    fun apply(config: HookConfig): Pair<Int, Int> {
        val report = applyWithReport(config)
        return report.activeCount to report.failed
    }

    @Synchronized
    fun applyWithReport(config: HookConfig): HookApplyReport {
        currentRules = config.rules.toList()
        val activeKeys = HashSet<String>()
        var added = 0
        var replaced = 0
        var retained = 0
        var removed = 0
        var failed = 0

        for (rule in config.rules) {
            if (!rule.enabled) continue
            val type = try {
                Class.forName(rule.className, false, classLoader)
            } catch (t: Throwable) {
                val oldKeys = installed.keys.filter { it.startsWith("${rule.id}:") }
                activeKeys.addAll(oldKeys)
                retained += oldKeys.size
                ModuleLogger.i("dynamic Hook waiting for class; id=${rule.id}; class=${rule.className}")
                continue
            }
            val result = installRuleOnClass(type, rule)
            activeKeys.addAll(result.keys)
            added += result.added
            replaced += result.replaced
            retained += result.retained
            failed += result.failed
            if (result.failed > 0 && result.keys.isEmpty()) {
                val oldKeys = installed.keys.filter { it.startsWith("${rule.id}:") }
                activeKeys.addAll(oldKeys)
                retained += oldKeys.size
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

    /** A matching class is hooked as soon as its dynamic ClassLoader returns it. */
    @Synchronized
    fun onClassLoaded(type: Class<*>): Int {
        if (!type.name.startsWith("com.openai.")) return 0
        val rules = currentRules.filter { it.enabled && it.className == type.name }
        if (rules.isEmpty()) return 0
        var installedCount = 0
        for (rule in rules) {
            val result = installRuleOnClass(type, rule)
            installedCount += result.added + result.replaced
            if (result.failed > 0) {
                ModuleLogger.w("dynamic Hook partially failed; id=${rule.id}; failed=${result.failed}")
            }
        }
        if (installedCount > 0) {
            ModuleLogger.i("dynamic Hook attached; class=${type.name}; hooks=$installedCount")
        }
        return installedCount
    }

    private data class InstallResult(
        val keys: Set<String>,
        val added: Int = 0,
        val replaced: Int = 0,
        val retained: Int = 0,
        val failed: Int = 0
    )

    private fun installRuleOnClass(type: Class<*>, rule: HookRule): InstallResult {
        val candidates: List<Executable> = try {
            (if (rule.methodName == "<init>") {
                type.declaredConstructors.filter { constructorMatches(it, rule) }
            } else {
                type.declaredMethods.filter { methodMatches(it, rule) }
            }).sortedBy { it.toGenericString() }.take(MAX_OVERLOADS)
        } catch (t: Throwable) {
            ModuleLogger.w("rule reflection failed; id=${rule.id}; reason=${t.javaClass.simpleName}")
            return InstallResult(emptySet(), failed = 1)
        }
        if (candidates.isEmpty()) {
            ModuleLogger.i("dynamic Hook has no matching member yet; id=${rule.id}; class=${type.name}")
            return InstallResult(emptySet(), failed = 1)
        }

        val keys = LinkedHashSet<String>()
        var added = 0
        var replaced = 0
        var retained = 0
        var failed = 0
        for (executable in candidates) {
            val signature = executable.toGenericString()
            val key = "${rule.id}:$signature"
            keys += key
            val fingerprint = listOf(
                rule.className, rule.methodName, rule.parameterTypes.joinToString(","),
                rule.methodDescriptor.orEmpty(), rule.sampleEvery.toString(), rule.mode,
                rule.result.toString(), rule.argumentOverrides.toSortedMap().toString(), rule.throwMessage
            ).joinToString("|")
            val existing = installed[key]
            if (existing?.fingerprint == fingerprint) {
                retained++
                continue
            }

            val actionError = validateAction(rule, executable)
            if (actionError != null) {
                failed++
                if (existing != null) retained++
                ModuleLogger.w("dynamic Hook action rejected; id=${rule.id}; reason=$actionError")
                continue
            }

            try {
                val hookId = stableHookId(rule.id, signature)
                val handle = framework.hook(executable)
                    .setId(hookId)
                    .intercept { chain ->
                        val count = counters.getOrPut(key) { AtomicLong() }.incrementAndGet()
                        val record = (count - 1) % rule.sampleEvery == 0L
                        val started = if (record) System.nanoTime() else 0L
                        var failure: Throwable? = null
                        try {
                            executeRule(chain, executable, rule)
                        } catch (t: Throwable) {
                            failure = t
                            throw t
                        } finally {
                            if (record) events.record(rule, signature, System.nanoTime() - started, failure)
                        }
                    }
                installed[key] = Installed(handle, fingerprint, rule.id, signature, rule.sampleEvery, rule.mode)
                if (existing == null) added++ else replaced++
            } catch (t: Throwable) {
                failed++
                if (existing != null) retained++
                ModuleLogger.w(
                    "method hook failed; id=${rule.id}; signature=${signature.take(180)}; " +
                        "reason=${t.javaClass.simpleName}"
                )
            }
        }
        return InstallResult(keys, added, replaced, retained, failed)
    }

    private fun validateAction(rule: HookRule, executable: Executable): String? {
        if (rule.mode in setOf("observe", "dump")) return null
        if (executable !is Method) return "constructor_actions_not_supported"
        if (!HookActionPolicy.allowsBehaviorChange(rule.className, rule.methodName)) return "sensitive_target"
        return try {
            when (rule.mode) {
                "return" -> {
                    require(executable.returnType != java.lang.Void.TYPE) { "void_return" }
                    coerceScalar(rule.result, executable.returnType)
                }
                "noop" -> Unit
                "args" -> {
                    require(rule.argumentOverrides.isNotEmpty()) { "empty_args" }
                    rule.argumentOverrides.forEach { (index, value) ->
                        require(index in executable.parameterTypes.indices) { "argument_index_out_of_range" }
                        coerceScalar(value, executable.parameterTypes[index])
                    }
                }
                "throw" -> require(rule.throwMessage.isNotBlank()) { "empty_throw_message" }
                else -> return "unsupported_mode"
            }
            null
        } catch (t: Throwable) {
            t.message ?: "invalid_action_type"
        }
    }

    private fun executeRule(
        chain: XposedInterface.Chain,
        executable: Executable,
        rule: HookRule
    ): Any? = when (rule.mode) {
        "return" -> coerceScalar(rule.result, (executable as Method).returnType)
        "noop" -> defaultValue((executable as Method).returnType)
        "args" -> {
            val method = executable as Method
            val arguments = Array(method.parameterCount) { index -> chain.getArg(index) }
            rule.argumentOverrides.forEach { (index, value) ->
                arguments[index] = coerceScalar(value, method.parameterTypes[index])
            }
            chain.proceed(arguments)
        }
        "throw" -> throw IllegalStateException(rule.throwMessage)
        else -> chain.proceed()
    }

    private fun coerceScalar(value: Any?, type: Class<*>): Any? {
        if (value == null) {
            require(!type.isPrimitive) { "null_for_primitive" }
            require(type == String::class.java) { "null_only_supported_for_string" }
            return null
        }
        if (type == String::class.java) return value as? String ?: throw IllegalArgumentException("expected_string")
        if (type == java.lang.Boolean.TYPE || type == java.lang.Boolean::class.java) {
            return value as? Boolean ?: throw IllegalArgumentException("expected_boolean")
        }
        if (type == java.lang.Character.TYPE || type == java.lang.Character::class.java) {
            val text = value as? String ?: throw IllegalArgumentException("expected_single_character")
            require(text.length == 1) { "expected_single_character" }
            return text[0]
        }
        val number = value as? Number ?: throw IllegalArgumentException("expected_number")
        val numeric = number.toDouble()
        require(numeric.isFinite()) { "number_must_be_finite" }
        return when (type) {
            java.lang.Byte.TYPE, java.lang.Byte::class.java -> {
                require(numeric % 1.0 == 0.0 && numeric in Byte.MIN_VALUE.toDouble()..Byte.MAX_VALUE.toDouble()) { "byte_out_of_range" }
                numeric.toInt().toByte()
            }
            java.lang.Short.TYPE, java.lang.Short::class.java -> {
                require(numeric % 1.0 == 0.0 && numeric in Short.MIN_VALUE.toDouble()..Short.MAX_VALUE.toDouble()) { "short_out_of_range" }
                numeric.toInt().toShort()
            }
            java.lang.Integer.TYPE, java.lang.Integer::class.java -> {
                require(numeric % 1.0 == 0.0 && numeric in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) { "int_out_of_range" }
                numeric.toInt()
            }
            java.lang.Long.TYPE, java.lang.Long::class.java -> {
                require(numeric % 1.0 == 0.0 && numeric >= Long.MIN_VALUE.toDouble() && numeric <= Long.MAX_VALUE.toDouble()) { "long_out_of_range" }
                numeric.toLong()
            }
            java.lang.Float.TYPE, java.lang.Float::class.java -> numeric.toFloat()
            java.lang.Double.TYPE, java.lang.Double::class.java -> numeric
            else -> throw IllegalArgumentException("unsupported_parameter_type")
        }
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
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

    fun discover(prefix: String, exactClassName: String? = null, limit: Int = 100, cursor: Int = 0): JSONObject =
        discoveryEngine.discover(prefix, exactClassName, limit, cursor)

    fun runtimeSnapshot(): JSONObject {
        val hooks = org.json.JSONArray()
        val entries = installed.entries.toList().sortedBy { it.key }
        entries.take(MAX_STATUS_HOOKS).forEach { (key, value) ->
            hooks.put(JSONObject()
                .put("ruleId", value.ruleId)
                .put("signature", value.signature.take(256))
                .put("fingerprint", value.fingerprint.take(256))
                .put("sampleEvery", value.sampleEvery)
                .put("mode", value.mode)
                .put("invocations", counters[key]?.get() ?: 0L)
                .put("state", "installed"))
        }
        return JSONObject()
            .put("state", "active")
            .put("activeHookCount", entries.size)
            .put("inventoryTruncated", entries.size > MAX_STATUS_HOOKS)
            .put("hooks", hooks)
            .put("capturedAtMs", System.currentTimeMillis())
    }

    @Synchronized
    fun clear(): Int {
        currentRules = emptyList()
        val removed = installed.size
        installed.values.forEach { runCatching { it.handle.unhook() } }
        installed.clear()
        counters.clear()
        return removed
    }

    private fun methodMatches(method: Method, rule: HookRule): Boolean {
        if (method.name != rule.methodName) return false
        rule.methodDescriptor?.let { return JvmDescriptors.method(method) == it }
        if (rule.parameterTypes.isEmpty()) return true
        return method.parameterTypes.map { it.name } == rule.parameterTypes
    }

    private fun constructorMatches(constructor: Constructor<*>, rule: HookRule): Boolean {
        rule.methodDescriptor?.let { return JvmDescriptors.constructor(constructor) == it }
        if (rule.parameterTypes.isEmpty()) return true
        return constructor.parameterTypes.map { it.name } == rule.parameterTypes
    }

    private fun stableHookId(ruleId: String, signature: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
        return "dsh.$ruleId.$digest"
    }

    private companion object {
        const val MAX_STATUS_HOOKS = 64
        const val MAX_OVERLOADS = 8
    }
}
