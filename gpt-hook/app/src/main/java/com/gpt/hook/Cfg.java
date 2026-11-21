package com.gpt.hook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;

/**
 * 配置中心：/data/data/<目标包>/files/gpt-hook.json
 * 注入早期即可读取（不依赖 Context）。root 修改后 2 秒内热加载。
 */
public final class Cfg {
    public static final String DEFAULT_JSON = "{"
            + "\"scope\":[\"com.openai.chatgpt\"],"
            + "\"device_spoof\":true,\"root_cloak\":true,\"net_watch\":true,"
            + "\"telemetry_block\":true,\"feature_unlock\":true,"
            + "\"dump_requests\":true,\"dump_headers\":true,\"scan_on_start\":false,"
            + "\"spoof\":{\"MANUFACTURER\":\"Google\",\"BRAND\":\"google\",\"MODEL\":\"Pixel 9 Pro\","
            + "\"DEVICE\":\"caiman\",\"PRODUCT\":\"caiman\",\"HARDWARE\":\"caiman\","
            + "\"FINGERPRINT\":\"google/caiman/caiman:15/AP4A.250105.002/1234567:user/release-keys\","
            + "\"BOOTLOADER\":\"caiman-1.0\",\"TAGS\":\"release-keys\",\"TYPE\":\"user\",\"HOST\":\"abfarm\"},"
            + "\"android_id\":\"a1b2c3d4e5f60718\","
            + "\"hooks\":[],\"fields\":[],\"noop\":[],"
            + "\"scan_filter\":\"plus|subscri|entitle|paywall|premium|feature|flag|integrity|attest|statsig|guard|abuse|bot|analytics|telemetry|crash|sentry|event\""
            + "}";

    private static JSONObject root = new JSONObject();
    private static JSONObject def;
    private static File file;

    public static JSONObject obj() { return root; }
    public static File file() { return file; }

    static JSONObject def() {
        if (def == null) {
            try { def = new JSONObject(DEFAULT_JSON); } catch (Throwable t) { def = new JSONObject(); }
        }
        return def;
    }

    public static void load() {
        try {
            file = new File(XLog.dir(), "gpt-hook.json");
            if (!file.exists()) {
                FileOutputStream fo = new FileOutputStream(file);
                fo.write(DEFAULT_JSON.getBytes(Charset.forName("UTF-8")));
                fo.close();
                XLog.i("cfg created " + file);
            }
            byte[] buf = new byte[(int) Math.max(file.length(), 1)];
            FileInputStream fi = new FileInputStream(file);
            int n = fi.read(buf);
            fi.close();
            root = new JSONObject(new String(buf, 0, Math.max(n, 0), Charset.forName("UTF-8")));
            XLog.i("cfg loaded " + file + " " + file.length() + "B hooks=" + arr("hooks").length());
        } catch (Throwable t) {
            XLog.e("cfg load failed, use defaults: " + t);
            try { root = new JSONObject(DEFAULT_JSON); } catch (Throwable ignored) { }
        }
    }

    public static boolean on(String k) { return root.optBoolean(k, def().optBoolean(k, false)); }

    public static String s(String k, String def) { return root.optString(k, def); }

    public static JSONObject spoof() {
        JSONObject o = root.optJSONObject("spoof");
        if (o == null) o = def().optJSONObject("spoof");
        return o == null ? new JSONObject() : o;
    }

    public static JSONArray arr(String k) {
        JSONArray a = root.optJSONArray(k);
        return a == null ? new JSONArray() : a;
    }

    public static String[] scope() {
        JSONArray a = arr("scope");
        if (a.length() == 0) return new String[]{"com.openai.chatgpt"};
        String[] r = new String[a.length()];
        for (int i = 0; i < r.length; i++) r[i] = a.optString(i, "");
        return r;
    }
}
