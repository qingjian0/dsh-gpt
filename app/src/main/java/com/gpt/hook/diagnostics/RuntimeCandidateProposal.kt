package com.gpt.hook.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.security.MessageDigest

/**
 * Stable identity and review-gated proposal lifecycle for runtime method observations.
 *
 * A proposal is data only: it never resolves a method, installs a Hook, or executes app code.
 */
data class RuntimeCandidateProposal private constructor(
    val schemaVersion: Int,
    val candidateId: String,
    val targetPackage: String,
    val runtimeClassName: String,
    val declaringClassName: String,
    val methodName: String,
    val jvmDescriptor: String,
    val evidence: List<String>,
    val action: String,
    val status: Status,
    val approvedBy: String? = null,
    val validationRef: String? = null,
    val applicationRef: String? = null,
    val rejectionReason: String? = null
) {
    enum class Status { PROPOSED, APPROVED, VALIDATED, APPLIED, REJECTED }

    fun approve(actor: String): RuntimeCandidateProposal {
        require(status == Status.PROPOSED) { "Only proposed candidates can be approved" }
        val reviewer = actor.trim()
        require(reviewer.isNotEmpty()) { "Approval actor is required" }
        return copy(status = Status.APPROVED, approvedBy = reviewer)
    }

    fun markValidated(reference: String): RuntimeCandidateProposal {
        require(status == Status.APPROVED) { "Candidate must be approved before validation" }
        val ref = reference.trim()
        require(ref.isNotEmpty()) { "Validation reference is required" }
        return copy(status = Status.VALIDATED, validationRef = ref)
    }

    fun markApplied(reference: String): RuntimeCandidateProposal {
        require(status == Status.VALIDATED) { "Candidate must pass validation before application" }
        val ref = reference.trim()
        require(ref.isNotEmpty()) { "Application reference is required" }
        return copy(status = Status.APPLIED, applicationRef = ref)
    }

    fun reject(reason: String): RuntimeCandidateProposal {
        require(status == Status.PROPOSED || status == Status.APPROVED) {
            "Only proposed or approved candidates can be rejected"
        }
        val detail = reason.trim()
        require(detail.isNotEmpty()) { "Rejection reason is required" }
        return copy(status = Status.REJECTED, rejectionReason = detail)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("candidateId", candidateId)
        .put("targetPackage", targetPackage)
        .put("runtimeClassName", runtimeClassName)
        .put("declaringClassName", declaringClassName)
        .put("methodName", methodName)
        .put("jvmDescriptor", jvmDescriptor)
        .put("evidence", JSONArray(evidence))
        .put("action", action)
        .put("status", status.name.lowercase())
        .put("approvedBy", approvedBy ?: JSONObject.NULL)
        .put("validationRef", validationRef ?: JSONObject.NULL)
        .put("applicationRef", applicationRef ?: JSONObject.NULL)
        .put("rejectionReason", rejectionReason ?: JSONObject.NULL)

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val ACTION_OBSERVE_TIMING = "observe_timing"

        fun fromMethod(
            targetPackage: String,
            runtimeClassName: String,
            method: Method,
            evidence: List<String>
        ): RuntimeCandidateProposal {
            require(targetPackage.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))) {
                "Invalid target package"
            }
            require(targetPackage.startsWith("com.openai.")) {
                "Candidate target must be within com.openai.*"
            }
            require(runtimeClassName.startsWith("com.openai.")) {
                "Runtime class must be within com.openai.*"
            }
            val safeEvidence = evidence.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(16)
            require(safeEvidence.isNotEmpty()) { "At least one evidence reference is required" }

            val declaring = method.declaringClass.name
            val descriptor = JvmDescriptors.method(method)
            val canonical = listOf(
                "v$CURRENT_SCHEMA_VERSION", targetPackage, runtimeClassName,
                declaring, method.name, descriptor
            ).joinToString("|")
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .take(16)
                .joinToString("") { "%02x".format(it) }

            return RuntimeCandidateProposal(
                schemaVersion = CURRENT_SCHEMA_VERSION,
                candidateId = "dshc1_$digest",
                targetPackage = targetPackage,
                runtimeClassName = runtimeClassName,
                declaringClassName = declaring,
                methodName = method.name,
                jvmDescriptor = descriptor,
                evidence = safeEvidence,
                action = ACTION_OBSERVE_TIMING,
                status = Status.PROPOSED
            )
        }
    }
}

internal object JvmDescriptors {
    fun method(method: Method): String =
        method.parameterTypes.joinToString(prefix = "(", postfix = ")") { type(it) } +
            type(method.returnType)

    private fun type(type: Class<*>): String = when (type) {
        java.lang.Void.TYPE -> "V"
        java.lang.Boolean.TYPE -> "Z"
        java.lang.Byte.TYPE -> "B"
        java.lang.Char.TYPE -> "C"
        java.lang.Short.TYPE -> "S"
        java.lang.Integer.TYPE -> "I"
        java.lang.Long.TYPE -> "J"
        java.lang.Float.TYPE -> "F"
        java.lang.Double.TYPE -> "D"
        else -> if (type.isArray) type.name.replace('.', '/') else "L${type.name.replace('.', '/')};"
    }
}
