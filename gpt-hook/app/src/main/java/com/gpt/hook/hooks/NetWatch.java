package com.gpt.hook.hooks;

import com.gpt.hook.Cfg;
import com.gpt.hook.DynHook;
import com.gpt.hook.XLog;
import com.gpt.hook.XF;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.lang.reflect.Method;
import java.util.List;

/** SSL Pinning 绕过 + 流量记录（OkHttp / Cronet / Conscrypt） */
public final class NetWatch {

    private static final int MAX_BODY = 2048;

    public static void init(ClassLoader cl) {
        boolean dump = Cfg.on("dump_requests");

        Class<?> pinner = DynHook.cls("okhttp3.CertificatePinner", cl);
        if (pinner != null) {
            for (Method m : pinner.getDeclaredMethods()) {
                if (!m.getName().equals("check")) continue;
                try { XposedBridge.hookAllMethods(pinner, "check", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(null); }
                }); } catch (Throwable ignored) { }
                break;
            }
            XLog.i("okhttp CertificatePinner bypassed");
        }

        Class<?> okhv = DynHook.cls("okhttp3.internal.tls.OkHostnameVerifier", cl);
        if (okhv != null) {
            for (String m : new String[]{"verify", "verifyHostname"}) {
                DynHook.hookAll(okhv, m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(true); }
                });
            }
        }

        Class<?> tmi = DynHook.cls("com.android.org.conscrypt.TrustManagerImpl", cl);
        if (tmi != null) {
            DynHook.hookAll(tmi, "verifyChain", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.getThrowable() != null) {
                        p.setThrowable(null);
                        p.setResult(p.args[0]);
                    }
                }
            });
            XLog.i("Conscrypt TrustManagerImpl bypassed");
        }

        Class<?> dvm = DynHook.cls("com.android.org.conscrypt.TrustManagerImpl", cl);
        if (dvm == null) {
            Class<?> jss = DynHook.cls("javax.net.ssl.X509TrustManager", cl);
            if (jss != null) XLog.i("fallback TrustManager 已跳过");
        }

        if (!dump) { XLog.i("net dump off"); return; }

        Class<?> realCall = DynHook.cls("okhttp3.RealCall", cl);
        if (realCall != null) {
            XC_MethodHook h = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { dumpResp(p.getResult()); }
            };
            DynHook.hookAll(realCall, "execute", h);
            DynHook.hookAll(realCall, "getResponseWithInterceptorChain", h);
            XLog.i("okhttp dump on");
        }
        Class<?> async = DynHook.cls("okhttp3.RealCall$AsyncCall", cl);
        if (async != null) {
            DynHook.hookAll(async, "run", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try { dumpResp(XF.get(p.thisObject, "response")); } catch (Throwable ignored) { }
                }
            });
        }

        Class<?> cronet = DynHook.cls("org.chromium.net.impl.CronetUrlRequest", cl);
        if (cronet != null) {
            try {
                XposedBridge.hookAllConstructors(cronet, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        for (java.lang.reflect.Field f : p.thisObject.getClass().getDeclaredFields()) {
                            f.setAccessible(true);
                            try {
                                Object v = f.get(p.thisObject);
                                if (v instanceof String && ((String) v).startsWith("http")) {
                                    XLog.i("[cronet] " + v); break;
                                }
                            } catch (Throwable ignored) { }
                        }
                    }
                });
                DynHook.hookAll(cronet, "start", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) { XLog.i("[cronet] start"); }
                });
                XLog.i("cronet dump on");
            } catch (Throwable t) { XLog.w("cronet hook miss"); }
        }
    }

    private static void dumpResp(Object resp) {
        if (resp == null) return;
        try {
            Object code = XposedHelpers.callMethod(resp, "code");
            Object req = XposedHelpers.callMethod(resp, "request");
            Object url = XposedHelpers.callMethod(req, "url");
            StringBuilder sb = new StringBuilder("[http] ").append(code).append(' ').append(url);
            if (Cfg.on("dump_headers")) {
                try {
                    Object hdrs = XposedHelpers.callMethod(req, "headers");
                    sb.append("\n   reqH: ").append(String.valueOf(hdrs).replace('\n', '|'));
                    Object rh = XposedHelpers.callMethod(resp, "headers");
                    sb.append("\n   resH: ").append(String.valueOf(rh).replace('\n', '|'));
                } catch (Throwable ignored) { }
            }
            try {
                Object body = XposedHelpers.callMethod(resp, "peekBody", new Class[]{long.class}, MAX_BODY);
                Object s = XposedHelpers.callMethod(body, "string");
                sb.append("\n   body: ").append(s);
            } catch (Throwable ignored) { }
            XLog.i(sb.toString());
        } catch (Throwable t) { XLog.w("dumpResp " + t); }
    }

    static { }
}
