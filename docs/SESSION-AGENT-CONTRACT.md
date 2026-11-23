# Session and Agent integration contract

Status: SQLite conversation persistence, caller-scoped CRUD, and an initial upstream continuation path are implemented in PR #9. The DSH Agent execution contract below remains architectural; no Agent engine is implemented.

## 1. Responsibilities

- `LocalApiServer` is the HTTP adapter. It parses OpenAI-compatible requests and forwards them upstream.
- `ConversationRepository` owns durable conversation identity, parent/child relationships, provider binding, and activity metadata.
- `AgentCoordinator` owns task planning, delegation policy, depth/concurrency limits, cancellation, and result aggregation.
- DSH is the intended orchestration host. DSH-GPT must expose an adapter contract and must not create a competing subagent engine.
- LSPosed runtime observation remains a separate capability. Agent planning must never directly execute arbitrary Hook code.

## 2. Conversation identity

A conversation is not identified by model name. Model is a provider selection, not a user/session identity.

Required identifiers:
- `conversationId`: stable local UUID generated when a conversation is created.
- `providerConversationId`: optional upstream conversation identifier.
- `parentConversationId`: optional parent conversation for agent lineage.
- `agentRunId`: optional run that owns the conversation.
- `clientSessionId`: opaque caller-supplied stable ID, scoped to the local API key/client.

Never fall back to `model:<model>` as a conversation key: unrelated callers using the same model would share upstream history. If a client supplies no stable ID, create an isolated conversation and return its local ID; do not silently merge callers.

## 3. Storage contract

Implement behind an interface so HTTP and Compose do not depend on a specific database:

```kotlin
interface ConversationRepository {
    suspend fun create(input: NewConversation): Conversation
    suspend fun get(id: String): Conversation?
    suspend fun list(query: ConversationQuery): List<Conversation>
    suspend fun updateProviderState(id: String, state: ProviderConversationState)
    suspend fun appendMessage(conversationId: String, message: ConversationMessage)
    suspend fun delete(id: String)
}
```

Current implementation:
- SQLite database `dsh_conversations.db`, schema version 2, with additive migration from v1.
- Local UUID, title, model, caller SHA-256 digest, parent ID, timestamps, ordered JSON message array.
- Upstream conversation ID and latest assistant message ID are persisted for chat continuation.
- Message count is capped at 500; each appended message is capped at 256 KiB.
- Caller-scoped CRUD and chat-turn persistence are connected to `LocalApiServer`.

Still required for the full Agent contract:
- provider registry, provider status, run ID, assigned agent ID, message completion/error state.
- encrypted provider continuation state at rest.
- paginated message reads, retention policy, and repository interface decoupled from SQLite.

Security:
- Keep access tokens and API keys out of conversation rows and logs.
- Do not return raw client keys or caller identifiers from `GET /v1/sessions`.
- Apply explicit retention and delete semantics; clear session lineage when a conversation is deleted.
- Prefer app-private storage; encrypt sensitive provider state at rest using Android Keystore-backed keys.
- Bound message size, conversation count, and retention; do not log message bodies by default.

## 4. Local API contract

Implemented endpoints:
- `POST /v1/conversations`: create a local conversation.
- `GET /v1/conversations`: list caller-scoped metadata.
- `GET /v1/conversations/{id}`: read a caller-owned conversation and messages.
- `POST /v1/conversations/{id}/messages`: append a message.
- `DELETE /v1/conversations/{id}`: delete a conversation and detach children.
- `POST /v1/chat/completions`: requires `X-DSH-Client-Id`; returns a local ID in `X-DSH-Conversation-Id`, which clients send to continue.
- `GET /health`: public listener health only.

Compatibility and remaining work:
- Standard OpenAI clients need the two DSH headers for stable local conversation continuity.
- Upstream conversation and parent message IDs are not exposed in public conversation responses.
- Add request-level API contract tests and authenticated device fixtures before treating upstream protocol continuity as production-verified.

## 5. Agent adapter contract

The UI and DSH integration should depend on a narrow interface:

```kotlin
interface AgentCoordinator {
    suspend fun capabilities(): AgentCapabilities
    suspend fun createRun(request: AgentRunRequest): AgentRun
    suspend fun getRun(runId: String): AgentRun?
    suspend fun cancelRun(runId: String): Boolean
    fun observeRun(runId: String): Flow<AgentRunEvent>
}
```

Run request fields:
- task and optional input conversation ID
- main conversation policy: existing ID, create new, or coordinator-selected
- maximum depth, maximum child agents, maximum concurrency
- allowed tools/capabilities and explicit approval policy
- deadline / cancellation token

Run events:
- queued, planning, delegated, child-started, progress, child-completed, aggregation, completed, failed, cancelled
- each child event carries run ID, agent ID, parent agent ID, and conversation ID
- redact credentials and sensitive message content from diagnostics

The DSH adapter should negotiate capabilities at runtime. If DSH does not expose the required operation, show it as unavailable; do not simulate success locally.

## 6. Review findings for PR #8 (do not merge as-is)

PR #8 contains useful exploratory work: upstream conversation ID / parent message ID capture, a session listing endpoint, request-size bounds, and improved cleanup around upstream streams. It is not yet a safe persistent session implementation.

Blockers before integration:
1. The session map is in-memory and disappears when the service process restarts.
2. Its fallback key is `model:<model>`; clients without a `user` field can unintentionally share one upstream conversation.
3. The listing endpoint exposes the raw map key as `user`, which can leak caller-provided identifiers.
4. The session data has no local conversation ID, parent/child lineage, title, messages, or deletion semantics.
5. Fixed timezone offset and upstream cursor handling need device/API fixtures before being treated as protocol-correct.
6. The implementation must be tested for simultaneous requests, incomplete/error streams, client disconnects, and TTL eviction.

Keep PR #8 open as a source of selected changes only. Rebase or port individual reviewed commits after the repository contract and tests exist; do not merge the full branch over the Compose UI branch.

## 7. Delivery sequence

1. Add repository models and unit tests independent of UI. (partially complete)
2. Implement app-private persistent repository and additive migrations. (implemented; device migration test pending)
3. Add local conversation endpoints and API contract tests. (endpoints implemented; HTTP integration tests pending)
4. Adapt the Compose Conversations tab to the repository. (basic list/create/delete integration implemented)
5. Implement DSH Agent adapter and capability negotiation. (not implemented; DSH host currently has no Agent engine)
6. Connect Agent tab to real runs, progress, cancellation, and conversation lineage.
7. Device-test process death, restart recovery, privacy, upstream continuation, and concurrent requests.
