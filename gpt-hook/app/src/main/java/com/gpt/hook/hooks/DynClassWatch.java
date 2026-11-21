package com.gpt.hook.hooks;

import com.gpt.hook.DynHook;
import com.gpt.hook.XF;
import com.gpt.hook.XLog;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 运行时类监听：hook ClassLoader.loadClass，在动态 dex（GmsCore cronet 等）里的类
 * 首次加载时立刻挂上 hook。绕开"实现类编译期不可见"的问题。
 */
public final class DynClassWatch {

    private static final Set<String> SEEN = Collections.synchronizedSet(new HashSet<String>());
    private static final ThreadLocal<Boolean> BUSY = new ThreadLocal<Boolean>() {
        @Override protected Boolean initialValue() { return false; }
    };

    private static final String[] WANT = {
            "org.chromium.net.impl.CronetUrlRequest",
            "org.chromium.net.impl.CronetEngineBuilderImpl",
            "org.chromium.net.impl.CronetUrlRequestContext"
    };

    public static void init() {
        XC_MethodHook h = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Object r = p.getResult();
                if (!(r instanceof Class)) return;
                if (Boolean.TRUE.equals(BUSY.get())) return;
                BUSY.set(true);
                try { check((Class<?>) r); } catch (Throwable ignored) { }
                finally { BUSY.set(false); }
            }
        };
        try {
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", h);
            XLog.i("[cwatch] ClassLoader.loadClass hooked");
        } catch (Throwable t) { XLog.w("[cwatch] hook fail " + t); }
    }

    private static void check(Class<?> c) {
        String n = c.getName();
        boolean want = false;
        for (String w : WANT) if (n.equals(w)) { want = true; break; }
        if (!want || !SEEN.add(n)) return;
        XLog.i("[cwatch] LOADED " + n);
        if (n.endsWith("CronetUrlRequest")) hookRequest(c);
        if (n.endsWith("CronetEngineBuilderImpl")) hookBuilder(c);
    }

    private static void hookRequest(Class<?> c) {
        try {
            XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object o = p.thisObject;
                    XLog.i("[cwatch] REQ url=" + XF.get(o, "mUrl") + " method=" + XF.get(o, "mMethod"));
                    Object hdrs = XF.get(o, "mRequestHeaders");
                    if (hdrs != null) XLog.i("[cwatch]   headers=" + String.valueOf(hdrs));
                }
            });
            XposedBridge.hookAllMethods(c, "start", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    XLog.i("[cwatch] START " + XF.get(p.thisObject, "mUrl"));
                }
            });
            XLog.i("[cwatch] request hooked on " + c.getName());
        } catch (Throwable t) { XLog.w("[cwatch] req hook fail " + t); }
    }

    private static void hookBuilder(Class<?> c) {
        try {
            XposedBridge.hookAllMethods(c, "build", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    XLog.i("[cwatch] BUILT engine " + (p.getResult() == null ? "null" : p.getResult().getClass().getName()));
                }
            });
        } catch (Throwable t) { XLog.w("[cwatch] builder hook fail " + t); }
    }
}
