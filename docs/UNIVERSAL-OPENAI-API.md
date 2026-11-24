# Universal OpenAI-compatible API

The local gateway is a generic OpenAI-compatible endpoint. It is not DSH-specific: any client that can configure an OpenAI-compatible provider can connect to it.

## Connection

- On the Android phone: `http://127.0.0.1:8766/v1`
- From another device on the same LAN: `http://<phone-lan-ip>:8766/v1`
- API key: copy **客户端 API Key** from the API Gateway screen.
- Model: `auto` (also advertises `gpt-6`, `gpt-6-mini`, `gpt-5-6-mini`, and `research`).

Use the Base URL exactly as shown; clients append the endpoint path themselves. On the phone, use loopback. On a computer, use the phone's LAN IPv4 address and enable **允许局域网访问** before saving/restarting the service.

Every protected endpoint uses the standard header:

```http
Authorization: Bearer <API_KEY>
```

No `X-DSH-Client-Id` header is required. It remains optional for clients that want separate local conversation namespaces.

## Endpoints

- `GET /v1/models`
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
curl -s http://127.0.0.1:8766/v1/models \
  -H "Authorization: Bearer $API_KEY"
```

Chat Completions:

```sh
curl -sN http://127.0.0.1:8766/v1/chat/completions \
  -H "Authorization: Bearer $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"auto","messages":[{"role":"user","content":"只回复OK"}],"stream":true}'
```

Responses:

```sh
curl -s http://127.0.0.1:8766/v1/responses \
  -H "Authorization: Bearer $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"auto","input":"只回复OK"}'
```

## Network security

The LAN listener binds to `0.0.0.0` when enabled. Keep the generated API key private and use the gateway only on trusted networks. Do not expose this session-backed gateway directly to the public internet. The key is generated and persisted locally when the configuration is first loaded.
