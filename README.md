# DSH GPT Diagnostics

An LSPosed module and on-device dynamic Hook configuration and observation console based on libxposed API 102.

## Features

- Modern libxposed module entry point and LSPosed metadata.
- Declarative JSON Hook rules with local validation and live remote-preference updates.
- Deterministic method matching and stable Hook IDs; per-method failures are isolated.
- Bounded metadata-only observation buffer with debounced target-to-module IPC.
- Exported ContentProvider validates the publishing Binder UID against the configured target package; only the module app UID may read reports.
- On-device report refresh, JSON copy and Android share sheet for an explicitly selected AI assistant.
- User-mediated AI iteration: share sanitized report → analyze externally → paste suggested JSON → validate → apply.
- CI debug APK build and metadata verification.

## Build

Use JDK 21 and Gradle 9.6.1:

```bash
gradle assembleDebug --no-daemon
```

## Install and operate

1. Install the debug APK and enable the module in LSPosed.
2. Ensure `com.openai.chatgpt` is in module scope.
3. Open DSH and confirm LSPosed service connection.
4. Paste/edit declarative JSON and tap **Validate and apply configuration**.
5. Trigger a configured method in the target app.
6. Return to DSH and tap **Refresh** to view the bounded observation report.
7. Use **Copy JSON** or **Share to AI**. Paste AI-generated rules back into the editor and validate/apply them.

See [AI_DYNAMIC_HOOK.md](docs/AI_DYNAMIC_HOOK.md) for schema, IPC design and privacy constraints.

See [GPT API Hook Control](docs/GPT_API_HOOK_CONTROL.md) for the authenticated local API control routes and their acceptance semantics.

## Privacy and security

Only method signature, timing, sequence, rule ID and exception class are recorded. No method arguments, return values, headers, cookies, tokens, request/response bodies, device identifiers or anti-abuse challenge/proof material are collected. No silent network upload or challenge bypass is implemented.

The observation ContentProvider is exported because the target app and module app have separate UIDs. It accepts writes only from the configured target package UID and restricts reads to the module app UID. IPC behavior still requires real-device verification.

## Local API gateway

The app can expose an OpenAI-compatible API on the device itself, backed by the ChatGPT Android
session that the module already holds. Open the **本地 API 网关** panel in the main screen, paste
`access_token` + `oai-did` (or the whole `kit.json` via **导入 kit.json（粘贴）**), toggle **启用**
and tap **保存并重启**.

```
base_url = http://127.0.0.1:8766/v1
api_key  = the "client apiKey" field, or empty to disable auth
models   = auto / gpt-6 / gpt-6-mini / gpt-5-6-mini / research
```

- Default port is `8766` (change it in the port field). **局域网监听（0.0.0.0）** additionally binds
  the LAN interface; keep that on `127.0.0.1` unless you really want other devices to reach it.
- If the client apiKey field is left empty, no `Authorization` header is checked; otherwise every
  request except `/health` needs `Authorization: Bearer <client apiKey>`.
- `stream: true` returns OpenAI `chat.completion.chunk` SSE and always ends with `data: [DONE]`;
  `stream: false` returns a single `chat.completion` object.
- Anything under `/backend-api/**` is passed through to `https://android.chat.openai.com` unchanged.

Three acceptance commands (run on the device or over `adb shell`, port adjusted if you changed it):

```sh
# 1. health
curl -s http://127.0.0.1:8766/health
# → {"ok":true,"port":8766,"ready":true,"upstream":"https://android.chat.openai.com"}

# 2. model list
curl -s -H "Authorization: Bearer $API_KEY" http://127.0.0.1:8766/v1/models
# → {"object":"list","data":[{"id":"auto",...},{"id":"gpt-6",...},{"id":"gpt-6-mini",...},
#    {"id":"gpt-5-6-mini",...},{"id":"research",...}]}

# 3. chat completion, streaming
curl -sN -H "Authorization: Bearer $API_KEY" -H 'Content-Type: application/json' \
  -d '{"model":"auto","messages":[{"role":"user","content":"只回复OK"}],"stream":true}' \
  http://127.0.0.1:8766/v1/chat/completions
# → data: {"id":"chatcmpl-…","object":"chat.completion.chunk","choices":[{"index":0,
#         "delta":{"content":"OK"},"finish_reason":null}]}
#   data: {"id":"chatcmpl-…","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
#   data: [DONE]
```

Drop the `Authorization` header in commands 2 and 3 when the client apiKey field is empty.
`ready:false` in `/health` means `access_token` or `oai-did` is missing, and `/v1/*` will fail
upstream with the ChatGPT error body.

## Validation status

A successful CI build verifies compilation, APK packaging and LSPosed metadata. It does not establish runtime hook behavior or IPC compatibility on a physical device.
