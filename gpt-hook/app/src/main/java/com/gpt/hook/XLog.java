package com.gpt.hook;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 统一日志：logcat + /data/data/<pkg>/files/gpt-hook.log（不依赖 Context，注入早期即可写） */
public final class XLog {
    private static String tag = "GPTHook";
    private static File file;
    private static String pkg;
    private static final long MAX = 512 * 1024;

    public static void tag(String t) { tag = t; }

    public static File file() { return file; }

    public static String dir() { return "/data/data/" + pkg() + "/files"; }

    /** 进程所属包名：/proc/self/cmdline 去掉 :process 后缀 */
    public static String pkg() {
        if (pkg != null) return pkg;
        String s = null;
        try {
            FileInputStream fi = new FileInputStream("/proc/self/cmdline");
            byte[] b = new byte[256];
            int n = fi.read(b);
            fi.close();
            s = new String(b, 0, Math.max(n, 0)).trim();
        } catch (Throwable ignored) { }
        if (s == null || s.isEmpty()) s = "com.openai.chatgpt";
        int c = s.indexOf(':');
        pkg = c < 0 ? s : s.substring(0, c);
        return pkg;
    }

    private static void open() {
        if (file != null && file.exists()) return;
        try {
            File d = new File(dir());
            if (!d.exists()) return;
            file = new File(d, "gpt-hook.log");
            if (!file.exists()) file.createNewFile();
        } catch (Throwable t) { file = null; }
    }

    public static void i(String m) { w("I", m, null); }
    public static void w(String m) { w("W", m, null); }
    public static void e(String m) { w("E", m, null); }
    public static void e(String m, Throwable t) { w("E", m, t); }

    private static void w(String lvl, String m, Throwable t) {
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                + " " + lvl + " " + m + (t == null ? "" : " :: " + Log.getStackTraceString(t));
        try { if ("E".equals(lvl)) Log.e(tag, m, t); else Log.w(tag, m); } catch (Throwable ignored) { }
        try { de.robv.android.xposed.XposedBridge.log("[" + tag + "] " + line); } catch (Throwable ignored) { }
        open();
        if (file == null) return;
        try {
            if (file.length() > MAX) {
                File old = new File(dir(), "gpt-hook.log.1");
                if (old.exists()) old.delete();
                file.renameTo(old);
            }
            FileWriter fw = new FileWriter(file, true);
            fw.write(line + "\n");
            fw.close();
        } catch (Throwable ignored) { }
    }
}
