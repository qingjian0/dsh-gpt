package com.gpt.hook.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InheritedMethodMetadataTest {
    open class Parent {
        fun inheritedParentMethod(): String = "not invoked"
    }

    interface Contract {
        fun inheritedContractMethod()
    }

    class Child : Parent(), Contract {
        fun declaredChildMethod() = Unit
        override fun inheritedContractMethod() = Unit
    }

    @Test
    fun returnsInheritedPublicMethodsButNotMethodsDeclaredOnTarget() {
        val result = InheritedMethodMetadata.collect(Child::class.java, 100)
        assertTrue(result.signatures.any { it.contains("inheritedParentMethod") })
        assertFalse(result.signatures.any { it.contains("declaredChildMethod") })
        assertFalse(result.truncated)
    }

    @Test
    fun respectsRecordBudgetAndReportsTruncation() {
        val result = InheritedMethodMetadata.collect(Child::class.java, 1)
        assertEquals(1, result.signatures.size)
        assertTrue(result.truncated)
    }

    @Test
    fun zeroBudgetDoesNotReturnRecords() {
        val result = InheritedMethodMetadata.collect(Child::class.java, 0)
        assertTrue(result.signatures.isEmpty())
        assertTrue(result.truncated)
    }
}
