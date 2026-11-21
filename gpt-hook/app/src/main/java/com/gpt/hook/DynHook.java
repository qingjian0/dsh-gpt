package com.gpt.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Iterator;

/**
 * 动态 hook 引擎（无需重新编译即可逆向任意类）。
 *
 * "hooks":[{
 *   "class":"com.openai.x.Foo", "method":"bar",
 *   "params":["java.lang.String","int"],     // 省略 = hook 同名全部重载
 *   "mode":"dump|return|noop|throw|args",    // 默认 dump
 *   "result":true,                            // mode=return 时返回 {@link V#parse}
 *   "args":{"0":"hello"},                     // mode=args：改第 0 个参数
 *   "stack":true, "tag":"custom"
 * }]
 * "fields":[{"class":"X","name":"FLAG","value":true,"static":true}]
 * "noop":["com.a.B.c"]                         // 直接置空
 */
public final class DynHook {

    public static int applied = 0;
    private static final java.util.Set<String> DONE = new java.util.HashSet<>();

    public static void apply(ClassLoader cl) {
        applied = 0;
        JSONArray noop = Cfg.arr("noop");
        for (int i = 0; i < noop.length(); i++) {
            String sig = noop.optString(i, "");
            int p = sig.lastIndexOf('.');
            if (p < 0) continue;
            if (!DONE.add("noop|" + sig)) continue;
            try {
                Class<?> c = XposedHelpers.findClass(sig.substring(0, p), cl);
                XposedBridge.hookAllMethods(c, sig.substring(p + 1), XC_MethodHook_DO_NOTHING);
                applied++;
                XLog.i("noop " + sig);
            } catch (Throwable t) { XLog.w("noop miss " + sig); }
        }

        JSONArray fields = Cfg.arr("fields");
        for (int i = 0; i < fields.length(); i++) {
            JSONObject f = fields.optJSONObject(i);
            if (f == null) continue;
            if (!DONE.add("fld|" + f)) continue;
            try {
                Class<?> c = XposedHelpers.findClass(f.getString("class"), cl);
                Object v = V.parse(f.opt("value"));
                if (f.optBoolean("static", true)) XposedHelpers.setStaticObjectField(c, f.getString("name"), v);
                else XLog.w("instance field 需要对象，跳过");
                applied++;
                XLog.i("field " + f.getString("class") + "." + f.getString("name") + " = " + v);
            } catch (Throwable t) { XLog.w("field miss " + f); }
        }

        JSONArray hooks = Cfg.arr("hooks");
        for (int i = 0; i < hooks.length(); i++) {
            JSONObject h = hooks.optJSONObject(i);
            if (h == null) continue;
            if (!DONE.add("hk|" + h)) continue;
            try { hookOne(cl, h); } catch (Throwable t) { XLog.e("hook fail " + h, t); }
        }
        XLog.i("dyn hooks applied=" + applied);
    }

    private static void hookOne(ClassLoader cl, JSONObject h) throws Throwable {
        String cn = h.getString("class");
        String mn = h.getString("method");
        Class<?> c = XposedHelpers.findClass(cn, cl);
        String mode = h.optString("mode", "dump");
        if ("inspect".equals(mode)) { inspect(c); return; }
        final String tag = h.optString("tag", cn + "." + mn);
        final boolean stack = h.optBoolean("stack", false);
        final Object result = V.parse(h.opt("result"));
        final JSONObject argMap = h.optJSONObject("args");

        XC_MethodHook cb = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                if ("throw".equals(mode)) {
                    p.setThrowable(new RuntimeException("[GPTHook] " + tag));
                    return;
                }
                if (argMap != null) {
                    for (Iterator<String> it = argMap.keys(); it.hasNext(); ) {
                        String k = it.next();
                        int idx = Integer.parseInt(k);
                        if (p.args != null && idx < p.args.length) p.args[idx] = V.parse(argMap.opt(k));
                    }
                }
                if ("return".equals(mode) || "noop".equals(mode)) p.setResult(result);
                if (!"return".equals(mode) && !"noop".equals(mode)) {
                    XLog.i("-> " + tag + " this=" + br(p.thisObject) + " args=" + br(p.args)
                            + (stack ? "\n" + shortStack() : ""));
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if ("dump".equals(mode) || "args".equals(mode)) {
                    XLog.i("<- " + tag + " ret=" + br(p.getResult())
                            + (p.getThrowable() == null ? "" : " EX=" + p.getThrowable()));
                }
            }
        };

