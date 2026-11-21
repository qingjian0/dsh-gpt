package com.gpt.hook;

/** JSON → Java 值（用于 return/args/field） */
public final class V {
    public static Object parse(Object o) {
        if (o == null || o == org.json.JSONObject.NULL) return null;
        if (o instanceof Boolean || o instanceof Number || o instanceof String) return o;
        if (o instanceof org.json.JSONArray) {
            org.json.JSONArray a = (org.json.JSONArray) o;
            Object[] r = new Object[a.length()];
            for (int i = 0; i < r.length; i++) r[i] = parse(a.opt(i));
            return r;
        }
        if (o instanceof org.json.JSONObject) {
            org.json.JSONObject j = (org.json.JSONObject) o;
            String t = j.optString("$type", "");
            Object v = parse(j.opt("value"));
            if (t.isEmpty()) return v;
            try {
                switch (t) {
                    case "int": return ((Number) v).intValue();
                    case "long": return ((Number) v).longValue();
                    case "float": return ((Number) v).floatValue();
                    case "double": return ((Number) v).doubleValue();
                    case "short": return ((Number) v).shortValue();
                    case "byte": return ((Number) v).byteValue();
                    case "char": return String.valueOf(v).charAt(0);
                    default: return v;
                }
            } catch (Throwable ignored) { return v; }
        }
        return o;
    }
}
