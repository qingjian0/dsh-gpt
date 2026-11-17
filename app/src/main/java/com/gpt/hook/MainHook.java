package com.gpt.hook;

import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.callbacks.XC_MethodHook;

/**
 * 研究型 hook 模块：通过 LSPosed 动态 hook ChatGPT，
 * 把反代所需的一切 dump 到 logcat。
 *
 * 动态 hook 三层：
 *  1. 网络层（OkHttp + HttpURLConnection）—— 捕获 token / device-id / sentinel header
 *  2. 底层汇聚点（MessageDigest）—— PoW 求解器必算 hash，hook 这里反推求解器类
 *  3. 调用栈分析 —— 过滤系统类，暴露 R8 混淆后的目标 App 类
 *
 * 目标搞清：access_token / oai-device-id / sentinel / PoW / arkose 从哪来、怎么算。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "GPTHook";
    private static final String TARGET = "com.openai.chatgpt";

    // 只 dump 调用栈含这些特征时才打印，避免 MessageDigest 刷屏
    private static final String[] STACK_HINTS = {
            "sentinel", "proof", "pow", "arkose", "turnstile", "challenge",
            "device", "requirements", "captcha", "nonce", "difficulty", "hashcash"
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!TARGET.equals(lp.packageName)) return;
        Log.w(TAG, "===== [loaded] " + lp.packageName + " =====");
        hookOkHttpBuilder(lp.classLoader);
        hookMessageDigest(lp.classLoader);
        hookHttpURLConnection(lp.classLoader);
    }

    // ---------- 1. OkHttp Request$Builder ----------
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

            Log.w(TAG, "[ok] hooked Request$Builder.url + headers");
        } catch (Throwable t) {
            Log.w(TAG, "[fail] Request$Builder: " + t);
        }
    }

    // ---------- 2. MessageDigest（PoW 必算 hash） ----------
    private void hookMessageDigest(ClassLoader cl) {
        try {
            Class<?> md = XposedHelpers.findClass("java.security.MessageDigest", cl);

            // getInstance —— 记录算法
            try {
                XposedHelpers.findAndHookMethod(md, "getInstance", String.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        String algo = (String) p.args[0];
                        StackTraceElement[] st = Thread.currentThread().getStackTrace();
                        if (stackHasHint(st)) {
                            Log.w(TAG, "[md.getInstance] " + algo);
                            dumpStack(st);
                        }
                    }
                });
            } catch (Throwable ignored) {}

            // digest —— PoW 计算核心，dump 调用栈反推求解器
            XC_MethodHook digestHook = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    StackTraceElement[] st = Thread.currentThread().getStackTrace();
                    if (stackHasHint(st)) {
                        Log.w(TAG, "[md.digest] algo=" + ((java.security.MessageDigest) p.thisObject).getAlgorithm());
                        dumpStack(st);
                    }
                }
            };
            try { XposedHelpers.findAndHookMethod(md, "digest", digestHook); } catch (Throwable ignored) {}
            try { XposedHelpers.findAndHookMethod(md, "digest", byte[].class, digestHook); } catch (Throwable ignored) {}

            Log.w(TAG, "[ok] hooked MessageDigest.getInstance + digest");
        } catch (Throwable t) {
            Log.w(TAG, "[fail] MessageDigest: " + t);
        }
    }

    // ---------- 3. HttpURLConnection 兜底 ----------
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
            Log.w(TAG, "[ok] hooked HttpURLConnection.setRequestProperty");
        } catch (Throwable t) {
            Log.w(TAG, "[fail] HttpURLConnection: " + t);
        }
    }

    // ---------- 工具 ----------
    private boolean isInterestingHeader(String n) {
        if (n == null) return false;
        String s = n.toLowerCase();
        return s.contains("sentinel") || s.contains("proof") || s.contains("pow")
                || s.contains("device") || s.contains("arkose") || s.contains("turnstile")
                || s.contains("challenge") || s.contains("requirements") || s.contains("oai-")
                || s.equals("authorization") || s.contains("captcha") || s.contains("token");
    }

    private boolean stackHasHint(StackTraceElement[] st) {
        for (StackTraceElement e : st) {
            String cn = e.getClassName().toLowerCase();
            for (String h : STACK_HINTS) {
                if (cn.contains(h)) return true;
            }
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
