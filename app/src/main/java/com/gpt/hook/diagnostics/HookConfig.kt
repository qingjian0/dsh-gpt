package com.gpt.hook.diagnostics

import org.json.JSONObject

data class HookRule(
    val id: String,
    val className: String,
    val methodName: String,
    val parameterTypes: List<String>,
    val enabled: Boolean,
    val sampleEvery: Int,
    val methodDescriptor: String? = null,
    /** observe/dump, return, noop, args or throw. */
    val mode: String = "observe",
    val result: Any? = null,
    val argumentOverrides: Map<Int, Any?> = emptyMap(),
    val throwMessage: String = "DSH dynamic Hook"
)

data class HookConfig(
    val schemaVersion: Int,
    val targetPackage: String,
    val rules: List<HookRule>,
    val observeClassLoads: Boolean = true
)

internal object HookActionPolicy {
    private val sensitiveSegments = listOf(
        "auth", "oauth", "token", "credential", "session", "dpop", "attest",
        "integrity", "challenge", "proof", "payment", "billing", "purchase",
        "login", "security", "crypto", "certificate", "network", "http",
        "cronet", "okhttp", "retrofit"
    )
    private val sensitiveMethods = Regex(
        "(?i)(authenticate|authorize|isAuthenticated|isAuthorized|isLoggedIn|isSessionValid|isValid|verify|validate|checkAccess|hasAccess|canAccess|isAllowed|checkPermission|hasPermission|isEntitled|sign|encrypt|decrypt|refreshToken)"
    )

    fun allowsBehaviorChange(className: String, methodName: String): Boolean {
        val target = "$className.$methodName".lowercase()
        return sensitiveSegments.none { target.contains(it) } && !sensitiveMethods.containsMatchIn(methodName)
    }
}

object HookConfigParser {
    private const val MAX_RULES = 64
    private const val MAX_PARAMETERS = 32
    private const val MAX_ARGUMENT_OVERRIDES = 32
    private const val TARGET = "com.openai.chatgpt"
    private val modes = setOf("observe", "dump", "return", "noop", "args", "throw")

    fun parse(source: String): HookConfig {
        require(source.length <= 128 * 1024) { "Configuration exceeds 128 KiB" }
        val root = JSONObject(source)
        val version = root.optInt("schemaVersion", 1)
        require(version == 1) { "Unsupported schemaVersion: $version" }
        val target = root.optString("targetPackage", TARGET)
        require(target == TARGET) { "Only the configured target package is supported" }
        val array = root.optJSONArray("rules") ?: throw IllegalArgumentException("rules array is required")
        require(array.length() <= MAX_RULES) { "Too many rules" }
        val ids = HashSet<String>()
        val rules = ArrayList<HookRule>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: throw IllegalArgumentException("rules[$i] must be an object")
            val id = item.optString("id")
            val className = item.optString("className")
            val methodName = item.optString("methodName")
            require(id.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "Invalid rule id at index $i" }
            require(ids.add(id)) { "Duplicate rule id: $id" }
            require(className.startsWith("com.openai.") && className.length <= 240) { "Class must be in com.openai.*" }
            require(
                methodName == "<init>" ||
                    methodName.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]{0,127}"))
            ) { "Invalid method name" }

            val params = item.optJSONArray("parameterTypes")
            require(params == null || params.length() <= MAX_PARAMETERS) { "Too many parameter types" }
            val parameterTypes = buildList {
                if (params != null) for (p in 0 until params.length()) {
                    val type = params.optString(p)
                    require(type.length in 1..200 && !type.contains(';')) { "Invalid parameter type" }
                    add(type)
                }
            }
            val descriptor = when (val raw = item.opt("methodDescriptor")) {
                null, JSONObject.NULL -> null
                is String -> raw.also {
                    require(JvmDescriptors.isValidMethodDescriptor(it)) { "Invalid methodDescriptor at index $i" }
                }
                else -> throw IllegalArgumentException("methodDescriptor must be a string")
            }
            require(descriptor == null || parameterTypes.isEmpty()) {
                "Use methodDescriptor or parameterTypes, not both"
            }
            if (methodName == "<init>" && descriptor != null) {
                require(descriptor.endsWith(")V")) { "Constructor descriptor must return void" }
            }

            val mode = item.optString("mode", "observe").lowercase()
            require(mode in modes) { "Unsupported Hook mode at index $i" }
            val result = if (item.has("result")) scalar(item.opt("result"), "result") else null
            if (mode == "return") require(item.has("result")) { "return mode requires result" }

            val argsJson = item.optJSONObject("args")
            require(argsJson == null || argsJson.length() <= MAX_ARGUMENT_OVERRIDES) { "Too many argument overrides" }
            val argumentOverrides = LinkedHashMap<Int, Any?>()
            if (argsJson != null) {
                val iterator = argsJson.keys()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    val index = key.toIntOrNull()
                        ?: throw IllegalArgumentException("Argument index must be a non-negative integer")
                    require(index >= 0) { "Argument index must be non-negative" }
                    argumentOverrides[index] = scalar(argsJson.opt(key), "args[$key]")
                }
            }
            if (mode == "args") require(argumentOverrides.isNotEmpty()) { "args mode requires at least one override" }
            if (mode != "args") require(argumentOverrides.isEmpty()) { "args is only valid in args mode" }
            if (mode != "return") require(!item.has("result")) { "result is only valid in return mode" }

            val throwMessage = item.optString("throwMessage", "DSH dynamic Hook")
            require(throwMessage.length <= 256) { "throwMessage is too long" }
            if (mode == "throw") require(throwMessage.isNotBlank()) { "throwMessage cannot be blank" }
            if (mode != "throw") require(!item.has("throwMessage")) { "throwMessage is only valid in throw mode" }

            if (mode !in setOf("observe", "dump")) {
                require(methodName != "<init>") { "Constructors support observe mode only" }
                require(HookActionPolicy.allowsBehaviorChange(className, methodName)) {
                    "Behavior-changing Hook is blocked for sensitive authentication, security, payment or network targets"
                }
            }

            val sampleEvery = item.optInt("sampleEvery", 1)
            require(sampleEvery in 1..10_000) { "sampleEvery must be between 1 and 10000" }
            rules += HookRule(
                id = id,
                className = className,
                methodName = methodName,
                parameterTypes = parameterTypes,
                enabled = item.optBoolean("enabled", true),
                sampleEvery = sampleEvery,
                methodDescriptor = descriptor,
                mode = mode,
                result = result,
                argumentOverrides = argumentOverrides,
                throwMessage = throwMessage
            )
        }
        return HookConfig(version, target, rules, root.optBoolean("observeClassLoads", true))
    }

    private fun scalar(value: Any?, field: String): Any? = when (value) {
        null, JSONObject.NULL -> null
        is String, is Boolean, is Number -> value
        else -> throw IllegalArgumentException("$field must be a JSON scalar")
    }
}
