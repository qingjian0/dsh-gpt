# Conversation API

The gateway stores conversations in the app-private SQLite database `dsh_conversations.db`.
The API is deliberately caller-scoped: every conversation request must include
`X-DSH-Client-Id` (8–256 non-whitespace characters). The raw value is never stored;
the database stores its SHA-256 digest. A conversation ID belonging to another caller
is indistinguishable from a missing ID (404).

All protected endpoints require the gateway's configured Bearer API key. Only `/health` remains public. If the API key is blank, protected routes fail closed with HTTP 503; an absent or incorrect key returns HTTP 401. Each conversation is limited to 500 messages, and each appended message is limited to 256 KiB.

## Endpoints

- `GET /v1/conversations` — list the caller's conversations, newest activity first.
- `POST /v1/conversations` — create with JSON `{"title":"…","model":"…","parent_id":null}`.
- `GET /v1/conversations/{id}` — fetch one conversation and its message array.
- `PATCH /v1/conversations/{id}` — update `title` and/or `model`; omitted fields are preserved.
- `POST /v1/conversations/{id}/messages` — append one JSON message object.
- `DELETE /v1/conversations/{id}` — delete one conversation. Children are retained and detached.

## Chat completion continuity

`POST /v1/chat/completions` also requires `X-DSH-Client-Id`. On the first request, the
gateway creates a local conversation and returns its ID in `X-DSH-Conversation-Id`.
Send that header on later requests to continue the same caller-owned conversation. A
conversation ID belonging to another caller returns 404.

Each accepted user turn and completed assistant response is persisted locally. The gateway
stores the upstream ChatGPT `conversation_id` and latest assistant message ID, then reuses
them as `conversation_id` and `parent_message_id` on the next upstream request. The
continuation state is migrated additively from database version 1 to version 2. Streaming
responses include the local conversation ID in the response headers. The server serializes
turns through a fixed set of striped conversation locks, preventing concurrent requests for
the same conversation from reusing a stale upstream parent cursor.

Conversation IDs are generated UUIDs. Parent IDs are validated against the same caller.
Messages are stored as JSON objects in append order. List responses omit message bodies.

## Security and limitations

- Caller identity is explicit; there is no fallback to model name or shared global session.
- The caller identifier is hashed before persistence and never returned by list/get.
- API key authentication is independent of caller scoping. Clients should use a distinct,
  high-entropy client ID per application/profile.
- This first durable store is local to the Android app and is not a cross-device sync service.
- Upstream continuation state is captured from ChatGPT SSE events when those events contain
  a conversation ID or assistant message ID. If the upstream protocol changes or omits these
  fields, a later turn may fail upstream; local message history remains available.
- Token usage is not yet calculated from authoritative upstream usage data.
- SQLite migrations must be additive. Do not drop the database to upgrade schema.


## Editing a conversation

Send an authenticated `PATCH /v1/conversations/{id}` request with the caller's
`X-DSH-Client-Id`:

```json
{"title":"Research notes","model":"gpt-6"}
```

The update is caller-scoped, persists locally, and advances `updated_at`. Supported model
values are client-facing aliases returned by `GET /v1/models`; they are not a promise that
every upstream account exposes every named model.
