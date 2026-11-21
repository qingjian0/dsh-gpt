package de.robv.android.xposed;
public abstract class XC_MethodReplacement extends XC_MethodHook {
    public static final XC_MethodReplacement DO_NOTHING = new XC_MethodReplacement(20000) {
        protected Object replaceHookedMethod(MethodHookParam param) { return null; }
    };
    public XC_MethodReplacement() { super(); }
    public XC_MethodReplacement(int priority) { super(priority); }
    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable { return null; }
    protected final void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected final void afterHookedMethod(MethodHookParam param) throws Throwable {}
    public static XC_MethodReplacement returnConstant(final Object result) {
        return new XC_MethodReplacement(10000) {
            protected Object replaceHookedMethod(MethodHookParam param) { return result; }
        };
    }
    public static XC_MethodReplacement doNothing() { return DO_NOTHING; }
}
