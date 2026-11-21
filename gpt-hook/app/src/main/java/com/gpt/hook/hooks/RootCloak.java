package com.gpt.hook.hooks;

import com.gpt.hook.DynHook;
import com.gpt.hook.XLog;

import de.robv.android.xposed.XC_MethodHook;

import java.io.File;
import java.util.Locale;

/** Root / Xposed / Frida 痕迹隐藏 */
public final class RootCloak {

    private static final String[] BAD_PKG = {
            "com.topjohnwu.magisk", "io.github.huskydg.magisk", "me.weishu.kernelsu",
            "com.rifsxd.ksunext", "me.bmax.apatch", "org.lsposed.manager",
            "de.robv.android.xposed.installer", "com.saurik.substrate", "com.zhenxi.hunter",
            "io.github.vvb2060.mahoshojo", "org.lsposed.lspatch", "com.termux"
    };

    private static volatile String lastPath;
    private static volatile boolean lastBad;

    public static boolean bad(String path) {
        if (path == null || path.isEmpty()) return false;
        if (path.equals(lastPath)) return lastBad;
        String l = path.toLowerCase(Locale.US);
        int i = l.lastIndexOf('/');
        String base = i < 0 ? l : l.substring(i + 1);
        boolean r = base.equals("su") || base.equals("sush") || base.equals("daemonsu")
                || base.equals("busybox") || base.equals("ksu") || base.startsWith("magisk")
                || base.startsWith("frida-server") || base.startsWith("riru")
                || l.contains("/magisk") || l.contains("/kernelsu") || l.contains("/apatch")
                || l.contains("/lsposed") || l.contains("/xposed") || l.contains("/edxposed")
                || l.contains("/frida") || l.contains("/supersu") || l.contains("/.magisk");
        lastPath = path;
        lastBad = r;
        return r;
    }

    private static boolean badPkg(String p) {
        for (String b : BAD_PKG) if (b.equals(p)) return true;
        return false;
    }

    public static void init(ClassLoader cl) {
        Class<?> file = DynHook.cls("java.io.File", cl);
        if (file != null) {
            XC_MethodHook h = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Object o = p.thisObject;
                    if (!(o instanceof File)) return;
                    if (bad(((File) o).getPath())) p.setResult(false);
                }
            };
            DynHook.hook(file, "exists", h);
            DynHook.hook(file, "isFile", h);
            DynHook.hook(file, "canRead", h);
            DynHook.hook(file, "canExecute", h);
            XLog.i("File checks cloaked");
        }

        Class<?> rt = DynHook.cls("java.lang.Runtime", cl);
        if (rt != null) {
            XC_MethodHook h = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    Object a = p.args != null && p.args.length > 0 ? p.args[0] : null;
                    String cmd = a instanceof String[] ? String.join(" ", (String[]) a) : String.valueOf(a);
                    if (cmd != null && bad(cmd.trim().split(" ")[0])) {
                        throw new java.io.IOException("Cannot run program: EACCES");
                    }
                }
            };
            DynHook.hook(rt, "exec", h, String.class);
            DynHook.hook(rt, "exec", h, String[].class);
            XLog.i("Runtime.exec cloaked");
        }

        Class<?> pb = DynHook.cls("java.lang.ProcessBuilder", cl);
        if (pb != null) {
            DynHook.hook(pb, "start", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    try {
                        java.util.List<?> c = (java.util.List<?>) p.thisObject.getClass()
                                .getMethod("command").invoke(p.thisObject);
                        if (c != null && !c.isEmpty() && bad(String.valueOf(c.get(0)))) {
                            throw new java.io.IOException("EACCES");
                        }
                    } catch (java.io.IOException e) { throw e; } catch (Throwable ignored) { }
                }
            });
        }

        Class<?> apm = DynHook.cls("android.app.ApplicationPackageManager", cl);
        if (apm != null) {
            DynHook.hook(apm, "getPackageInfo", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    if (p.args[0] instanceof String && badPkg((String) p.args[0]))
                        throw new android.content.pm.PackageManager.NameNotFoundException((String) p.args[0]);
                }
            }, String.class, int.class);
            XC_MethodHook filter = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object r = p.getResult();
                    if (r instanceof java.util.List) {
                        java.util.List<?> l = (java.util.List<?>) r;
                        for (int i = l.size() - 1; i >= 0; i--) {
                            Object it = l.get(i);
                            String pn = (String) com.gpt.hook.XF.get(it, "packageName");
                            if (pn == null) pn = (String) com.gpt.hook.XF.get(it, "name");
                            if (pn != null && badPkg(pn)) l.remove(i);
                        }
                    }
                }
            };
            DynHook.hook(apm, "getInstalledPackages", filter, int.class);
            DynHook.hook(apm, "getInstalledApplications", filter, int.class);
        }

        Class<?> dbg = DynHook.cls("android.os.Debug", cl);
        if (dbg != null) {
            for (String m : new String[]{"isDebuggerConnected", "waitingForDebugger"}) {
                DynHook.hookAll(dbg, m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(false); }
                });
            }
        }
        XLog.i("root cloak armed");
    }
}
