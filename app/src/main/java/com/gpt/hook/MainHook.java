package com.gpt.hook;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 迭代式动态 hook 模块（libxposed API 102）。
 *
 * 闭环：固定锚点 hook → 后台线程读 hook_config.json → 动态 hook 指定类/方法 → dump → 分析 → 改配置循环。
 */
public class MainHook extends XposedModule {

    private static final String TAG = "GPTHook";
    private static final String TARGET = "com.openai.chatgpt";
    private static final String CONFIG_PATH = "/data/local/tmp/gpt/hook_config.json";

    public MainHook(XposedInterface base, XposedModuleInterface.ModuleLoadedParam param) {
        super(base, param);
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!TARGET.equals(param.getPackageName())) return;
        log(Log.WARN, TAG, "===== loaded into " + param.getPackageName() + " =====");

        ClassLoader cl = param.getClassLoader();
        hookOkHttp(cl);
        hookMessageDigest(cl);
        hookHttpURLConnection(cl);

        new Thread(new ConfigWatcher(cl), "gpt-hook-watcher").start();
    }

    // ---------- 固定锚点 ----------

    private void hookOkHttp(ClassLoader cl) {
        try {
            Class<?> c = cl.loadClass("okhttp3.Request$Builder");
            for (Method m : c.getDeclaredMethods()) {
                String n = m.getName();
                if (n.equals("url") && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == String.class) {
                    hook(m).intercept(chain -> {
                        log(Log.WARN, TAG, "[url] " + chain.getArgs().get(0));
                        return chain.proceed();
                    });
                }
                if (n.equals("addHeader") || n.equals("header")) {
                    hook(m).intercept(chain -> {
                        String hn = (String) chain.getArgs().get(0);
                        String hv = (String) chain.getArgs().get(1);
                        boolean interesting = isInterestingHeader(hn);
                        log(Log.WARN, TAG, (interesting ? "[HDR*] " : "[hdr] ") + hn + " = " + short_(hv));
                        if (interesting) dumpStack();
                        return chain.proceed();
                    });
                }
            }
            log(Log.WARN, TAG, "[ok] Request$Builder");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "[fail] Request$Builder: " + t);
        }
    }

    private void hookMessageDigest(ClassLoader cl) {
        try {
            Class<?> c = cl.loadClass("java.security.MessageDigest");
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("digest")) {
                    hook(m).intercept(chain -> {
                        if (stackHasHint(Thread.currentThread().getStackTrace())) {
                            log(Log.WARN, TAG, "[md.digest] algo=" + ((java.security.MessageDigest) chain.getThisObject()).getAlgorithm());
                            dumpStack();
                        }
                        return chain.proceed();
                    });
                }
            }
            log(Log.WARN, TAG, "[ok] MessageDigest.digest");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "[fail] MessageDigest: " + t);
        }
    }

    private void hookHttpURLConnection(ClassLoader cl) {
        try {
            Class<?> c = cl.loadClass("java.net.HttpURLConnection");
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("setRequestProperty")) {
                    hook(m).intercept(chain -> {
                        String n = (String) chain.getArgs().get(0);
                        String v = (String) chain.getArgs().get(1);
                        if (isInterestingHeader(n)) {
                            log(Log.WARN, TAG, "[HUC*] " + n + " = " + short_(v));
                            dumpStack();
                        }
                        return chain.proceed();
                    });
                }
            }
            log(Log.WARN, TAG, "[ok] HttpURLConnection");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "[fail] HttpURLConnection: " + t);
        }
    }

    // ---------- 动态配置监听 ----------

    class ConfigWatcher implements Runnable {
        private final ClassLoader cl;
        private String last = "";
        private final Set<String> applied = new HashSet<>();

        ConfigWatcher(ClassLoader cl) { this.cl = cl; }

        @Override
        public void run() {
            log(Log.WARN, TAG, "[watcher] watching " + CONFIG_PATH);
            while (true) {
                try {
                    String s = readFile(CONFIG_PATH);
                    if (s != null && !s.equals(last)) {
                        last = s;
                        apply(s);
                    }
                    Thread.sleep(2000);
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "[watcher] err " + t);
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
                log(Log.WARN, TAG, "[config] parse fail: " + t);
            }
        }

        private boolean hookByName(String cls, String mn, boolean da, boolean dr, boolean ds) {
            try {
                Class<?> c = cl.loadClass(cls);
                int count = 0;
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(mn)) continue;
                    hook(m).intercept(chain -> {
                        StringBuilder sb = new StringBuilder("[dyn] " + cls + "." + mn + "(");
                        if (da) {
                            java.util.List<Object> args = chain.getArgs();
                            for (int k = 0; k < args.size(); k++) {
                                if (k > 0) sb.append(", ");
                                sb.append(fmt(args.get(k)));
                            }
                        }
                        sb.append(")");
                        log(Log.WARN, TAG, sb.toString());
                        if (ds) dumpStack();
                        Object r = chain.proceed();
                        if (dr) log(Log.WARN, TAG, "[dyn] " + cls + "." + mn + " => " + fmt(r));
                        return r;
                    });
                    count++;
                }
                log(Log.WARN, TAG, "[dyn-hook ok] " + cls + "." + mn + " (" + count + " 重载)");
                return count > 0;
            } catch (Throwable t) {
                log(Log.WARN, TAG, "[dyn-hook fail] " + cls + "." + mn + " : " + t);
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
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
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
        log(Log.WARN, TAG, sb.toString());
    }

    private boolean interestingClass(String cn) {
        String[] sys = {"java.", "javax.", "android.", "dalvik.", "libcore.",
                "com.android.", "kotlin.", "kotlinx.", "okhttp3.", "okio.",
                "retrofit2.", "com.squareup.", "org.json.", "com.google.",
                "fi.iki.", "com.gpt.hook.", "io.github.", "androidx.",
                "com.facebook.", "com.bumptech.", "sun.", "jdk."};
        for (String s : sys) if (cn.startsWith(s)) return false;
        return true;
    }

    private String short_(String v) {
        if (v == null) return "null";
        return v.length() > 120 ? v.substring(0, 120) + "…(" + v.length() + ")" : v;
    }
}
