package com.gpt.hook.hardening

import com.gpt.hook.diagnostics.ModuleLogger
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 内置硬化 hook：设备指纹伪装、root 痕迹遮蔽、SSL pinning 绕过、遥测阻断。
 *
 * 与声明式规则引擎（HookRuntime）互不影响：这里全部由代码直接安装，
 * 不需要 className 白名单，能作用于 android.* / java.* / conscrypt.*。
 */
object Hardening {

    private const val TAG = "hardening"

    private val BUILD_FIELDS = mapOf(
        "MANUFACTURER" to "Google",
        "BRAND" to "google",
        "MODEL" to "Pixel 9 Pro",
        "DEVICE" to "caiman",
        "PRODUCT" to "caiman",
        "HARDWARE" to "caiman",
        "FINGERPRINT" to "google/caiman/caiman:15/AP4A.250105.002/1234567:user/release-keys",
        "BOOTLOADER" to "caiman-1.0",
        "TAGS" to "release-keys",
        "TYPE" to "user",
        "HOST" to "abfarm"
    )

    private val BAD_SEGMENTS = listOf(
        "/magisk", "/kernelsu", "/apatch", "/lsposed", "/xposed",
        "/edxposed", "/frida", "/supersu", "/.magisk"
    )
    private val BAD_BASENAMES = listOf("su", "sush", "daemonsu", "busybox", "ksu")

    private val BAD_PACKAGES = setOf(
        "com.topjohnwu.magisk", "io.github.huskydg.magisk", "me.weishu.kernelsu",
        "com.rifsxd.ksunext", "me.bmax.apatch", "org.lsposed.manager",
        "de.robv.android.xposed.installer", "com.saurik.substrate"
    )

    private val TELEMETRY_METHODS = listOf(
        "com.google.firebase.analytics.FirebaseAnalytics" to "logEvent",
        "com.google.firebase.crashlytics.FirebaseCrashlytics" to "recordException",
        "com.statsig.androidsdk.StatsigClient" to "logEvent",
        "com.amplitude.api.Amplitude" to "logEvent",
        "com.segment.analytics.Analytics" to "track",
        "io.sentry.Sentry" to "captureException",
        "io.sentry.Sentry" to "captureMessage",
        "com.mixpanel.android.mpmetrics.MixpanelAPI" to "track",
        "com.appsflyer.AppsFlyerLib" to "logEvent",
        "com.adjust.sdk.Adjust" to "trackEvent"
    )

    private var installed = false

    fun install(framework: XposedInterface, cl: ClassLoader) {
        if (installed) return
        installed = true
        var ok = 0
        ok += spoofBuild(cl)
        ok += spoofSystemProperties(framework, cl)
        ok += cloakFiles(framework, cl)
        ok += cloakRuntime(framework, cl)
        ok += cloakPackages(framework, cl)
        ok += cloakDebug(framework, cl)
        ok += bypassPinning(framework, cl)
        ok += blockTelemetry(framework, cl)
        ModuleLogger.i("$TAG installed; hooks=$ok")
    }

