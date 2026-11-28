package com.gpt.hook.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OAuth 协议层与凭证生命周期的纯 JVM 测试（不依赖 Android）。 */
class ChatGptOAuthTest {

    /** 造一个 payload 形如 `header.payload.sig` 的假 JWT。 */
    private fun fakeJwt(payload: String): String {
        fun b64(s: String) = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(s.toByteArray(Charsets.UTF_8))
        return b64("""{"alg":"RS256"}""") + "." + b64(payload) + ".sig"
    }

    private fun accessToken(
        accountId: String = "acc-1",
        plan: String = "plus",
        exp: Long = 1_900_000_000L,
        email: String = "a@b.c"
    ): String = fakeJwt(
        """{"exp":$exp,
           |"https://api.openai.com/auth":{"chatgpt_account_id":"$accountId","chatgpt_plan_type":"$plan"},
           |"email":"$email"}""".trimMargin()
    )

    private fun transport(status: Int, body: String): ChatGptOAuth.Transport =
        object : ChatGptOAuth.Transport {
            var lastUrl = ""
            var lastBody = ""
            var lastContentType = ""
            override fun post(url: String, contentType: String, body: String, userAgent: String): Pair<Int, String> {
                lastUrl = url
                lastBody = body
                lastContentType = contentType
                return status to body
            }
        }

    @Test
    fun pkceVerifierAndChallengeAreUrlSafeAndDistinct() {
        val oauth = ChatGptOAuth()
        val pkce = oauth.newPkce()
        assertTrue(pkce.verifier.isNotBlank())
        assertTrue(pkce.challenge.isNotBlank())
        assertTrue(pkce.verifier != pkce.challenge)
        // RFC 7636：S256 challenge = base64url(sha256(verifier))
        val expected = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(pkce.verifier.toByteArray(Charsets.US_ASCII))
        )
        assertEquals(expected, pkce.challenge)
        assertFalse(pkce.verifier.contains("+") || pkce.verifier.contains("/"))
    }

    @Test
    fun authUrlCarriesRegistrableValues() {
        val oauth = ChatGptOAuth()
        val pkce = oauth.newPkce()
        val url = oauth.buildAuthUrl("state-abc", pkce)
        assertTrue(url.startsWith(ChatGptOAuth.Constants.AUTH_ENDPOINT))
        assertTrue(url.contains("client_id=" + ChatGptOAuth.Constants.CLIENT_ID))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("state=state-abc"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback"))
        assertTrue(url.contains("originator=codex_cli_rs"))
    }

    @Test
    fun exchangeCodeUsesFormEncodingAndParsesCredential() {
        val token = accessToken(accountId = "acc-9", plan = "prolite")
        val body = """{"access_token":"$token","refresh_token":"rt-1","expires_in":3600,
            |"id_token":"${fakeJwt("""{"https://api.openai.com/profile":{"email":"x@y.z"}}""")}"}"""
            .trimMargin()
        val fake = transport(200, body)
        val oauth = ChatGptOAuth(nowMillis = { 1_000_000L }, transport = fake)

        val credential = oauth.exchangeCode("the-code", "the-verifier")

        assertEquals("application/x-www-form-urlencoded", fake.lastContentType)
        assertTrue(fake.lastBody.contains("grant_type=authorization_code"))
        assertTrue(fake.lastBody.contains("code_verifier=the-verifier"))
        assertEquals(ChatGptOAuth.Constants.TOKEN_ENDPOINT, fake.lastUrl)
        assertEquals("rt-1", credential.refreshToken)
        assertEquals("acc-9", credential.accountId)
        assertEquals("prolite", credential.plan)
        assertEquals("Pro", credential.planLabel)
        assertEquals("x@y.z", credential.email)
        // expires_in=3600 → now + 1h
        assertEquals(1_000_000L + 3_600_000L, credential.expiresAtMillis)
    }

    @Test
    fun refreshUsesJsonAndKeepsOldRefreshTokenWhenOmitted() {
        val token = accessToken()
        val fake = transport(200, """{"access_token":"$token","expires_in":1800}""")
        val oauth = ChatGptOAuth(nowMillis = { 5_000L }, transport = fake)
        val previous = OAuthCredential(accessToken = "old", refreshToken = "rt-old", expiresAtMillis = 1L)

        val refreshed = oauth.refresh("rt-old", previous)

        assertEquals("application/json", fake.lastContentType)
        assertTrue(fake.lastBody.contains("\"grant_type\":\"refresh_token\""))
        // 响应没给 refresh_token 时必须沿用旧值，否则账号被锁死
        assertEquals("rt-old", refreshed.refreshToken)
    }

    @Test
    fun failedTokenResponseSurfacesUpstreamMessage() {
        val fake = transport(400, """{"error":"invalid_grant","error_description":"refresh token expired"}""")
        val oauth = ChatGptOAuth(transport = fake)
        val message = runCatching { oauth.refresh("rt") }.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("invalid_grant") || message.contains("refresh token expired"))
    }

    @Test
    fun jwtClaimsAreReadWithoutVerification() {
        val token = accessToken(accountId = "acc-77", plan = "team", exp = 1_700_000_000L)
        assertEquals("acc-77", ChatGptJwt.accountId(token))
        assertEquals("team", ChatGptJwt.planType(token))
        assertEquals(1_700_000_000_000L, ChatGptJwt.expiresAtMillis(token))
        assertEquals("a@b.c", ChatGptJwt.email(null, token))
        assertNull(ChatGptJwt.claims("not-a-jwt"))
    }

    @Test
    fun credentialRoundTripsThroughJson() {
        val credential = OAuthCredential(
            accessToken = "at", refreshToken = "rt", expiresAtMillis = 42L,
            accountId = "acc", email = "e@x", plan = "plus"
        )
        val restored = OAuthCredential.fromJson(credential.toJson().toString())
        assertNotNull(restored)
        assertEquals(credential, restored)
        assertNull(OAuthCredential.fromJson("{}"))
        assertNull(OAuthCredential.fromJson(null))
    }

    @Test
    fun expiringCheckRespectsLeeway() {
        val credential = OAuthCredential(accessToken = "at", expiresAtMillis = 1_000_000L)
        assertTrue(credential.isExpiringWithin(120_000L, 950_000L))
        assertFalse(credential.isExpiringWithin(120_000L, 500_000L))
        // 过期时间为 0（未知）按「需要刷新」处理
        assertTrue(credential.copy(expiresAtMillis = 0L).isExpiringWithin(0L, 1L))
    }
}

