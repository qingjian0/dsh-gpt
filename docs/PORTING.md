# 把已验证的 hook 能力移植进 dsh-gpt（交给 GPT 执行）

来源：`gpt-hook/`（经典 Xposed API，Java）→ 目标：`app/src/main/java/com/gpt/hook/`（libxposed 102，Kotlin）

---

## 0 已验证成功的能力（只移植这些，别扩）

| # | 能力 | 源文件 | 实测证据 |
|---|---|---|---|
| 1 | 模块注入 | `MainHook.java` | `all hooks in 63~77ms`，0 miss |
| 2 | 设备指纹伪装 | `hooks/DeviceSpoof.java` | 日志逐条打印 11 个 Build 字段写入成功 |
| 3 | root/xposed 痕迹遮蔽 | `hooks/RootCloak.java` | `File checks cloaked` / `Runtime.exec cloaked` |
| 4 | 运行环境一致性 | `hooks/GuardHook.java` | `guard armed` |
| 5 | 声明式动态 hook 引擎 | `DynHook.java` | `mode=dump/return/noop/args/inspect` 全部实测 |
| 6 | 运行时类枚举 | `ClassScanner.java` | `classes=97226 matched=97226` |
| 7 | 本地 OpenAI 兼容 API | `hooks/Gateway.java` + `api/LocalApiServer.kt` | `/health` 200、chat `content="OK"`、流式 `[DONE]` |
| 8 | 反代（ChatGPT 会话） | `relay/*.mjs` | `/me` `/models` `/conversation` 全 200 |
| 9 | 会话复用 | `relay/openai-relay.mjs` | 第 2 轮答出第 1 轮记的 `47` |

**不要移植**（实测无效或未触发）：`CronetSpy`、`DynClassWatch`、`Capture`、`DPoPSign`、`Telemetry`（类 0 命中）、`FeatureGate`（类 0 命中）。

---

## 1 libxposed 102 写法对照

经典 API（源）→ libxposed 102（目标）：

```kotlin
// 源（经典）
XposedHelpers.findAndHookMethod(clazz, "name", A::class.java, object : XC_MethodHook() {
    override fun beforeHookedMethod(p: MethodHookParam) { p.result = X }
})

// 目标（libxposed 102）
val m = clazz.getDeclaredMethod("name", A::class.java)
hook(m).setId("dsh.device.manufacturer").intercept { chain ->
    chain.setResult(X)      // 对应 before + setResult
    chain.proceed()         // 或直接 return X 短路
}
```

关键差异：

| 经典 | libxposed 102 |
|---|---|
| `beforeHookedMethod` + `param.setResult` | `chain.setResult(x)` 后 `chain.proceed()`，或直接 `return x` |
| `afterHookedMethod` + `param.getResult` | `val r = chain.proceed(); ...; r` |
| `param.args[i] = v` | `chain.setArg(i, v)` |
| `param.setThrowable(t)` | `throw t` |
| `XposedBridge.hookAllMethods(c, name, cb)` | 遍历 `c.declaredMethods.filter { it.name == name }` 逐个 `hook()`，id 要带签名摘要 |
| `XposedHelpers.findClass(name, cl)` | `Class.forName(name, false, param.classLoader)` |

---

## 2 移植清单（按优先级）

### P0 · 设备指纹伪装（体积 ≈ 4KB dex）

新文件 `app/src/main/java/com/gpt/hook/hardening/DeviceSpoof.kt`

```kotlin
object DeviceSpoof {
    private val BUILD_FIELDS = mapOf(
        "MANUFACTURER" to "Google", "BRAND" to "google", "MODEL" to "Pixel 9 Pro",
        "DEVICE" to "caiman", "PRODUCT" to "caiman", "HARDWARE" to "caiman",
        "FINGERPRINT" to "google/caiman/caiman:15/AP4A.250105.002/1234567:user/release-keys",
        "BOOTLOADER" to "caiman-1.0", "TAGS" to "release-keys", "TYPE" to "user", "HOST" to "abfarm",
    )

    fun install(mod: XposedModule, cl: ClassLoader) {
        val build = Class.forName("android.os.Build", false, cl)
        for ((name, value) in BUILD_FIELDS) {
            val f = build.getDeclaredField(name)
            f.isAccessible = true
            runCatching { f.set(null, value) }
        }
        // SystemProperties.get(String) / get(String,String)
        val sp = Class.forName("android.os.SystemProperties", false, cl)
        val get1 = sp.getDeclaredMethod("get", String::class.java)
        val get2 = sp.getDeclaredMethod("get", String::class.java, String::class.java)
        for ((m, id) in listOf(get1 to "dsh.sp.get1", get2 to "dsh.sp.get2")) {
            m.isAccessible = true
            mod.hook(m).setId(id).intercept { chain ->
                when (val k = chain.getArg(0) as? String) {
                    "ro.debuggable" -> "0"
                    "ro.secure" -> "1"
                    "ro.build.tags" -> "release-keys"
                    "ro.build.type" -> "user"
                    else -> if (k?.startsWith("init.svc.magisk") == true) "" else chain.proceed()
                }
            }
        }
    }
}
```

