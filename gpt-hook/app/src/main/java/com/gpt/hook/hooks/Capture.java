package com.gpt.hook.hooks;

import com.gpt.hook.DynHook;
import com.gpt.hook.XF;
import com.gpt.hook.XLog;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 请求捕获：Cronet 动态实现类 + okhttp + HttpURLConnection 三路兜底。
 * 命中后写 files/capture.jsonl，同时把 header 快照落到 files/headers.last.json 供网关复用。
 */
public final class Capture {

    private static final Set<String> SEEN = Collections.synchronizedSet(new HashSet<String>());
    private static volatile boolean armed;

    public static void init(ClassLoader cl) {
        if (armed) return;
        armed = true;
        XLog.i("[cap] init");

        // 1) okhttp（若存在）
        Class<?> rb = DynHook.cls("okhttp3.Request$Builder", cl);
        if (rb != null) {
            DynHook.hookAll(rb, "addHeader", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    XLog.i("[cap] okhttp hdr " + p.args[0] + ": " + shortStr(String.valueOf(p.args[1]), 160));
                }
            });
        }
        // 2) HttpURLConnection
        try {
            XposedBridge.hookAllMethods(java.net.URLConnection.class, "setRequestProperty", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    String k = String.valueOf(p.args[0]), v = shortStr(String.valueOf(p.args[1]), 160);
                    if (k.toLowerCase().startsWith("oai") || k.equalsIgnoreCase("authorization") || k.equalsIgnoreCase("dpop"))
                        XLog.i("[cap] urlconn hdr " + k + ": " + v);
                }
            });
        } catch (Throwable ignored) { }
        // 3) Cronet 实现类（动态 dex）
        try {
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object r = p.getResult();
                    if (r instanceof Class) onClass((Class<?>) r);
                }
            });
            XLog.i("[cap] classloader watch on");
        } catch (Throwable t) { XLog.w("[cap] watch fail " + t); }
    }

    private static void onClass(Class<?> c) {
        String n = c.getName();
        if (n.indexOf("Cronet") < 0) return;
        if (n.indexOf("CronetUrlRequest") < 0 || n.indexOf('$') >= 0) return;
        if (!SEEN.add(n)) return;
        XLog.i("[cap] cronet impl " + n);
        try {
            XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { snap(p.thisObject, "ctor"); }
            });
            XposedBridge.hookAllMethods(c, "start", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { snap(p.thisObject, "start"); }
            });
            XposedBridge.hookAllMethods(c, "onSucceeded", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object info = p.args != null && p.args.length > 1 ? p.args[1] : null;
                    if (info != null) XLog.i("[cap] resp " + shortStr(String.valueOf(info), 300));
                }
            });
        } catch (Throwable t) { XLog.w("[cap] hook fail " + t); }
    }

    /** 反射 dump 请求对象：url / method / headers / body */
    private static void snap(Object req, String how) {
        try {
            Object url = XF.get(req, "mUrl");
            Object method = XF.get(req, "mMethod");
            Object hdrs = XF.get(req, "mRequestHeaders");
            JSONObject j = new JSONObject();
            j.put("t", System.currentTimeMillis());
            j.put("src", how);
            j.put("url", String.valueOf(url));
            j.put("method", String.valueOf(method));
            if (hdrs instanceof Map) {
                JSONObject h = new JSONObject();
                StringBuilder sb = new StringBuilder();
                for (Object o : ((Map<?, ?>) hdrs).entrySet()) {
                    Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
                    String k = String.valueOf(e.getKey()), v = String.valueOf(e.getValue());
                    h.put(k, v);
                    sb.append(k).append(": ").append(v).append('\n');
                }
                j.put("headers", h);
                writeFile("headers.last.json", h.toString(2));
                if (!sb.toString().contains("oai-did")) { /* 首次可能尚未注入 */ }
            }
            writeJsonl(j);
            XLog.i("[cap] " + how + " " + method + " " + url + " hdrs=" + (hdrs == null ? 0 : ((Map<?, ?>) hdrs).size()));
        } catch (Throwable t) { XLog.w("[cap] snap fail " + t); }
    }

    private static synchronized void writeJsonl(JSONObject j) {
        try (FileWriter w = new FileWriter(new File(XLog.dir(), "capture.jsonl"), true)) {
            w.write(j.toString() + "\n");
        } catch (Throwable ignored) { }
    }

    public static synchronized void writeFile(String name, String content) {
        try (FileWriter w = new FileWriter(new File(XLog.dir(), name), false)) {
            w.write(content);
        } catch (Throwable ignored) { }
    }

    private static String shortStr(String s, int n) {
        if (s == null) return "null";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