/** 凭证存储：刷新去重、失败冷却、refresh 失效拉黑。 */
class ChatGptCredentialStoreTest {

    private fun credential(access: String, refresh: String, expiresAt: Long, uid: String = "acc") =
        OAuthCredential(
            accessToken = access,
            refreshToken = refresh,
            expiresAtMillis = expiresAt,
            accountId = uid,
            email = "u@x",
            plan = "plus"
        )

    private class CountingOAuth(
        var failWith: String? = null,
        val issued: MutableList<OAuthCredential> = mutableListOf(),
    ) : ChatGptOAuth.Transport {
        var calls = 0
        override fun post(url: String, contentType: String, body: String, userAgent: String): Pair<Int, String> {
            calls++
            failWith?.let { return 400 to """{"error":"$it","error_description":"$it"}""" }
            val token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("""{"exp":2000000000,"https://api.openai.com/auth":{"chatgpt_account_id":"acc"}}""")
                    .toByteArray()
            )
            val fresh = "header.$token.sig"
            issued += OAuthCredential(accessToken = fresh, refreshToken = "rt-2", expiresAtMillis = 2_000_000_000_000L)
            return 200 to """{"access_token":"$fresh","refresh_token":"rt-2","expires_in":3600}"""
        }
    }

    @Test
    fun freshCredentialIsReturnedWithoutRefresh() {
        ChatGptCredentialStore.attach(ChatGptCredentialStore.MemoryBox())
        ChatGptCredentialStore.resetRuntimeStateForTest()
        val transport = CountingOAuth()
        val current = credential("at-1", "rt-1", System.currentTimeMillis() + 3_600_000L)
        val result = ChatGptCredentialStore.ensureUsable(
            current, now = System.currentTimeMillis(), oauth = ChatGptOAuth(transport = transport)
        )
        assertEquals(current, result)
        assertEquals(0, transport.calls)
    }

    @Test
    fun expiringCredentialIsRefreshedAndRotatedTokenPersisted() {
        ChatGptCredentialStore.attach(ChatGptCredentialStore.MemoryBox())
        ChatGptCredentialStore.resetRuntimeStateForTest()
        val transport = CountingOAuth()
        val now = System.currentTimeMillis()
        val current = credential("at-1", "rt-1", now + 60_000L) // 1 分钟内到期 → 需要刷新

        val result = ChatGptCredentialStore.ensureUsable(current, now = now, oauth = ChatGptOAuth(transport = transport))

        assertNotNull(result)
        assertEquals(1, transport.calls)
        // 轮换后的 refresh_token 必须落盘
        assertEquals("rt-2", ChatGptCredentialStore.load()?.refreshToken)
    }

    @Test
    fun concurrentRefreshIsDeduplicatedWithinReuseWindow() {
        ChatGptCredentialStore.attach(ChatGptCredentialStore.MemoryBox())
        ChatGptCredentialStore.resetRuntimeStateForTest()
        val transport = CountingOAuth()
        val now = System.currentTimeMillis()
        val current = credential("at-1", "rt-1", now + 60_000L)
        val oauth = ChatGptOAuth(transport = transport)

        ChatGptCredentialStore.ensureUsable(current, now = now, oauth = oauth)
        // 30 秒窗口内再来一次：不应重复打 auth 端点
        ChatGptCredentialStore.ensureUsable(current, now = now + 5_000L, oauth = oauth)
        assertEquals(1, transport.calls)
    }

    @Test
    fun invalidGrantRevokesCredentialAndBlocksFurtherAttempts() {
        ChatGptCredentialStore.attach(ChatGptCredentialStore.MemoryBox())
        ChatGptCredentialStore.resetRuntimeStateForTest()
        val transport = CountingOAuth(failWith = "invalid_grant")
        val now = System.currentTimeMillis()
        val current = credential("at-1", "rt-1", now + 60_000L)
        val oauth = ChatGptOAuth(transport = transport)

        val result = ChatGptCredentialStore.ensureUsable(current, now = now, oauth = oauth)

        assertNull(result)
        assertTrue(ChatGptCredentialStore.isRevoked("at-1"))
        assertNull(ChatGptCredentialStore.load())
        // 已拉黑 → 后续直接返回 null，不再撞 auth 端点
        assertNull(ChatGptCredentialStore.ensureUsable(current, now = now + 1_000L, oauth = oauth))
        assertEquals(1, transport.calls)
    }

    @Test
    fun transientFailureCoolsDownInsteadOfRevoking() {
        ChatGptCredentialStore.attach(ChatGptCredentialStore.MemoryBox())
        ChatGptCredentialStore.resetRuntimeStateForTest()
        val transport = CountingOAuth(failWith = "temporarily_unavailable")
        val now = System.currentTimeMillis()
        val current = credential("at-1", "rt-1", now + 60_000L)
        val oauth = ChatGptOAuth(transport = transport)

        assertNull(ChatGptCredentialStore.ensureUsable(current, now = now, oauth = oauth))
        assertFalse(ChatGptCredentialStore.isRevoked("at-1"))
        // 冷却期内不再重试
        assertNull(ChatGptCredentialStore.ensureUsable(current, now = now + 1_000L, oauth = oauth))
        assertEquals(1, transport.calls)
    }

    @Test
    fun memoryBoxIsolatesValues() {
        val box = ChatGptCredentialStore.MemoryBox()
        assertNull(box.read("k"))
        box.write("k", "v")
        assertEquals("v", box.read("k"))
        box.erase("k")
        assertNull(box.read("k"))
    }
}

