package com.gpt.hook.diagnostics

import org.json.JSONObject

data class HookRule(
    val id: String,
    val className: String,
    val methodName: String,
    val parameterTypes: List<String>,
    val enabled: Boolean,
    val sampleEvery: Int,
    /** Exact JVM descriptor, e.g. (Ljava/lang/String;)Z. Null preserves legacy matching. */
    val methodDescriptor: String? = null
)

data class HookConfig(
    val schemaVersion: Int,
    val targetPackage: String,
    val rules: List<HookRule>,
    val observeClassLoads: Boolean = true
)

object HookConfigParser {
    private const val MAX_RULES = 64
    private const val MAX_PARAMETERS = 32
    private const val TARGET = "com.openai.chatgpt"

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
            val sampleEvery = item.optInt("sampleEvery", 1)
            require(sampleEvery in 1..10_000) { "sampleEvery must be between 1 and 10000" }
            rules += HookRule(
                id, className, methodName, parameterTypes,
                item.optBoolean("enabled", true), sampleEvery, descriptor
            )
        }
        return HookConfig(version, target, rules, root.optBoolean("observeClassLoads", true))
    }
}
