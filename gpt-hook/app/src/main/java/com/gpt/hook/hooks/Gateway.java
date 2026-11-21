package com.gpt.hook.hooks;

import com.gpt.hook.Cfg;
import com.gpt.hook.XLog;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 设备内反代网关：127.0.0.1:PORT → https://android.chat.openai.com
 * 注入 Authorization(DPoP) / DPoP proof / oai-* 头，proof 由本进程 Keystore 现场签。
 * 手写 HTTP/1.1，支持 SSE 流式（Connection: close 定界）。
 */
public final class Gateway {

    public static final String BASE = "https://android.chat.openai.com";
    private static volatile boolean running;
    private static int port;
    private static String kitToken = "kit";
    private static ServerSocket server;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(6);
    private static Map<String, String> kitHeaders = new LinkedHashMap<>();
    private static String accessToken;
    private static long kitMtime = -1;
    private static java.net.Proxy proxy = java.net.Proxy.NO_PROXY;

    public static synchronized void start(int p, String tok) {
        if (running) { XLog.i("[gw] already running " + port); return; }
        port = p;
        kitToken = (tok == null || tok.isEmpty()) ? "kit" : tok;
        try {
            loadKit();
            server = new ServerSocket(port, 16, InetAddress.getByName("0.0.0.0"));
            running = true;
            Thread t = new Thread(() -> {
                XLog.i("[gw] listening http://0.0.0.0:" + port);
                while (running) {
                    try {
                        final Socket s = server.accept();
                        POOL.execute(() -> handle(s));
                    } catch (Throwable e) {
                        if (running) XLog.w("[gw] accept " + e);
                    }
                }
            }, "gpt-gateway");
            t.setDaemon(true);
            t.start();
        } catch (Throwable e) {
            running = false;
            XLog.e("[gw] start fail", e);
        }
    }

