package com.gpt.hook.api

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Network boundary for first-party SIWC discovery, token exchange and model discovery. */
internal class ChatGptPlanHttp(
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    data class HttpResult(val status: Int, val body: String, val requestId: String?)
    data class ProviderMetadata(
        val issuer: String,
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val jwksUri: String,
        val revocationEndpoint: String?,
    )
    data class Model(val slug: String, val displayName: String)

    fun discover(): ProviderMetadata {
        val result = request(
            URL(ChatGptPlanOAuth.DISCOVERY_ENDPOINT),
            method = "GET",
            accept = "application/json",
        )
        require(result.status in 200..299) { "OpenAI sign-in discovery failed (HTTP ${result.status})" }
        val json = JSONObject(result.body)
        val issuer = json.optString("issuer")
        val authorization = json.optString("authorization_endpoint")
        val token = json.optString("token_endpoint")
        val jwks = json.optString("jwks_uri")
        val revocation = json.optString("revocation_endpoint").takeIf { it.isNotBlank() }
        require(issuer == ChatGptPlanOAuth.ISSUER) { "OpenAI sign-in issuer mismatch" }
        requireEndpoint(authorization, "/api/accounts/authorize")
        requireEndpoint(token, "/api/accounts/oauth/token")
        requireEndpoint(jwks, null)
        if (revocation != null) requireEndpoint(revocation, null)
        return ProviderMetadata(issuer, authorization, token, jwks, revocation)
    }

    fun exchangeCode(attempt: ChatGptPlanOAuth.Attempt, callback: ChatGptPlanOAuth.Callback): HttpResult {
        val metadata = discover()
        return postForm(metadata.tokenEndpoint, ChatGptPlanOAuth.codeExchangeForm(attempt, callback))
    }

    fun refresh(clientId: String, refreshToken: String): HttpResult {
        val metadata = discover()
        return postForm(metadata.tokenEndpoint, ChatGptPlanOAuth.refreshForm(clientId, refreshToken))
    }

    fun fetchJwks(metadata: ProviderMetadata): String {
        val result = request(URL(metadata.jwksUri), "GET", accept = "application/json")
        require(result.status in 200..299) { "OpenAI signing keys are temporarily unavailable (HTTP ${result.status})" }
        val json = JSONObject(result.body)
        require(json.optJSONArray("keys") != null) { "OpenAI signing-key response is invalid" }
        return result.body
    }

    fun listModels(accessToken: String): List<Model> {
        require(accessToken.isNotBlank())
        val connection = connectionFactory(URL("https://api.openai.com/v1/models"))
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        connection.setRequestProperty("Accept", "application/json")
        val result = read(connection)
        require(result.status in 200..299) { "ChatGPT model catalog failed (HTTP ${result.status})" }
        return parseModels(result.body)
    }

    fun parseModels(raw: String): List<Model> {
        val models = JSONObject(raw).optJSONArray("models")
            ?: throw IllegalArgumentException("ChatGPT returned an invalid model catalog")
        val result = ArrayList<Model>()
        for (i in 0 until models.length()) {
            val item = models.optJSONObject(i) ?: continue
            if (item.optString("visibility") != "list") continue
            val slug = item.optString("slug")
            val name = item.optString("display_name")
            require(slug.isNotBlank() && slug.length <= 200 && name.isNotBlank() && name.length <= 200) {
                "ChatGPT returned an incomplete model entry"
            }
            result += Model(slug, name)
        }
        return result
    }

    private fun postForm(endpoint: String, form: String): HttpResult {
        val connection = connectionFactory(URL(endpoint))
        connection.requestMethod = "POST"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        val bytes = form.toByteArray(StandardCharsets.UTF_8)
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }
        return read(connection)
    }

    private fun request(url: URL, method: String, accept: String): HttpResult {
        val connection = connectionFactory(url)
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", accept)
        return read(connection)
    }

    private fun read(connection: HttpURLConnection): HttpResult {
        try {
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val output = ByteArrayOutputStream()
            stream?.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_RESPONSE_BYTES) { "OpenAI response exceeds the size limit" }
                    output.write(buffer, 0, count)
                }
            }
            return HttpResult(status, String(output.toByteArray(), StandardCharsets.UTF_8), connection.getHeaderField("x-request-id"))
        } finally {
            connection.disconnect()
        }
    }

    private fun requireEndpoint(value: String, requiredPath: String?) {
        val url = URL(value)
        require(url.protocol == "https" && url.host == "auth.openai.com" && url.userInfo == null && url.port in listOf(-1, 443)) {
            "OpenAI sign-in metadata contains an untrusted endpoint"
        }
        if (requiredPath != null) require(url.path == requiredPath) { "OpenAI sign-in endpoint path mismatch" }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_RESPONSE_BYTES = 1_048_576
    }
}
