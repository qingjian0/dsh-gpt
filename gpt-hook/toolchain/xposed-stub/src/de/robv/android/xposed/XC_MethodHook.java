package de.robv.android.xposed;
import java.lang.reflect.Member;
public abstract class XC_MethodHook {
    public static final int PRIORITY_DEFAULT = 50;
    public static final int PRIORITY_LOWEST = -10000;
    public static final int PRIORITY_HIGHEST = 10000;
    private final int priority;
    public XC_MethodHook() { this(PRIORITY_DEFAULT); }
    public XC_MethodHook(int priority) { this.priority = priority; }
    public int getPriority() { return priority; }
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        public Object getResult() { return null; }
        public void setResult(Object result) {}
        public Throwable getThrowable() { return null; }
        public void setThrowable(Throwable t) {}
        public boolean hasThrowable() { return false; }
        public Object getObjectExtra(String key) { return null; }
        public void setObjectExtra(String key, Object o) {}
    }
    public static class Unhook {
        public Member getHookedMethod() { return null; }
        public XC_MethodHook getCallback() { return null; }
        public void unhook() {}
    }
}
