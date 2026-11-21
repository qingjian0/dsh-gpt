package com.gpt.hook;

import android.os.Process;

import com.gpt.hook.hooks.Capture;
import com.gpt.hook.hooks.CronetSpy;
import com.gpt.hook.hooks.DPoPSign;
import com.gpt.hook.hooks.Gateway;
import com.gpt.hook.hooks.DynClassWatch;
import com.gpt.hook.hooks.DeviceSpoof;
import com.gpt.hook.hooks.FeatureGate;
import com.gpt.hook.hooks.GuardHook;
import com.gpt.hook.hooks.NetWatch;
import com.gpt.hook.hooks.RootCloak;
import com.gpt.hook.hooks.Telemetry;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * SukiSU/KernelSU + Zygisk + LSPosed 模式的 GPT Hook 入口。
 *
 * 静态层：设备伪装 / root 隐身 / pinning 绕过 / 上报阻断 / 灰度闸门 / 流量记录
 * 动态层：app/files/gpt-hook.json 热加载，无需重编译即可 hook 任意类（见 DynHook）
 */
public class MainHook implements IXposedHookLoadPackage {

    public static volatile ClassLoader sCL;
    private static volatile long sMtime = -1L;
    private static volatile boolean sWatcherStarted;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (lp.packageName == null || !match(lp.packageName)) return;
        sCL = lp.classLoader;
        XLog.tag("GPT:" + shortP(lp.processName));
        XLog.i("=== inject " + lp.packageName + " proc=" + lp.processName
                + " pid=" + Process.myPid() + " cl=" + lp.classLoader.getClass().getName());

        Cfg.load();
        long t0 = System.currentTimeMillis();
        safe("device_spoof", Cfg.on("device_spoof"), () -> DeviceSpoof.init(sCL));
        safe("root_cloak", Cfg.on("root_cloak"), () -> { RootCloak.init(sCL); GuardHook.init(sCL); });
        safe("net_watch", Cfg.on("net_watch"), () -> NetWatch.init(sCL));
        safe("cronet_spy", Cfg.on("net_watch"), () -> CronetSpy.init(sCL));
        safe("class_watch", Cfg.on("net_watch"), () -> DynClassWatch.init());
        safe("telemetry_block", Cfg.on("telemetry_block"), () -> Telemetry.init(sCL));
        safe("feature_unlock", Cfg.on("feature_unlock"), () -> FeatureGate.init(sCL));
        safe("capture", Cfg.on("capture"), () -> Capture.init(sCL));
        safe("dpop", Cfg.on("capture"), () -> DPoPSign.init(sCL));
        safe("gateway", Cfg.on("gateway"), () -> Gateway.start(parseInt(Cfg.s("gateway_port", "8787"), 8787), Cfg.s("gateway_token", "kit")));
        safe("dyn", true, () -> DynHook.apply(sCL));
        if (Cfg.on("scan_on_start")) {
            safe("scan", true, () -> ClassScanner.scan(sCL, Cfg.s("scan_filter", ""), true));
        }
        XLog.i("=== all hooks in " + (System.currentTimeMillis() - t0) + "ms, dyn=" + DynHook.applied);
        watchCfg();
    }

    private static boolean match(String pkg) {
        for (String s : Cfg.scope()) if (s.equals(pkg)) return true;
        return pkg.startsWith("com.openai.");
    }

    private static String shortP(String p) {
        if (p == null) return "?";
        int i = p.indexOf(':');
        return i < 0 ? "main" : p.substring(i + 1);
    }

    interface Block { void run() throws Throwable; }

    static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; }
    }

    private static void safe(String name, boolean on, Block b) {
        if (!on) { XLog.i("skip " + name); return; }
        try { b.run(); } catch (Throwable t) { XLog.e("FAIL " + name, t); }
    }

    /** 配置热加载：mtime 变化 → 重新应用动态 hook（内置签名去重，重复应用安全） */
    private static void watchCfg() {
        if (sWatcherStarted) return;
        sWatcherStarted = true;
        try { sMtime = Cfg.file() == null ? -1 : Cfg.file().lastModified(); } catch (Throwable ignored) { }
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(2000);
                    java.io.File f = Cfg.file();
                    if (f == null || !f.exists()) continue;
                    long m = f.lastModified();
                    if (m != sMtime) {
                        sMtime = m;
                        XLog.w("cfg changed -> reload");
                        Cfg.load();
                        DynHook.apply(MainHook.sCL);
                        XLog.w("cfg reload done, dyn=" + DynHook.applied);
                    }
                } catch (Throwable ignored) { }
            }
        }, "gpt-cfg-watch");
        t.setDaemon(true);
        t.start();
        XLog.i("cfg watcher on");
    }
}
