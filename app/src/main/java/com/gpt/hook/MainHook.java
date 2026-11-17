package com.gpt.hook;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 迭代式动态 hook 模块（经典 Xposed API）。
 *
 * 闭环：固定锚点 hook → 后台线程读 /data/local/tmp/gpt/hook_config.json →
 *       动态 hook 配置里的类名/方法名（hookAllMethods 无需签名）→ dump 到 logcat →
 *       外部分析 → 改配置 → 自动重新 hook，循环。
 *
 * hook_config.json 格式：
 * {
 *   "hooks": [
 *     {"class":"com.openai.xxx.Foo","methods":["a","b"],"dumpArgs":true,"dumpResult":true,"dumpStack":false}
 *   ]
 * }
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "GPTHook";
    private static final String TARGET = "com.openai.chatgpt";
    private static final String CONFIG_PATH = "/data/local/tmp/gpt/hook_config.json";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!TARGET.equals(lp.packageName)) return;
        Log.w(TAG, "===== [loaded] " + lp.packageName + " =====");

        hookOkHttpBuilder(lp.classLoader);
        hookMessageDigest(lp.classLoader);
        hookHttpURLConnection(lp.classLoader);

        new Thread(new ConfigWatcher(lp.classLoader), "gpt-hook-watcher").start();
    }

    // ---------- 固定锚点 ----------

    private void hookOkHttpBuilder(ClassLoader cl) {
        try {
            Class<?> rb = XposedHelpers.findClass("okhttp3.Request$Builder", cl);
            try {
                XposedHelpers.findAndHookMethod(rb, "url", String.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        Log.w(TAG, "[url] " + p.args[0]);
                    }
                });
            } catch (Throwable ignored) {}

            XC_MethodHook hh = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    String n = (String) p.args[0];
                    String v = (String) p.args[1];
                    boolean interesting = isInterestingHeader(n);
                    Log.w(TAG, (interesting ? "[HDR*] " : "[hdr] ") + n + " = " + short_(v));
                    if (interesting) dumpStack();
                }
            };
            try { XposedHelpers.findAndHookMethod(rb, "addHeader", String.class, String.class, hh); } catch (Throwable ignored) {}
            try { XposedHelpers.findAndHookMethod(rb, "header", String.class, String.class, hh); } catch (Throwable ignored) {}

            Log.w(TAG, "[ok] Request$Builder.url + headers");
        } catch (Throwable t) {
            Log.w(TAG, "[fail] Request$Builder: " + t);
        }
    }

    private void hookMessageDigest(ClassLoader cl) {
        try {
            Class<?> md = XposedHelpers.findClass("java.security.MessageDigest", cl);
            XC_MethodHook dh = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    StackTraceElement[] st = Thread.currentThread().getStackTrace();
                    if (stackHasHint(st)) {
                        Log.w(TAG, "[md.digest] algo=" + ((java.security.MessageDigest) p.thisObject).getAlgorithm());
                        dumpStack(st);
                    }
                }
            };
            try { XposedHelpers.findAndHookMethod(md, "digest", dh); } catch (Throwable ignored) {}
            try { XposedHelpers.findAndHookMethod(md, "digest", byte[].class, dh); } catch (Throwable ignored) {}
            Log.w(TAG, "[ok] MessageDigest.digest");
        } catch (Throwable t) {
            Log.w(TAG, "[fail] MessageDigest: " + t);
        }
    }

    private void hookHttpURLConnection(ClassLoader cl) {
        try {
            Class<?> huc = XposedHelpers.findClass("java.net.HttpURLConnection", cl);
            XC_MethodHook hh = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    String n = (String) p.args[0];
                    String v = (String) p.args[1];
                    if (isInterestingHeader(n)) {
                        Log.w(TAG, "[HUC*] " + n + " = " + short_(v));
                        dumpStack();
                    }
                }
            };
            try { XposedHelpers.findAndHookMethod(huc, "setRequestProperty", String.class, String.class, hh); } catch (Throwable ignored) {}
            Log.w(TAG, "[ok] HttpURLConnection.setRequestProperty");
        } catch (Throwable t) {
            Log.w(TAG, "[fail] HttpURLConnection: " + t);
        }
    }

    // ---------- 动态配置监听 ----------

    static class ConfigWatcher implements Runnable {
        private final ClassLoader cl;
        private String last = "";
        private final Set<String> applied = new HashSet<>();

        ConfigWatcher(ClassLoader cl) { this.cl = cl; }

        @Override
        public void run() {
            Log.w(TAG, "[watcher] watching " + CONFIG_PATH);
            while (true) {
                try {
                    String s = readFile(CONFIG_PATH);
                    if (s != null && !s.equals(last)) {
                        last = s;
                        apply(s);
                    }
                    Thread.sleep(2000);
                } catch (Throwable t) {
                    Log.w(TAG, "[watcher] err " + t);
                }
            }
        }

        private void apply(String json) {
            try {
                JSONArray hooks = new JSONObject(json).optJSONArray("hooks");
                if (hooks == null) return;
                for (int i = 0; i < hooks.length(); i++) {
                    JSONObject h = hooks.optJSONObject(i);
                    if (h == null) continue;
                    String cls = h.optString("class");
                    JSONArray methods = h.optJSONArray("methods");
                    final boolean da = h.optBoolean("dumpArgs", true);
                    final boolean dr = h.optBoolean("dumpResult", true);
                    final boolean ds = h.optBoolean("dumpStack", false);
                    if (cls.isEmpty() || methods == null) continue;
                    for (int j = 0; j < methods.length(); j++) {
                        String mn = methods.optString(j);
                        String key = cls + "#" + mn;
                        if (applied.contains(key)) continue;
                        if (hookByName(cls, mn, da, dr, ds)) applied.add(key);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "[config] parse fail: " + t);
            }
        }

        private boolean hookByName(String cls, String mn, boolean da, boolean dr, boolean ds) {
            try {
                Class<?> c = XposedHelpers.findClass(cls, cl);
                XposedBridge.hookAllMethods(c, mn, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        StringBuilder sb = new StringBuilder("[dyn] " + cls + "." + mn + "(");
                        if (da) {
                            Object[] args = p.args;
                            for (int k = 0; k < args.length; k++) {
                                if (k > 0) sb.append(", ");
                                sb.append(fmt(args[k]));
                            }
                        }
                        sb.append(")");
                        Log.w(TAG, sb.toString());
                        if (ds) dumpStack();
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (dr) {
                            Log.w(TAG, "[dyn] " + cls + "." + mn + " => " + fmt(p.getResult()));
                        }
                    }
                });
                Log.w(TAG, "[dyn-hook ok] " + cls + "." + mn);
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "[dyn-hook fail] " + cls + "." + mn + " : " + t);
                return false;
            }
        }
    }

    // ---------- 工具 ----------

    private static String readFile(String path) {
        try {
            File f = new File(path);
            if (!f.exists()) return null;
            FileInputStream in = new FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            int n = in.read(b);
            in.close();
            return new String(b, 0, n, "UTF-8");
        } catch (Throwable t) { return null; }
    }

    private static String fmt(Object o) {
        if (o == null) return "null";
        try {
            String s = String.valueOf(o);
            return s.length() > 200 ? s.substring(0, 200) + "…(" + s.length() + ")" : s;
        } catch (Throwable t) { return "<" + o.getClass().getName() + ">"; }
    }

    private boolean isInterestingHeader(String n) {
        if (n == null) return false;
        String s = n.toLowerCase();
        return s.contains("sentinel") || s.contains("proof") || s.contains("pow")
                || s.contains("device") || s.contains("arkose") || s.contains("turnstile")
                || s.contains("challenge") || s.contains("requirements") || s.contains("oai-")
                || s.equals("authorization") || s.contains("captcha") || s.contains("token");
    }

    private static boolean stackHasHint(StackTraceElement[] st) {
        for (StackTraceElement e : st) {
            String cn = e.getClassName().toLowerCase();
            if (cn.contains("sentinel") || cn.contains("proof") || cn.contains("pow")
                    || cn.contains("arkose") || cn.contains("turnstile")
                    || cn.contains("challenge") || cn.contains("device")) return true;
        }
        return false;
    }

    private void dumpStack() {
        dumpStack(Thread.currentThread().getStackTrace());
    }

    private void dumpStack(StackTraceElement[] st) {
        StringBuilder sb = new StringBuilder("[stack]\n");
        int c = 0;
        for (StackTraceElement e : st) {
            String cn = e.getClassName();
            if (interestingClass(cn)) {
                sb.append("  ").append(cn).append(".").append(e.getMethodName())
                        .append("(").append(e.getFileName()).append(":").append(e.getLineNumber()).append(")\n");
                c++;
            }
        }
        if (c == 0) {
            for (StackTraceElement e : st) {
                sb.append("  ").append(e.getClassName()).append(".").append(e.getMethodName())
                        .append("(").append(e.getLineNumber()).append(")\n");
            }
        }
        Log.w(TAG, sb.toString());
    }

    private boolean interestingClass(String cn) {
        String[] sys = {"java.", "javax.", "android.", "dalvik.", "libcore.",
                "com.android.", "kotlin.", "kotlinx.", "okhttp3.", "okio.",
                "retrofit2.", "com.squareup.", "org.json.", "com.google.",
                "fi.iki.", "com.gpt.hook.", "de.robv.", "androidx.",
                "com.facebook.", "com.bumptech.", "sun.", "jdk."};
        for (String s : sys) if (cn.startsWith(s)) return false;
        return true;
    }

    private String short_(String v) {
        if (v == null) return "null";
        return v.length() > 120 ? v.substring(0, 120) + "…(" + v.length() + ")" : v;
    }
}