    /**
     * android.os.Build 的字段是 static final，ART 上普通反射 set 会被拒。
     * 用 sun.misc.Unsafe 的 staticFieldBase/staticFieldOffset + putObject 绕过。
     */
    private fun spoofBuild(cl: ClassLoader): Int {
        val build = try { Class.forName("android.os.Build", false, cl) } catch (_: Throwable) { return 0 }
        val unsafeClass = try { Class.forName("sun.misc.Unsafe") } catch (_: Throwable) { null }
        val theUnsafe = try {
            unsafeClass?.getDeclaredField("theUnsafe")?.apply { isAccessible = true }?.get(null)
        } catch (_: Throwable) { null }
        val base = try { unsafeClass?.getMethod("staticFieldBase", Field::class.java) } catch (_: Throwable) { null }
        val offset = try { unsafeClass?.getMethod("staticFieldOffset", Field::class.java) } catch (_: Throwable) { null }
        val putObject = try {
            unsafeClass?.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
        } catch (_: Throwable) { null }

        var n = 0
        for ((name, value) in BUILD_FIELDS) {
            try {
                val f = build.getDeclaredField(name)
                f.isAccessible = true
                if (theUnsafe != null && base != null && offset != null && putObject != null) {
                    val b = base.invoke(theUnsafe, f)
                    val off = offset.invoke(theUnsafe, f) as Long
                    putObject.invoke(theUnsafe, b, off, value)
                    n++
                } else {
                    f.set(null, value)
                    n++
                }
            } catch (t: Throwable) {
                ModuleLogger.w("$TAG build field $name failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        ModuleLogger.i("$TAG Build fields spoofed: $n/${BUILD_FIELDS.size} (unsafe=${theUnsafe != null})")
        return n
    }

    private fun spoofSystemProperties(framework: XposedInterface, cl: ClassLoader): Int {
        return try {
            val sp = Class.forName("android.os.SystemProperties", false, cl)
            val get1 = sp.getDeclaredMethod("get", String::class.java).apply { isAccessible = true }
            val get2 = sp.getDeclaredMethod("get", String::class.java, String::class.java).apply { isAccessible = true }
            framework.hook(get1).setId("dsh.spoof.sp1").intercept { chain ->
                when (val k = chain.getArg(0) as? String) {
                    "ro.debuggable" -> "0"
                    "ro.secure" -> "1"
                    "ro.build.tags" -> "release-keys"
                    "ro.build.type" -> "user"
                    else -> if (k != null && k.startsWith("init.svc.magisk")) "" else chain.proceed()
                }
            }
            framework.hook(get2).setId("dsh.spoof.sp2").intercept { chain ->
                when (val k = chain.getArg(0) as? String) {
                    "ro.debuggable" -> "0"
                    "ro.secure" -> "1"
                    "ro.build.tags" -> "release-keys"
                    "ro.build.type" -> "user"
                    else -> if (k != null && k.startsWith("init.svc.magisk")) "" else chain.proceed()
                }
            }
            2
        } catch (t: Throwable) {
            ModuleLogger.w("$TAG SystemProperties hook failed: ${t.javaClass.simpleName}")
            0
        }
    }

    private fun isSuspicious(path: String?): Boolean {
        if (path == null || path.isEmpty()) return false
        val lower = path.lowercase()
        val slash = lower.lastIndexOf('/')
        val base = if (slash < 0) lower else lower.substring(slash + 1)
        if (BAD_BASENAMES.any { base == it }) return true
        if (base.startsWith("magisk") || base.startsWith("frida-server") || base.startsWith("riru")) return true
        return BAD_SEGMENTS.any { lower.contains(it) }
    }

    private fun cloakFiles(framework: XposedInterface, cl: ClassLoader): Int {
        val fileClass = try { Class.forName("java.io.File", false, cl) } catch (_: Throwable) { return 0 }
        var n = 0
        for (name in listOf("exists", "isFile", "canRead", "canExecute")) {
            try {
                val m: Method = fileClass.getDeclaredMethod(name)
                framework.hook(m).setId("dsh.cloak.file.$name").intercept { chain ->
                    val f = chain.thisObject as? java.io.File
                    if (f != null && isSuspicious(f.path)) false else chain.proceed()
                }
                n++
            } catch (_: Throwable) {
            }
        }
        return n
    }

    private fun cloakRuntime(framework: XposedInterface, cl: ClassLoader): Int {
        val rt = try { Class.forName("java.lang.Runtime", false, cl) } catch (_: Throwable) { return 0 }
        var n = 0
        try {
            val exec1 = rt.getDeclaredMethod("exec", String::class.java)
            framework.hook(exec1).setId("dsh.cloak.exec1").intercept { chain ->
                val cmd = chain.getArg(0) as? String
                if (cmd != null && isSuspicious(cmd.trim().split(" ").firstOrNull())) {
                    throw java.io.IOException("Cannot run program: EACCES")
                }
                chain.proceed()
            }
            n++
        } catch (_: Throwable) {
        }
        try {
            val exec2 = rt.getDeclaredMethod("exec", Array<String>::class.java)
            framework.hook(exec2).setId("dsh.cloak.exec2").intercept { chain ->
                @Suppress("UNCHECKED_CAST")
                val arr = chain.getArg(0) as? Array<String>
                if (arr != null && arr.isNotEmpty() && isSuspicious(arr[0])) {
                    throw java.io.IOException("Cannot run program: EACCES")
                }
                chain.proceed()
            }
            n++
        } catch (_: Throwable) {
        }
        return n
    }

    private fun cloakPackages(framework: XposedInterface, cl: ClassLoader): Int {
        val apm = try { Class.forName("android.app.ApplicationPackageManager", false, cl) } catch (_: Throwable) { return 0 }
        return try {
            val m = apm.getDeclaredMethod("getPackageInfo", String::class.java, Int::class.java)
            framework.hook(m).setId("dsh.cloak.pkg").intercept { chain ->
                val pkg = chain.getArg(0) as? String
                if (pkg != null && BAD_PACKAGES.contains(pkg)) {
                    throw android.content.pm.PackageManager.NameNotFoundException(pkg)
                }
                chain.proceed()
            }
            1
        } catch (_: Throwable) {
            0
        }
    }

    private fun cloakDebug(framework: XposedInterface, cl: ClassLoader): Int {
        val dbg = try { Class.forName("android.os.Debug", false, cl) } catch (_: Throwable) { return 0 }
        var n = 0
        for (name in listOf("isDebuggerConnected", "waitingForDebugger")) {
            try {
                val m = dbg.getDeclaredMethod(name)
                framework.hook(m).setId("dsh.cloak.dbg.$name").intercept { false }
                n++
            } catch (_: Throwable) {
            }
        }
        return n
    }

    private fun bypassPinning(framework: XposedInterface, cl: ClassLoader): Int {
        var n = 0
        try {
            val pinner = Class.forName("okhttp3.CertificatePinner", false, cl)
            for (m in pinner.declaredMethods) {
                if (m.name != "check") continue
                framework.hook(m).setId("dsh.pin.okhttp.${m.parameterCount}").intercept {
                    null
                }
                n++
                break
            }
        } catch (_: Throwable) {
        }
        try {
            val tm = Class.forName("com.android.org.conscrypt.TrustManagerImpl", false, cl)
            val m = tm.declaredMethods.firstOrNull { it.name == "verifyChain" }
            if (m != null) {
                framework.hook(m).setId("dsh.pin.conscrypt").intercept { chain ->
                    val r = runCatching { chain.proceed() }.getOrNull()
                    if (r == null) chain.getArg(0) else r
                }
                n++
            }
        } catch (_: Throwable) {
        }
        return n
    }

    private fun blockTelemetry(framework: XposedInterface, cl: ClassLoader): Int {
        var n = 0
        for ((cls, method) in TELEMETRY_METHODS) {
            try {
                val c = Class.forName(cls, false, cl)
                val m = c.declaredMethods.firstOrNull { it.name == method } ?: continue
                framework.hook(m).setId("dsh.tlm.$cls.$method").intercept {
                    null
                }
                n++
            } catch (_: Throwable) {
            }
        }
        return n
    }
}
