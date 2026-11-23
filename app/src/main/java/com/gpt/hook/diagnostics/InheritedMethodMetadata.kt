package com.gpt.hook.diagnostics

import java.lang.reflect.Method

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
        val timedOut: Boolean,
        val unavailable: Boolean = false,
        val methods: List<Method> = emptyList()
    )

    fun collect(type: Class<*>, limit: Int, deadlineNanos: Long = Long.MAX_VALUE): Result {
        require(limit >= 0)
        if (limit == 0) {
            // Do not perform an unbounded reflection query just to decide whether
            // omitted records existed. Report conservatively that this section was skipped.
            return Result(emptyList(), truncated = true, timedOut = System.nanoTime() >= deadlineNanos)
        }
        if (System.nanoTime() >= deadlineNanos) {
            return Result(emptyList(), truncated = true, timedOut = true)
        }

        // Class.methods itself is a VM reflection operation and cannot be interrupted.
        // Check immediately before and after it, and report an overrun rather than
        // pretending the wall-clock budget was strictly enforceable inside the VM call.
        val methods = try {
            type.methods
        } catch (_: Throwable) {
            return Result(emptyList(), truncated = true, timedOut = false, unavailable = true)
        }
        if (System.nanoTime() >= deadlineNanos) {
            return Result(emptyList(), truncated = true, timedOut = true)
        }

        val selected = ArrayList<Method>(limit + 1)
        var timedOut = false
        for (method in methods) {
            if (System.nanoTime() >= deadlineNanos) {
                timedOut = true
                break
            }
            if (method.declaringClass == type) continue
            selected += method
            if (selected.size > limit) break
        }
        val truncated = selected.size > limit || timedOut
        val bounded = selected.take(limit).sortedBy { it.toGenericString() }
        return Result(bounded.map { it.toGenericString() }, truncated, timedOut, methods = bounded)
    }
}
