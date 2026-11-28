package com.gpt.hook.api

import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/**
 * First-party Sign in with ChatGPT (SIWC) protocol primitives.
 *
 * This is deliberately separate from the Android DPoP backend and legacy
 * chatgpt.com web-session backend. SIWC credentials are only for api.openai.com/v1.
 */
internal object ChatGptPlanOAuth {
    const val ISSUER = "https://auth.openai.com"
    const val AUTHORIZE_ENDPOINT = "$ISSUER/api/accounts/authorize"
    const val TOKEN_ENDPOINT = "$ISSUER/api/accounts/oauth/token"
    const val DISCOVERY_ENDPOINT = "$ISSUER/.well-known/openid-configuration"
    const val RESOURCE = "https://api.openai.com/v1"
    const val FIRST_REGISTRATION_CLIENT_ID = "dynamic_agent_client"
    const val REQUIRED_SCOPE = "chatgpt.tokens.use.direct"
    const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    const val CALLBACK_PATH = "/auth/callback"
    const val REFRESH_LEEWAY_MS = 5 * 60 * 1000L

    data class Pkce(val verifier: String, val challenge: String)
    data class Attempt(
        val state: String,
        val nonce: String,
        val pkce: Pkce,
        val redirectUri: String,
        val hostId: String,
        val clientId: String,
        val isFirstRegistration: Boolean,
    )
    data class Callback(val code: String, val clientId: String)
    data class TokenSet(
        val accessToken: String,
        val refreshToken: String,
        val idToken: String,
        val tokenType: String,
        val expiresAtMillis: Long,
        val earliestRefreshAtMillis: Long?,
        val scopes: Set<String>,
    ) {
        fun canUseChatGptPlan(): Boolean = REQUIRED_SCOPE in scopes
        fun mayRefresh(nowMillis: Long): Boolean =
            nowMillis >= (earliestRefreshAtMillis ?: Long.MIN_VALUE) &&
                nowMillis >= expiresAtMillis - REFRESH_LEEWAY_MS
    }

    fun newPkce(random: SecureRandom = SecureRandom()): Pkce {
        val verifierBytes = ByteArray(32).also(random::nextBytes)
        val verifier = base64Url(verifierBytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Pkce(verifier, base64Url(digest))
    }

    fun newOpaqueValue(random: SecureRandom = SecureRandom()): String =
        base64Url(ByteArray(32).also(random::nextBytes))

    fun newHostId(randomUuid: String = java.util.UUID.randomUUID().toString()): String {
        require(runCatching { java.util.UUID.fromString(randomUuid) }.isSuccess) { "host UUID is invalid" }
        return "urn:uuid:$randomUuid"
    }

    fun createAttempt(
        redirectUri: String,
        hostId: String,
        issuedClientId: String? = null,
        random: SecureRandom = SecureRandom(),
    ): Attempt {
        validateRedirectUri(redirectUri)
        require(hostId.startsWith("urn:uuid:") || hostId.startsWith("urn:ietf:params:oauth:jwk-thumbprint:") || hostId.startsWith("did:key:")) {
            "ext_agent_host_id must be a supported opaque host identifier"
        }
        if (issuedClientId != null) validateIssuedClientId(issuedClientId)
        return Attempt(
            state = newOpaqueValue(random),
            nonce = newOpaqueValue(random),
            pkce = newPkce(random),
            redirectUri = redirectUri,
            hostId = hostId,
            clientId = issuedClientId ?: FIRST_REGISTRATION_CLIENT_ID,
            isFirstRegistration = issuedClientId == null,
        )
    }

    fun buildAuthorizationUrl(attempt: Attempt, appName: String, idTokenHint: String? = null, loginHint: String? = null): String {
        require(appName.isNotBlank())
        val params = linkedMapOf(
            "client_id" to attempt.clientId,
            "response_type" to "code",
            "redirect_uri" to attempt.redirectUri,
            "scope" to SCOPES,
            "resource" to RESOURCE,
            "state" to attempt.state,
            "nonce" to attempt.nonce,
            "code_challenge_method" to "S256",
            "code_challenge" to attempt.pkce.challenge,
            "ext_agent_host_id" to attempt.hostId,
        )
        if (attempt.isFirstRegistration) params["agent_name_hint"] = appName
        else {
            idTokenHint?.takeIf { it.isNotBlank() }?.let { params["id_token_hint"] = it }
            loginHint?.takeIf { it.isNotBlank() }?.let { params["login_hint"] = it }
        }
        return AUTHORIZE_ENDPOINT + "?" + params.entries.joinToString("&") { (k, v) ->
            "${encode(k)}=${encode(v)}"
        }
    }

    fun validateCallback(
        callbackUrl: String,
        expectedState: String,
        expectedClientId: String? = null,
    ): Callback {
        val uri = java.net.URI(callbackUrl)
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.path == CALLBACK_PATH) {
            "OAuth callback URI mismatch"
        }
        val params = parseQuery(uri.rawQuery.orEmpty())
        val state = params["state"].orEmpty()
        require(state.isNotBlank() && constantTimeEquals(state, expectedState)) { "OAuth state mismatch" }
        val error = params["error"]
        if (!error.isNullOrBlank()) {
            val description = params["error_description"].orEmpty()
            throw IllegalStateException("OAuth authorization failed: ${error.take(80)} ${description.take(160)}".trim())
        }
        val code = params["code"].orEmpty()
        require(code.isNotBlank()) { "OAuth callback is missing code" }
        val callbackClientId = params["client_id"].orEmpty()
        val selectedClientId = when {
            expectedClientId != null -> {
                validateIssuedClientId(expectedClientId)
                require(callbackClientId.isBlank() || callbackClientId == expectedClientId) {
                    "OAuth callback client_id does not match the selected registration"
                }
                expectedClientId
            }
            else -> {
                validateIssuedClientId(callbackClientId)
                callbackClientId
            }
        }
        return Callback(code, selectedClientId)
    }

