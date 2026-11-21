package com.gpt.hook.hooks;

import com.gpt.hook.DynHook;
import com.gpt.hook.XLog;

import de.robv.android.xposed.XC_MethodHook;

/** 远程开关 / 灰度闸门强制为开 */
public final class FeatureGate {

    public static void init(ClassLoader cl) {
        XC_MethodHook yes = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                XLog.i("[gate] ON " + (p.method == null ? "?" : p.method.getName())
                        + (p.args != null && p.args.length > 0 ? " " + p.args[0] : ""));
                p.setResult(true);
            }
        };

        Class<?> fg = DynHook.cls("com.statsig.androidsdk.FeatureGate", cl);
        if (fg != null) {
            DynHook.hookAll(fg, "isEnabled", yes);
            DynHook.hookAll(fg, "isUserInExperiment", yes);
            XLog.i("statsig FeatureGate unlocked");
        }
        Class<?> sc = DynHook.cls("com.statsig.androidsdk.StatsigClient", cl);
        if (sc != null) {
            DynHook.hookAll(sc, "checkGate", yes);
            XLog.i("statsig checkGate unlocked");
        }
        Class<?> dc = DynHook.cls("com.statsig.androidsdk.DynamicConfig", cl);
        if (dc != null) XLog.i("statsig DynamicConfig 保留原值（被配置覆盖时用 hooks 注入）");

        // OpenAI 自研 gate（类名随版本变化，用包级扫描 + 通用命名匹配）
        for (String cn : new String[]{
                "com.openai.chatgpt.features.Features",
                "com.openai.chatgpt.config.RemoteConfig",
                "com.openai.chatgpt.experiments.Experiments"}) {
            Class<?> c = DynHook.cls(cn, cl);
            if (c == null) continue;
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getReturnType() != boolean.class) continue;
                if (!m.getName().startsWith("is")) continue;
                DynHook.hookAll(c, m.getName(), yes);
            }
            XLog.i("gate sweep " + cn);
        }
    }
}
