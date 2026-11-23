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
- `POST /v1/conversations/{id}/messages` — append one JSON message object.
- `DELETE /v1/conversations/{id}` — delete one conversation. Children are retained and detached.

Conversation IDs are generated UUIDs. Parent IDs are validated against the same caller.
Messages are stored as JSON objects in append order. List responses omit message bodies.

## Security and limitations

- Caller identity is explicit; there is no fallback to model name or shared global session.
- The caller identifier is hashed before persistence and never returned by list/get.
- API key authentication is independent of caller scoping. Clients should use a distinct,
  high-entropy client ID per application/profile.
- This first durable store is local to the Android app and is not a cross-device sync service.
- Message history is currently a DSH conversation record; it is not automatically inferred
  from upstream ChatGPT SSE traffic. A later adapter can write upstream message IDs and
  token usage without changing the caller isolation contract.
- SQLite migrations must be additive. Do not drop the database to upgrade schema.
