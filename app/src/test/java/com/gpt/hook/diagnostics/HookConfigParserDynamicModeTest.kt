package com.gpt.hook.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HookConfigParserDynamicModeTest {
    @Test
    fun parsesReturnAndArgumentPatchRules() {
        val config = HookConfigParser.parse(
            """{"schemaVersion":1,"rules":[{"id":"r1","className":"com.openai.x.Foo","methodName":"value","mode":"return","result":42},{"id":"r2","className":"com.openai.x.Bar","methodName":"call","mode":"args","args":{"0":"replacement","1":7},"stack":true}]}"""
        )

        assertEquals("return", config.rules[0].mode)
        assertEquals(42, config.rules[0].result)
        assertEquals("args", config.rules[1].mode)
        assertEquals("replacement", config.rules[1].argPatch[0])
        assertEquals(7, config.rules[1].argPatch[1])
        assertTrue(config.rules[1].dumpStack)
    }

    @Test
    fun observeRemainsTheDefaultMetadataOnlyMode() {
        val config = HookConfigParser.parse(
            """{"schemaVersion":1,"rules":[{"id":"r1","className":"com.openai.x.Foo","methodName":"value"}]}"""
        )
        assertEquals("observe", config.rules.single().mode)
        assertTrue(config.rules.single().argPatch.isEmpty())
    }

    @Test
    fun fullClassScanIsOptIn() {
        val disabled = HookConfigParser.parse("""{"schemaVersion":1,"rules":[]}""")
        val enabled = HookConfigParser.parse("""{"schemaVersion":1,"rules":[],"scanAllClasses":true}""")
        assertFalse(disabled.scanAllClasses)
        assertTrue(enabled.scanAllClasses)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownMode() {
        HookConfigParser.parse(
            """{"schemaVersion":1,"rules":[{"id":"r1","className":"com.openai.x.Foo","methodName":"value","mode":"arbitrary"}]}"""
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeArgumentIndex() {
        HookConfigParser.parse(
            """{"schemaVersion":1,"rules":[{"id":"r1","className":"com.openai.x.Foo","methodName":"value","mode":"args","args":{"-1":"bad"}}]}"""
        )
    }
}
