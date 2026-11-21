package com.gpt.hook.hooks;

import com.gpt.hook.XLog;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DPoP proof 捕获：Keystore 私钥不可导出，所以 proof 只能在 ChatGPT 进程内产生。
 * 监听类名含 DPoP 的类，把返回 eyJ… 的调用结果按 htu 缓存到 files/dpop_proofs.json。
 */
public final class DPoPSign {

    /** htu -> {proof, iat, exp, ts} */
    public static final Map<String, JSONObject> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> SEEN = Collections.synchronizedSet(new HashSet<String>());
    private static volatile boolean armed;

    public static void init(ClassLoader cl) {
        if (armed) return;
        armed = true;
        try {
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object r = p.getResult();
                    if (r instanceof Class) onClass((Class<?>) r);
                }
            });
            XLog.i("[dpop] watch on");
        } catch (Throwable t) { XLog.w("[dpop] watch fail " + t); }
    }

    private static void onClass(Class<?> c) {
        String n = c.getName();
        if (n.indexOf("DPoP") < 0 && n.indexOf("Dpop") < 0 && n.indexOf("dpop") < 0) return;
        if (!SEEN.add(n)) return;
        XLog.i("[dpop] candidate " + n);
        try {
            for (final Method m : c.getDeclaredMethods()) {
                if (m.getReturnType() != String.class) continue;
                XposedBridge.hookAllMethods(c, m.getName(), new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Object r = p.getResult();
                        if (!(r instanceof String)) return;
                        String proof = (String) r;
                        if (!proof.startsWith("eyJ")) return;
                        record(proof, p.args);
                    }
                });
            }
        } catch (Throwable t) { XLog.w("[dpop] hook fail " + n + " " + t); }
    }

    private static void record(String proof, Object[] args) {
        try {
            String[] parts = proof.split("\\.");
            if (parts.length < 2) return;
            String json = new String(android.util.Base64.decode(
                    parts[1].replace('-', '+').replace('_', '/'), android.util.Base64.DEFAULT), "UTF-8");
            JSONObject c = new JSONObject(json);
            String htu = c.optString("htu", "");
            String htm = c.optString("htm", "");
            long iat = c.optLong("iat", 0);
            JSONObject e = new JSONObject();
            e.put("proof", proof);
            e.put("htm", htm);
            e.put("htu", htu);
            e.put("iat", iat);
            e.put("ts", System.currentTimeMillis());
            if (!htu.isEmpty()) CACHE.put(htm + " " + htu, e);
            flush();
            XLog.i("[dpop] captured " + htm + " " + htu + " iat=" + iat);
        } catch (Throwable t) { XLog.w("[dpop] parse fail " + t); }
    }

    private static synchronized void flush() {
        try {
            JSONObject all = new JSONObject();
            for (Map.Entry<String, JSONObject> e : CACHE.entrySet()) all.put(e.getKey(), e.getValue());
            try (FileWriter w = new FileWriter(new File(XLog.dir(), "dpop_proofs.json"), false)) {
                w.write(all.toString(2));
            }
        } catch (Throwable ignored) { }
    }

    /** 取可用 proof：同 htm+htu 且在 280s 窗口内 */
    public static String proofFor(String htm, String htu) {
        JSONObject e = CACHE.get(htm + " " + htu);
        if (e == null) return null;
        long age = (System.currentTimeMillis() - e.optLong("ts", 0)) / 1000L;
        if (age > 280) return null;
        return e.optString("proof", null);
    }

    public static String dump() {
        JSONObject all = new JSONObject();
        for (Map.Entry<String, JSONObject> e : CACHE.entrySet()) {
            try { all.put(e.getKey(), e.getValue().optString("proof")); } catch (Throwable ignored) { }
        }
        return all.toString();
    }
}
