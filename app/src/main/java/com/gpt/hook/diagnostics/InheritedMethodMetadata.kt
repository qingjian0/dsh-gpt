package com.gpt.hook.diagnostics

/**
 * Bounded discovery of inherited public method signatures.
 *
 * Class.methods exposes public methods inherited from superclasses and interfaces.
 * Methods declared directly on the inspected class remain in the separate methods list.
 * No method is invoked and no application value is read.
 */
internal object InheritedMethodMetadata {
    data class Result(
        val signatures: List<String>,
        val truncated: Boolean,
        val timedOut: Boolean
    )

    fun collect(type: Class<*>, limit: Int, deadlineNanos: Long = Long.MAX_VALUE): Result {
        require(limit >= 0)
        if (limit == 0) {
            val hasInherited = runCatching { type.methods.any { it.declaringClass != type } }.getOrDefault(false)
            return Result(emptyList(), hasInherited, System.nanoTime() >= deadlineNanos)
        }

        val selected = ArrayList<String>(limit + 1)
        var timedOut = false
        val methods = runCatching { type.methods }.getOrDefault(emptyArray())
        for (method in methods) {
            if (System.nanoTime() >= deadlineNanos) {
                timedOut = true
                break
            }
            if (method.declaringClass == type) continue
            selected += method.toGenericString()
            if (selected.size > limit) break
        }
        val truncated = selected.size > limit || timedOut
        return Result(selected.take(limit).sorted(), truncated, timedOut)
    }
}
