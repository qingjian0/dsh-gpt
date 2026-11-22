package com.gpt.hook.diagnostics

import org.json.JSONArray
import org.json.JSONObject

data class HookRule(
    val id: String,
    val className: String,
    val methodName: String,
    val parameterTypes: List<String>,
    val enabled: Boolean,
    val sampleEvery: Int,
    val mode: String = "observe",
    val result: Any? = null,
    val argPatch: Map<Int, Any?> = emptyMap(),
    val dumpStack: Boolean = false
)

data class HookConfig(
    val schemaVersion: Int,
    val targetPackage: String,
    val rules: List<HookRule>,
    val observeClassLoads: Boolean = false,
    val scanAllClasses: Boolean = false
)

object HookConfigParser {
    private const val MAX_RULES = 64
    private const val TARGET = "com.openai.chatgpt"
    private val MODES = setOf("observe", "dump", "return", "noop", "args", "throw", "inspect")

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
            require(methodName.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]{0,127}"))) { "Invalid method name" }
            val params = item.optJSONArray("parameterTypes")
            val parameterTypes = buildList {
                if (params != null) for (p in 0 until params.length()) {
                    val type = params.optString(p)
                    require(type.length in 1..200 && !type.contains(';')) { "Invalid parameter type" }
                    add(type)
                }
            }
            val sampleEvery = item.optInt("sampleEvery", 1)
            require(sampleEvery in 1..10_000) { "sampleEvery must be between 1 and 10000" }
            val mode = item.optString("mode", "observe").ifBlank { "observe" }
            require(mode in MODES) { "Unsupported hook mode: $mode" }
            val argPatch = LinkedHashMap<Int, Any?>()
            val args = item.optJSONObject("args")
            if (args != null) {
                val keys = args.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val index = key.toIntOrNull() ?: throw IllegalArgumentException("Argument index must be an integer")
                    require(index in 0..31) { "Argument index out of range" }
                    argPatch[index] = jsonValue(args.opt(key))
                }
            }
            rules += HookRule(
                id, className, methodName, parameterTypes,
                item.optBoolean("enabled", true), sampleEvery, mode,
                jsonValue(item.opt("result")), argPatch, item.optBoolean("stack", false)
            )
        }
        return HookConfig(
            version, target, rules,
            root.optBoolean("observeClassLoads", false),
            root.optBoolean("scanAllClasses", false)
        )
    }

    private fun jsonValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONArray -> value.toString()
        is JSONObject -> value.toString()
        else -> value
    }
}
