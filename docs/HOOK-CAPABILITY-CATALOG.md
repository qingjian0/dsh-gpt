# DSH-GPT Hook 功能清单与运行边界

> 审查基准：`fix/hook-overview-models-ui-v2`（PR #53 head）
> 目标包：`com.openai.chatgpt`
> 本文区分“当前 APK 已编译能力”“旧版参考实现”“运行时实际安装”。代码存在不等于目标进程安装成功，更不等于真机验收。

## 1. 当前 APK 的 Hook 体系

根 Gradle 工程只编译 `:app`。当前 Hook 入口为 libxposed API 102 的 `MainModule`，运行在 ChatGPT 目标进程。控制 App 的 `MainActivity` / `LocalApiServer` 负责配置和查看状态；`ObservationProvider` 负责跨进程交换有限元数据。

### A. 内置硬化 Hook（Hardening.kt）

当前配置默认 `hardening=true`。这些 Hook 不属于动态规则列表，必须在 UI 单独展示，不能只显示动态规则数。

| 分组 | 代码中的实际目标 | 行为/说明 | 当前状态判断 |
|---|---|---|---|
| 设备信息 | `android.os.Build` 的 MANUFACTURER、BRAND、MODEL、DEVICE、PRODUCT、HARDWARE、FINGERPRINT、BOOTLOADER、TAGS、TYPE、HOST | 通过反射/Unsafe 尝试覆盖字段 | 逐字段成功数需运行时登记；不能只按配置显示已生效 |
| 系统属性 | `android.os.SystemProperties.get(String)`、`get(String,String)` | 对少量指定属性返回固定值 | 每个方法 Hook 成功后才可标记已安装 |
| 文件检测 | `java.io.File.exists/isFile/canRead/canExecute` | 对代码内列出的可疑路径返回隐藏结果 | 最多 4 个目标方法，逐个报告 |
| 命令执行 | `java.lang.Runtime.exec(String)`、`exec(String[])` | 对特定命令阻止执行 | 最多 2 个目标方法，逐个报告 |
| 包查询 | `ApplicationPackageManager.getPackageInfo(String,int)` | 对代码内列出的包名返回未找到 | 单独报告安装结果 |
| 调试检测 | `android.os.Debug.isDebuggerConnected()`、`waitingForDebugger()` | 返回固定布尔结果 | 最多 2 个目标方法 |
| 证书校验 | OkHttp `CertificatePinner.check(...)`；Conscrypt `TrustManagerImpl.verifyChain(...)` | 改变证书校验调用结果 | 只有类和方法存在且安装成功才算已安装；目标 App 可能不走这些路径 |
| 遥测调用 | Firebase、Crashlytics、Statsig、Amplitude、Segment、Sentry、Mixpanel、AppsFlyer、Adjust 的指定方法 | 仅对代码中列出的 SDK/方法尝试 Hook | 逐个报告；SDK 不存在时为未匹配，不可算成功 |

**重要限制：**
- 内置硬化模块目前只记录汇总成功数到日志，没有把每个成功安装的 Hook ID、目标方法和失败原因发布到状态 API。
- `Hardening.install()` 使用进程级 `installed` 标记；当前没有完整的内置 Hook 句柄登记/卸载实现。
- 动态 Hook 总开关关闭时，`HookRuntime.clear()` 会撤销动态规则，但不能据此宣称上表所有内置 Hook 都已关闭。
- 因此当前 UI 必须明确区分“动态规则开关”和“内置硬化状态”，不能给一个含义不清的全局开关。

### B. 动态 Hook Runtime（HookRuntime.kt）

当前规则协议限制：
- 仅目标包 `com.openai.chatgpt`，类名前缀仅允许 `com.openai.*`。
- 最多 64 条规则；每条最多 32 个参数类型；最多 8 个匹配重载。
- 使用精确类名 + 方法名 + 可选参数类型或 JVM 方法描述符匹配。
- 支持模式：
  - `observe`：执行原方法，记录调用耗时和异常类型。
  - `dump`：当前实现与 observe 同样只记录元数据；不是参数/返回值 dump。
  - `return`：对通过安全策略验证的方法返回配置标量。
  - `args`：覆盖指定的 primitive/String 参数后继续原调用。
  - `noop`：跳过原方法并返回默认值。
  - `throw`：跳过原方法并抛出配置异常。
- 构造器仅支持观察模式。
- 变更行为的模式受到敏感目标策略限制；认证、会话、Token、网络、支付、完整性、安全和加密等目标不允许使用行为修改模式。
- 配置快照更新时支持新增、替换、保留、移除、失败统计；可撤销已登记动态 Hook。
- 事件仅保存规则 ID、类/方法签名、耗时、异常类型，不保存参数、返回值、Cookie、Token、HTTP 头和请求体。

