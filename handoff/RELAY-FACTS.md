# GPT 反代要素包（实测通过）

生成: 2026-11-21T09:36:49.124Z  ·  账号: user-qZIk58qGss72HGh2CY0NI9N6  ·  plan: free

## 0 一句话结论

**不需要 DPoP proof。** 只要 `Authorization: DPoP <access_token>` + `oai-did` 两个头，
直连 `https://android.chat.openai.com/backend-api/*` 即 200，含 SSE 对话。
实测 `/backend-api/me`、`/backend-api/models`、`/backend-api/conversation` 全部通过，
对话返回 `反代成功。` 并正常 `[DONE]`。

→ 反代可以完全离线部署在任何能出网的机器上，不需要手机在线。

## 1 必备要素（最小集）

| 要素 | 取值 | 来源 |
|---|---|---|
| access_token | 见 auth.json（JWT，exp=2026-11-27T07:56:33.180Z） | shared_prefs/com.auth0.authentication.storage.xml |
| oai-did | `711f4d8c21cc498cac150984836fc1e4` | files/INSTALLATION |
| 头 scheme | `Authorization: DPoP <token>`（**不是 Bearer**） | 实测 |
| base | `https://android.chat.openai.com` | dex |

其余头（`oai-session-id` / `oai-client-auth-session` / `oai-android-play-integrity-token`）
**实测非必需**，带上更稳。

## 2 端点

```
GET  /backend-api/me
GET  /backend-api/models
POST /backend-api/conversation          (SSE)
POST /backend-api/f/conversation        (新格式)
POST /backend-api/conversation/prepare
POST /backend-api/sentinel/chat-requirements
POST /backend-api/playintegrity
POST /backend-api/transcribe
```
完整清单见 endpoints.txt（494 URL / 30 backend 路径）。

## 3 模型 slug（实测列表）

```
auto  gpt-6  gpt-6-mini  gpt-6-t-mini  gpt-6-t-mini-mini
gpt-5-6-mini  gpt-5-6-t-mini  research
gpt-5.6-terra-wm  gpt-5.6-luna-wm   ← work 模型，免费号返回 403 work_subscription_required
```
免费号请用 `auto` / `gpt-6`；`*-wm` 需要 Plus。

## 4 三种用法

### A. 独立 relay（推荐，服务器上跑）

```sh
node tools/relay.mjs --kit ./kit --get /backend-api/me
node tools/relay.mjs --kit ./kit --post /backend-api/conversation --body '{"action":"next",...}'
node tools/relay.mjs --kit ./kit --serve 8788     # 对外提供 http://127.0.0.1:8788/backend-api/*
```
需要该机器能访问 android.chat.openai.com（配 `HTTPS_PROXY`）。

### B. 手机内网关（设备在场时）

```sh
sh hook.sh gateway on        # ChatGPT 进程内起 0.0.0.0:8787
curl -H 'X-Kit-Token: k1t-9f2c' http://<phone-ip>:8787/backend-api/models
```
好处：复用设备网络与 TLS，不需要服务器侧代理。

### C. 纯 curl

```sh
curl https://android.chat.openai.com/backend-api/me \
  -H "Authorization: DPoP $TOKEN" \
  -H "oai-did: 711f4d8c21cc498cac150984836fc1e4" \
  --proxy http://PROXY:PORT
```

## 5 对话请求体模板

```json
{
  "action": "next",
  "messages": [{"id":"<uuid>","author":{"role":"user"},
                "content":{"content_type":"text","parts":["你好"]}}],
  "parent_message_id": "<uuid>",
  "model": "auto",
  "conversation_mode": {"kind":"primary_assistant"},
  "timezone_offset_min": -480,
  "history_and_training_disabled": false
}
```

返回 `text/event-stream`，逐条 `data: {...}`，末条 `data: [DONE]`。

## 6 续期

access_token 到期: 2026-11-27T07:56:33.180Z
用 refresh_token 走 `POST https://auth.openai.com/oauth/token`：
```
grant_type=refresh_token&refresh_token=<rt>&client_id=app_xwBKzt04752TTSfXnki17hmB
```
若被要求 DPoP（`invalid_dpop_proof`），改从设备重新导出 auth0 xml 即可。

## 7 额度

免费号 `conversation` 返回 `limits_progress: [{feature_name:"reason", remaining:2}]`，
即每窗口剩余次数；耗尽返回 429 / `rate_limit`。

## 8 文件

| 文件 | 内容 |
|---|---|
| auth.json | 三 token + expires + key alias + workspace_tokens |
| claims.json | JWT 解析：user/account/plan/org/session/jkt |
| device.json | oai-did(INSTALLATION) + FCM token + Build |
| endpoints.txt | 全部 URL 与 backend 路径 |
| headers.txt | oai-* / x-oai-* / x-openai-* 全集 |
| kit_auth.json | 手机网关用的精简凭证（已部署到 app files） |