/** Responses 协议转换：请求形状与 SSE 翻译。 */
class ChatGptResponsesTest {

    private fun chatRequest(): JSONObject = JSONObject()
        .put("model", "gpt-5.1-codex")
        .put(
            "messages",
            org.json.JSONArray()
                .put(JSONObject().put("role", "system").put("content", "be brief"))
                .put(JSONObject().put("role", "user").put("content", "hi"))
        )

    @Test
    fun requestSetsStoreFalseAndIncludesEncryptedReasoning() {
        val body = ChatGptResponses.toResponsesRequest(chatRequest(), "gpt-5.1-codex")
        assertEquals("gpt-5.1-codex", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertFalse(body.getBoolean("store"))
        assertEquals("reasoning.encrypted_content", body.getJSONArray("include").getString(0))
        // system → developer，且不允许出现 instructions
        val input = body.getJSONArray("input")
        assertEquals("developer", input.getJSONObject(0).getString("role"))
        assertEquals("hi", input.getJSONObject(1).getString("content"))
        assertFalse(body.has("instructions"))
        assertFalse(body.has("temperature"))
        assertFalse(body.has("max_output_tokens"))
    }

    @Test
    fun reasoningEffortIsPassedThroughWhenPresent() {
        val request = chatRequest().put("reasoning_effort", "high")
        val body = ChatGptResponses.toResponsesRequest(request, "gpt-5.1-codex")
        assertEquals("high", body.getJSONObject("reasoning").getString("effort"))
    }

    @Test
    fun toolConversionForcesStrictFalse() {
        val request = chatRequest().put(
            "tools",
            org.json.JSONArray().put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject().put("name", "read_file")
                        .put("parameters", JSONObject().put("type", "object"))
                )
            )
        )
        val body = ChatGptResponses.toResponsesRequest(request, "m")
        val tool = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("read_file", tool.getString("name"))
        assertFalse(tool.getBoolean("strict"))
    }

    @Test
    fun sseTranslatorMapsDeltasAndTerminalUsage() {
        val translator = ChatGptResponses.SseTranslator("chatcmpl-test", "gpt-5.1-codex")
        val created = translator.translate("""data: {"type":"response.created"}""")
        assertTrue(created.joinToString("").contains("chat.completion.chunk"))

        val deltas = translator.translate("""data: {"type":"response.output_text.delta","delta":"你"}""")
            .joinToString("")
        assertTrue(deltas.contains("你"))

        val terminal = translator.translate(
            """data: {"type":"response.completed","response":{"status":"completed",
               |"usage":{"input_tokens":11,"output_tokens":7,"total_tokens":18}}}""".trimMargin()
        ).joinToString("")
        assertTrue(terminal.contains("\"finish_reason\":\"stop\""))
        assertTrue(terminal.contains("\"total_tokens\":18"))
        assertTrue(terminal.contains("[DONE]"))
        assertEquals(18, translator.lastUsage()?.getInt("total_tokens"))
    }

    @Test
    fun toolCallEventsBecomeToolCallsWithMatchingIndex() {
        val translator = ChatGptResponses.SseTranslator("chatcmpl-t", "m")
        val added = translator.translate(
            """data: {"type":"response.output_item.added","output_index":0,
               |"item":{"type":"function_call","call_id":"call-1","name":"read_file"}}""".trimMargin()
        ).joinToString("")
        assertTrue(added.contains("\"tool_calls\""))
        assertTrue(added.contains("call-1"))

        val args = translator.translate(
            """data: {"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\"p\":1}"}"""
        ).joinToString("")
        assertTrue(args.contains("\\\"p\\\":1") || args.contains("p"))

        val terminal = translator.translate(
            """data: {"type":"response.completed","response":{"status":"completed"}}"""
        ).joinToString("")
        assertTrue(terminal.contains("tool_calls"))
    }

    @Test
    fun failedEventSurfacesMessageInsteadOfThrowing() {
        val translator = ChatGptResponses.SseTranslator("chatcmpl-f", "m")
        val frames = translator.translate(
            """data: {"type":"response.failed","response":{"error":{"message":"boom"}}}"""
        ).joinToString("")
        assertTrue(frames.contains("boom"))
        assertTrue(frames.contains("[DONE]"))
        // 已收尾，onFinish 不再重复发帧
        assertTrue(translator.onFinish().isEmpty())
    }

    @Test
    fun modelsParsingKeepsOnlyListedVisibility() {
        val raw = """{"models":[
            {"slug":"gpt-5.1-codex","visibility":"list"},
            {"slug":"gpt-hidden","visibility":"hide"},
            {"id":"gpt-plain"}
        ]}"""
        assertEquals(listOf("gpt-5.1-codex", "gpt-plain"), ChatGptResponses.parseModels(raw))
    }

    @Test
    fun usageParsingProjectsRollingWindows() {
        val raw = """{"rate_limit":{
            "primary_window":{"used_percent":42.5,"reset_at":1800000000},
            "secondary_window":{"used_percent":120.0}
        }}"""
        val usage = ChatGptResponses.parseUsage(raw)
        val primary = usage.getJSONObject("5 小时窗口")
        assertEquals(42L, primary.getLong("used"))
        assertEquals(58L, primary.getLong("remain"))
        assertEquals(1_800_000_000L, primary.getLong("reset_at"))
        // 超过 100% 要夹到 100，remain 不得为负
        assertEquals(100L, usage.getJSONObject("周窗口").getLong("used"))
        assertEquals(0L, usage.getJSONObject("周窗口").getLong("remain"))
    }
}
