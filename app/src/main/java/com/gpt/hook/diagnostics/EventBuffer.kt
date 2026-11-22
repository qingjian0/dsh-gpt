package com.gpt.hook.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** Bounded, metadata-only event buffer. Never stores arguments, return values, headers or bodies. */
class EventBuffer(
    private val capacity: Int = 100,
    private val onChanged: (() -> Unit)? = null
) {
    data class Event(
        val sequence: Long,
        val ruleId: String,
        val className: String,
        val methodName: String,
        val signature: String,
        val elapsedNanos: Long,
        val errorType: String?
    )

    private val sequence = AtomicLong()
    private val lock = Any()
    private val events = ArrayDeque<Event>()

    init { require(capacity in 10..1_000) }

    fun record(rule: HookRule, signature: String, elapsedNanos: Long, error: Throwable?) {
        val event = Event(sequence.incrementAndGet(), rule.id, rule.className, rule.methodName,
            signature.take(256), elapsedNanos.coerceAtLeast(0), error?.javaClass?.name?.take(120))
        append(event)
    }

    /** Store only a class name and fixed watcher signature; never retain Class or ClassLoader. */
    fun recordClassLoaded(className: String, elapsedNanos: Long) {
        if (!className.startsWith("com.openai.") || className.length > 240) return
        append(Event(
            sequence.incrementAndGet(),
            "system.classloader",
            className,
            "loadClass",
            "ClassLoader.loadClass(String,boolean)",
            elapsedNanos.coerceAtLeast(0),
            null
        ))
    }

    private fun append(event: Event) {
        synchronized(lock) {
            if (events.size >= capacity) events.removeFirst()
            events.addLast(event)
        }
        runCatching { onChanged?.invoke() }
    }

    fun snapshot(): List<Event> = synchronized(lock) { events.toList() }

    fun toJson(): String {
        val array = JSONArray()
        snapshot().forEach { event ->
            array.put(JSONObject()
                .put("sequence", event.sequence)
                .put("ruleId", event.ruleId)
                .put("className", event.className)
                .put("methodName", event.methodName)
                .put("signature", event.signature)
                .put("elapsedNanos", event.elapsedNanos)
                .put("errorType", event.errorType ?: JSONObject.NULL))
        }
        return JSONObject().put("schemaVersion", 1)
            .put("kind", "dsh-hook-observations")
            .put("events", array).toString(2)
    }
}
