package com.gpt.hook.diagnostics

import dalvik.system.BaseDexClassLoader
import dalvik.system.DexFile
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Modifier
import java.util.TreeSet

/**
 * Bounded, metadata-only discovery for the target process.
 *
 * It never invokes app methods or reads field values. Every broad scan has independent
 * DEX, class, method, wall-clock and serialized-response budgets. A response explicitly
 * reports truncation so callers cannot mistake a partial index for a complete one.
 */
class DexDiscoveryEngine(
    private val classLoader: ClassLoader,
    private val limits: Limits = Limits()
) {
    data class Limits(
        val maxDexCount: Int = 16,
        val maxClassesScanned: Int = 2_500,
        val maxMethods: Int = 500,
        val maxDurationMs: Long = 1_500,
        val maxResponseBytes: Int = 64 * 1024
    ) {
        init {
            require(maxDexCount in 1..32)
            require(maxClassesScanned in 1..10_000)
            require(maxMethods in 1..1_000)
            require(maxDurationMs in 100..5_000)
            require(maxResponseBytes in 4 * 1024..128 * 1024)
        }
    }

    fun discover(
        prefix: String,
        exactClassName: String? = null,
        limit: Int = 100,
        cursor: Int = 0
    ): JSONObject {
        require(prefix.startsWith("com.openai.") && prefix.length <= 180) {
            "Discovery prefix must be within com.openai.*"
        }
        require(limit in 1..limits.maxMethods)
        require(cursor >= 0)
        if (exactClassName != null) {
            require(exactClassName.startsWith("com.openai.") && exactClassName.length <= 240)
        }

        val started = System.nanoTime()
        val deadline = started + limits.maxDurationMs * 1_000_000
        val names = if (exactClassName != null) {
            listOf(exactClassName)
        } else {
            val indexed = indexClassNames(prefix, deadline)
            if (indexed.error != null) {
                return JSONObject()
                    .put("ok", false)
                    .put("error", "class_index_unavailable")
                    .put("reason", indexed.error)
                    .put("classesScanned", indexed.scanned)
                    .put("dexScanned", indexed.dexScanned)
            }
            indexed.names
        }

        if (exactClassName != null && names.isEmpty()) {
            return JSONObject().put("ok", false).put("error", "class_not_found")
        }

        val classes = JSONArray()
        var records = 0
        var inspected = 0
        var timedOut = false
        var responseTruncated = false
        val end = (cursor.toLong() + limit).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        for ((index, name) in names.withIndex()) {
            if (index < cursor) continue
            if (records >= limit || System.nanoTime() >= deadline) {
                timedOut = System.nanoTime() >= deadline
                break
            }
            inspected++
            val type = try {
                Class.forName(name, false, classLoader)
            } catch (_: Throwable) {
                continue
            }

            val classJson = JSONObject().put("className", type.name)
            val methods = JSONArray()
            val constructors = JSONArray()
            val fields = JSONArray()
            try {
                type.declaredMethods.sortedBy { it.toGenericString() }.forEach { method ->
                    if (records >= limit || System.nanoTime() >= deadline) return@forEach
                    methods.put(method.toGenericString())
                    records++
                }
                if (records < limit && System.nanoTime() < deadline) {
                    type.declaredConstructors.sortedBy { it.toGenericString() }.forEach { constructor ->
                        if (records >= limit || System.nanoTime() >= deadline) return@forEach
                        constructors.put(constructor.toGenericString())
                        records++
                    }
                }
                if (records < limit && System.nanoTime() < deadline) {
                    type.declaredFields.sortedBy { it.name }.forEach { field ->
                        if (records >= limit || System.nanoTime() >= deadline) return@forEach
                        fields.put(JSONObject()
                            .put("name", field.name)
                            .put("type", field.type.name)
                            .put("modifiers", Modifier.toString(field.modifiers)))
                        records++
                    }
                }
            } catch (_: Throwable) {
                // Metadata access can be denied for individual classes; continue safely.
            }
            classJson.put("methods", methods)
                .put("constructors", constructors)
                .put("fields", fields)
            if (methods.length() + constructors.length() + fields.length() > 0 || exactClassName != null) {
                classes.put(classJson)
                if (classes.toString().toByteArray(Charsets.UTF_8).size > limits.maxResponseBytes) {
                    classes.remove(classes.length() - 1)
                    responseTruncated = true
                    break
                }
            }
        }

        val next = cursor + inspected
        val hasMore = !responseTruncated && !timedOut && next < names.size
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val output = JSONObject()
            .put("ok", true)
            .put("prefix", prefix)
            .put("exactClassName", exactClassName ?: JSONObject.NULL)
            .put("cursor", cursor)
            .put("nextCursor", if (hasMore) next else JSONObject.NULL)
            .put("hasMore", hasMore)
            .put("responseTruncated", responseTruncated)
            .put("timedOut", timedOut)
            .put("classesInspected", inspected)
            .put("scanTruncated", exactClassName == null && names.size >= limits.maxClassesScanned)
            .put("recordCount", records)
            .put("elapsedMs", elapsedMs)
            .put("classes", classes)

        // Defensive final cap; omit records rather than returning an oversized Binder payload.
        if (output.toString().toByteArray(Charsets.UTF_8).size > limits.maxResponseBytes) {
            return JSONObject()
                .put("ok", false)
                .put("error", "response_budget_exceeded")
                .put("classesInspected", inspected)
                .put("recordCount", records)
                .put("elapsedMs", elapsedMs)
        }
        return output
    }

    private data class IndexResult(
        val names: List<String>,
        val scanned: Int,
        val dexScanned: Int,
        val error: String? = null
    )

    private fun indexClassNames(prefix: String, deadline: Long): IndexResult {
        val base = classLoader as? BaseDexClassLoader
            ?: return IndexResult(emptyList(), 0, 0, error = "unsupported_class_loader")
        return try {
            val pathListField = BaseDexClassLoader::class.java.getDeclaredField("pathList").apply {
                isAccessible = true
            }
            val pathList = pathListField.get(base)
            val elementsField = pathList.javaClass.getDeclaredField("dexElements").apply {
                isAccessible = true
            }
            val elements = elementsField.get(pathList) as? Array<*>
                ?: return IndexResult(emptyList(), 0, 0, error = "dex_elements_unavailable")

            val names = TreeSet<String>()
            var scanned = 0
            var dexScanned = 0
            var truncated = false
            for (element in elements) {
                if (element == null) continue
                if (dexScanned >= limits.maxDexCount || scanned >= limits.maxClassesScanned || System.nanoTime() >= deadline) {
                    truncated = true
                    break
                }
                dexScanned++
                val dexField = runCatching {
                    element.javaClass.getDeclaredField("dexFile").apply { isAccessible = true }
                }.getOrNull() ?: continue
                val dex = runCatching { dexField.get(element) as? DexFile }.getOrNull() ?: continue
                val entries = runCatching { dex.entries() }.getOrNull() ?: continue
                while (entries.hasMoreElements()) {
                    if (scanned >= limits.maxClassesScanned || System.nanoTime() >= deadline) { truncated = true; break }
                    val name = entries.nextElement()
                    scanned++
                    if (name.startsWith(prefix)) names.add(name)
                }
            }
            IndexResult(names.toList(), scanned, dexScanned, truncated)
        } catch (t: Throwable) {
            IndexResult(emptyList(), 0, 0, error = t.javaClass.simpleName)
        }
    }
}
