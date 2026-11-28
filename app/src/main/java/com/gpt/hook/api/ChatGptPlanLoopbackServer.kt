package com.gpt.hook.api

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/** One-shot loopback callback listener for the system-browser SIWC flow. */
internal class ChatGptPlanLoopbackServer(
    private val attempt: ChatGptPlanOAuth.Attempt,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private var server: ServerSocket? = null

    val redirectUri: String
        get() = attempt.redirectUri

    @Synchronized
    fun start(): String {
        check(server == null) { "OAuth callback listener is already started" }
        val requested = URI(attempt.redirectUri)
        require(requested.scheme == "http" && requested.host == "127.0.0.1" &&
            requested.path == ChatGptPlanOAuth.CALLBACK_PATH && requested.port in 1..65535)
        val socket = ServerSocket(requested.port, 4, InetAddress.getByName("127.0.0.1"))
        socket.soTimeout = 1_000
        server = socket
        return attempt.redirectUri
    }

    /** Blocking call; invoke on a worker thread, never the Android main thread. */
    fun await(timeoutMillis: Long = DEFAULT_TIMEOUT_MS): ChatGptPlanOAuth.Callback {
        require(timeoutMillis > 0)
        val listener = checkNotNull(server) { "OAuth callback listener is not started" }
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!closed.get() && System.currentTimeMillis() < deadline) {
            val client = try {
                listener.accept()
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (closed.get()) throw IllegalStateException("OAuth callback listener was cancelled")
                throw e
            }
            client.use { socket ->
                socket.soTimeout = 5_000
                if (!socket.inetAddress.isLoopbackAddress) {
                    respond(socket, 403, "Only loopback callbacks are accepted.")
                    continue
                }
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
                val requestLine = reader.readLine().orEmpty()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val parts = requestLine.split(' ')
                if (parts.size < 2 || parts[0] != "GET") {
                    respond(socket, 405, "Use GET for the OAuth callback.")
                    continue
                }
                val target = runCatching { URI("http://127.0.0.1:${listener.localPort}" + parts[1]) }.getOrNull()
                if (target == null || target.path != ChatGptPlanOAuth.CALLBACK_PATH) {
                    respond(socket, 404, "OAuth callback path not found.")
                    continue
                }
                val callbackUrl = "http://127.0.0.1:${listener.localPort}" + parts[1]
                return try {
                    val callback = ChatGptPlanOAuth.validateCallback(
                        callbackUrl,
                        attempt.state,
                        attempt.redirectUri,
                        if (attempt.isFirstRegistration) null else attempt.clientId,
                    )
                    respond(socket, 200, "Sign-in received. Return to DSH GPT.")
                    close()
                    callback
                } catch (error: IllegalArgumentException) {
                    respond(socket, 400, "The sign-in callback could not be validated. Restart sign-in.")
                    // Do not consume the one-shot listener for a malformed local request.
                    continue
                } catch (error: IllegalStateException) {
                    // A valid-state OAuth denial is terminal and must not be retried with this state.
                    respond(socket, 400, "Sign-in was not completed. Return to DSH GPT and try again.")
                    close()
                    throw error
                }
            }
        }
        close()
        throw IllegalStateException("ChatGPT sign-in timed out")
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server?.close() }
        server = null
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

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 3 * 60 * 1000L
    }
}
