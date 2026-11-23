# Session and Agent integration contract

Status: architecture contract only; no persistence or Agent execution is claimed as implemented.

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

Minimum persisted fields:
- local ID, title, created/updated timestamps, status
- provider ID and upstream conversation ID (sensitive identifiers should not be exposed in list responses)
- parent conversation ID, agent run ID, assigned agent ID
- provider cursor / parent message ID required to continue upstream conversations
- message role, content reference, timestamps, completion/error state

Security:
- Keep access tokens and API keys out of conversation rows and logs.
- Do not return raw client keys or caller identifiers from `GET /v1/sessions`.
- Apply explicit retention and delete semantics; clear session lineage when a conversation is deleted.
- Prefer app-private storage; encrypt sensitive provider state at rest using Android Keystore-backed keys.
- Bound message size, conversation count, and retention; do not log message bodies by default.

## 4. Local API contract

Proposed endpoints (versioned before implementation):
- `POST /v1/conversations`: create a local conversation.
- `GET /v1/conversations`: list metadata with pagination and filters.
- `GET /v1/conversations/{id}`: read metadata and paginated messages.
- `DELETE /v1/conversations/{id}`: delete local data and provider continuation state.
- `POST /v1/chat/completions`: accepts optional `conversation_id`; when absent, create an isolated conversation.
- `GET /health`: report listener health only; upstream readiness is a separate field.

Compatibility:
- Keep standard OpenAI clients working when `conversation_id` is absent.
- Accept a caller-supplied stable `user` only as a namespaced client-session hint, never as an unbounded raw database key.
- Do not expose upstream conversation IDs in public list endpoints unless explicitly requested by a privileged local client.

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

1. Add repository models and unit tests independent of UI.
2. Implement app-private persistent repository and migrations.
3. Add local conversation endpoints and API contract tests.
4. Adapt the Compose Conversations tab to repository flows.
5. Implement DSH Agent adapter and capability negotiation.
6. Connect Agent tab to real runs, progress, cancellation, and conversation lineage.
7. Device-test process death, restart recovery, privacy, and concurrent requests.
