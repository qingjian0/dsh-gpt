# Agent Mode and Adaptive Mode

This document defines the end-to-end contract for the DSH-GPT extension. It is an implementation and acceptance contract, not a claim that the host bridge or physical-device workflow is already complete.

## Product modes

### Agent mode

Agent mode is an execution workflow, not a synonym for exposing tools.

The DSH host Agent owns the orchestration loop:

1. Read capabilities and live Hook status.
2. Build a bounded plan from the user's goal and current observations.
3. Discover/inspect only within the configured target package.
4. Convert findings into typed candidate proposals.
5. Validate every candidate against the Hook policy and exact JVM descriptor.
6. Require approval for any behavior-changing action; observation-only candidates may be auto-applied only when the user has explicitly enabled that policy.
7. Apply a complete revision snapshot.
8. Wait for the target process to acknowledge that revision.
9. Verify the actual HookHandle inventory, not merely the saved configuration.
10. Observe bounded metadata, evaluate the result, and either finish or create a new proposal.

The loop must stop with a clear state when a target process is absent, Provider IPC is unavailable, a class is not found, an installation fails, the verification deadline expires, or an approval is required.

### Adaptive mode

Adaptive mode changes the plan based on verified evidence. It must not blindly rewrite rules or increase privileges.

- Start with observation-only behavior.
- Prefer exact class/method/descriptor candidates over broad class prefixes.
- Use bounded discovery pages, bounded retries, and a per-run time/tool-call budget.
- Re-plan when a class is absent, a descriptor changes, a Hook install fails, the target process restarts, or observations contradict the hypothesis.
- Retain successful rules; replace only the affected rule IDs.
- Remove stale rules and verify their HookHandles disappear.
- Roll back to the last known-good revision when a new revision causes a regression.
- Never interpret "config accepted" or "Provider reachable" as "Hook installed".

## Shared state machine

`idle -> planning -> discovering -> proposing -> awaiting_approval -> applying -> verifying -> observing -> adapting -> completed`

Terminal states: `blocked`, `failed`, `cancelled`, `budget_exhausted`.

Every transition records: `runId`, `revision`, `state`, `startedAtMs`, `updatedAtMs`, `attempt`, `reasonCode`, and a bounded evidence reference. State must survive host process recreation or be safely recoverable from the module's current revision and task lease.

## Candidate contract

A candidate is a proposal, not an active Hook:

- `candidateId`
- `targetPackage`
- `className`
- `methodName`
- `methodDescriptor`
- `mode`
- `confidence`
- `rationale`
- `evidence` (metadata only)
- `risk`
- `approvalRequired`
- `status`: proposed / approved / rejected / applying / installed / failed / stale

The exact JVM descriptor is mandatory. Candidate generation must not guess overloaded methods. The target package must match the configured allowlist. Sensitive auth, session, credential, network, payment, attestation, security, and cryptographic paths remain blocked for behavior-changing modes.

## Revision and verification contract

A successful `PUT /v1/hook/config` means only that the control plane stored a revision. It is not an install acknowledgement.

A run is considered installed only when all of these are true:

1. The target process reports the same revision.
2. `lastApplyReport` reports no unresolved failure for the candidate.
3. The candidate's Hook ID appears in the live HookHandle inventory.
4. The installed mode and descriptor match the proposal.

A removal is complete only after the Hook ID is absent from the live inventory. If the target process is not running, report `pending_target_process`, not success.

## API and tool contract

- `GET /v1/capabilities` is authoritative for the API surface.
- Chat Completions supports the current ToolBridge text-envelope adapter. The gateway returns standard OpenAI `tool_calls`; the calling Agent/client executes them and sends `role=tool` results back.
- The gateway's MCP routes are direct, separately authenticated tool/resource operations. They do not imply that the model autonomously selected or executed a tool.
- Responses API currently does not implement tool-call item round-tripping. Requests containing `tools` or `tool_choice` must be rejected instead of silently dropping those fields.
- Tool-enabled streaming must buffer until the response can be classified as either normal assistant text or tool calls. Never send the private ToolBridge envelope as user-visible content before returning `finish_reason=tool_calls`.
- Tool names and argument objects are checked against the request's declared names, required fields, property types, nested object/array schemas, enum values, and additionalProperties policy before being returned to the Agent.

## Adaptive policy defaults

| Situation | Required behavior |
|---|---|
| Unknown class/method | Discover/inspect; do not guess a descriptor |
| Observation candidate | Safe proposal; auto-apply only under explicit user policy |
| Behavior-changing candidate | Explicit approval |
| Provider unavailable | Retry within budget, then blocked |
| Target process absent | Pending; do not claim installed |
| Hook install failure | Preserve last known-good revision and report evidence |
| Unexpected behavior/regression | Roll back and verify inventory |
| Tool schema mismatch | Reject before execution |
| Tool timeout/error | Return structured error; never fabricate a result |

## Acceptance gates

### Hook
- delayed class loading and multiple ClassLoaders
- overload and constructor descriptor handling
- apply, replace, disable, remove, process restart
- revision acknowledgement and HookHandle inventory agreement
- failure isolation and rollback

### API
- auth, readiness, request limits, method/status semantics
- Chat Completions non-stream and SSE
- Responses text-only behavior and explicit unsupported-feature errors
- upstream errors, timeouts, disconnects, and conversation continuity

### Tools and Agent
- tool listing and schema preservation
- model-generated tool call -> client execution -> tool result -> model continuation
- parallel tool calls, malformed envelope, unknown tool, invalid arguments
- MCP direct-call path separately tested from model tool-calling
- host Agent state recovery, cancellation, retry budget, and adaptive replanning

### Device acceptance
CI and JVM tests are necessary but insufficient. Final acceptance requires a real Android device with the LSPosed module enabled and the DSH host plugin installed. Record device/app/module versions, request/response traces with secrets redacted, target revision, HookHandle inventory, and observed result.

## Current known implementation gaps

- Host PR #35 remains Draft and has not been reconciled with the current DSH host mainline or device-tested.
- The module API's config write is asynchronous; clients must poll live status for revision and HookHandle confirmation.
- The Responses adapter previously normalized input to Chat Completions and dropped tool fields; this branch changes that behavior to an explicit unsupported-feature response.
- Tool-enabled Chat Completions SSE previously emitted text deltas before classifying the final output as tool calls; this branch buffers that path to preserve the protocol.
- The complete host-side Agent state machine, adaptive planner, approval UI, durable run state, and device acceptance remain required before claiming Agent/Adaptive mode is complete.
