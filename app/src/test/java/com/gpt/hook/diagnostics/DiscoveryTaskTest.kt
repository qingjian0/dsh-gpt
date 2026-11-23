package com.gpt.hook.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DiscoveryTaskTest {
    private fun task() = DiscoveryTask.queued("0123456789abcdef", 1_000L)

    @Test
    fun queuedTaskCanBeClaimedAndCompleted() {
        val running = task().claim(2_000L, leaseMs = 30_000L)
        assertEquals(DiscoveryTaskStatus.RUNNING, running.status)
        assertEquals(1, running.attempt)
        val completed = running.finish(3_000L, succeeded = true)
        assertEquals(DiscoveryTaskStatus.SUCCEEDED, completed?.status)
    }

    @Test
    fun activeLeasePreventsDuplicateClaim() {
        val running = task().claim(2_000L, leaseMs = 30_000L)
        assertSame(running, running.claim(3_000L, leaseMs = 30_000L))
    }

    @Test
    fun expiredLeaseCanBeReclaimed() {
        val first = task().claim(2_000L, leaseMs = 5_000L)
        val second = first.claim(8_000L, leaseMs = 5_000L)
        assertEquals(DiscoveryTaskStatus.RUNNING, second.status)
        assertEquals(2, second.attempt)
    }

    @Test
    fun staleWorkerCannotCompleteAfterLeaseExpiry() {
        val running = task().claim(2_000L, leaseMs = 5_000L)
        assertNull(running.finish(8_000L, succeeded = true))
    }

    @Test
    fun transientFailureCanBeRetriedWithinAttemptBudget() {
        val running = task().claim(2_000L, leaseMs = 30_000L)
        val retry = running.finish(3_000L, succeeded = false, failureCode = "temporary", retryable = true)
        assertEquals(DiscoveryTaskStatus.QUEUED, retry?.status)
        assertEquals(1, retry?.attempt)
    }

    @Test
    fun terminalTaskCannotBeClaimedAgain() {
        val done = task().claim(2_000L, leaseMs = 30_000L).finish(3_000L, true)!!
        assertSame(done, done.claim(4_000L, leaseMs = 30_000L))
    }
}
