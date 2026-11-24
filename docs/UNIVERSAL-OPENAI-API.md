# Universal OpenAI-compatible API

The local gateway is a generic OpenAI-compatible endpoint. It is not DSH-specific: any client that can configure an OpenAI-compatible provider can connect to it.

## Connection

- On the Android phone: `http://127.0.0.1:8766/v1`
- From another device on the same LAN: `http://<phone-lan-ip>:8766/v1`
- API key: copy **客户端 API Key** from the API Gateway screen.
- Default model: `auto` for normal conversation; choose `gpt-6` to request the measured free-tier reasoning path.

Use the Base URL exactly as shown; clients append the endpoint path themselves. On the phone, use loopback. On a computer, use the phone's LAN IPv4 address and enable **允许局域网访问** before saving/restarting the service.

Every protected endpoint uses the standard header:

```http
Authorization: Bearer <API_KEY>
```

No `X-DSH-Client-Id` header is required. It remains optional for clients that want separate local conversation namespaces.

## Model routing

The gateway exposes model names as client-facing aliases; they are not a list of public OpenAI API models.

| Client model | Gateway behavior | Observed result |
|---|---|---|
| `auto` | Pass through without adding hints | ChatGPT Android backend selects its normal default (observed: `gpt-6-mini`) |
| `gpt-6-mini` | Pass through without adding hints | Normal response path |
| `gpt-6` | Automatically adds `system_hints: ["reason"]` | Observed on the free account to resolve to `gpt-6-t-mini-mini` |
| `gpt-5-6-mini`, `research` | Passed through as requested | Not validated by this reasoning test matrix |

The `gpt-6` alias is intentional: the bare slug returned an empty response in the reported test, while the same request with `system_hints: ["reason"]` produced a response from `gpt-6-t-mini-mini`. The gateway adds that hint automatically, so OpenAI-compatible clients only need to select `gpt-6`.

`reasoning_effort` is not translated because the Android backend ignored it in the reported tests. Thinking duration is selected by the backend/model based on the task; the gateway does not promise a fixed thinking-time level.

The `deep_research` and `web_search` hints are not implied by these aliases. A response that looks like a research report is not proof of actual web retrieval: real browsing requires executed search calls and returned search results/citations. The reported free-account tests returned zero search queries and zero citations.

## Endpoints

- `GET /v1/models`
- `GET /v1/capabilities` — machine-readable list of implemented and unsupported adapter capabilities.
- `POST /v1/chat/completions` — text messages, normal JSON response or SSE chunks.
- `POST /v1/responses` — text input/instructions, normal JSON response or Responses-style SSE events.
- Existing `/v1/hook/*` routes remain available and use the same Bearer authentication.
- `GET /health` remains unauthenticated for local service health checks.

CORS preflight and common HTTP request framing (Content-Length and chunked request bodies) are supported.

## Compatibility boundary

The upstream is the ChatGPT Android conversation backend, not the public OpenAI API. The gateway translates the supported text-only subset of OpenAI Chat Completions and Responses into that backend's conversation protocol. Image/audio input, tool/function calling, JSON-schema constrained output, and other OpenAI features that the upstream adapter does not implement are not advertised as supported. A client that requires those features is not fully compatible yet.

## Quick test

On the phone:

```sh
curl -s http://127.0.0.1:8766/v1/models \\
  -H "Authorization: Bearer $API_KEY"
```

Chat Completions:

```sh
curl -sN http://127.0.0.1:8766/v1/chat/completions \\
  -H "Authorization: Bearer $API_KEY" \\
  -H "Content-Type: application/json" \\
  -d '{"model":"gpt-6","messages":[{"role":"user","content":"解释一下浮点误差"}],"stream":true}'
```

Responses:

```sh
curl -s http://127.0.0.1:8766/v1/responses \\
  -H "Authorization: Bearer $API_KEY" \\
  -H "Content-Type: application/json" \\
  -d '{"model":"auto","input":"只回复OK"}'
```

## Network security

The LAN listener binds to `0.0.0.0` when enabled. Keep the generated API key private and use the gateway only on trusted networks. Do not expose this session-backed gateway directly to the public internet. The key is generated and persisted locally when the configuration is first loaded.


## Capability discovery and explicit rejection

Call `GET /v1/capabilities` with the gateway Bearer key before selecting advanced client features.
The current adapter advertises text input/output, Chat Completions, Responses, SSE, conversation
persistence and Hook control. It explicitly lists image/audio, tool/function calling and
JSON-Schema constrained output as unsupported.

Requests containing unsupported multimodal parts, tool/function definitions, or constrained
output declarations return HTTP 400 with error type `unsupported_feature`. They are rejected
rather than silently flattened into text or reported as successful. This does not add upstream
image, audio or tool execution; those require protocol support and execution semantics in the
ChatGPT Android backend adapter.
