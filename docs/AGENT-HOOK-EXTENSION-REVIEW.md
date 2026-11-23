# DSH Agent Hook Extension 审查记录（第二轮）

> 审查对象：`feat/dsh-agent-hook-extension`
> 对照基线：`main` 与 `refactor/hook-capability-baseline`
> 审查性质：静态代码审查；不是设备验收，不代表可以直接合并。

## 1. 结论

该分支的方向正确：把 AI Agent 控制面放在 DSH 外部，把 Hook 执行留在 ChatGPT 目标进程，APK 不需要打包 Node.js 或 AI 模型。

**建议保留其 Agent 插件与声明式工具接口设计，但不建议直接合并 27 个提交。** 应拆分为两个独立交付：
1. Android Runtime 的 metadata discovery 与 IPC 队列能力；
2. DSH 外部 Cordis Agent 插件及宿主桥接适配。

先把 Runtime 能力和协议做成可测试的独立改动，再单独接入 Agent 插件。

## 2. 已确认的优点

- Agent 工具在 `integration/dsh-agent-hook-extension/`，不进入 APK assets / Gradle source set；不会因为安装该 Agent 工具而把 Node runtime 塞进模块 APK。
- 工具职责清楚：status、events、rules get/update、discover、enable。
- DSH bridge token 只由外部 DSH engine 使用，README 明确禁止把 token 传入 ChatGPT 目标进程。
- Hook 规则仍是 JSON 声明式配置，沿用 `HookConfigParser` 限制目标包、规则数量、类名前缀和采样参数。
- 观察事件只包含 rule ID、类/方法签名、耗时和异常类型，不记录参数、返回值、HTTP 头、Cookie、请求体或凭据。
- 类发现只枚举目标进程中的类与方法签名，不主动调用目标方法。

## 3. 必须修正或补测的问题

### P0 — 发现任务可靠性

当前 `MainModule.pollDiscoveryRequest()` 在发现开始前就写入 `lastDiscoveryRequestId`。若发现过程中异常、进程中断或 Provider 回传失败，同一个 requestId 会被永久跳过；Agent 侧没有可靠的 retry/ack 机制。

要求：
- 引入明确状态：queued / running / succeeded / failed / expired。
- 只有 Provider 确认接收结果后才标记完成；失败允许同 requestId 安全重试。
- requestId 之外增加单调 revision，避免并发请求覆盖。
- 提供超时与取消语义，防止目标进程长时间扫描。

### P0 — DEX 扫描成本与可分页性

当前 `discover(prefix, className, limit)` 在每次前缀发现时都会枚举 DEX、最多收集 20,000 个类名、排序并物化到 List，然后才输出最多 500 条方法记录。限制的是返回记录数，不是扫描成本。

风险：
- 大型 APK / 多 dex 设备上，扫描可能占用明显 CPU、内存并阻塞 Agent 任务。
- 目标类加载器反射访问 `pathList/dexElements/dexFile` 可能受 Android 版本和隐藏 API 策略影响。
- 没有分页游标；多次请求会重复全量扫描。
- 没有按 dex、时间预算或结果字节数限额。

要求：
- 将扫描放在专用低优先级执行器，禁止主线程执行。
- 增加 maxDexCount、maxClassesScanned、maxMethods、maxDurationMs、maxBytes 等独立上限。
- 分页返回 cursor / nextCursor，并可取消。
- 先对当前 classLoader、Android API 级别和目标 APK 进行设备探测；反射不可用时返回明确错误，不崩溃。
- 对结果排序/稳定性和重复项做测试。

### P0 — Hook 快照更新语义

当前 `HookRuntime.apply()` 对查找失败或无匹配方法的规则，会把旧安装项加入 activeKeys，从而保留旧 Hook；这避免配置错误导致旧 Hook 被立即卸载，是合理的 fail-safe 方向。

