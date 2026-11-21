package de.robv.android.xposed;
import java.util.HashSet;
import java.util.Set;
public final class XposedBridge {
    public static int getXposedVersion() { return 93; }
    public static void log(String text) { }
    public static void log(Throwable t) { }
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) { return new HashSet<>(); }
    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) { return new HashSet<>(); }
}
