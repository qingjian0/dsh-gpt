package com.gpt.hook;

import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.callbacks.XC_MethodHook;

/**
 * 研究型 hook 模块：通过 LSPosed 加载到 ChatGPT 进程，
 * hook 网络层 + 关键点，把反代所需的一切 dump 到 logcat，供分析。
 *
 * 目标搞清：
 *  1. access_token 从哪来、长什么样
 *  2. oai-device-id 怎么生成
 *  3. sentinel / proof-of-work / chat-requirements 怎么算
 *  4. arkose / turnstile 在哪触发
 *  5. /conversation 请求的完整 header 与格式
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "GPTHook";
    private static final String TARGET = "com.openai.chatgpt";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!TARGET.equals(lp.packageName)) return;
        Log.w(TAG, "===== [loaded] " + lp.packageName + " v" + lp.appInfo.versionName + " =====");
        hookOkHttpBuilder(lp.classLoader);
        hookInterceptor(lp.classLoader);
        hookHttpURLConnection(lp.classLoader);
    }

    // ---------- 1. OkHttp Request$Builder ----------
    private void hookOkHttpBuilder(ClassLoader cl) {
        try {
            Class<?> rb = XposedHelpers.findClass("okhttp3.Request$Builder", cl);

            // url() —— 捕获所有请求 URL，识别 sentinel/requirements 端点
            try {
                XposedHelpers.findAndHookMethod(rb, "url", String.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        Log.w(TAG, "[url] " + p.args[0]);
                    }
                });
            } catch (Throwable ignored) {}

            // addHeader / header —— 捕获所有 header + 调用栈
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

            Log.w(TAG, "[ok] hooked Request$Builder.url + addHeader/header");
        } catch (Throwable t) {
            Log.w(TAG, "[fail] Request$Builder: " + t);
        }
    }

    // ---------- 2. OkHttp Interceptor（看完整请求/响应） ----------
    private void hookInterceptor(ClassLoader cl) {
        try {
            // hook 所有 Interceptor 的 intercept，打印 URL + 关键 header
            Class<?> ic = XposedHelpers.findClass("okhttp3.Interceptor", cl);
            // 不直接 hook 接口，改为 hook 具体实现类较难；改用 OkHttpClient 的拦截器列表打印
        } catch (Throwable ignored) {}
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
        Log.w(TAG, sb.toString());
    }

    private boolean interestingClass(String cn) {
        String[] sys = {"java.", "javax.", "android.", "dalvik.", "libcore.",
                "com.android.", "kotlin.", "kotlinx.", "okhttp3.", "okio.",
                "retrofit2.", "com.squareup.", "org.json.", "com.google.",
                "fi.iki.", "com.gpt.hook.", "de.robv.", "androidx.",
                "com.facebook.", "com.bumptech."};
        for (String s : sys) if (cn.startsWith(s)) return false;
        return true;
    }

    private String short_(String v) {
        if (v == null) return "null";
        return v.length() > 120 ? v.substring(0, 120) + "…(" + v.length() + ")" : v;
    }
}
