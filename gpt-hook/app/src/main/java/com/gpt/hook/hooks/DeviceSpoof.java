package com.gpt.hook.hooks;

import com.gpt.hook.Cfg;
import com.gpt.hook.DynHook;
import com.gpt.hook.XLog;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

import org.json.JSONObject;

import java.util.Iterator;

/** 设备指纹伪造：Build 字段 / SystemProperties / ANDROID_ID / 电话 & WiFi 标识 / MediaDrm */
public final class DeviceSpoof {

    public static void init(ClassLoader cl) {
        JSONObject s = Cfg.spoof();
        Class<?> build = DynHook.cls("android.os.Build", cl);
        if (build != null) {
            for (Iterator<String> it = s.keys(); it.hasNext(); ) {
                String k = it.next();
                String v = s.optString(k, null);
                if (v == null) continue;
                try {
                    XposedHelpers.setStaticObjectField(build, k, v);
                    XLog.i("Build." + k + " = " + v);
                } catch (Throwable t) { XLog.w("Build." + k + " miss"); }
            }
        }

        final String fp = s.optString("FINGERPRINT", "");
        Class<?> sysp = DynHook.cls("android.os.SystemProperties", cl);
        if (sysp != null) {
            XC_MethodHook sp = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    String k = (String) p.args[0];
                    if (k == null) return;
                    if (k.equals("ro.debuggable")) p.setResult("0");
                    else if (k.equals("ro.secure")) p.setResult("1");
                    else if (k.equals("ro.build.fingerprint")) p.setResult(fp);
                    else if (k.equals("ro.build.tags")) p.setResult("release-keys");
                    else if (k.equals("ro.build.type")) p.setResult("user");
                    else if (k.startsWith("init.svc.magisk") || k.contains("zygisk")) p.setResult("");
                }
            };
            DynHook.hook(sysp, "get", sp, String.class);
            DynHook.hook(sysp, "get", sp, String.class, String.class);
            XLog.i("SystemProperties spoofed");
        }

        final String aid = Cfg.s("android_id", "a1b2c3d4e5f60718");
        Class<?> sec = DynHook.cls("android.provider.Settings$Secure", cl);
        if (sec != null) {
            DynHook.hook(sec, "getString", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if ("android_id".equals(p.args[1])) p.setResult(aid);
                }
            }, android.content.ContentResolver.class, String.class);
            XLog.i("ANDROID_ID = " + aid);
        }

        Class<?> tm = DynHook.cls("android.telephony.TelephonyManager", cl);
        if (tm != null) {
            String imei = "356938035643809";
            String[] m1 = {"getImei", "getDeviceId", "getMeid"};
            for (String m : m1) {
                final String v = imei;
                DynHook.hookAll(tm, m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(v); }
                });
            }
            for (String m : new String[]{"getSubscriberId", "getSimSerialNumber", "getLine1Number"}) {
                DynHook.hookAll(tm, m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(""); }
                });
            }
            XLog.i("Telephony spoofed");
        }

        Class<?> wi = DynHook.cls("android.net.wifi.WifiInfo", cl);
        if (wi != null) {
            DynHook.hookAll(wi, "getMacAddress", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult("02:00:00:00:00:00"); }
            });
        }

        Class<?> serial = DynHook.cls("android.os.Build", cl);
        if (serial != null) {
            DynHook.hookAll(serial, "getSerial", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult("R58M40ABCDE"); }
            });
        }
        XLog.i("device spoof done");
    }
}
