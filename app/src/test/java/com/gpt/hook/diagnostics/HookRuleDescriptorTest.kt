package com.gpt.hook.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HookRuleDescriptorTest {
    @Test
    fun parsesExactMethodDescriptor() {
        val config = HookConfigParser.parse(
            """{"schemaVersion":1,"targetPackage":"com.openai.chatgpt","rules":[{"id":"exact","className":"com.openai.auth.Session","methodName":"isValid","methodDescriptor":"(Ljava/lang/String;)Z"}]}"""
        )
        assertEquals("(Ljava/lang/String;)Z", config.rules.single().methodDescriptor)
        assertTrue(config.rules.single().parameterTypes.isEmpty())
    }

    @Test
    fun rejectsMalformedOrConflictingMethodSelectors() {
        val malformed = """{"rules":[{"id":"bad","className":"com.openai.auth.Session","methodName":"isValid","methodDescriptor":"(V)Z"}]}"""
        assertThrows(IllegalArgumentException::class.java) { HookConfigParser.parse(malformed) }

        val conflicting = """{"rules":[{"id":"bad","className":"com.openai.auth.Session","methodName":"isValid","parameterTypes":["java.lang.String"],"methodDescriptor":"(Ljava/lang/String;)Z"}]}"""
        assertThrows(IllegalArgumentException::class.java) { HookConfigParser.parse(conflicting) }
    }

    @Test
    fun validatesJvmDescriptorGrammar() {
        assertTrue(JvmDescriptors.isValidMethodDescriptor("()V"))
        assertTrue(JvmDescriptors.isValidMethodDescriptor("([Ljava/lang/String;)[[I"))
        assertTrue(JvmDescriptors.isValidMethodDescriptor("(I)Ljava/lang/Object;"))
        assertFalse(JvmDescriptors.isValidMethodDescriptor("(V)Z"))
        assertFalse(JvmDescriptors.isValidMethodDescriptor("()[V"))
        assertFalse(JvmDescriptors.isValidMethodDescriptor("(Ljava/lang/String)Z"))
        assertFalse(JvmDescriptors.isValidMethodDescriptor("(I)"))
    }
}
