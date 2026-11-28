package com.gpt.hook.api

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** Verifies SIWC ID tokens against a fetched OpenAI JWKS document before reading identity claims. */
internal object ChatGptPlanOidcVerifier {
    data class Identity(
        val subject: String,
        val email: String?,
        val name: String?,
        val emailVerified: Boolean,
    )

    fun verify(
        compactJwt: String,
        issuedClientId: String,
        expectedNonce: String,
        jwksJson: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Identity {
        ChatGptPlanOAuth.validateIssuedClientId(issuedClientId)
        require(expectedNonce.isNotBlank()) { "OIDC nonce is required" }
        val parts = compactJwt.split('.')
        require(parts.size == 3 && parts.all { it.isNotBlank() }) { "ID token is not a compact JWT" }

        val header = JSONObject(String(decode(parts[0]), Charsets.UTF_8))
        require(header.optString("alg") == "RS256") { "Unsupported ID token signing algorithm" }
        val kid = header.optString("kid")
        require(kid.isNotBlank()) { "ID token is missing key id" }

        val jwks = JSONObject(jwksJson).optJSONArray("keys")
            ?: throw IllegalArgumentException("OpenAI JWKS has no keys")
        val jwk = findSigningKey(jwks, kid)
        val modulus = BigInteger(1, decode(jwk.getString("n")))
        val exponent = BigInteger(1, decode(jwk.getString("e")))
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, exponent)) as RSAPublicKey

        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        require(verifier.verify(decode(parts[2]))) { "ID token signature is invalid" }

        // Claims are parsed and trusted only after signature verification.
        val claims = JSONObject(String(decode(parts[1]), Charsets.UTF_8))
        require(claims.optString("iss") == ChatGptPlanOAuth.ISSUER) { "ID token issuer mismatch" }
        val audiences = audiences(claims.opt("aud"))
        require(issuedClientId in audiences) { "ID token audience mismatch" }
        val azp = claims.optString("azp")
        require(azp.isBlank() || azp == issuedClientId) { "ID token authorized party mismatch" }
        if (audiences.size > 1) require(azp == issuedClientId) { "ID token multi-audience token is missing azp" }

        val nowSeconds = nowMillis / 1000L
        val exp = claims.optLong("exp", 0L)
        val iat = claims.optLong("iat", 0L)
        val nbf = claims.optLong("nbf", 0L)
        require(exp > 0L && nowSeconds < exp + CLOCK_SKEW_SECONDS) { "ID token has expired" }
        require(iat > 0L && iat <= nowSeconds + CLOCK_SKEW_SECONDS) { "ID token issued-at time is invalid" }
        require(nbf == 0L || nbf <= nowSeconds + CLOCK_SKEW_SECONDS) { "ID token is not yet valid" }
        require(claims.optString("nonce") == expectedNonce) { "ID token nonce mismatch" }
        val subject = claims.optString("sub")
        require(subject.isNotBlank()) { "ID token subject is missing" }

        val email = claims.optString("email").takeIf { it.isNotBlank() }
        val name = claims.optString("name").takeIf { it.isNotBlank() }
        return Identity(subject, email, name, claims.optBoolean("email_verified", false))
    }

    private fun findSigningKey(keys: JSONArray, kid: String): JSONObject {
        for (i in 0 until keys.length()) {
            val key = keys.optJSONObject(i) ?: continue
            if (key.optString("kid") != kid) continue
            require(key.optString("kty") == "RSA") { "ID token key is not RSA" }
            require(key.optString("use").let { it.isBlank() || it == "sig" }) { "ID token key is not a signing key" }
            require(key.optString("alg").let { it.isBlank() || it == "RS256" }) { "ID token key algorithm mismatch" }
            val operations = key.optJSONArray("key_ops")
            if (operations != null) {
                var canVerify = false
                for (iOp in 0 until operations.length()) if (operations.optString(iOp) == "verify") canVerify = true
                require(canVerify) { "ID token key cannot verify signatures" }
            }
            require(key.optString("n").isNotBlank() && key.optString("e").isNotBlank()) { "ID token RSA key is incomplete" }
            return key
        }
        throw IllegalArgumentException("ID token signing key was not found")
    }

    private fun audiences(value: Any?): Set<String> = when (value) {
        is String -> setOf(value)
        is JSONArray -> buildSet {
            for (i in 0 until value.length()) {
                val item = value.optString(i)
                if (item.isNotBlank()) add(item)
            }
        }
        else -> emptySet()
    }

    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
    private const val CLOCK_SKEW_SECONDS = 5L
}
