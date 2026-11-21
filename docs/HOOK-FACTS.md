# ChatGPT 安卓端 hook / 反代 实测结论

目标：`com.openai.chatgpt` 1.2026.321（versionCode 2632116，arm64-v8a，targetSdk 37）
环境：SukiSU + Zygisk + LSPosed 2.2.1（HYOS 改版），Android 16
工具：自研 LSPosed 模块 `gpt-hook`（见 `gpt-hook/`）+ 反代 `relay/`

## 1 注入与模块

| 项 | 结果 |
|---|---|
| 注入耗时 | 63–77 ms |
| 进程 | `com.openai.chatgpt` 主进程 |
| 失效点 | LSPosed dex 混淆后 `hookAllMethods` 必须返回 `Set<Unhook>`，否则 `NoSuchMethodError` |
| 早期上下文 | `ActivityThread.currentApplication()` 在 `handleLoadPackage` 为 null → 用 `/proc/self/cmdline` 推出包名后走 `/data/data/<pkg>/files/` |

## 2 设备指纹与检测

- Build 15 个字段 + `SystemProperties(ro.debuggable/secure/fingerprint)` 全部可写。
- `File.exists/isFile/canRead/canExecute` + `Runtime.exec` + `ProcessBuilder` + `PackageManager` 可完整遮蔽 root/模块痕迹（路径黑名单：su/magisk/kernelsu/lsposed/frida/apatch）。
- `Debug.isDebuggerConnected`、`getRunningAppProcesses` 可屏蔽。

## 3 网络栈（关键）

- **没有 okhttp 主路径**，`okhttp3` 仅 4 个类。
- 真正网络栈是 **Cronet（`org.chromium.net.*`，154 个类）**，实现类在 GmsCore 动态 dex，`org.chromium.net.impl.CronetUrlRequest` 不在 base dex。
- 用 `ClassLoader.loadClass` 运行时监听可兜住动态类，但本版本启动阶段未触发构建。
- `network_security_config`：base 禁明文，例外 `localhost / 10.0.2.2 / 10.0.1.1 / verygoodproxy.com`，**无 pin-set**。

## 4 认证模型

| 项 | 值 |
|---|---|
| 类型 | Auth0 + **DPoP**（`token_type=DPoP`） |
| 头 | `Authorization: DPoP <access_token>` + `oai-did: <INSTALLATION>` |
| DPoP proof | **实测非必需**（`/me` `/models` `/conversation` 不带 proof 均 200） |
| client_id | `app_xwBKzt04752TTSfXnki17hmB` |
| cnf.jkt | `8QimZV51n51x-JSKFHQeChd6T5GV2eX3KOrb9nANTjE` |
| Keystore alias | `openai.login.dpop.<uuid>`（私钥不可导出，但用不上） |
| token 位置 | `shared_prefs/com.auth0.authentication.storage.xml` |
| oai-did 位置 | `files/INSTALLATION` |

## 5 端点

```
GET  /backend-api/me
GET  /backend-api/models
POST /backend-api/conversation          SSE
POST /backend-api/f/conversation        新格式
POST /backend-api/sentinel/chat-requirements
POST /backend-api/playintegrity
POST /backend-api/transcribe
```
base 只能是 `https://android.chat.openai.com`（`chatgpt.com/backend-api` 不认这个 token）。
完整清单见 `relay/kit/endpoints.txt`（494 URL / 30 路径），头清单见 `relay/kit/headers.txt`。

## 6 协议细节

- 对话响应是 `text/event-stream`，每条 `data: {...}`，末条 `data: [DONE]`。
- assistant 文本在 `message.content.parts[0]`，且**每条 data 都带完整文本（全量重复）**，必须做前缀差量。
- 续聊需要 `conversation_id` + `parent_message_id`（详见 `RELAY-SESSION.md`）。

## 7 模型

可用：`auto` `gpt-6` `gpt-6-mini` `gpt-6-t-mini` `gpt-5-6-mini` `research`
`-wm` 后缀（`gpt-5.6-terra-wm` 等）是 Work 模型，免费号 403 `work_subscription_required`。
免费号有 `limits_progress.reason` 次数限制，归零后 429。

## 8 订阅相关类（类扫描锚点）

```
com.openai.valdi.celsius.CelsiusSubscriptionState      // enum: Pending / Ready
com.openai.feature.subscriptions.PurchaseResult$Error$*
com.openai.integrity.impl.a                             // Play Integrity 包装
com.openai.valdi.oai.platform.OAIAPIRequest             // _path/_method/_headers/_bodyJson
com.openai.valdi.oai.platform.OAIAPIResponse            // _statusCode/_bodyText
com.openai.network.cloudflare.*                         // CF 错误分类
com.revenuecat.purchases.*                              // 订阅走 RevenueCat
```
类总量 97226，全量类表可由模块 `ClassScanner` 导出到 `files/class-scan.txt`。
