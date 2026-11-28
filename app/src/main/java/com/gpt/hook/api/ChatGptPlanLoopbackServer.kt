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
    /**
     * 处理一次回调就返回 HTML 响应体。返回 null 表示"还没完，继续等待下一次回调"。
     *
     * 首次注册后必须用新签发的 client_id 再授权一次，而那次跳转**不能由 App 发起**
     * （Android 10+ 禁止后台启动 Activity，浏览器根本不会弹出）。改为在响应页里
     * 用 location.replace 让浏览器自己跳过去：会话、登录态都保持，用户只需再确认一次。
     */
    data class ServeResult(val html: String, val finished: Boolean)

    fun serve(handler: (ChatGptPlanOAuth.Callback) -> ServeResult, timeoutMillis: Long = DEFAULT_TIMEOUT_MS): ChatGptPlanOAuth.Callback {
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
                val callback = try {
                    ChatGptPlanOAuth.validateCallback(
                        callbackUrl,
                        attempt.state,
                        attempt.redirectUri,
                        if (attempt.isFirstRegistration) null else attempt.clientId,
                    )
                } catch (error: IllegalArgumentException) {
                    respond(socket, 400, "The sign-in callback could not be validated. Restart sign-in.")
                    continue
                } catch (error: IllegalStateException) {
                    respond(socket, 400, "Sign-in was not completed. Return to DSH GPT and try again.")
                    close()
                    throw error
                }
                val outcome = handler(callback)
                respondRaw(socket, 200, outcome.html)
                if (!outcome.finished) {
                    // 继续等待下一次回调（注册完成后由浏览器自动跳转重授权）
                    continue
                }
                close()
                return callback
            } finally {
                runCatching { socket.close() }
            }
        }
        close()
        throw IllegalStateException("ChatGPT sign-in timed out")
    }

    /** 兼容旧调用：只等一次回调。 */
    fun await(timeoutMillis: Long = DEFAULT_TIMEOUT_MS): ChatGptPlanOAuth.Callback =
        serve({ ServeResult("Sign-in received. Return to DSH GPT.", true) }, timeoutMillis)

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

    private fun respondRaw(socket: Socket, status: Int, html: String) {
        val body = html.toByteArray(StandardCharsets.UTF_8)
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
            // 显式 bind 并开启 SO_REUSEADDR：注册完成后要用同一端口立刻重新监听，
            // 直接 ServerSocket(port, ...) 在部分内核上会撞 Address already in use。
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4)
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