        JSONArray params = h.optJSONArray("params");
        if ("<init>".equals(mn)) {
            XposedBridge.hookAllConstructors(c, cb);
            applied++;
        } else if (params != null && params.length() > 0) {
            Object[] types = new Object[params.length() + 1];
            for (int i = 0; i < params.length(); i++) types[i] = params.optString(i);
            types[params.length()] = cb;
            XposedHelpers.findAndHookMethod(c, mn, types);
            applied++;
        } else {
            XposedBridge.hookAllMethods(c, mn, cb);
            applied++;
        }
        XLog.i("hook " + cn + "." + mn + " mode=" + mode);
    }

    private static final XC_MethodHook XC_MethodHook_DO_NOTHING = new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(null); }
    };


    /** 导出类结构：方法签名 + 字段（不需要方法名即可发现锚点） */
    static void inspect(Class<?> c) {
        StringBuilder sb = new StringBuilder("=== inspect " + c.getName() + "\n");
        try {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                f.setAccessible(true);
                sb.append("  F ").append(java.lang.reflect.Modifier.toString(f.getModifiers())).append(' ')
                  .append(f.getType().getSimpleName()).append(' ').append(f.getName());
                try { sb.append(" = ").append(br(f.get(null))); } catch (Throwable ignored) { }
                sb.append('\n');
            }
        } catch (Throwable ignored) { }
        try {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                StringBuilder p = new StringBuilder();
                for (Class<?> t : m.getParameterTypes()) p.append(t.getSimpleName()).append(',');
                sb.append("  M ").append(java.lang.reflect.Modifier.toString(m.getModifiers())).append(' ')
                  .append(m.getReturnType().getSimpleName()).append(' ').append(m.getName())
                  .append('(').append(p).append(')').append('\n');
            }
        } catch (Throwable ignored) { }
        try {
            for (java.lang.reflect.Constructor<?> k : c.getDeclaredConstructors()) {
                StringBuilder p = new StringBuilder();
                for (Class<?> t : k.getParameterTypes()) p.append(t.getSimpleName()).append(',');
                sb.append("  C <init>(").append(p).append(')').append('\n');
            }
        } catch (Throwable ignored) { }
        XLog.i(sb.toString());
    }

    public static void hookAll(Class<?> c, String m, XC_MethodHook cb) {
        try { XposedBridge.hookAllMethods(c, m, cb); applied++; }
        catch (Throwable t) { XLog.w("hookAll miss " + c.getName() + "." + m + " :: " + t); }
    }

    public static boolean hook(Class<?> c, String m, XC_MethodHook cb, Class<?>... types) {
        try {
            Object[] a = new Object[types.length + 1];
            System.arraycopy(types, 0, a, 0, types.length);
            a[types.length] = cb;
            XposedHelpers.findAndHookMethod(c, m, a);
            applied++;
            return true;
        } catch (Throwable t) {
            XLog.w("hook miss " + c.getName() + "." + m);
            return false;
        }
    }

    public static Class<?> cls(String name, ClassLoader cl) {
        try { return XposedHelpers.findClass(name, cl); } catch (Throwable t) { return null; }
    }

    static String br(Object o) {
        if (o == null) return "null";
        if (o instanceof Object[]) {
            StringBuilder sb = new StringBuilder("[");
            for (Object x : (Object[]) o) sb.append(br(x)).append(", ");
            return sb.append("]").toString();
        }
        String s = String.valueOf(o);
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    static String shortStack() {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder("    at ");
        for (int i = 3; i < Math.min(st.length, 12); i++) {
            sb.append(st[i].getClassName()).append('.').append(st[i].getMethodName())
              .append(':').append(st[i].getLineNumber()).append('\n');
        }
        return sb.toString();
    }
}
