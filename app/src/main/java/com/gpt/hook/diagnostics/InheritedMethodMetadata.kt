package com.gpt.hook.diagnostics

/**
 * Bounded discovery of inherited public method signatures.
 *
 * Class.methods includes public methods inherited from superclasses and interfaces.
 * Methods declared directly on the inspected class remain in the separate methods list.
 * No method is invoked and no application value is read.
 */
internal object InheritedMethodMetadata {
    data class Result(val signatures: List<String>, val truncated: Boolean)

    fun collect(type: Class<*>, limit: Int): Result {
        require(limit >= 0)
        if (limit == 0) return Result(emptyList(), runCatching {
            type.methods.any { it.declaringClass != type }
        }.getOrDefault(false))

        val inherited = runCatching {
            type.methods.asSequence()
                .filter { it.declaringClass != type }
                .distinctBy { it.toGenericString() }
                .map { it.toGenericString() }
                .sorted()
                .toList()
        }.getOrDefault(emptyList())

        return Result(inherited.take(limit), inherited.size > limit)
    }
}
