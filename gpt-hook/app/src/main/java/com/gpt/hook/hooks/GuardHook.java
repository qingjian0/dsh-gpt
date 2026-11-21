package com.gpt.hook.hooks;

import com.gpt.hook.DynHook;
import com.gpt.hook.XF;
import com.gpt.hook.XLog;

import de.robv.android.xposed.XC_MethodHook;

import java.util.List;

/** 运行环境一致性：进程表 / 安装来源 / 调试属性 */
public final class GuardHook {

    private static final String[] BAD_PROC = {"frida", "magisk", "kernelsu", "ksud", "lspd", "zygisk"};

    public static void init(ClassLoader cl) {
        Class<?> am = DynHook.cls("android.app.ActivityManager", cl);
        if (am != null) {
            XC_MethodHook clean = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object r = p.getResult();
                    if (!(r instanceof List)) return;
                    List<?> l = (List<?>) r;
                    for (int i = l.size() - 1; i >= 0; i--) {
                        Object o = l.get(i);
                        String pn = String.valueOf(XF.get(o, "processName"));
                        String lc = pn.toLowerCase();
                        boolean bad = false;
                        for (String b : BAD_PROC) if (lc.contains(b)) { bad = true; break; }
                        if (bad) { l.remove(i); XLog.i("[guard] hide proc " + pn); }
                    }
                }
            };
            DynHook.hook(am, "getRunningAppProcesses", clean);
            DynHook.hook(am, "getRunningServices", clean, int.class);
        }

        Class<?> apm = DynHook.cls("android.app.ApplicationPackageManager", cl);
        if (apm != null) {
            DynHook.hook(apm, "getInstallerPackageName", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.setResult("com.android.vending");
                }
            }, String.class);
        }

        Class<?> sys = DynHook.cls("java.lang.System", cl);
        if (sys != null) {
            DynHook.hook(sys, "getProperty", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    String k = String.valueOf(p.args[0]);
                    if (k.contains("debug") || k.contains("test") || k.contains("frida")) p.setResult("");
                }
            }, String.class);
        }
        XLog.i("guard armed");
    }
}
