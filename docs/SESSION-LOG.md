# 本次会话纪要（dsh-gpt 相关）

按时间顺序记录关键决策、失败尝试与最终可用结论。

## 阶段 1 · 目标

做一个 hook ChatGPT 安卓端的 LSPosed 模块（SukiSU/Zygisk/LSPosed 环境），
后续扩展成「本地 OpenAI 兼容 API + 反代网关」，再要求 APK 体积 ≤2M（目标 500K）。

## 阶段 2 · 模块骨架与构建

- 手工工具链出包：`aapt` + `javac` + `d8` + `zipalign` + `apksigner`，不依赖 gradle/联网。
- 编译桩 `toolchain/xposed-stub`，运行时用 LSPosed 真实实现。
- 踩坑 1：LSPosed 开启 dex 混淆后 `hookAllMethods` 返回类型必须是 `Set<Unhook>`，写成 `Unhook` 会 `NoSuchMethodError`。
- 踩坑 2：`ActivityThread.currentApplication()` 在 `handleLoadPackage` 阶段为 null，改用 `/proc/self/cmdline` 推导包名后直接读写 `/data/data/<pkg>/files/`。

## 阶段 3 · 首次注入验证

- 干净启动 `=== all hooks in 63~77ms`，0 miss。
- Build/SystemProperties/ANDROID_ID/File/Runtime.exec/PackageManager/Debug/TrustManager 全部挂上。
- 类扫描：97226 个类，`com.openai.*` 锚点定位到 valdi / subscriptions / integrity / oai.platform。

## 阶段 4 · 网络栈定位

- okhttp 仅 4 个类，主路径是 Cronet（154 个类），实现类在 GmsCore 动态 dex。
- 写了 `CronetSpy`（hook `CronetEngine$Builder.build` 拿实现类再二次 hook）和
  `DynClassWatch`（hook `ClassLoader.loadClass` 监听动态类），本版本启动阶段未触发。
- `network_security_config` 无 pin-set，只有明文例外。

## 阶段 5 · 反代要素采集

- 从 `shared_prefs/com.auth0.authentication.storage.xml` 拿到 access/id/refresh token、
  client_id、cnf.jkt、Keystore alias。
- 从 10 个 dex 提取 494 个 URL、30 条 backend 路径、37 个 `oai-*` 头。
- 关键结论：**不需要 DPoP proof**，`Authorization: DPoP <token>` + `oai-did` 两个头即可。

## 阶段 6 · 反代跑通

```
GET  /backend-api/me            → 200 用户信息
GET  /backend-api/models        → 200 模型列表
POST /backend-api/conversation  → 200 SSE，模型回「反代成功。」
```

- 免费号用 `auto` 可用；`*-wm` 403。
- 设备内网关（Java，ChatGPT 进程内 `0.0.0.0:8787`）也跑通，`/backend-api/models` 200。
- 设备出网当时依赖 FlClash 的 tun；`127.0.0.1` 跨进程访问在 Android 上不可用，改绑 `0.0.0.0` 后正常。

## 阶段 7 · 交接 GitHub 仓库 qingjian0/dsh-gpt

- 仓库原为 libxposed 102 的 Kotlin 模块（MainModule/MainActivity/diagnostics）。
- 新增 `api/` 五个文件（RelayConfig / ChatGptClient / OpenAiCompat / LocalApiServer / ApiService）
  与 `RelayActivity`。
- 构建坑：AGP 9.3.0 **内置 Kotlin**，再显式 apply `org.jetbrains.kotlin.android` 会
  `Cannot add extension with name 'kotlin'` → 删掉显式插件即恢复。
- 本地 gradle 仍缺 `ANDROID_HOME`（只有 android.jar，无完整 SDK），所以改用手工工具链出 20K 参考 APK。

## 阶段 8 · 让 GPT 生成代码

- 通过反代把提示词发给 ChatGPT（`handoff/*.txt`），产出：
  - `RelayActivity.kt`（602 行 UI）
  - `api/RelayConfig.kt` / `ChatGptClient.kt` / `OpenAiCompat.kt`（670 行）
- 生成脚本：`handoff/gen.mjs <prompt.txt> <out.md> [model]`。

## 阶段 9 · 会话复用

- 发现每次调用都是新对话：没传 `conversation_id` / `parent_message_id`。
- 修复后实测：
  - 第 1 轮「记住数字47」→「收到」
  - 第 2 轮「我刚才让你记的数字是多少」→「47」
- key 用 OpenAI 请求体的 `user` 字段，`GET /v1/sessions` 可查。

## 当前状态

| 产物 | 位置 |
|---|---|
| LSPosed 模块源码 | `gpt-hook/` |
| 反代（node） | `relay/openai-relay.mjs`、`relay/relay.mjs` |
| 采集器 | `relay/kit.mjs` |
| 实测事实 | `docs/HOOK-FACTS.md`、`relay/kit/FACTS.md` |
| 会话复用说明 | `docs/RELAY-SESSION.md` |
| 20K 参考 APK | `dsh-gpt-relay.apk` |

## 未完成

1. Kotlin 侧 `LocalApiServer` 还没接会话复用（文档已给出改法）。
2. CI 需要修 `AGP 内置 Kotlin` 与 `local.properties`（CI 自带 SDK，通常不需要）。
3. APK 体积目标：手工 Java 版 20K 已达标；Kotlin + libxposed 版预计 300–600K。
