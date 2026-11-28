package com.gpt.hook.api

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/** One-shot loopback callback listener for the system-browser SIWC flow. */
internal class ChatGptPlanLoopbackServer private constructor(
    val attempt: ChatGptPlanOAuth.Attempt,
    private val server: ServerSocket,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    /** The socket is already bound before this URI is opened in the system browser. */
    fun start(): String = attempt.redirectUri

    /** Blocking call; invoke on a worker thread, never the Android main thread. */
    fun await(timeoutMillis: Long = DEFAULT_TIMEOUT_MS): ChatGptPlanOAuth.Callback {
        require(timeoutMillis > 0)
        val deadline = System.currentTimeMillis() + timeoutMillis
        server.soTimeout = 1_000
        while (!closed.get() && System.currentTimeMillis() < deadline) {
            val socket = try {
                server.accept()
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (closed.get()) throw IllegalStateException("OAuth callback listener was cancelled")
                throw e
            }
            try {
                socket.soTimeout = 5_000
                if (!socket.inetAddress.isLoopbackAddress) {
                    respond(socket, 403, "Only loopback callbacks are accepted.")
                    continue
                }
                val input = socket.getInputStream()
                val requestLine = readAsciiLine(input, MAX_REQUEST_LINE_BYTES) ?: ""
                var headerBytes = 0
                var headerCount = 0
                while (true) {
                    val line = readAsciiLine(input, MAX_HEADER_LINE_BYTES) ?: break
                    headerBytes += line.length + 2
                    headerCount++
                    require(headerBytes <= MAX_HEADER_BYTES && headerCount <= MAX_HEADER_COUNT) {
                        "OAuth callback headers exceed the local request limit"
                    }
                    if (line.isEmpty()) break
                }
                val parts = requestLine.split(' ')
                if (parts.size < 2 || parts[0] != "GET") {
                    respond(socket, 405, "Use GET for the OAuth callback.")
                    continue
                }
                val target = runCatching { URI("http://127.0.0.1:${server.localPort}" + parts[1]) }.getOrNull()
                if (target == null || target.path != ChatGptPlanOAuth.CALLBACK_PATH) {
                    respond(socket, 404, "OAuth callback path not found.")
                    continue
                }
                val callbackUrl = "http://127.0.0.1:${server.localPort}" + parts[1]
                try {
                    val callback = ChatGptPlanOAuth.validateCallback(
                        callbackUrl,
                        attempt.state,
                        attempt.redirectUri,
                        if (attempt.isFirstRegistration) null else attempt.clientId,
                    )
                    respond(socket, 200, "Sign-in received. Return to DSH GPT.")
                    close()
                    return callback
                } catch (error: IllegalArgumentException) {
                    respond(socket, 400, "The sign-in callback could not be validated. Restart sign-in.")
                    // Ignore malformed or state-mismatched local requests; keep waiting for the real callback.
                } catch (error: IllegalStateException) {
                    // A valid-state OAuth denial is terminal and must not reuse this state.
                    respond(socket, 400, "Sign-in was not completed. Return to DSH GPT and try again.")
                    close()
                    throw error
                }
            } finally {
                runCatching { socket.close() }
            }
        }
        close()
        throw IllegalStateException("ChatGPT sign-in timed out")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
    }

    private fun readAsciiLine(input: InputStream, maxBytes: Int): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
            if (value == 10) {
                val line = bytes.toByteArray()
                val length = if (line.isNotEmpty() && line.last() == 13.toByte()) line.size - 1 else line.size
                return String(line, 0, length, StandardCharsets.ISO_8859_1)
            }
            if (bytes.size() >= maxBytes) throw IllegalArgumentException("OAuth callback request line is too long")
            bytes.write(value)
        }
    }

    private fun respond(socket: Socket, status: Int, message: String) {
        val body = """<!doctype html><meta charset="utf-8"><title>DSH GPT</title><main style="font:16px system-ui;padding:40px;text-align:center"><h2>$message</h2></main>"""
            .toByteArray(StandardCharsets.UTF_8)
        val reason = if (status == 200) "OK" else "Error"
        val headers = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Cache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        runCatching {
            socket.getOutputStream().apply {
                write(headers.toByteArray(StandardCharsets.ISO_8859_1))
                write(body)
                flush()
            }
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 15 * 60 * 1000L
        private const val MAX_REQUEST_LINE_BYTES = 8 * 1024
        private const val MAX_HEADER_LINE_BYTES = 4 * 1024
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_HEADER_COUNT = 64

        /** Bind an ephemeral loopback port first, then freeze its exact redirect URI into the attempt. */
        fun create(
            hostId: String,
            issuedClientId: String? = null,
            port: Int = 0,
        ): ChatGptPlanLoopbackServer {
            // port 必须与首次注册时一致：上游逐字符校验 redirect_uri，
            // 换端口会让已注册的 oaiapp_... client 在换 token 时报 400。
            val socket = ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"))
            return try {
                val redirect = "http://127.0.0.1:${socket.localPort}${ChatGptPlanOAuth.CALLBACK_PATH}"
                val attempt = ChatGptPlanOAuth.createAttempt(redirect, hostId, issuedClientId)
                ChatGptPlanLoopbackServer(attempt, socket)
            } catch (error: Throwable) {
                runCatching { socket.close() }
                throw error
            }
        }
    }
}
