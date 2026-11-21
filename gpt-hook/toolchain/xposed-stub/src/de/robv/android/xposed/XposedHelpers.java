package de.robv.android.xposed;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
public final class XposedHelpers {
    public static class ClassNotFoundError extends Error {
        public ClassNotFoundError(String m) { super(m); }
        public ClassNotFoundError(Throwable t) { super(t); }
    }
    public static Class<?> findClass(String className, ClassLoader cl) { return null; }
    public static Class<?> findClassIfExists(String className, ClassLoader cl) { return null; }
    public static Field findField(Class<?> clazz, String fieldName) { return null; }
    public static Field findFieldIfExists(Class<?> clazz, String fieldName) { return null; }
    public static Field findField(Class<?> clazz, String fieldName, Class<?> fieldType) { return null; }
    public static Method findMethodExact(Class<?> clazz, String methodName, Object... parameterTypes) { return null; }
    public static Method findMethodExact(String className, ClassLoader cl, String methodName, Object... parameterTypes) { return null; }
    public static Method findMethodExactIfExists(Class<?> clazz, String methodName, Object... parameterTypes) { return null; }
    public static Method[] findMethodsByName(Class<?> clazz, String methodName, boolean exact) { return new Method[0]; }
    public static Constructor<?> findConstructorExact(Class<?> clazz, Object... parameterTypes) { return null; }
    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) { return null; }
    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader cl, String methodName, Object... parameterTypesAndCallback) { return null; }
    public static XC_MethodHook.Unhook findAndHookConstructor(Class<?> clazz, Object... parameterTypesAndCallback) { return null; }
    public static Object getObjectField(Object obj, String fieldName) { return null; }
    public static void setObjectField(Object obj, String fieldName, Object value) { }
    public static Object getStaticObjectField(Class<?> clazz, String fieldName) { return null; }
    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) { }
    public static int getIntField(Object obj, String fieldName) { return 0; }
    public static void setIntField(Object obj, String fieldName, int value) { }
    public static boolean getBooleanField(Object obj, String fieldName) { return false; }
    public static void setBooleanField(Object obj, String fieldName, boolean value) { }
    public static long getLongField(Object obj, String fieldName) { return 0L; }
    public static void setLongField(Object obj, String fieldName, long value) { }
    public static int getStaticIntField(Class<?> clazz, String fieldName) { return 0; }
    public static void setStaticIntField(Class<?> clazz, String fieldName, int v) { }
    public static boolean getStaticBooleanField(Class<?> clazz, String fieldName) { return false; }
    public static void setStaticBooleanField(Class<?> clazz, String fieldName, boolean v) { }
    public static Object callMethod(Object obj, String methodName, Object... args) { return null; }
    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) { return null; }
    public static Object newInstance(Class<?> clazz, Object... args) { return null; }
    public static void setAdditionalInstanceField(Object obj, String key, Object value) { }
    public static Object getAdditionalInstanceField(Object obj, String key) { return null; }
}
