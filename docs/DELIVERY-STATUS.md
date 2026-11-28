# DSH-GPT Delivery Status

Last reviewed: 2026-11-26 (post-PR #42 merge)
Main baseline reviewed: `d1b7fa7142a0ce8c198828f28bf61a32ee030191`
Latest delivery fix merged: PR #42 (`d1b7fa7142a0ce8c198828f28bf61a32ee030191`)

This document is the delivery truth source. Source presence and CI success are not substitutes for live upstream, DSH-host, or physical-device acceptance.

## 2026-11-28 ChatGPT-only gateway delivery branch

The active implementation is being consolidated on `feat/chatgpt-only-complete-gateway` from main baseline `771e976aa5d5d9cb5c2074b14749b141806a610a`.

Product scope is ChatGPT only. The gateway supports two ChatGPT upstream access methods (Android DPoP/device-DID and web Bearer/session-cookie); there is no separate Codex provider, token, route, or model namespace.

This branch adds persisted upstream/model controls, quick vs. thinking request shaping, reasoning effort, web-search hints, temporary-chat configuration, status/config/proxy-mode/kit-import routes, and usage telemetry. TTFT is measured at the first assistant text delta; token counts are estimates (request/response character counts divided by four), not authoritative upstream billing usage. The API page now exposes the real upstream settings and usage history.

CI and physical-device acceptance are tracked separately below. Do not mark this branch release-complete until PR checks pass and a real device confirms both ChatGPT upstream modes, model/reasoning options, kit import, usage records, Hook runtime and DSH Agent integration.


## Current verified state

| Area | Evidence | Status |
|---|---|---|
| Android module foundation | libxposed API 102 integration and LSPosed module metadata checks are in the mainline build workflow | Source and PR CI present; physical-device injection still requires device acceptance |
| Runtime discovery | PR #13 merged: bounded DEX metadata discovery, pagination, cancellation, task state, ClassLoader observation and inherited method metadata | Source and JVM CI present; target-process execution on device unverified |
| Dynamic Hook runtime | PR #14 merged: authenticated `/v1/hook/*` routes, full config snapshots, revision polling, late ClassLoader attachment, HookHandle inventory and apply reports | Constrained dynamic execution exists; actual device application and end-to-end AI feedback loop unverified |
| Hook action policy | Runtime parser supports `observe`, `dump`, `return`, `args`, `noop`, and `throw`; behavior-changing actions are blocked for sensitive authentication, session, credential, network, payment, security and cryptographic targets | Policy exists in source; keep the console's default workflow metadata-only |
| Hook console | Mainline PR #38 four-tab UI had a protocol mismatch: it used POST, legacy mode names and legacy parameter keys. PR #40 merged the console fix: GET current config → append a valid rule → PUT the complete snapshot | Merged; PR CI passed Debug build, JVM tests, LSPosed metadata verification and APK artifact upload |
| OpenAI-compatible gateway | Local API listener can start independently of upstream credentials; `/health` reports readiness, and Chat Completions/Responses return explicit HTTP 503 until upstream credentials are configured. Authenticated routes include models, `/v1/capabilities`, text Responses, SSE, ToolBridge-mediated tool calls and local conversations | Text and ToolBridge are supported; image/audio/video/file inputs and structured JSON output are explicitly rejected with HTTP 400 rather than silently flattened |
| MCP gateway | PR #37 merged MCP tool/resource listing and calls plus web reading | MCP routes exist; this is not equivalent to OpenAI standard Function Calling |
| DSH Agent plugin | PR #12 merged plugin package, 9 modes, task-state tools and 7 Skills | Package/source present; real DSH Profile installation, tool visibility and workflow execution unverified |
| DSH host bridge | Host implementation was identified on `qingjian0/Deepseek-Harness-Local-Android:refactor/dsh-gpt-hook-bridge-hardening`; host PR #35 is draft and divergent | Not release-integrated; reconcile and validate host branch before merge |
| UI Customizer | No confirmed stable ChatGPT UI target binding | Not implemented; do not present a simulated success state |
| CI | PR #40 [45826199024](https://github.com/qingjian0/dsh-gpt/actions/runs/45826199024), #41 [45835490142](https://github.com/qingjian0/dsh-gpt/actions/runs/45835490142), and #42 [45838306530](https://github.com/qingjian0/dsh-gpt/actions/runs/45838306530) passed Debug build, JVM tests, LSPosed metadata verification and artifact upload | All three delivery fixes passed PR-level CI; physical-device acceptance remains separate |
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

## Branch and PR triage (2026-11-26, post-PR #42)

The reviewed snapshot had 46 branches and 8 open PRs. PR #40, PR #41 and PR #42 have since been merged; PRs #36, #29, #39, #24 and #25 were closed as superseded after confirming their relevant behavior is already present in main or selectively ported.

| PR | Branch/topic | Current assessment | Action |
|---|---|---|---|
| #42 | Local API lifecycle and Android system bars | Merged as `d1b7fa71`; PR CI passed Debug build, JVM tests, LSPosed metadata verification and APK artifact upload | Complete |
| #41 | API capability discovery and unsupported-media guardrails | Merged as `247413a2`; PR CI passed build, JVM tests, LSPosed metadata verification and APK artifact upload | Complete |
| #40 | Hook UI config contract | Merged as `6790e227`; PR CI passed | Complete |
| #36 | Rate-limit awareness | The same `sendBlockedUntil()` probe/cache and `Retry-After` behavior is already in main | Closed as superseded; do not merge the stale branch |
| #29 | Keep-alive revert | Explicit upstream `disconnect()` is already in main | Closed as superseded; do not merge the stale branch |
| #39 | Legacy five-tab UI | Replaces newer four-tab UI; its latest CI runs failed | Closed as obsolete |
| #30 | Compact tool schema | 1 unique commit, 9 behind; reduces schema detail and needs token/accuracy benchmark | Keep open; do not merge without measured quality results |
| #25 | UI state/API lifecycle | Relevant API startup/readiness behavior selectively ported in PR #42; its older UI implementation was not compatible with current main | Closed as superseded by selective port |
| #24 | Window/system bars | NoActionBar theme and edge-to-edge setup selectively ported in PR #42; old TopAppBar styling did not match current UI | Closed as superseded by selective port |
| #23 | APK size optimization | 3 commits, 11 behind; removing icons dependency is not currently safe because main UI imports Material icons | Keep Draft; do not merge as-is |
| #20 | API sessions/Hook console | 18 commits, 12 behind; contains useful ideas but its OpenAiCompat and MainActivity are based on an older incompatible architecture | Keep open for selective porting; do not merge wholesale |

Historical discovery branches and the Prslc migration branch remain archival candidates after a final reachability check. The old `feat/dsh-agent-hook-extension` branch still requires commit-level review because it contains unique work despite substantial divergence.

Remote branch deletion was not performed: the available GitHub connection exposes branch/ref updates but no delete-ref operation. The three superseded PRs are closed, while their remote branch refs remain untouched. No branch was force-updated or deleted.

## Remaining release blockers

1. Validate the merged Hook console against a live target process and confirm installed HookHandle inventory.
2. Reconcile the DSH host bridge branch against host main; its PR #35 is still Draft and 38 ahead / 12 behind. Validate Provider authorization, capability negotiation, status/events/rules/discovery/cancel routes, retry and timeout semantics.
3. Install the Agent plugin into a real DSH Profile and verify tool visibility, Skill loading, native workflow/subagent execution, cancellation and result aggregation.
4. Validate the gateway from phone loopback and a desktop on the same LAN against the configured upstream, including authentication, text Chat Completions, Responses, SSE, ToolBridge tool-call round trips and consecutive turns.
5. Run [DEVICE-ACCEPTANCE.md](DEVICE-ACCEPTANCE.md) on the supported Android/KernelSU/LSPosed/ChatGPT device, covering module injection, Provider Binder identity, discovery, dynamic Hook apply/replace/unhook, process restart, privacy and performance.
6. Record the full controlled feedback loop: AI decision → bounded discovery → candidate review → authorized rule update → target HookHandle confirmation → metadata-only feedback → next AI iteration.

## Release gate

DSH-GPT is mainline-integrated and buildable at the PR level, with a constrained dynamic Hook runtime, a packaged DSH Agent integration, a corrected Hook configuration console, and explicit API capability guardrails. It is **not yet a device-accepted, end-to-end autonomous AI Hook release**. Do not claim full completion until host integration, real DSH Profile execution, live upstream checks, target-process Hook inventory and physical-device acceptance have recorded evidence.


## Follow-up review (2026-11-26, post-PR #42 merge)

- PR #42 selectively ported the still-relevant parts of stale PR #25 and PR #24 onto current main. It decouples local listener startup from upstream credentials, returns HTTP 503 `upstream_not_configured` from the shared chat path (covering Chat Completions and Responses), adds an explicit NoActionBar theme, and enables edge-to-edge layout.
- PR #42 merged as `d1b7fa7142a0ce8c198828f28bf61a32ee030191`; CI run [45838306530](https://github.com/qingjian0/dsh-gpt/actions/runs/45838306530) passed Debug build, JVM tests, LSPosed metadata verification and APK artifact upload.
- PR #25's old MainActivity and DshApplication state changes were not ported because the current mainline UI is a different dashboard implementation; only its API lifecycle behavior was independently applicable.
- PR #24's old TopAppBar styling was not ported because mainline uses a custom dashboard header; only the theme/window behavior was independently applicable. Android 17 visual acceptance is still required.
- PRs #24 and #25 were closed as superseded after the selective port merged.
- PR #30 remains open pending a real token-size/latency benchmark and tool-call accuracy regression test; its shortened descriptions and omitted schema detail are not safe to merge on estimated savings alone.
- PR #23 remains Draft because its release workflow points at the debug APK artifact path and the proposed dependency removal is not yet proven safe.
- PR #20 remains open for selective extraction only; its release workflow and older conversation UI are not safe to merge wholesale.
- Host PR #35 remains Draft in `qingjian0/Deepseek-Harness-Local-Android`; it is 38 commits ahead / 12 behind host main. Static Provider/Bundle contract agreement is not runtime acceptance. Reconcile only its Hook bridge, endpoint catalog and profile-plugin packaging against current host main, then run host CI and real Profile installation tests.
