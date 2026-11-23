package com.gpt.hook.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DexDiscoveryLimitsTest {
    @Test
    fun defaultsStayWithinResourceBudgets() {
        val limits = DexDiscoveryEngine.Limits()
        assertEquals(16, limits.maxDexCount)
        assertEquals(2_500, limits.maxClassesScanned)
        assertEquals(500, limits.maxMemberRecords)
        assertEquals(1_500L, limits.maxDurationMs)
        assertEquals(64 * 1024, limits.maxResponseBytes)
    }

    @Test
    fun rejectsUnboundedDexAndClassBudgets() {
        assertThrows(IllegalArgumentException::class.java) {
            DexDiscoveryEngine.Limits(maxDexCount = 33)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DexDiscoveryEngine.Limits(maxClassesScanned = 10_001)
        }
    }

    @Test
    fun rejectsInvalidTimeAndPayloadBudgets() {
        assertThrows(IllegalArgumentException::class.java) {
            DexDiscoveryEngine.Limits(maxDurationMs = 5_001)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DexDiscoveryEngine.Limits(maxResponseBytes = 128 * 1024 + 1)
        }
    }
}