    fun codeExchangeForm(attempt: Attempt, callback: Callback): String {
        if (attempt.isFirstRegistration) validateIssuedClientId(callback.clientId)
        else require(callback.clientId == attempt.clientId) { "OAuth client registration mismatch" }
        return form(linkedMapOf(
            "grant_type" to "authorization_code",
            "client_id" to callback.clientId,
            "code" to callback.code,
            "code_verifier" to attempt.pkce.verifier,
            "redirect_uri" to attempt.redirectUri,
            "resource" to RESOURCE,
        ))
    }

    fun refreshForm(clientId: String, refreshToken: String): String {
        validateIssuedClientId(clientId)
        require(refreshToken.isNotBlank())
        return form(linkedMapOf(
            "grant_type" to "refresh_token",
            "client_id" to clientId,
            "refresh_token" to refreshToken,
            "resource" to RESOURCE,
        ))
    }

    fun parseTokenResponse(raw: String, nowMillis: Long, previousScopes: Set<String>? = null): TokenSet {
        val json = JSONObject(raw)
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token")
        val idToken = json.optString("id_token")
        val tokenType = json.optString("token_type")
        val expiresIn = json.optLong("expires_in", 0L)
        val scopeString = json.optString("scope").ifBlank { previousScopes?.joinToString(" ").orEmpty() }
        val scopes = scopeString.split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        require(access.isNotBlank() && refresh.isNotBlank() && tokenType.equals("Bearer", true) && expiresIn > 0L) {
            "OAuth token response is incomplete"
        }
        require(expiresIn <= 24 * 60 * 60L) { "OAuth access token lifetime is out of bounds" }
        val expiresAt = Math.addExact(nowMillis, Math.multiplyExact(expiresIn, 1000L))
        val earliest = parseEpochMillis(json.opt("earliest_refresh_at"))
        return TokenSet(access, refresh, idToken, tokenType, expiresAt, earliest, scopes)
    }

    fun validateIssuedClientId(clientId: String) {
        require(clientId.startsWith("oaiapp_") && clientId.length in 10..200 &&
            clientId.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            "OAuth callback did not contain a valid issued oaiapp client_id"
        }
    }

    private fun validateRedirectUri(value: String) {
        val uri = java.net.URI(value)
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.path == CALLBACK_PATH &&
            uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "redirect_uri must be an exact 127.0.0.1 /auth/callback loopback URI"
        }
        require(uri.port in 1..65535)
    }

    private fun parseQuery(query: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        if (query.isBlank()) return result
        for (part in query.split("&")) {
            if (part.isEmpty()) continue
            val index = part.indexOf("=")
            val key = decode(if (index < 0) part else part.substring(0, index))
            val value = decode(if (index < 0) "" else part.substring(index + 1))
            require(key !in result) { "OAuth callback contains duplicate parameters" }
            result[key] = value
        }
        return result
    }

    private fun parseEpochMillis(value: Any?): Long? = when (value) {
        is Number -> value.toLong().let { if (it < 10_000_000_000L) it * 1000L else it }
        is String -> value.toLongOrNull()?.let { if (it < 10_000_000_000L) it * 1000L else it }
            ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        else -> null
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
    private fun form(values: Map<String, String>): String =
        values.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
}
