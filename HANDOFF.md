# 交接给 GPT：本地 API / 反代网关

日期 2026-11-21 · 由 lead 完成核心实现，UI 由 GPT 继续

## 1 现在仓库里已经有什么

### 已实现（可直接用，勿改契约）

| 文件 | 作用 |
|---|---|
| `api/RelayConfig.kt` | 配置存储（SharedPreferences `relay`）：enabled/port/lan/apiKey/upstreamBase/accessToken/oaiDid/sessionId/userAgent；`ready` = token 和 did 都非空 |
| `api/ChatGptClient.kt` | 直连 `android.chat.openai.com` 的最小 HttpURLConnection 客户端，注入 `Authorization: DPoP <token>` + `oai-did` |
| `api/OpenAiCompat.kt` | OpenAI `/v1` ↔ ChatGPT conversation 互转；SSE chunk 生成 |
| `api/LocalApiServer.kt` | 零依赖手写 HTTP/1.1，路由 `/health` `/v1/models` `/v1/chat/completions` `/backend-api/*` 透传 |
| `api/ApiService.kt` | 前台 Service，`start(ctx)` / `stop(ctx)` / `restart()` |
| `MainActivity.kt` | 已加「本地 API」面板（Switch/端口/apiKey/oai-did/token/保存重启/导入 kit.json/复制命令/测试 health） |
| `AndroidManifest.xml` | 已加 INTERNET / FOREGROUND_SERVICE / FOREGROUND_SERVICE_DATA_SYNC / POST_NOTIFICATIONS + `ApiService`（exported=false, foregroundServiceType=dataSync） |
| `DshApplication.kt` | 启动时若 `enabled` 且 `ready` 自动拉起 ApiService |

### 对外契约（别改，客户端按这个写）

```
GET  http://127.0.0.1:<port>/health            → {"ok":true,"port":8766,"ready":true,"upstream":"..."}
GET  http://127.0.0.1:<port>/v1/models         → OpenAI 格式 {"object":"list","data":[...]}
POST http://127.0.0.1:<port>/v1/chat/completions
      支持 stream:true（SSE，OpenAI chunk 格式）与 stream:false（一次性 JSON）
ANY  http://127.0.0.1:<port>/backend-api/**    → 原样透传到 android.chat.openai.com
鉴权：若 apiKey 非空，需 `Authorization: Bearer <apiKey>`
```

## 2 实测事实（已用真实流量验证，不要重新推导）

- **不需要 DPoP proof**。两个头就够：`Authorization: DPoP <access_token>` + `oai-did: <INSTALLATION>`。实测 `/backend-api/me`、`/models`、`/conversation` 全 200。
- `oai-session-id`、`oai-client-auth-session`、play-integrity 头**非必需**。
- 可用模型 slug：`auto` `gpt-6` `gpt-6-mini` `gpt-6-t-mini` `gpt-5-6-mini` `research`。
  带 `-wm` 后缀的是 Work 模型，免费号返回 403 `work_subscription_required`。
- 免费号有 `reason` 次数限制，`limits_progress.remaining` 会归零，归零后 429。
- 对话 SSE 里 assistant 文本是**全量重复**的（每条 data 带完整 parts），`LocalApiServer.extractDelta` 已按前缀差量处理。
- 上游 base 只能 `https://android.chat.openai.com`，`chatgpt.com/backend-api` 是 Web 端，不认这个 token。
- 设备需能出网（本机靠 FlClash 的 tun；代理模式下 JVM 的 TLS 握手会被打断，别指望 gradle 直连）。

## 3 你必须先修的构建问题（当前 CI 一定失败）

`app/build.gradle` 只有：

```groovy
plugins { alias(libs.plugins.android.application) }
```

但 `app/src/main/java/**` 全是 `.kt`。**缺 Kotlin 插件**，`gradle assembleDebug` 会在编译阶段失败。

两种修法，选一种：

**A（推荐）显式加 Kotlin 插件**
```toml
# gradle/libs.versions.toml
[versions]
kotlin = "2.2.0"
[plugins]
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```
```groovy
// app/build.gradle
plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android) }
```

**B** 删掉所有 `.kt`，把 `api/*` 与 `MainActivity` 改写成 Java（我这边已有可直接抄的 Java 版，见附件 APK 的 dex）。

另外 `compileSdk 37` / `targetSdk 37` 需要 AGP 9.3.0 + JDK 21，CI 上注意 `actions/setup-java` 用 21（现有 workflow 已是 21，OK）。

## 4 APK 体积

要求 500K 左右、上限 2M。
- 当前 Kotlin 版带 `libxposed.service` 依赖 + `minifyEnabled true`，预计 300–600K，达标。
- 我本地手工构建的 Java 参考实现只有 **20K**（无 libxposed、无依赖），可作体积基线。

## 5 交付物

| 文件 | 说明 |
|---|---|
| `dsh-gpt-relay.apk` | 20K 可运行参考实现（Java，包名 com.gpt.dsh，已在本机实测安装） |
| `kit-template.json` | 凭据模板，真实 token 不入库 |
| `api/*.kt` | 上面那 5 个文件，直接可编（修好 Kotlin 插件后） |

## 6 你接下来的活

1. 修 build.gradle 的 Kotlin 插件（第 3 节）。
2. UI 打磨：状态行要显示 `ready` 与端口；`0.0.0.0` 切换后要重启服务（现在走 stop+start）。
3. 首次启动申请 `POST_NOTIFICATIONS`（Android 13+，否则网关通知不可见，功能不受影响）。
4. 在 README 补「本地 API」章节：客户端 base_url + api_key 怎么填。
5. 跑通 CI，出 debug APK，验收三条：`/health` 200、`/v1/models` 有 5 个模型、`/v1/chat/completions` 能流式返回。

---

## 7 验收用的活体反代（已启动）

本机已跑着一个 **OpenAI 兼容**的反代服务，用它验证你的实现，不用等 CI、不用真机：

```
base_url = http://127.0.0.1:8788/v1
api_key  = 留空（不校验）
模型      = auto / gpt-6 / gpt-6-mini / gpt-5-6-mini / research
```

三条验收命令：

```sh
# 1 健康
node -e "fetch('http://127.0.0.1:8788/health').then(r=>r.json()).then(console.log)"
# → {"ok":true,"port":8788,"models":[...]}

# 2 模型列表
node -e "fetch('http://127.0.0.1:8788/v1/models').then(r=>r.json()).then(j=>console.log(j.data.map(m=>m.id)))"
# → [ 'auto', 'gpt-6', 'gpt-6-mini', 'gpt-5-6-mini', 'research' ]

# 3 对话（非流式 / 流式）
node -e "fetch('http://127.0.0.1:8788/v1/chat/completions',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({model:'auto',messages:[{role:'user',content:'只回复OK'}],stream:false})}).then(r=>r.json()).then(j=>console.log(j.choices[0].message.content))"
# → OK
```

实测输出（2026-11-21 21:55）：

- 非流式：`content = "OK"`
- 流式：4 个 chunk，delta 依次 `"1 2 "` → `"3"`，最后 `data: [DONE]`

**协议层就是你要实现的东西**：`messages` → conversation payload、上游全量 parts → 增量 delta、SSE 转 OpenAI chunk。
你改完 `OpenAiCompat.kt` / `LocalApiServer.kt` 后，用上面第 3 条命令对照，输出结构必须一致。

启动方式（如果服务挂了）：

```sh
cd /data/user/0/dsh.dsh.mobile/files/dsh-home/GPT/gpt-hook
nohup node tools/openai-relay.mjs --kit ./kit --port 8788 &
```
