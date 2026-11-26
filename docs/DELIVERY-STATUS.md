# DSH-GPT Delivery Status

Last reviewed: 2026-11-26 (post-PR #40 merge)
Main baseline reviewed: `f0530efc4c67e392aeb622c6b5709bc90baaf291`
Latest delivery fix merged: PR #40 (`6790e22798231859f0c09ee6e7bf8e7b5a12b569`)

This document is the delivery truth source. Source presence and CI success are not substitutes for live upstream, DSH-host, or physical-device acceptance.

## Current verified state

| Area | Evidence | Status |
|---|---|---|
| Android module foundation | libxposed API 102 integration and LSPosed module metadata checks are in the mainline build workflow | Source and PR CI present; physical-device injection still requires device acceptance |
| Runtime discovery | PR #13 merged: bounded DEX metadata discovery, pagination, cancellation, task state, ClassLoader observation and inherited method metadata | Source and JVM CI present; target-process execution on device unverified |
| Dynamic Hook runtime | PR #14 merged: authenticated `/v1/hook/*` routes, full config snapshots, revision polling, late ClassLoader attachment, HookHandle inventory and apply reports | Constrained dynamic execution exists; actual device application and end-to-end AI feedback loop unverified |
| Hook action policy | Runtime parser supports `observe`, `dump`, `return`, `args`, `noop`, and `throw`; behavior-changing actions are blocked for sensitive authentication, session, credential, network, payment, security and cryptographic targets | Policy exists in source; keep the console's default workflow metadata-only |
| Hook console | Mainline PR #38 four-tab UI had a protocol mismatch: it used POST, legacy mode names and legacy parameter keys. PR #40 merged the console fix: GET current config → append a valid rule → PUT the complete snapshot | Merged; PR CI passed Debug build, JVM tests, LSPosed metadata verification and APK artifact upload |
| OpenAI-compatible gateway | Local API exposes models, Chat Completions, text Responses, SSE and local conversation routes | Text-oriented compatibility only; images, audio and full standard Function Calling are not established |
| MCP gateway | PR #37 merged MCP tool/resource listing and calls plus web reading | MCP routes exist; this is not equivalent to OpenAI standard Function Calling |
| DSH Agent plugin | PR #12 merged plugin package, 9 modes, task-state tools and 7 Skills | Package/source present; real DSH Profile installation, tool visibility and workflow execution unverified |
| DSH host bridge | Host implementation was identified on `qingjian0/Deepseek-Harness-Local-Android:refactor/dsh-gpt-hook-bridge-hardening`; host PR #35 is draft and divergent | Not release-integrated; reconcile and validate host branch before merge |
| UI Customizer | No confirmed stable ChatGPT UI target binding | Not implemented; do not present a simulated success state |
| CI | PR #38 run [45820342105](https://github.com/qingjian0/dsh-gpt/actions/runs/45820342105) passed Debug build, JVM tests, LSPosed metadata verification and artifact upload | PR-level build green; the Hook UI/backend contract defect was not caught by existing tests |
| Physical device | See [DEVICE-ACCEPTANCE.md](DEVICE-ACCEPTANCE.md) | Not run/recorded for this delivery |

## Hook configuration contract

The local API contract is:

- `GET /v1/hook/config` returns an envelope containing `config` and `revision`.
- `PUT /v1/hook/config` accepts a complete configuration snapshot, not an individual rule.
- The snapshot contains `schemaVersion`, `targetPackage` and a `rules` array.
- Each rule requires a unique `id`, `className`, `methodName`, and a supported `mode`; parameter matching uses `parameterTypes` or `methodDescriptor`.
- A successful PUT means the control plane accepted the snapshot. It does **not** prove the target process installed the hook. Confirm the target report and HookHandle inventory through `GET /v1/hook/status`.

The merged PR #40 console fix uses metadata-only `observe` and `dump` modes, validates the target package namespace, generates a unique rule ID, preserves existing rules, and submits the complete snapshot with PUT. Behavior-changing modes remain available only through the bounded runtime API and its policy checks.

## Integration decisions

- PR #14 is merged into DSH-GPT main; do not merge the historical `feat/gpt-api-hook-runtime-bridge` branch wholesale.
- R1/R2/R3 discovery work is represented on main; do not re-merge the historical discovery branches wholesale.
- PR #2 completed the Prslc template migration; the old migration branch is not a candidate for wholesale merge.
- Do not merge the old `feat/dsh-agent-hook-extension` branch wholesale. Its reported divergence is substantial; inspect and port only independently useful commits.
- DSH host bridge hardening must be reconciled against host main by reviewed commits. The host PR is not proof of production integration.
- Never claim a Hook rule is active solely because its configuration was accepted. Use target-process runtime reports.
- LAN gateway access must remain opt-in and protected by the configured Bearer API key. Do not expose the local gateway directly to the public internet.

## Branch and PR triage (2026-11-26)

The review snapshot identified 46 branches and 8 open PRs. No branches were deleted and no existing PRs were merged during this review.

| PR | Branch/topic | Snapshot | Action |
|---|---|---:|---|
| #39 | `feat/ui-complete`, legacy five-tab UI | 1 ahead / 0 behind | Do not merge wholesale; current four-tab main UI is newer. |
| #36 | Rate-limit awareness | 1 ahead / 2 behind | Rebase/review and retain the independent rate-limit behavior if still valid. |
| #30 | Compact tool schema | 1 ahead / 7 behind | Verify behavior and tests; cherry-pick only if still useful. |
| #29 | Keep-alive revert | 1 ahead / 7 behind | Validate against current connection lifecycle before considering. |
| #25 | UI state/API lifecycle | 7 ahead / 8 behind | Do not merge as-is; rebase and isolate fixes. |
| #24 | Top-window system bars | 3 ahead / 8 behind | Rebase and validate on supported Android versions. |
| #23 | APK size optimization | 3 ahead / 9 behind; Draft | Keep draft; require size comparison and regression evidence. |
| #20 | API sessions/Hook console | 18 ahead / 10 behind | Do not merge wholesale; extract any still-missing independent commits. |

Historical branch comparison also showed that `feat/dsh-agent-hook-extension` is heavily behind main despite having unique commits; it requires commit-level triage, not a branch merge. The R1/R2/R3 and template migration branches have no known unique commits in the reviewed comparison and are likely archival candidates after a final reachability check. Deletion is intentionally deferred.

PR #40 was squash-merged after all four required workflow steps passed: Debug APK build, JVM unit tests, LSPosed metadata verification and APK artifact upload. Merge commit: `6790e22798231859f0c09ee6e7bf8e7b5a12b569`.

## Remaining release blockers

1. Add focused regression tests for Hook config snapshot parsing and UI/backend contract behavior; PR #40 CI passed, but current JVM tests did not catch the original UI protocol mismatch.
2. Validate the merged Hook console against a live target process and confirm installed HookHandle inventory.
3. Validate the gateway from phone loopback and a desktop on the same LAN against the configured upstream, including authentication, text Chat Completions, Responses, SSE and consecutive turns.
4. Reconcile the DSH host bridge branch against host main; validate Provider authorization, capability negotiation, status/events/rules/discovery/cancel routes, retry and timeout semantics.
5. Install the Agent plugin into a real DSH Profile and verify tool visibility, Skill loading, native workflow/subagent execution, cancellation and result aggregation.
6. Run [DEVICE-ACCEPTANCE.md](DEVICE-ACCEPTANCE.md) on the supported Android/KernelSU/LSPosed/ChatGPT device, covering module injection, Provider Binder identity, discovery, dynamic Hook apply/replace/unhook, process restart, privacy and performance.
7. Record the full controlled feedback loop: AI decision → bounded discovery → candidate review → authorized rule update → target HookHandle confirmation → metadata-only feedback → next AI iteration.

## Release gate

DSH-GPT is mainline-integrated and buildable at the PR level, with a constrained dynamic Hook runtime and a packaged DSH Agent integration. It is **not yet a device-accepted, end-to-end autonomous AI Hook release**. Do not claim full completion until the Hook UI fix, host integration, real DSH Profile execution, live upstream checks and physical-device acceptance have recorded evidence.