但当前返回值 `applied` 统计每次重新调用 `framework.hook()` 的次数，不代表新增 Hook 数量；同一稳定 ID 可能被重复替换。需要把结果拆成 added / replaced / retained / removed / failed，避免 Agent 把 applied 误读成真实安装数。

另需补测：
- 更新某条规则时旧 handle 是否确实由 libxposed 的 setId 原子替换。
- 规则被禁用、删除或 clear 后，回调是否不再执行。
- 多条规则匹配同一个 Method 时是否发生 hook ID 冲突或重复观察。
- 单条规则失败时，旧有效 Hook 保留且其他规则继续应用。

### P1 — 发现覆盖面

当前发现只返回 `declaredMethods`：
- 不包含继承方法。
- 不返回构造器。
- 不返回字段元数据。
- 对 exactClassName 无法加载的情况，结果可能只是空 classes，而不是明确的 class_not_found。
- 方法签名只截断到 1000 字符，需要避免截断导致签名无法唯一标识。

首批建议先做 declared + inherited method 元数据与构造器元数据（只读）；字段只返回名称/类型/修饰符，不读字段值。任何方法都不得为了“探测”而被调用。

### P1 — IPC 身份与配对

Provider 对 DSH Host 的校验是调用 UID 对应包集合包含 `app.dsh.mobile`。这比无鉴权 exported Provider 好，但若设备上存在 shared UID / 签名身份边界不清，仍不等同于强配对。

README 已将显式 pairing / signature pin 标为 follow-up。进入正式发布前必须补：
- 校验宿主包签名证书摘要，而不只看包名；
- 明确 shared UID 行为；
- 所有方法 fail-closed；
- 配置变更与发现任务具备审计记录和速率限制。

### P1 — Agent 插件鲁棒性

`index.mjs` 的工具边界简洁，但仍需补：
- `dsh_hook_rules_update` 在调用前验证 schemaVersion / targetPackage / rules 类型，并把错误结构化返回。
- `dsh_hook_discover` 对 prefix 限制在工具层也校验，不只依赖 Android Runtime。
- fetch 响应体加字节上限，避免本地 bridge 返回异常大内容。
- 对 15 秒超时进行区分：连接失败、超时、HTTP 错误、Provider 拒绝。
- 工具输出使用固定 schema，不用 `additionalProperties: true` 无限放宽。
- defineTool 未找到时应有可观测的插件加载失败状态，不能只 console.warn 后静默无工具。

## 4. 体积和依赖结论

- Agent 插件是 DSH 用户空间模块，不属于 Android APK 构建输入。
- Android 端当前新增的主要成本来自 Kotlin 发现逻辑与 Provider/轮询线程；不引入 Node 或模型依赖。
- 需要实际测量 Release APK、DEX、方法数和资源体积；不能仅凭源代码体量估算。
- 不建议为了体积把 DEX 扫描挪进 Node 或删除扫描能力；应控制扫描时机、分页和运行开销。

## 5. 推荐拆分交付

### R1：Runtime Discovery Core
- 独立 DiscoveryRequest / DiscoveryResult schema。
- bounded worker + cancellation + pagination。
- DEX scan capability probe。
- class / method / constructor metadata。
- 单元测试覆盖限额、错误、分页、重复项。

### R2：Provider IPC
- revision + request lifecycle。
- host signature pin。
- status/events/rules/discover/control 的一致错误协议。
- 速率限制与最大 payload。

### R3：DSH Agent Plugin
- Cordis 工具注册与结构化输出。
- bridge client 超时/错误/响应大小限制。
- 插件安装、禁用、升级回归测试。

### R4：真机验收
- 指定 Android / LSPosed / ChatGPT 版本验证 DEX 发现。
- 记录冷启动扫描时间、峰值内存、结果数、CPU 时间。
- 验证 Hook 规则热更新、撤销、重试和目标进程重启恢复。

## 6. 当前决定