注意：Build 字段是 `static final String`，ART 上直接 `Field.set` 对部分字段会抛 `IllegalAccessException`，用 `XposedHelpers.setStaticObjectField` 等价做法不可用时，**用 hook 加在 `Build.<clinit>` 之后无效**，实际可行的是 hook 各 getter 调用点；本机实测直接 `f.set` 对 11 个字段全部成功，所以你沿用 `runCatching` 即可。

验收：日志里出现每个字段的写入成功记录；在目标应用内 hook `Build.MODEL` 的读取方，返回应为 `Pixel 9 Pro`。

### P0 · root 痕迹遮蔽（体积 ≈ 3KB）

新文件 `hardening/RootCloak.kt`，hook 点：

```
java.io.File.exists / isFile / canRead / canExecute   → 路径命中黑名单返回 false
java.lang.Runtime.exec(String) / exec(String[])       → 命中 su/magisk/ksu 抛 IOException("EACCES")
java.lang.ProcessBuilder.start                        → 同上
android.app.ApplicationPackageManager.getPackageInfo  → magisk/ksu/lsposed 抛 NameNotFoundException
android.app.ApplicationPackageManager.getInstalledPackages/Applications → 过滤
android.os.Debug.isDebuggerConnected / waitingForDebugger → false
```

黑名单（大小写不敏感，basename 精确匹配 + 路径包含）：
`su, sush, daemonsu, busybox, ksu, magisk*, frida-server*`；路径包含 `/magisk /kernelsu /apatch /lsposed /xposed /edxposed /frida`。

**性能红线**：`File.exists` 是热点，必须做「上一路径缓存」或前缀快速判断，禁止每次都 `toLowerCase()` 全串匹配。

验收：目标应用内 `new File("/system/bin/su").exists()` 返回 false；`Runtime.exec("su")` 抛 IOException。

### P1 · 动态规则引擎（体积 ≈ 8KB）

`DynHook.java` 的 JSON 能力并进现有 `HookConfigParser`，给 `HookRule` 加字段：

```kotlin
val mode: String = "observe"        // observe | return | block | args | throw
val result: Any? = null             // mode=return
val argPatch: Map<Int, Any> = emptyMap()  // mode=args
val dumpStack: Boolean = false
```

`HookRuntime` 里按 `mode` 分派。**保留现有 `observe` 的元数据-only 语义**，新 mode 才读写参数/返回值。

验收：配置一条 `mode:"return"` 规则，目标方法返回值被改写。

### P1 · 类枚举（体积 ≈ 3KB）

`ClassScanner.java` → `diagnostics/ClassScanner.kt`，仍然只写 `files/class-scan.txt`，UI 侧加一个「导出类表」按钮。

验收：`class-scan.txt` 行数 ≈ 97000。

### P2 · 本地 API 增强（体积 ≈ 0，已有）

`LocalApiServer` 补会话复用（见 `docs/RELAY-SESSION.md`）：`ConcurrentHashMap<String, Pair<String,String>>`，key 用 OpenAI 请求体的 `user`。

验收：两轮请求，第二轮能答出第一轮记的内容。

---

## 3 体积预算

| 模块 | 增量 dex |
|---|---|
| DeviceSpoof | ~4KB |
| RootCloak + GuardHook | ~5KB |
| DynHook 扩展 | ~8KB |
| ClassScanner | ~3KB |
| 合计 | **~20KB** |

当前 APK 987K，移植后 ~1.0M，仍在 2M 内；若要压到 500K，先砍 `libxposed.service` 的可选部分或 UI 依赖，别砍 hook。

---

## 4 不许做的事

- 不要动 `ObservationProvider` 的 UID 校验逻辑。
- 不要把 hook 的返回类型写成 `Unhook`（libxposed 102 的 `hook()` 返回 `HookHandle`）。
- 不要在 `File.exists` 里做正则。
- 不要引入第三方依赖。
