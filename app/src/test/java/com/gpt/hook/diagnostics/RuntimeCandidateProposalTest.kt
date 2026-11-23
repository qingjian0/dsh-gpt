package com.gpt.hook.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCandidateProposalTest {
    open class Parent {
        fun overloaded(value: Int): String = value.toString()
        fun overloaded(value: String): String = value
        fun arrays(values: Array<String>): Array<IntArray> = emptyArray()
    }

    class Child : Parent()

    @Test
    fun createsStableIdsAndJvmDescriptorsForOverloads() {
        val methods = Parent::class.java.declaredMethods.filter { it.name == "overloaded" }
        val first = RuntimeCandidateProposal.fromMethod(
            "com.openai.chatgpt", "com.openai.chatgpt.FakeChild", methods.first { it.parameterTypes[0] == Int::class.javaPrimitiveType },
            listOf("discovery:42")
        )
        val same = RuntimeCandidateProposal.fromMethod(
            "com.openai.chatgpt", "com.openai.chatgpt.FakeChild", methods.first { it.parameterTypes[0] == Int::class.javaPrimitiveType },
            listOf("discovery:42")
        )
        val second = RuntimeCandidateProposal.fromMethod(
            "com.openai.chatgpt", "com.openai.chatgpt.FakeChild", methods.first { it.parameterTypes[0] == String::class.java },
            listOf("discovery:42")
        )

        assertEquals(first.candidateId, same.candidateId)
        assertNotEquals(first.candidateId, second.candidateId)
        assertEquals("(I)Ljava/lang/String;", first.jvmDescriptor)
        assertEquals("(Ljava/lang/String;)Ljava/lang/String;", second.jvmDescriptor)
        assertEquals("com.gpt.hook.diagnostics.RuntimeCandidateProposalTest\$Parent", first.declaringClassName)
        val arrayMethod = Parent::class.java.declaredMethods.first { it.name == "arrays" }
        assertEquals("([Ljava/lang/String;)[[I", JvmDescriptors.method(arrayMethod))
    }

    @Test
    fun requiresEvidenceAndRestrictsTargetPackage() {
        val method = Parent::class.java.declaredMethods.first()
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeCandidateProposal.fromMethod("com.example.app", "com.openai.chatgpt.FakeChild", method, listOf("e1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeCandidateProposal.fromMethod("com.openai.chatgpt", "com.openai.chatgpt.FakeChild", method, emptyList())
        }
    }

    @Test
    fun lifecycleRequiresApprovalAndValidationBeforeApplication() {
        val method = Parent::class.java.declaredMethods.first()
        val proposed = RuntimeCandidateProposal.fromMethod(
            "com.openai.chatgpt", "com.openai.chatgpt.FakeChild", method, listOf("discovery:7")
        )
        assertThrows(IllegalArgumentException::class.java) { proposed.markValidated("test:1") }
        val approved = proposed.approve("operator")
        val validated = approved.markValidated("ci:run-184")
        val applied = validated.markApplied("runtime:session-9")

        assertEquals(RuntimeCandidateProposal.Status.APPROVED, approved.status)
        assertEquals(RuntimeCandidateProposal.Status.VALIDATED, validated.status)
        assertEquals(RuntimeCandidateProposal.Status.APPLIED, applied.status)
        assertEquals("operator", approved.approvedBy)
        assertTrue(applied.candidateId.startsWith("dshc1_"))
    }

    @Test
    fun rejectionIsTerminal() {
        val method = Parent::class.java.declaredMethods.first()
        val rejected = RuntimeCandidateProposal.fromMethod(
            "com.openai.chatgpt", "com.openai.chatgpt.FakeChild", method, listOf("discovery:9")
        ).reject("not relevant")
        assertEquals(RuntimeCandidateProposal.Status.REJECTED, rejected.status)
        assertThrows(IllegalArgumentException::class.java) { rejected.approve("operator") }
    }
}
