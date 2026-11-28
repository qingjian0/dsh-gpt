package com.gpt.hook.api

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 一次性回环回调服务器，只为 ChatGPT OAuth 登录服务。
 *
 * 上游把 `http://localhost:1455/auth/callback` 写死在注册信息里，所以端口与路径都不可改。
 * 两个必须守住的约束：
 * - **state 必须校验**：否则别人可以把他的回调塞进来（登录劫持）；
 * - **一次性消费**：同一个 code 不允许重放，处理完立刻关端口。
 *
 * 只绑定 127.0.0.1，不监听局域网；登录完成即关，不常驻。
 */
class OAuthLoopbackServer(
    private val port: Int = ChatGptOAuth.Constants.CALLBACK_PORT,
    private val path: String = ChatGptOAuth.Constants.CALLBACK_PATH,
) {

    data class Result(val code: String, val state: String)

    private var server: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val result = AtomicReference<Result?>(null)
    private val failure = AtomicReference<String?>(null)
    private val done = AtomicBoolean(false)

    /** 绑定端口；端口被占用时返回 false（通常意味着上一次登录没关干净）。 */
    fun start(): Boolean {
        if (running.get()) return true
        return try {
            server = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
            running.set(true)
            Thread({ acceptLoop() }, "dsh-oauth-callback").apply { isDaemon = true }.start()
            true
        } catch (t: Throwable) {
            running.set(false)
            failure.set("回调端口 $port 绑定失败：${t.message.orEmpty()}")
            false
        }
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
    }

    /** 阻塞等待回调，返回 code+state；超时/关闭返回 null。 */
    fun await(timeoutMs: Long): Result? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            result.get()?.let { return it }
            if (!running.get() && !done.get()) return null
            Thread.sleep(200)
        }
        return result.get()
    }

    fun failureMessage(): String? = failure.get()

    private fun acceptLoop() {
        val socket = server
        while (running.get() && socket != null) {
            val client = try { socket.accept() } catch (t: Throwable) { break } ?: break
            Thread({ handle(client) }, "dsh-oauth-callback-req").apply { isDaemon = true }.start()
        }
    }

    private fun handle(client: Socket) {
        client.use { s ->
            try {
                s.soTimeout = 10_000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                val requestLine = reader.readLine() ?: return
                // 读掉请求头，避免回包时对端还在写
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val target = requestLine.split(" ").getOrNull(1) ?: return
                val query = target.substringAfter('?', "")
                val uriPath = target.substringBefore('?')

                if (uriPath != path) {
                    respond(s.getOutputStream(), 404, "回调路径不匹配")
                    return
                }
                if (done.getAndSet(true)) {
                    respond(s.getOutputStream(), 409, "该登录回调已被消费，请重新发起登录")
                    return
                }
                val params = parseQuery(query)
                val error = params["error"]
                if (!error.isNullOrBlank()) {
                    failure.set("上游返回错误：$error ${params["error_description"].orEmpty()}".trim())
                    respond(s.getOutputStream(), 400, "登录被拒绝：$error")
                    return
                }
                val code = params["code"]
                if (code.isNullOrBlank()) {
                    failure.set("回调里没有 code 参数")
                    respond(s.getOutputStream(), 400, "回调缺少 code")
                    return
                }
                result.set(Result(code, params["state"].orEmpty()))
                respond(s.getOutputStream(), 200, "登录完成，可以关闭此页面回到 DSH GPT")
            } catch (t: Throwable) {
                failure.set(t.message.orEmpty())
            } finally {
                stop()
            }
        }
    }

    private fun respond(out: OutputStream, status: Int, message: String) {
        val body = """<!doctype html><meta charset="utf-8"><title>DSH GPT</title>
<body style="font:16px/1.6 -apple-system,system-ui,sans-serif;padding:48px;text-align:center">
<h2>$message</h2></body>""".toByteArray(Charsets.UTF_8)
        val reason = if (status == 200) "OK" else "Error"
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        runCatching {
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.flush()
        }
    }

    companion object {
        fun parseQuery(query: String): Map<String, String> {
            if (query.isBlank()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val idx = pair.indexOf('=')
                val key = if (idx < 0) pair else pair.substring(0, idx)
                val raw = if (idx < 0) "" else pair.substring(idx + 1)
                out[decode(key)] = decode(raw)
            }
            return out
        }

        private fun decode(value: String): String =
            runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
}
