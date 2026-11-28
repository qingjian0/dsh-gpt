package com.gpt.hook.api

import java.net.URLDecoder
import java.net.URI
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatGptPlanOAuthTest {
    private val hostId = "urn:uuid:123e4567-e89b-12d3-a456-426614174000"
    private val redirect = "http://127.0.0.1:43127/auth/callback"

    @Test
    fun firstRegistrationUsesDynamicClientAndRequiredSiwcParameters() {
        val attempt = ChatGptPlanOAuth.createAttempt(redirect, hostId)
        val query = query(URI(ChatGptPlanOAuth.buildAuthorizationUrl(attempt, "DSH GPT")))
        assertEquals("dynamic_agent_client", query["client_id"])
        assertEquals("DSH GPT", query["agent_name_hint"])
        assertEquals(hostId, query["ext_agent_host_id"])
        assertEquals(ChatGptPlanOAuth.RESOURCE, query["resource"])
        assertEquals(ChatGptPlanOAuth.SCOPES, query["scope"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals(attempt.state, query["state"])
        assertEquals(attempt.nonce, query["nonce"])
        assertFalse(query.containsKey("id_token_hint"))
        assertFalse(query.containsKey("login_hint"))
        assertFalse(query.containsKey("codex_cli_simplified_flow"))
    }

    @Test
    fun returningRegistrationReusesIssuedClientAndHintsWithoutRegisteringAgain() {
        val issued = "oaiapp_1234567890abc"
        val attempt = ChatGptPlanOAuth.createAttempt(redirect, hostId, issued)
        val query = query(URI(ChatGptPlanOAuth.buildAuthorizationUrl(attempt, "DSH GPT", "expired.id.token", "user@example.com")))
        assertEquals(issued, query["client_id"])
        assertFalse(query.containsKey("agent_name_hint"))
        assertEquals("expired.id.token", query["id_token_hint"])
        assertEquals("user@example.com", query["login_hint"])
    }

    @Test
    fun callbackRequiresExactLoopbackPathAndConstantTimeStateMatch() {
        val attempt = ChatGptPlanOAuth.createAttempt(redirect, hostId)
        val callback = "http://127.0.0.1:43127/auth/callback?code=one-time-code&state=${attempt.state}&client_id=oaiapp_1234567890abc"
        val parsed = ChatGptPlanOAuth.validateCallback(callback, attempt.state, redirect)
        assertEquals("one-time-code", parsed.code)
        assertEquals("oaiapp_1234567890abc", parsed.clientId)
        assertRejected { ChatGptPlanOAuth.validateCallback(callback.replace(attempt.state, "wrong"), attempt.state, redirect) }
        assertRejected { ChatGptPlanOAuth.validateCallback(callback.replace("/auth/callback", "/callback"), attempt.state, redirect) }
        assertRejected { ChatGptPlanOAuth.validateCallback(callback.replace("127.0.0.1", "localhost"), attempt.state, redirect) }
    }

    @Test
    fun callbackCannotSwitchAnExistingRegistration() {
        val attempt = ChatGptPlanOAuth.createAttempt(redirect, hostId, "oaiapp_1234567890abc")
        val callback = "http://127.0.0.1:43127/auth/callback?code=c&state=${attempt.state}&client_id=oaiapp_different123"
        assertRejected { ChatGptPlanOAuth.validateCallback(callback, attempt.state, redirect, attempt.clientId) }
    }

    @Test
    fun codeAndRefreshGrantsAreFormEncodedAndUseResource() {
        val attempt = ChatGptPlanOAuth.createAttempt(redirect, hostId)
        val callback = ChatGptPlanOAuth.Callback("code with + chars", "oaiapp_1234567890abc")
        val codeForm = form(ChatGptPlanOAuth.codeExchangeForm(attempt, callback))
        assertEquals("authorization_code", codeForm["grant_type"])
        assertEquals(callback.clientId, codeForm["client_id"])
        assertEquals(callback.code, codeForm["code"])
        assertEquals(attempt.pkce.verifier, codeForm["code_verifier"])
        assertEquals(redirect, codeForm["redirect_uri"])
        assertEquals(ChatGptPlanOAuth.RESOURCE, codeForm["resource"])

        val refreshForm = form(ChatGptPlanOAuth.refreshForm(callback.clientId, "refresh + token"))
        assertEquals("refresh_token", refreshForm["grant_type"])
        assertEquals(callback.clientId, refreshForm["client_id"])
        assertEquals("refresh + token", refreshForm["refresh_token"])
        assertEquals(ChatGptPlanOAuth.RESOURCE, refreshForm["resource"])
        assertRejected { ChatGptPlanOAuth.refreshForm("dynamic_agent_client", "refresh") }
    }

    @Test
    fun tokenResponseRequiresBearerRefreshAndTracksEarliestRefreshTime() {
        val now = 1_800_000_000_000L
        val raw = JSONObject()
            .put("access_token", "access")
            .put("refresh_token", "refresh")
            .put("id_token", "signed.id.token")
            .put("token_type", "Bearer")
            .put("expires_in", 3600)
            .put("scope", ChatGptPlanOAuth.SCOPES)
            .put("earliest_refresh_at", now + 20 * 60_000L)
            .toString()
        val token = ChatGptPlanOAuth.parseTokenResponse(raw, now)
        assertTrue(token.canUseChatGptPlan())
        assertFalse(token.mayRefresh(now + 56 * 60_000L))
        assertTrue(token.mayRefresh(now + 80 * 60_000L))
        assertEquals(now + 3_600_000L, token.expiresAtMillis)
    }

    @Test
    fun refreshMayReusePreviouslyGrantedScopesWhenResponseOmitsScope() {
        val now = 1_800_000_000_000L
        val raw = JSONObject()
            .put("access_token", "new-access")
            .put("refresh_token", "rotated-refresh")
            .put("token_type", "Bearer")
            .put("expires_in", 3600)
            .toString()
        val token = ChatGptPlanOAuth.parseTokenResponse(raw, now, ChatGptPlanOAuth.SCOPES.split(" ").toSet())
        assertEquals("rotated-refresh", token.refreshToken)
        assertTrue(token.canUseChatGptPlan())
        assertTrue(token.idToken.isEmpty())
    }

    private fun query(uri: URI): Map<String, String> = form(uri.rawQuery)
    private fun form(value: String): Map<String, String> = value.split("&").associate {
        val i = it.indexOf("=")
        URLDecoder.decode(it.substring(0, i), Charsets.UTF_8) to
            URLDecoder.decode(it.substring(i + 1), Charsets.UTF_8)
    }
    private fun assertRejected(block: () -> Unit) {
        try { block(); fail("expected rejection") } catch (_: IllegalArgumentException) { }
    }
}
