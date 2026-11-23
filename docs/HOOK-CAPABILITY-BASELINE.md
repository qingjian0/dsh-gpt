# DSH-GPT 动态 Hook 能力保全基线

> 基线提交：`ee01fef00e3b588e9df7b0b701f973fe260ef121`（main，2026-11-21）
> 本文用途：重构前的只读盘点与验收基线。本文本身不代表真机验收，也不授权删除现有实现。

## 0. 不可妥协目标

1. DSH 保留并增强动态 Hook：类/方法发现、运行时观察、规则热更新、Hook 安装与撤销。
2. ChatGPT 本地反代 API 独立稳定运行：OpenAI 兼容接口、SSE、上游透传、会话连续性。
3. APK 保持轻量：不内置 Node、AI 模型、开发工具和不必要运行时。
4. 以当前 main 为基线，分块验证；未证明等价前，不删除旧能力或替换引擎。

## 1. 当前仓库有两套不同代际的 Hook 实现

### A. 当前 APK 实际构建的 Kotlin / libxposed 运行时

根目录 `settings.gradle` 只 include `:app`。当前 APK 的入口和动态 Hook 实现在：

| 文件 | 当前职责 | 运行位置 | 当前判断 |
|---|---|---|---|
| `app/.../MainModule.kt` | libxposed API 102 入口；目标包过滤；读取远程偏好/共享文件；配置变更后异步应用 | ChatGPT 目标进程 | 当前有效入口 |
| `app/.../diagnostics/HookConfig.kt` | JSON 规则解析；64 条规则、128 KiB、仅 `com.openai.*`、每条最多 8 个重载 | 目标进程 | 当前规则契约 |
| `app/.../diagnostics/HookRuntime.kt` | 按方法签名安装 interceptor；稳定 Hook ID；采样；记录耗时/异常类型；撤销不再需要的 Hook | 目标进程 | 当前有效引擎 |
| `app/.../diagnostics/EventBuffer.kt` | 有界事件缓冲 | 目标进程 | 当前有效 |
| `app/.../diagnostics/RemoteConfigLoader.kt` | 读取 framework shared-files 中的规则文件 | 目标进程 | 当前有效回退通道 |
| `app/.../ObservationProvider.kt` | 通过 ContentProvider 把目标进程的有界元数据报告送回控制 App；校验 Binder UID | 控制 App 进程 | IPC 已实现，真机兼容性仍需验收 |
| `app/.../MainActivity.kt` | 规则编辑、校验、应用、观察报告查看/分享 | 控制 App 进程 | 当前控制界面 |

**当前 Kotlin Runtime 已有能力：**
- 以精确类名 + 方法名 + 可选参数类型匹配方法。
- 按确定性签名排序，最多匹配 8 个重载。
- 通过稳定 ID 安装/替换 Hook，单个方法失败不阻断其他规则。
- 规则快照更新后撤销已不再请求的 Hook；支持 clear。
- 按 sampleEvery 采样；事件只记录规则 ID、签名、耗时和异常类型。
- 目标进程与控制 App 通过 libxposed remote preferences / shared file / ContentProvider 协作。

**当前 Kotlin Runtime 尚不具备（不能误称已经实现）：**
- 从 DEX 全量枚举类并按关键词搜索。
- 任意类的交互式方法/字段/构造器发现。
- 构造器 Hook、字段读写 Hook。
- 参数/返回值变更、方法替换等通用执行模式。
- 目标类尚未加载时的通用类加载监听和延迟挂钩。
- 规则版本、差异预览、dry-run 与失败后的自动回滚策略。

### B. `gpt-hook/` 下的 Java / 旧 Xposed API 实现

该目录包含独立 Gradle 工程，但**根工程当前没有 include 它**，因此它不是当前根工程 APK 的编译输入。它应视为能力参考实现，不能直接当作当前 APK 已拥有的能力。

