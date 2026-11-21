package com.gpt.hook;

import java.lang.reflect.Field;

/** 安全反射取值（遍历父类） */
public final class XF {
    public static Object get(Object o, String name) {
        if (o == null) return null;
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            catch (Throwable t) { return null; }
        }
        return null;
    }

    public static void set(Object o, String name, Object v) {
        if (o == null) return;
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(o, v);
                return;
            } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            catch (Throwable t) { return; }
        }
    }
}