UI 应逐条显示：规则 ID、类名、方法名、参数/描述符、模式、启用开关、匹配重载数、实际安装方法签名、调用次数、最近事件、安装失败原因、配置版本和应用时间。

### C. 动态类加载监听（ClassLoadWatcher.kt）

- 监听 `ClassLoader.loadClass(String,boolean)`。
- 只关注 `com.openai.*` 类。
- 使用去重门和线程重入保护，最多记忆 2048 个动态类。
- 新类加载后立即尝试应用当前匹配规则。
- 可由配置 `observeClassLoads` 控制。
- 当前报告仅暴露 watcher 是否启用；应补充类加载计数、最近类名、最近触发时间、回调安装成功/失败统计。

### D. DEX / 类元数据发现（DexDiscoveryEngine.kt）

- DEX 扫描上限：16 个 DEX、2500 个类、500 条成员记录、1500ms、64 KiB 响应（均可由 Limits 控制）。
- 支持类名前缀筛选和精确类名查询。
- 返回 declared methods、inherited methods、constructors、fields 的名称/类型/修饰符。
- 可产生候选规则提案，但不会调用目标方法，也不会读取字段值。
- 结果可能因时间、数量或字节预算截断，必须显示“部分结果/已截断”，不能当成完整扫描。
- 支持 cursor 参数；需要 UI 显示扫描范围、耗时、已扫描类数、DEX 数、记录数、截断状态和下一页。

### E. 观察事件与请求抓取

- `EventBuffer`：最多 100 条默认事件，元数据级别；动态 Hook 触发和类加载记录会写入。
- `RequestDump`：可选开关 `dumpRequests`，Hook `JSONObject.toString()`，对包含特定 conversation 字段的 JSON 请求写入 `request-dump.log`。这不是动态 Hook 事件，可能含敏感请求内容；默认关闭，UI 必须明确提示敏感性、显示开关和清理入口，不应把原文直接放在首页。
- 观察报告当前通过 ContentProvider 保存；`targetObserved` 只是“曾收到报告”，不是在线心跳。没有心跳协议前 UI 只能显示“最近报告时间”，不能显示“当前在线”。

## 2. 旧版 gpt-hook（不属于当前 APK）

`gpt-hook/` 是独立 Java / 旧 Xposed API 工程，根工程没有 include，不得显示为当前已安装功能。

旧版代码包含：
- `DeviceSpoof`：Build、系统属性、ANDROID_ID、IMEI、MAC、MediaDrm 等设备信息修改。
- `RootCloak` / `GuardHook`：文件、命令、包管理、进程/安装来源/系统属性检测相关逻辑。
- `NetWatch`：OkHttp/Conscrypt 证书校验相关逻辑和网络观察。
- `CronetSpy` / `Capture`：网络路径/请求元数据观察尝试。
- `Telemetry`：多种统计 SDK 事件调用。
- `FeatureGate`：Statsig 与特定开关方法观察/修改尝试。
- `DynClassWatch`、`ClassScanner`、`DynHook`：旧动态类观察、DEX 类扫描和 JSON 规则引擎。

这些只应出现在“旧版参考/未迁移能力”页面或开发者能力矩阵，状态明确标记“未集成到当前 APK”，不能与当前运行清单混排。

## 3. 当前 Hook API 能力

| API | 用途 |
|---|---|
| `GET /v1/hook/status` | 配置规则、动态已安装清单、应用报告、最近报告时间、发现任务 |
| `GET /v1/hook/events` | 有界元数据事件 |
| `GET /v1/hook/startup-discovery` | 自动启动发现结果 |
| `GET /v1/hook/config` / `PUT /v1/hook/config` | 读取/替换完整规则快照 |
| `PUT /v1/hook/enabled` | 启用/关闭动态规则运行时 |
| `POST /v1/hook/inspect` | 检查指定类的元数据 |
| `POST /v1/hook/discover` | 有界 DEX 类/成员发现 |
| `GET /v1/hook/discovery/{requestId}` / `DELETE ...` | 查询/取消发现任务 |

## 4. UI 必须遵守的状态语义

- **已配置**：控制 App 本地持有规则；不代表目标进程收到。
- **已下发**：目标进程确认读取当前 config revision。
- **已安装**：当前目标进程会话中的 HookHandle 确认存在。
- **已触发**：至少有一次调用计数或事件。
- **未匹配**：目标类已加载/扫描，但没有匹配方法。
- **安装失败**：目标进程返回具体失败阶段/原因。
- **待检测**：当前目标进程没有足够证据。
- **最近报告**：报告时间；不得等价为在线状态。
- **旧版未集成**：仅存在于 `gpt-hook/` 参考工程。

禁止用一个“等待目标进程报告”代替规则列表；本地规则必须立即展示，实际状态异步补充。
