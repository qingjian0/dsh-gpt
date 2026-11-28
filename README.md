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
- ChatGPT-only gateway with Android DPoP and web Bearer/session upstream modes.
- Configurable default model, fast/thinking mode, reasoning effort, web search and temporary chat.
- Persistent usage panel with estimated tokens, first-text latency (TTFT), total latency and recent request history.
- Authenticated `/v1/status`, `/v1/config`, `/v1/proxy/mode`, `/v1/usage` and `/v1/kit/import` routes.
- CI Release APK build and metadata verification.

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

The app exposes a **generic OpenAI-compatible API**, not a DSH-only endpoint. Configure any compatible
phone or desktop client with the Base URL and generated API key shown in the API Gateway screen.

- Android app on the same phone: `http://127.0.0.1:8766/v1`
- Desktop or another device on the same LAN: `http://<phone-lan-ip>:8766/v1` (enable LAN access)
- Authentication: `Authorization: Bearer <API_KEY>`; no DSH-specific client header is required.
- Endpoints: `GET /v1/models`, `GET /v1/capabilities`, `GET /v1/status`, `GET/PUT /v1/config`, `GET/PUT /v1/proxy/mode`, `GET /v1/usage`, `POST /v1/kit/import`, `POST /v1/chat/completions`, and `POST /v1/responses`.
- Both chat and Responses support normal JSON output and SSE streaming for text.
- The local API key is generated and persisted automatically. Keep it private; LAN access is intended
  only for trusted networks.

The only AI provider is ChatGPT. It supports the ChatGPT Android conversation backend (DPoP + device DID) and ChatGPT web session (Bearer token + optional session cookie). There is no Codex provider or Codex model routing. The supported compatibility surface is
text chat; image/audio input, tool/function calling, and other unsupported upstream capabilities are
not implied. Hook control remains available separately under `/v1/hook/*`.

See [Universal OpenAI API](docs/UNIVERSAL-OPENAI-API.md) for connection examples and compatibility details.

## Validation status

A successful CI build verifies compilation, APK packaging and LSPosed metadata. It does not establish runtime hook behavior or IPC compatibility on a physical device.
