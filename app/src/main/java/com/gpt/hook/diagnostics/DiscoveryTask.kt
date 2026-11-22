package com.gpt.hook.diagnostics

enum class DiscoveryTaskStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED, EXPIRED, CANCELLED;

    val terminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == EXPIRED || this == CANCELLED
}

/**
 * Pure state machine for a durable discovery request.
 *
 * Provider persistence owns serialization; this class owns legal transitions and lease
 * semantics so target-process restarts do not permanently consume a request ID.
 */
data class DiscoveryTask(
    val requestId: String,
    val status: DiscoveryTaskStatus,
    val attempt: Int,
    val revision: Long,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val leaseUntilMs: Long,
    val expiresAtMs: Long,
    val errorCode: String? = null
) {
    init {
        require(requestId.matches(Regex("[A-Fa-f0-9-]{16,64}")))
        require(attempt >= 0)
        require(revision >= 0)
        require(createdAtMs >= 0 && updatedAtMs >= createdAtMs)
        require(leaseUntilMs >= 0 && expiresAtMs >= createdAtMs)
    }

    fun claim(nowMs: Long, leaseMs: Long, maxAttempts: Int = DEFAULT_MAX_ATTEMPTS): DiscoveryTask {
        require(leaseMs in 1_000..300_000)
        require(maxAttempts in 1..10)
        if (status.terminal) return this
        if (nowMs >= expiresAtMs) return copy(
            status = DiscoveryTaskStatus.EXPIRED,
            updatedAtMs = nowMs,
            leaseUntilMs = 0,
            revision = revision + 1,
            errorCode = "request_expired"
        )
        if (status == DiscoveryTaskStatus.RUNNING && nowMs < leaseUntilMs) return this
        if (attempt >= maxAttempts) return copy(
            status = DiscoveryTaskStatus.FAILED,
            updatedAtMs = nowMs,
            leaseUntilMs = 0,
            revision = revision + 1,
            errorCode = "attempts_exhausted"
        )
        return copy(
            status = DiscoveryTaskStatus.RUNNING,
            attempt = attempt + 1,
            updatedAtMs = nowMs,
            leaseUntilMs = minOf(nowMs + leaseMs, expiresAtMs),
            revision = revision + 1,
            errorCode = null
        )
    }

    fun finish(nowMs: Long, succeeded: Boolean, failureCode: String? = null): DiscoveryTask? {
        if (status != DiscoveryTaskStatus.RUNNING || nowMs >= leaseUntilMs || nowMs >= expiresAtMs) {
            return null
        }
        return copy(
            status = if (succeeded) DiscoveryTaskStatus.SUCCEEDED else DiscoveryTaskStatus.FAILED,
            updatedAtMs = nowMs,
            leaseUntilMs = 0,
            revision = revision + 1,
            errorCode = if (succeeded) null else (failureCode ?: "discovery_failed").take(96)
        )
    }

    fun cancel(nowMs: Long): DiscoveryTask =
        if (status.terminal) this else copy(
            status = DiscoveryTaskStatus.CANCELLED,
            updatedAtMs = nowMs,
            leaseUntilMs = 0,
            revision = revision + 1,
            errorCode = "cancelled"
        )

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3

        fun queued(requestId: String, nowMs: Long, ttlMs: Long = 10 * 60_000L): DiscoveryTask {
            require(ttlMs in 5_000..60 * 60_000L)
            return DiscoveryTask(
                requestId = requestId,
                status = DiscoveryTaskStatus.QUEUED,
                attempt = 0,
                revision = 0,
                createdAtMs = nowMs,
                updatedAtMs = nowMs,
                leaseUntilMs = 0,
                expiresAtMs = nowMs + ttlMs
            )
        }
    }
}