| 文件/组件 | 能力 | 迁移判断 |
|---|---|---|
| `ClassScanner.java` | 读取 BaseDexClassLoader 的 dexElements / DexFile.entries，按正则筛选类名并导出 | 高优先级保全；需做 Android 版本兼容和性能测试 |
| `DynHook.java` | JSON 驱动的动态 Hook；方法/构造器；重载；dump/return/noop/throw/args；静态字段设置；inspect | 作为功能参考；按安全策略拆分和重新实现，不能原样照搬 |
| `MainHook.java` | 旧 IXposedHookLoadPackage 入口；静态 Hook 组件装配；配置文件 mtime 轮询热加载 | 迁移装配思想，不迁移旧入口/API |
| `Cfg.java` | 本地 JSON 配置读取、默认值和开关 | 合并到统一规则配置协议 |
| `DynClassWatch.java` | 监听 ClassLoader.loadClass，目标动态类加载后延迟安装 Hook | 高优先级保全；避免重复监听、递归和热路径性能问题 |
| `XF.java` | 反射字段/对象辅助 | 按需保留小型工具 |
| `XLog.java` | 文件日志 | 与当前 ModuleLogger 统一 |
| `hooks/CronetSpy.java` | Cronet Builder / 实现类动态发现和观察 | 仅保留合规的元数据诊断用途 |
| `hooks/NetWatch.java`、`Capture.java` | 网络路径观察、请求元数据观察 | 重新定义严格脱敏字段和数据生命周期 |
| `hooks/DeviceSpoof.java`、`RootCloak.java`、`GuardHook.java`、`FeatureGate.java`、`Telemetry.java` | 设备伪装、隐藏运行环境、修改应用保护/功能门控等 | 不作为本次动态 Hook 核心能力的迁移目标；逐项独立审查，不能混入通用规则引擎 |
| `hooks/DPoPSign.java` | 观察并缓存 DPoP proof | 不迁移 proof/凭据捕获与持久化；反代凭据使用用户明确配置的 token 和设备标识 |
| `hooks/Gateway.java` | 旧进程内手写 HTTP 反代 | 不与控制 App Gateway 并存；统一使用控制 App 的 LocalApiServer |

旧 `DynHook` 的 `DONE` 集合是永久去重集合，配置热加载时不会清空；这会让同一条 JSON 规则的修改/撤销不能可靠生效。新实现必须以规则 ID + 完整方法签名建立可撤销的安装登记表，不能复刻该问题。

## 2. 能力保全矩阵

状态定义：
- **已有（代码）**：在当前 main 的 APK 编译输入中找到实现；不等于真机验收。
- **参考实现**：只在未纳入根工程的 `gpt-hook/` 找到。
- **待实现/待验收**：当前 main 尚无完整实现，或缺少真机证据。

| 能力 | 当前 APK Kotlin Runtime | Java 参考实现 | 重构目标 |
|---|---|---|---|
| libxposed API 102 注入入口 | 已有（代码） | 旧 API | 保留 API 102 |
| 规则 JSON 校验 | 已有（代码） | 旧 JSON 配置 | 统一 schema、版本化 |
| 指定类/方法 Hook | 已有（代码） | 已有 | 保留 |
| 指定参数类型/重载匹配 | 已有（代码，最多 8） | 已有 | 保留并允许显式上限配置 |
| DEX 类枚举/关键词发现 | 待实现 | 参考实现 | 补回并提供分页/限额 |
| 方法签名发现 | 仅规则指定类时内部匹配 | inspect | 提供显式 discover API |
| 构造器 Hook | 待实现 | 参考实现 | 评估后纳入安全规则模型 |
| 字段元数据发现 | 待实现 | inspect | 只读元数据发现 |
| 静态字段写入 | 待实现 | 参考实现 | 不作为首批；需单独权限和类型验证 |
| 参数/返回值修改 | 待实现 | 参考实现 | 不纳入默认观察规则；单独受控能力 |
| 类加载监听/延迟 Hook | 待实现 | 参考实现 | 保留动态类加载场景 |
| Hook 采样/性能统计 | 已有（代码） | 基础日志 | 保留并补性能指标 |
| 规则热更新 | 已有（代码） | 文件轮询但去重缺陷 | 保留，改成版本化原子应用 |
| Hook 撤销/清理 | 已有（代码） | 旧实现不足 | 必须有 |
| 观察事件回传控制 App | 已有（代码） | 文件日志 | 保留并验收 Binder/Provider |
| AI 多轮分析与改规则 | 人工分享/粘贴闭环 | 无 | 接 DSH Agent 扩展，APK 不内置模型 |
| API Gateway / SSE | 当前 app 有 Kotlin 实现 | 旧 Gateway | 以控制 App Kotlin Gateway 为唯一设备内服务 |
| 会话复用 | Kotlin 当前实现需核实/补齐 | Node Relay 有参考实现 | 统一 conversation_id / parent_message_id 协议 |

## 3. 三个执行环境必须分开

1. **控制 App 主进程**：界面、规则管理、本地 Gateway、凭据配置、观察报告读取。不能承担目标进程的 Hook。
2. **ChatGPT 目标进程**：libxposed Runtime、DEX/类发现、方法 Hook、动态类监听、受控事件发布。不要在此进程启动第二个 Gateway。
3. **外部 DSH Agent / Node 工具**：模型推理、任务规划、分析观察结果、生成候选规则。它是可选外部控制器，不进入 APK。

## 4. DSH Agent 分支审查结论

与 main 比较的当前分支状态（以本次盘点读取时为准）：