- [x] 认可外部 DSH Agent + Android 轻量 Runtime 的分层方向。
- [x] 认可元数据发现与声明式 Hook 规则作为第一阶段接口。
- [ ] 不直接合并整个 `feat/dsh-agent-hook-extension`。
- [ ] 不把任意代码执行、任意方法参数/返回值捕获加入首批能力。
- [ ] Discovery 必须先解决扫描成本、分页和 request retry。
- [ ] Provider 必须补签名配对后才能视为正式安全边界。
- [ ] 完成设备验收后才合并 Runtime 子集。

**最终原则：Agent 可以持续研究和提出规则，但执行权始终由目标进程中的受限 Runtime 管理；发现过程有资源上限，规则有 schema 和权限边界，任何失败都能明确报告并恢复。**


### P0 — DSH 宿主桥接实现定位未闭环

本轮对 `qingjian0/Deepseek-Harness-Local-Android` 当前 `main` 做了只读核对：
- `AgentBridge.kt` 和 `ShizukuHttpBridge.kt` 中未检索到 `/hook/status`、`/hook/events`、`ObservationProvider` 等对应路由/Provider 适配符号。
- 对仓库代码搜索 `/hook/status`、`dsh_hook_discover` 和 `ObservationProvider.METHOD_HOST_DISCOVER` 均未找到匹配。
- 因此，Agent 插件 README 中“status/events/rules/control routes are wired in both repositories”的描述，**目前无法由宿主仓库 main 的可检索代码证实**。

这不等于断言实现不存在：它可能位于其他分支、未跟踪的运行时补丁、生成代码或尚未推送的提交。正式接入前必须定位具体文件和 commit，并做端到端测试；在此之前，Agent 插件只能标记为“模块侧 Provider + 插件工具代码存在，宿主路由待核实”，不能标记为双仓库已贯通。


### P0 — 宿主桥接分支已定位，不能再按 main 缺失处理

后续全局分支扫描找到了宿主仓库分支
`qingjian0/Deepseek-Harness-Local-Android:feat/dsh-gpt-hook-agent-bridge`。
该分支相对宿主 `main` ahead 27 / behind 12（diverged），包含可检索、可定位的真实实现：

- `app/src/main/java/app/dsh/mobile/engine/HookAgentBridge.kt`：Provider 适配、status/events/rules/control/discover。
- `AgentBridge.kt`：已注册 `/hook/status`、`/hook/events`、`/hook/rules`、`/hook/discover`、`/hook/control`。
- `EndpointCatalog.kt`：已有 Hook 端点目录。
- `ProfilePluginProvisioner.kt`、`app/build.gradle.kts`、`android-build.yml`：已把 Agent 插件作为 DSH profile bundle 单独打包并首装启用。
- `AndroidManifest.xml`：已声明 dsh-gpt Provider 的 package visibility。

因此，旧结论“宿主桥接实现未定位”已被新证据修正：宿主桥接代码确实存在于该功能分支，但不在当时检查的 `main` 中。

进一步发现的协议问题：宿主 `HookAgentBridge.discover()` 原先只要收到非空 result 就立即返回，没有等待任务进入终态；而模块 Provider 已升级为 revision/lease/retry 状态机，非终态的失败尝试可能重新排队。宿主如果立即返回，会把一次可恢复失败提前暴露给 Agent。

已在宿主独立分支
`refactor/dsh-gpt-hook-bridge-hardening` 开始对齐：
- 发现请求携带 cursor；
- 等待 SUCCEEDED / FAILED / EXPIRED / CANCELLED 终态，不把 QUEUED 重试结果当最终结果；
- 增加 GET `/hook/discover?requestId=...` 状态查询；
- 增加 POST `/hook/discover/cancel` 取消接口；
- Agent 工具增加分页、状态和取消；
- EndpointCatalog 同步登记新路由。

该宿主分支尚未合并、尚未完成 CI 和设备验收。它相对 main 的 12 个落后提交仍需做冲突审查，不能直接合并整个 27 提交分支。
