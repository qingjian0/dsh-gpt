package com.gpt.hook.hooks;

import com.gpt.hook.DynHook;
import com.gpt.hook.XF;
import com.gpt.hook.XLog;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cronet 抓包：实现类在 GmsCore 动态 dex 里，编译期拿不到，
 * 因此先 hook 可见的 CronetEngine$Builder.build()，拿到真实 engine 实例后
 * 再对它的实现类做二次动态 hook（newUrlRequestBuilder → build → 请求 URL/头）。
 */
public final class CronetSpy {

    private static final AtomicBoolean ENGINE_HOOKED = new AtomicBoolean(false);

    public static void init(ClassLoader cl) {
        Class<?> b = DynHook.cls("org.chromium.net.CronetEngine$Builder", cl);
        if (b == null) { XLog.w("no CronetEngine$Builder"); return; }
        XLog.i("hook CronetEngine$Builder.build");
        DynHook.hookAll(b, "build", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Object engine = p.getResult();
                if (engine == null) return;
                XLog.i("[cronet] engine impl = " + engine.getClass().getName());
                if (ENGINE_HOOKED.compareAndSet(false, true)) hookEngine(engine.getClass());
            }
        });
        DynHook.hookAll(b, "setUserAgent", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                XLog.i("[cronet] UA = " + p.args[0]);
            }
        });
    }

    private static void hookEngine(Class<?> ec) {
        try { XLog.i("[cronet] engine methods = " + ec.getDeclaredMethods().length); } catch (Throwable ignored) { }
        Set<?> r = XposedBridge.hookAllMethods(ec, "newUrlRequestBuilder", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.args == null || p.args.length == 0) return;
                XLog.i("[cronet] REQ " + p.args[0]);
                Object ub = p.getResult();
                if (ub == null) return;
                try { hookUrlRequestBuilder(ub.getClass()); } catch (Throwable t) { XLog.w("hook urb fail " + t); }
            }
        });
        XLog.i("[cronet] newUrlRequestBuilder hooks = " + (r == null ? 0 : r.size()));
    }

    private static final java.util.Set<String> SEEN = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private static void hookUrlRequestBuilder(Class<?> ub) {
        if (!SEEN.add(ub.getName())) return;
        XLog.i("[cronet] UrlRequest.Builder impl = " + ub.getName());
        try {
            XposedBridge.hookAllMethods(ub, "addHeader", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    XLog.i("[cronet]   hdr " + p.args[0] + ": " + trunc(String.valueOf(p.args[1])));
                }
            });
            XposedBridge.hookAllMethods(ub, "build", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object req = p.getResult();
                    if (req == null) return;
                    XLog.i("[cronet] SEND url=" + XF.get(req, "mUrl") + " headers=" + trunc(String.valueOf(XF.get(req, "mRequestHeaders"))));
                }
            });
        } catch (Throwable t) { XLog.w("urb hook fail " + t); }
    }

    private static String trunc(String s) {
        return s == null ? "null" : (s.length() > 400 ? s.substring(0, 400) + "…" : s);
    }
}