| 分支 | 相对 main | 初步结论 |
|---|---:|---|
| `feat/dsh-agent-hook-extension` | ahead 27 / behind 3 | **优先研究**。提供 DSH Agent 工具：status/events/rules/discover/enable；增加目标进程 DEX 类发现。分支文档明确声明尚未完成设备端验收；不能直接合并。 |
| `feat/dsh-gpt-runtime-plugin` | ahead 89 / behind 92 | 独立的 DSH 通用 Agent/Skill/任务状态插件；与 APK Hook Runtime 不是同一层。先保留为外部插件研究，不并入 APK，也不整体合并到 main。 |
| `feat/observation-bridge-console` | ahead 0 / behind 9 | 相对 main 无独有提交；可视为已被 main 超越，确认无未推送工作后再清理。 |
| `feat/prslc-template-migration` | ahead 0 / behind 22 | 相对 main 无独有提交；暂不迁移旧模板，确认无未推送工作后再清理。 |

`feat/dsh-agent-hook-extension` 的价值是把“复制报告 → 粘贴 AI → 手动回贴规则”升级为 Agent 工具调用闭环；其边界仍是声明式规则和元数据，不是任意代码注入。它适合作为 DSH 外部扩展，不增加 Android APK 的 Node 运行时体积。

## 5. 当前构建与体积基线

- 当前 main 的 CI 对提交 `ee01fef` **运行过但失败**，不是未运行。
- 失败点：`AndroidManifest.xml` 把 `<activity>` 放进 `<queries>`；`RelayActivity.kt` 有 `fillViewport`、`LayoutParams`、`singleLine` 未解析错误。元数据检查和 APK 上传因此跳过。
- 仓库内 `dsh-gpt-relay.apk` 是 20,873 字节的参考 APK；不能把它当作当前 Kotlin/libxposed APK 的体积数据。
- 当前 app 使用 libxposed API/service 依赖，Release 已配置 R8 和资源压缩；最终体积必须在修复构建后用实际 Release APK 测量。
- 体积验收需同时记录 APK 总大小、DEX 大小、方法数、资源大小、native 库大小；不以牺牲 Hook 反射入口为代价压缩。

## 6. 下一阶段实施顺序

### P0：建立可靠基线
- 修复构建阻塞，确保 debug/release 都能打包。
- 生成当前 APK 的体积和依赖清单。
- 在目标设备确认 libxposed 注入、配置更新、Hook 命中、事件回传和撤销。

### P1：保全动态发现
- 从 Java 参考实现提取 DEX 类枚举能力，移植到 libxposed API 102。
- 实现分页/限额的 class → methods/signatures discover。
- 加入动态类加载监听，发现尚未加载的目标类后再安装规则。
- 每一步都要做启动耗时、内存、目标线程开销和崩溃回归。

### P2：增强动态规则执行
- 统一规则 schema、版本号、规则 ID、方法签名、执行模式和权限级别。
- 规则更新采用“解析 → 校验 → 预览 → 应用 → 验证 → 可回滚”。
- Hook 句柄登记和撤销必须可靠；规则应用失败不能误删旧的有效 Hook。
- 先完成观察模式；需要改变方法行为的能力另设明确授权与单独验收。

### P3：接入 DSH Agent
- 先审查 `feat/dsh-agent-hook-extension` 与当前 main 的差异。
- 外部 Agent 通过宿主桥调用 status/events/rules/discover/enable。
- 不将 DSH bridge token、模型密钥或会话凭据传入 ChatGPT 目标进程。

### P4：统一本地 Gateway
- 只保留控制 App 进程中的 Kotlin Gateway。
- 以 Node Relay 已验证的会话模型为协议参考，补齐 Kotlin 会话连续性。
- API 与 Hook Runtime 解耦：任一方故障不应让另一方无法启动。

## 7. 本轮验收门槛

本轮盘点完成的标准：
- [x] 确认根工程只构建 `:app`，旧 `gpt-hook/` 不在当前 APK 编译输入内。
- [x] 识别当前 Kotlin Runtime 和旧 Java 动态引擎的能力差异。
- [x] 识别 Agent 扩展分支及其未设备验收状态。
- [x] 记录 main 当前 CI 的具体失败原因。
- [ ] 在真实设备验证所有当前 Hook 基线行为。
- [ ] 获取当前 Release APK 的真实大小。
- [ ] 对 Agent 分支完成完整逐文件审查并在设备上验收。

**结论：当前 APK 的 Kotlin Runtime 并没有继承旧 Java 引擎的全部动态能力。重构的第一核心任务，是把旧引擎中有价值的类发现、动态类加载监听和更完整的规则能力，安全地迁移到 libxposed API 102，而不是把旧工程整个塞进 APK。**
