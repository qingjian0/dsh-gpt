package com.gpt.hook.api

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatGptPlanOidcVerifierTest {
    private val clientId = "oaiapp_1234567890abc"
    private val nonce = "fresh-nonce-value"
    private val now = 1_800_000_000_000L
    private val keyPair = KeyPairGenerator.getInstance("RSA").run {
        initialize(2048)
        generateKeyPair()
    }
    private val jwks = jwks(keyPair)

    @Test
    fun verifiesSignatureIssuerAudienceNonceAndIdentity() {
        val token = sign(keyPair, payload())
        val identity = ChatGptPlanOidcVerifier.verify(token, clientId, nonce, jwks, now)
        assertEquals("subject-123", identity.subject)
        assertEquals("user@example.com", identity.email)
        assertEquals("DSH User", identity.name)
        assertTrue(identity.emailVerified)
    }

    @Test
    fun rejectsWrongNonceAudienceIssuerAndExpiredToken() {
        val token = sign(keyPair, payload())
        assertRejected { ChatGptPlanOidcVerifier.verify(token, clientId, "wrong", jwks, now) }
        assertRejected { ChatGptPlanOidcVerifier.verify(token, "oaiapp_other12345", nonce, jwks, now) }
        assertRejected {
            ChatGptPlanOidcVerifier.verify(sign(keyPair, payload().put("iss", "https://attacker.invalid")), clientId, nonce, jwks, now)
        }
        assertRejected {
            ChatGptPlanOidcVerifier.verify(sign(keyPair, payload().put("exp", now / 1000L - 10)), clientId, nonce, jwks, now)
        }
    }

    @Test
    fun rejectsSignatureTamperingAndUnknownKey() {
        val token = sign(keyPair, payload())
        val parts = token.split('.')
        val tamperedPayload = encode(JSONObject(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
            .put("sub", "attacker").toString().toByteArray())
        assertRejected { ChatGptPlanOidcVerifier.verify("${parts[0]}.$tamperedPayload.${parts[2]}", clientId, nonce, jwks, now) }
        assertRejected { ChatGptPlanOidcVerifier.verify(token, clientId, nonce, jwks(keyPair, "different-kid"), now) }
    }

    @Test
    fun requiresIssuedClientIdAndRejectsUnsupportedAlgorithm() {
        assertRejected { ChatGptPlanOidcVerifier.verify("a.b.c", "dynamic_agent_client", nonce, jwks, now) }
        val token = sign(keyPair, payload(), alg = "HS256")
        assertRejected { ChatGptPlanOidcVerifier.verify(token, clientId, nonce, jwks, now) }
    }

    private fun payload() = JSONObject()
        .put("iss", ChatGptPlanOAuth.ISSUER)
        .put("aud", clientId)
        .put("sub", "subject-123")
        .put("nonce", nonce)
        .put("iat", now / 1000L - 10)
        .put("exp", now / 1000L + 3600)
        .put("email", "user@example.com")
        .put("email_verified", true)
        .put("name", "DSH User")

    private fun sign(pair: KeyPair, payload: JSONObject, alg: String = "RS256"): String {
        val header = encode(JSONObject().put("alg", alg).put("kid", "test-key-1").toString().toByteArray())
        val body = encode(payload.toString().toByteArray())
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(pair.private)
        signer.update("$header.$body".toByteArray(Charsets.US_ASCII))
        return "$header.$body.${encode(signer.sign())}"
    }

    private fun jwks(pair: KeyPair, kid: String = "test-key-1"): String {
        val public = pair.public as RSAPublicKey
        return JSONObject().put("keys", org.json.JSONArray().put(
            JSONObject()
                .put("kty", "RSA")
                .put("use", "sig")
                .put("alg", "RS256")
                .put("kid", kid)
                .put("n", encode(unsigned(public.modulus)))
                .put("e", encode(unsigned(public.publicExponent)))
        )).toString()
    }

    private fun unsigned(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun assertRejected(block: () -> Unit) {
        try { block(); fail("expected token rejection") } catch (_: IllegalArgumentException) { }
    }
}
