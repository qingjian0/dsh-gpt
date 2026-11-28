package com.gpt.hook.api

import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptPlanStorageAndCallbackTest {
    private val hostId = "urn:uuid:123e4567-e89b-12d3-a456-426614174000"

    @Test
    fun profileRoundTripKeepsRegistrationIdentityAndPermissionBoundary() {
        val profile = ChatGptPlanProfile(
            id = "profile-1",
            clientId = "oaiapp_1234567890abc",
            subject = "subject-1",
            email = "user@example.com",
            name = "DSH User",
            idToken = "signed.id.token",
            accessToken = "access",
            refreshToken = "refresh",
            scopes = ChatGptPlanOAuth.SCOPES.split(" ").toSet(),
            expiresAtMillis = 1_800_003_600_000L,
            earliestRefreshAtMillis = 1_800_000_300_000L,
        )
        val restored = ChatGptPlanProfile.fromJson(profile.toJson())
        assertEquals(profile, restored)
        assertTrue(restored.canUseChatGptPlan)
        assertFalse(restored.toJson().toString().contains("dynamic_agent_client"))
    }

    @Test
    fun identityOnlyProfileCannotUseChatGptPlan() {
        val json = JSONObject()
            .put("id", "profile-2")
            .put("clientId", "oaiapp_1234567890abc")
            .put("subject", "subject-2")
            .put("scopes", org.json.JSONArray(listOf("openid", "email", "profile")))
        val profile = ChatGptPlanProfile.fromJson(json)
        assertFalse(profile.canUseChatGptPlan)
        assertTrue(profile.accessToken == null)
    }

    @Test
    fun callbackListenerBindsEphemeralPortAndConsumesOnlyValidState() {
        val listener = ChatGptPlanLoopbackServer.create(hostId)
        val redirect = listener.start()
        val port = URI(redirect).port
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<ChatGptPlanOAuth.Callback> { listener.await(8_000) }
            sendCallback(port, "wrong-state")
            sendCallback(port, listener.attempt.state)
            val callback = future.get(8, TimeUnit.SECONDS)
            assertEquals("one-time-code", callback.code)
            assertEquals("oaiapp_1234567890abc", callback.clientId)
        } finally {
            listener.close()
            executor.shutdownNow()
        }
    }

    private fun sendCallback(port: Int, state: String) {
        val path = "/auth/callback?code=one-time-code&state=" +
            URLEncoder.encode(state, "UTF-8") + "&client_id=oaiapp_1234567890abc"
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3_000
            socket.getOutputStream().write(
                "GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.ISO_8859_1)
            )
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes()
        }
    }
}