    public static synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Throwable ignored) { }
        server = null;
        XLog.i("[gw] stopped");
    }

    public static boolean isRunning() { return running; }
    public static int port() { return port; }

    private static void setupProxy() {
        String p = Cfg.s("upstream_proxy", "");
        if (p == null || p.isEmpty()) { proxy = java.net.Proxy.NO_PROXY; return; }
        try {
            String h = p;
            int port = 7890;
            if (h.startsWith("http://")) h = h.substring(7);
            int i = h.lastIndexOf(':');
            if (i > 0) { port = Integer.parseInt(h.substring(i + 1)); h = h.substring(0, i); }
            proxy = new java.net.Proxy(java.net.Proxy.Type.HTTP, new java.net.InetSocketAddress(h, port));
            XLog.i("[gw] upstream proxy " + h + ":" + port);
        } catch (Throwable t) { proxy = java.net.Proxy.NO_PROXY; XLog.w("[gw] proxy parse fail " + t); }
    }

    /** kit_auth.json: {"access_token":"…","headers":{"oai-did":"…"}} */
    private static void loadKit() {
        File f = new File(XLog.dir(), "kit_auth.json");
        if (!f.exists()) { XLog.w("[gw] no kit_auth.json"); return; }
        if (f.lastModified() == kitMtime) return;
        kitMtime = f.lastModified();
        try {
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int n = in.read(b); in.close();
            JSONObject j = new JSONObject(new String(b, 0, Math.max(n, 0), Charset.forName("UTF-8")));
            accessToken = j.optString("access_token", null);
            JSONObject h = j.optJSONObject("headers");
            Map<String, String> m = new LinkedHashMap<>();
            if (h != null) {
                for (Iterator<String> it = h.keys(); it.hasNext(); ) {
                    String k = it.next();
                    m.put(k, h.optString(k, ""));
                }
            }
            kitHeaders = m;
            XLog.i("[gw] kit loaded token=" + (accessToken != null) + " headers=" + m.size());
        } catch (Throwable e) { XLog.e("[gw] kit load fail", e); }
    }

    private static void handle(Socket s) {
        try {
            s.setSoTimeout(60000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, Charset.forName("ISO-8859-1")));
            String line = r.readLine();
            if (line == null) { s.close(); return; }
            String[] first = line.split(" ");
            if (first.length < 2) { s.close(); return; }
            String method = first[0].toUpperCase(Locale.US);
            String target = first[1];

            Map<String, String> hdrs = new LinkedHashMap<>();
            while ((line = r.readLine()) != null && !line.isEmpty()) {
                int i = line.indexOf(':');
                if (i > 0) hdrs.put(line.substring(0, i).trim().toLowerCase(Locale.US), line.substring(i + 1).trim());
            }
            String given = hdrs.get("x-kit-token");
            if (!kitToken.equals(given)) {
                reply(out, 403, "application/json", "{\"error\":\"bad kit token\"}".getBytes("UTF-8"), -1);
                s.close(); return;
            }
            if ("/_ping".equals(target)) {
                loadKit(); setupProxy();
                reply(out, 200, "application/json", ("{\"ok\":true,\"port\":" + port
                        + ",\"token\":" + (accessToken != null)
                        + ",\"headers\":" + kitHeaders.size()
                        + ",\"proxy\":" + proxy.type()
                        + ",\"proofs\":" + DPoPSign.CACHE.size() + "}").getBytes("UTF-8"), -1);
                s.close(); return;
            }
            if (target.startsWith("/_dpop")) {
                reply(out, 200, "application/json", DPoPSign.dump().getBytes("UTF-8"), -1);
                s.close(); return;
            }
            loadKit();
            setupProxy();

            int len = 0;
            try { len = Integer.parseInt(hdrs.getOrDefault("content-length", "0")); } catch (Throwable ignored) { }
            byte[] body = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(body, off, len - off);
                if (n < 0) break;
                off += n;
            }

            String url = gatewayUrl(target);
            XLog.i("[gw] -> " + method + " " + url);
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(proxy);
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(45000);
            c.setDoInput(true);
            if (len > 0) { c.setDoOutput(true); c.setFixedLengthStreamingMode(len); }

            // 注入凭证
            if (accessToken != null) c.setRequestProperty("Authorization", "DPoP " + accessToken);
            String dpop = DPoPSign.proofFor(method, url);
            if (dpop != null) c.setRequestProperty("DPoP", dpop);
            for (Map.Entry<String, String> e : kitHeaders.entrySet()) {
                String k = e.getKey();
                if (k.equalsIgnoreCase("authorization") || k.equalsIgnoreCase("dpop")) continue;
                try { c.setRequestProperty(k, e.getValue()); } catch (Throwable ignored) { }
            }
            // 透传客户端的业务头（白名单）
            for (Map.Entry<String, String> e : hdrs.entrySet()) {
                String k = e.getKey();
                if (k.startsWith("oai-") || k.startsWith("x-oai-") || k.equals("content-type")
                        || k.equals("accept") || k.equals("user-agent")) {
                    try { c.setRequestProperty(k, e.getValue()); } catch (Throwable ignored) { }
                }
            }
            if (c.getRequestProperty("Content-Type") == null && len > 0)
                c.setRequestProperty("Content-Type", "application/json");
            if (c.getRequestProperty("User-Agent") == null)
                c.setRequestProperty("User-Agent", "ChatGPT/1.2026.321 (Android; 15; 22081212C)");

            if (len > 0) {
                OutputStream co = c.getOutputStream();
                co.write(body);
                co.flush();
                co.close();
            }

            int code = c.getResponseCode();
            XLog.i("[gw] <- " + code + " " + c.getContentType());
            String ct = c.getContentType();
            out.write(("HTTP/1.1 " + code + " " + c.getResponseMessage() + "\r\n").getBytes("ISO-8859-1"));
            out.write(("Content-Type: " + (ct == null ? "application/json" : ct) + "\r\n").getBytes("ISO-8859-1"));
            if (dpop == null) out.write("X-Kit-DPoP: missing\r\n".getBytes("ISO-8859-1"));
            out.write("Connection: close\r\n\r\n".getBytes("ISO-8859-1"));
            out.flush();

            InputStream rs = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (rs != null) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = rs.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
                rs.close();
            }
            c.disconnect();
            s.close();
        } catch (Throwable e) {
            XLog.w("[gw] handler " + e);
            try { s.close(); } catch (Throwable ignored) { }
        }
    }

    private static String gatewayUrl(String target) {
        String t = target;
        if (t.startsWith("/backend-api/") || t.startsWith("/backend-anon/")
                || t.startsWith("/public-api/") || t.startsWith("/realtime/")) return BASE + t;
        if (t.startsWith("/")) return BASE + "/backend-api" + t;
        return BASE + "/" + t;
    }

    private static void reply(OutputStream out, int code, String ct, byte[] body, int len) throws Exception {
        out.write(("HTTP/1.1 " + code + " X\r\nContent-Type: " + ct + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
        out.write(body);
        out.flush();
    }
}
