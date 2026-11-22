package com.gpt.hook.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class HookApplyReportTest {
    @Test
    fun activeCountIncludesAddedReplacedAndRetainedOnly() {
        val report = HookApplyReport(
            added = 2,
            replaced = 3,
            retained = 5,
            removed = 4,
            failed = 1
        )
        assertEquals(10, report.activeCount)
    }

    @Test
    fun emptyReportHasNoActiveHooks() {
        assertEquals(0, HookApplyReport(0, 0, 0, 0, 0).activeCount)
    }
}
