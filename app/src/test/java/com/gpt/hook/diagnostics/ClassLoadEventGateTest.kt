package com.gpt.hook.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassLoadEventGateTest {
    @Test
    fun acceptsOnlyOpenAiNamesAndDeduplicates() {
        val gate = ClassLoadEventGate(4)
        assertFalse(gate.accept("java.lang.String"))
        assertTrue(gate.accept("com.openai.auth.a"))
        assertFalse(gate.accept("com.openai.auth.a"))
        assertEquals(1, gate.size())
    }

    @Test
    fun stopsAtConfiguredBudget() {
        val gate = ClassLoadEventGate(2)
        assertTrue(gate.accept("com.openai.a.A"))
        assertTrue(gate.accept("com.openai.b.B"))
        assertFalse(gate.accept("com.openai.c.C"))
        assertEquals(2, gate.size())
    }

    @Test
    fun clearAllowsFreshObservationWindow() {
        val gate = ClassLoadEventGate(1)
        assertTrue(gate.accept("com.openai.a.A"))
        gate.clear()
        assertEquals(0, gate.size())
        assertTrue(gate.accept("com.openai.a.A"))
    }
}
