# GPT Hook Mode

SukiSU/KernelSU + Zygisk + LSPosed 的 ChatGPT（`com.openai.chatgpt`）hook 模式。
纯 Java + 反射，无第三方依赖，`build.sh` 在手机上直接出包，不联网、不需要 gradle。

## 结构

```
app/src/main/java/com/gpt/hook/
  MainHook.java      入口：作用域判断 → 分层装载 → 配置热加载线程
  Cfg.java           gpt-hook.json 配置（目标应用私有目录，root 可写）
  DynHook.java       动态 hook 引擎：类/方法/参数/返回值/字段全部由 JSON 驱动
  ClassScanner.java  运行时枚举 dex 类名 → class-scan.txt（免 jadx 全量反编译）
  XLog.java          logcat + files/gpt-hook.log 双写
  XF.java            ���全反射取值
  hooks/
    DeviceSpoof      Build / SystemProperties / ANDROID_ID / IMEI / MAC / MediaDrm
    RootCloak        File.exists·isFile·canRead、Runtime.exec、ProcessBuilder、
                     PackageManager、Debug 检测
    GuardHook        进程表、安装来源、System.getProperty
    NetWatch         OkHttp CertificatePinner、Conscrypt TrustManagerImpl、
                     OkHostnameVerifier 绕过 + OkHttp/Cronet 请求落日志
    Telemetry        Firebase/Statsig/Amplitude/Segment/Sentry/Mixpanel/
                     Braze/AppsFlyer/Adjust/... 上报置空
    FeatureGate      Statsig FeatureGate/checkGate + 自研 isXxx() 开关强制 true
toolchain/xposed-stub/  Xposed API 编译桩（compileOnly，不进 dex）
build.sh   一键编译 + 签名    install.sh  安装 + 写 LSPosed 作用域 + 部署配置
hook.sh    动态 hook 控制台    tools/hook.mjs  CLI 实现
```

## 构建 / 安装

```sh
sh build.sh              # -> build/gpt-hook.apk
sh install.sh            # 安装 + 写 LSPosed db + 部署 gpt-hook.json + force-stop 目标
```

`install.sh` 直接写 `/data/adb/lspd/config/modules_config.db`（先备份）：
`modules` / `modules_state(enabled=1)` / `scope(com.openai.chatgpt)`，
然后重启 lspd 守护进程。**若 lspd 未拉起，重启设备一次即可**（service.sh 会重新
`daemon --force`），也可以在 LSPosed 管理器里手动启用模块 + 勾选作用域后重启应用。

## 配置

`/data/data/com.openai.chatgpt/files/gpt-hook.json`（install.sh 自动部署）。

| 键 | 说明 |
|---|---|
| `device_spoof` `root_cloak` `net_watch` `telemetry_block` `feature_unlock` | 各静态层开关 |
| `dump_requests` `dump_headers` | HTTP 头/体落日志（OkHttp + Cronet） |
| `spoof` | Build 字段覆盖表 |
| `android_id` | 固定 ANDROID_ID |
| `scan_on_start` + `scan_filter` | 启动时导出匹配的类名表 |
| `hooks` / `fields` / `noop` | 动态引擎条目 |

文件被修改后 2 秒内自动热加载（内置签名去重，重复应用安全）。

## 动态 hook（不用重编译）

```sh
sh hook.sh show
sh hook.sh add '{"class":"com.openai.chatgpt.X","method":"isPlus","mode":"return","result":true}'
sh hook.sh add '{"class":"com.openai.chatgpt.Y","method":"a","mode":"dump","stack":true}'
sh hook.sh noop com.statsig.androidsdk.StatsigClient logEvent
sh hook.sh field com.openai.chatgpt.BuildConfig DEBUG true
sh hook.sh ret com.openai.chatgpt.Z isPro true
sh hook.sh scan on          # 下次启动写 files/class-scan.txt
sh hook.sh cat plus         # 过滤类名表
sh hook.sh log              # 日志尾部
```

`mode`: `dump`（记参数/返回/可选堆栈）· `return`（强制返回 `result`）·
`noop`（返回 null）· `throw`（抛出使检测失败）· `args`（按 `"args":{"0":...}` 改参）。

## 逆向闭环

1. `sh hook.sh scan on` → 重启应用 → `sh hook.sh cat plus|entitle|integrity|bot`
2. 从 class-scan.txt 挑锚点，`add` 一条 `mode:"dump", "stack":true`
3. 看 `hook.sh log` 找真实调用路径与返回值
4. 改成 `mode:"return"` 落最终效果，全程无需重编译 APK

## 反编译辅助（可选）

```sh
apktool d -f -o out target.apk      # 资源/manifest
jadx -d src target.apk              # 全量 Java（大 APK 慢，优先用 ClassScanner）
unzip -l split_config.arm64_v8a.apk # native 库分布
```

## 边界

- 只在 scope 内的进程注入；`system_server` 不在作用域。
- 订阅/计费类伪造默认关闭（`billing` 未启用）：伪造 Purchase 需要服务端签名，
  客户端改状态只在 UI 层有效，且会触发校验失败路径。
- Play Integrity 交由本机 `playintegrityfix`/`tricky_store` 模块处理，本模块只做
  客户端侧的设备指纹一致性。

## 实测记录（Android 16 / ChatGPT 1.2026.321 / LSPosed 2.2.1）

注入与静态层：`=== all hooks in 63~77ms`，Build/SystemProperties/ANDROID_ID/File/Runtime.exec/
PackageManager/Debug/TrustManager/FeatureGate 全部挂上。

需要注意的坑（已修）：

1. **LSPosed 的 dex 混淆**把模块里的 `de.robv.android.xposed.*` 重命名为随机包，
   运行时按 **完整 proto（含返回类型）** 解析方法。`hookAllMethods` 必须是
   `Set<XC_MethodHook.Unhook>` 而不是 `Unhook`，否则 `NoSuchMethodError`。
   编译桩已按真实签名对齐，见 `toolchain/xposed-stub`。
2. `ActivityThread.currentApplication()` 在 `handleLoadPackage` 阶段是 null，
   配置/日志路径改用 `/proc/self/cmdline` 推导包名 + `/data/data/<pkg>/files/`，
   注入第一时间即可读写。
3. 该版本网络栈是 **Cronet（`org.chromium.net.*`，154 个类）+ GmsCore 动态 provider**，
   `org.chromium.net.impl.CronetUrlRequest` 不在 base dex 里，Java 层 hook 抓不到请求体；
   `okhttp3` 只剩 4 个类。Cronet 的 pinning 在 native(BoringSSL)，需要证书注入而非 hook。
4. `Firebase/Statsig/Amplitude/...` 在包里 **0 命中**，统计栈是自研的
   `com.openai.valdi.oai.logging.*`，要按实际类名用动态层挂。
5. 类总量 97226；`com.openai.*` 里可用的锚点族：
   `com.openai.valdi.celsius.*`（订阅状态）、`com.openai.feature.subscriptions.*`（购买/错误枚举）、
   `com.openai.integrity.*`（Play Integrity）、`com.openai.valdi.oai.platform.*`（API/账户）。

典型探测流程：

```sh
sh hook.sh inspect com.openai.valdi.oai.platform.OAIAPIRequest
# 重启目标 → files/gpt-hook.log 里有全部方法签名 + 字段值
sh hook.sh add '{"class":"...","method":"...","mode":"dump","stack":true}'
sh hook.sh ret  '...' '...' true        # 落效果
```
