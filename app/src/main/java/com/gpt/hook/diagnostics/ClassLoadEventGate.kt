package com.gpt.hook.diagnostics

/**
 * Thread-safe bounded deduplication gate for class-load metadata.
 * No Class, ClassLoader, method body, or application value is retained.
 */
class ClassLoadEventGate(private val maxEvents: Int = 256) {
    private val seen = HashSet<String>()

    init {
        require(maxEvents in 1..1_024)
    }

    fun accept(className: String): Boolean = synchronized(seen) {
        if (!className.startsWith("com.openai.") || className.length > 240) return@synchronized false
        if (seen.size >= maxEvents || !seen.add(className)) return@synchronized false
        true
    }

    fun clear() = synchronized(seen) { seen.clear() }
    fun size(): Int = synchronized(seen) { seen.size }
}
