package com.gpt.hook.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestDumpSanitizerTest {
    @Test
    fun redactsCredentialFieldsRecursively() {
        val input = """{"messages":[{"role":"user","content":"hello"}],"access_token":"secret-value","nested":{"Cookie":"sid=private","safe":"ok"}}"""
        val output = RequestDumpSanitizer.sanitize(input)
        assertFalse(output.contains("secret-value"))
        assertFalse(output.contains("sid=private"))
        assertTrue(output.contains("[REDACTED]"))
        assertTrue(output.contains("hello"))
    }

    @Test
    fun redactsBearerJwtAndApiSecretsInFreeText() {
        val input = "Authorization: Bearer abc.def_123; access_token=private-token sk-abcdefghijklmnop eyJabcdefghijk.abcdefghijk.abcdefghijk"
        val output = RequestDumpSanitizer.sanitize(input)
        assertFalse(output.contains("abc.def_123"))
        assertFalse(output.contains("private-token"))
        assertFalse(output.contains("sk-abcdefghijklmnop"))
        assertFalse(output.contains("eyJabcdefghijk"))
        assertTrue(output.contains("[REDACTED]"))
    }

    @Test
    fun keepsOrdinaryRequestText() {
        val output = RequestDumpSanitizer.sanitize("""{"model":"auto","input":"summarize this"}""")
        assertTrue(output.contains("summarize this"))
        assertTrue(output.contains("auto"))
    }
}
