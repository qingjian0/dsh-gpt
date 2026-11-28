-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# androidx.security-crypto 经 tink 传递引用 errorprone 注解，运行时不需要
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.j2objc.annotations.**
