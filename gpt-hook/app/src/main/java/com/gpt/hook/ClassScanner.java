package com.gpt.hook;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 运行时类名枚举：直接读 BaseDexClassLoader 的 dexElements → DexFile.entries()，
 * 按关键词过滤，输出到应用私有目录 class-scan.txt。免 jadx 全量反编译即可定位锚点。
 */
public final class ClassScanner {

    public static Set<String> names(ClassLoader cl) {
        Set<String> out = new LinkedHashSet<>();
        try {
            Field f = Class.forName("dalvik.system.BaseDexClassLoader").getDeclaredField("pathList");
            f.setAccessible(true);
            Object pathList = f.get(cl);
            Field ef = pathList.getClass().getDeclaredField("dexElements");
            ef.setAccessible(true);
            Object[] els = (Object[]) ef.get(pathList);
            for (Object el : els) {
                if (el == null) continue;
                Object dex = null;
                for (Field ff : el.getClass().getDeclaredFields()) {
                    ff.setAccessible(true);
                    Object v = ff.get(el);
                    if (v != null && v.getClass().getName().equals("dalvik.system.DexFile")) { dex = v; break; }
                }
                if (dex == null) continue;
                Method en = dex.getClass().getMethod("entries");
                Enumeration<?> e = (Enumeration<?>) en.invoke(dex);
                while (e.hasMoreElements()) out.add(String.valueOf(e.nextElement()));
            }
        } catch (Throwable t) { XLog.e("scan fail", t); }
        return out;
    }

    public static int scan(ClassLoader cl, String filter, boolean save) {
        long t0 = System.currentTimeMillis();
        Set<String> all = names(cl);
        Pattern p = null;
        try { if (filter != null && !filter.isEmpty()) p = Pattern.compile(filter, Pattern.CASE_INSENSITIVE); }
        catch (Throwable ignored) { }
        List<String> hit = new ArrayList<>();
        for (String n : all) {
            if (p == null) hit.add(n);
            else if (p.matcher(n).find()) hit.add(n);
        }
        Collections.sort(hit);
        XLog.i("scan total=" + all.size() + " hit=" + hit.size()
                + " in " + (System.currentTimeMillis() - t0) + "ms");
        StringBuilder sb = new StringBuilder("# classes=" + all.size() + " matched=" + hit.size() + "\n");
        int shown = 0;
        for (String h : hit) {
            sb.append(h).append('\n');
            if (shown++ < 60) XLog.i("  " + h);
        }
        if (hit.size() > 60) XLog.i("  … 其余 " + (hit.size() - 60) + " 条只写入文件");
        if (save) {
            File f = XLog.file();
            File out = f == null ? null : new File(f.getParentFile(), "class-scan.txt");
            try (FileWriter w = new FileWriter(out)) { w.write(sb.toString()); XLog.i("wrote " + out); }
            catch (Throwable t) { XLog.e("write scan fail", t); }
        }
        return hit.size();
    }
}
