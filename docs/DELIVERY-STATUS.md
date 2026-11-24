# DSH-GPT Delivery Status

Last reviewed: 2026-11-24
Baseline: `main` at `0270a78b1c319a00ae0eda952cc7dd378b1fe951`

This is the delivery truth source. Source presence and CI success are not substitutes for live upstream, DSH-host, or physical-device acceptance.

## Current verified state

| Area | Evidence | Status |
|---|---|---|
| Six-tab Compose console | PR #9 merged | Shell and navigation present; Agent and Customizer remain incomplete |
| Android build | PR #14 head CI run [45209578307](https://github.com/qingjian0/dsh-gpt/actions/runs/45209578307) passed build, JVM tests, LSPosed metadata verification and artifact upload | PR CI and post-merge main run [45357004687](https://github.com/qingjian0/dsh-gpt/actions/runs/45357004687) both passed |
| Conversations | PR #11 merged; persistent local records, chat UI and gateway continuation path | Code present; live upstream multi-turn/device acceptance pending |
| Agent runtime package | PR #12 merged; 9 modes, task-state tools, 7 Skills | Plugin contract CI passed; DSH Profile installation, tool visibility and real subagent execution unverified |
| Hook discovery | PR #13 merged; bounded DEX metadata discovery, pagination, cancellation and task state | Source and CI present; target-process device execution unverified |
| R1/R2/R3 discovery | Bounded discovery, ClassLoader observation, inherited method metadata and stable candidate proposals on main | Source and JVM CI present; device acceptance pending |
| GPT API dynamic Hook Runtime | PR #14 merged as `bc8a9280`; authenticated `/v1/hook/*` routes, live rule snapshots, dynamic ClassLoader attachment, typed scalar actions, HookHandle inventory and apply reports | PR CI passed; physical-device behavior and full feedback loop remain unverified |
| Universal OpenAI-compatible API | PR #18 merged as `0270a78b`; generated Bearer key, optional DSH client header, `/v1/models`, `/v1/chat/completions`, text-only `/v1/responses`, SSE, CORS and LAN endpoint display | PR CI run [45367206357](https://github.com/qingjian0/dsh-gpt/actions/runs/45367206357) passed build, JVM tests, metadata verification and APK upload; live upstream and physical-device acceptance pending. Images, audio and tool/function calling are not supported by the current adapter |
| DSH host bridge | Host implementation exists on `qingjian0/Deepseek-Harness-Local-Android:refactor/dsh-gpt-hook-bridge-hardening` | Draft host PR [#35](https://github.com/qingjian0/Deepseek-Harness-Local-Android/pull/35); branch is 38 commits ahead and 12 behind host main, and GitHub currently reports it non-mergeable. Resolve conflicts and review before any merge |
| Agent UI/orchestration | Agent page exists; controls are not connected to the DSH host coordinator | Not complete |
| GPT UI Customizer | Tab exists as a placeholder; no confirmed target-side menu binding | Not implemented |
| Physical device | See `docs/DEVICE-ACCEPTANCE.md` | Not run/recorded for this delivery |

## Integration decisions

- PR #14 was reviewed, marked ready, and squash-merged into DSH-GPT main on 2026-11-24.
- PR #18 was squash-merged as `0270a78b` on 2026-11-24. The local gateway is now a generic OpenAI-compatible text API; no DSH-specific client header is required. Phone clients use loopback, while desktop clients use the phone's LAN IPv4 address when LAN access is enabled.
- The dynamic Hook API exposes real target-process state; a stored configuration is not reported as installed until the target runtime publishes its HookHandle inventory.
- Behavior-changing Hook actions remain constrained to scalar primitive/String values and are blocked for sensitive authentication, session, credential, network, payment, security and cryptographic targets. Do not use this module to bypass authentication, payment, access controls or security enforcement.
- Do not merge the old `feat/dsh-agent-hook-extension` DSH-GPT branch wholesale: it has diverged substantially from main. The current module-side Provider and Agent plugin assets should be reconciled with the host-side hardening branch as a reviewed protocol integration.
- The host hardening branch contains the revision/lease discovery lifecycle and DSH Cordis plugin packaging, but its 12-commit divergence from host main must be reconciled before release.
- No UI state may claim a host operation succeeded unless the host capability and operation are actually available.

## Remaining release blockers

1. Confirm the post-merge main Android workflow for `0270a78b` completes successfully and retain its APK artifact; PR validation [45367206357](https://github.com/qingjian0/dsh-gpt/actions/runs/45367206357) is green.
2. Validate the generic local GPT API from phone loopback and a desktop on the same LAN against the configured upstream, including Chat Completions, Responses, SSE, authentication and consecutive turns. Current upstream adapter is text-only.
3. Reconcile the DSH host bridge hardening branch against host main by porting reviewed commits; validate provider authorization, capability negotiation, status/events/rules/discovery/cancel routes, retry and timeout semantics.
4. Install the Agent plugin into a real DSH Profile and verify tool visibility, Skill loading, native workflow/subagent execution, cancellation and result aggregation. Connect the Agent UI only to those confirmed host capabilities.
5. Complete GPT UI Customizer only after identifying stable ChatGPT UI targets; implement reversible changes and restore-default behavior. Until then, show the feature as unavailable rather than simulating success.
6. Run `docs/DEVICE-ACCEPTANCE.md` on the supported Android/KernelSU/LSPosed/ChatGPT device, covering module injection, Provider Binder identity, startup discovery, dynamic class loading, Hook apply/replace/unhook, process restart, privacy and performance.
7. Add end-to-end acceptance evidence for the closed loop: GPT API decision → bounded discovery → rule update → target HookHandle confirmation → metadata-only feedback → next GPT iteration.

## Release gate

DSH-GPT is now **mainline-integrated and buildable at the PR level, but not yet a complete device-accepted release**. Do not claim full completion until the post-merge build, DSH host integration, UI Customizer, live upstream checks and physical-device acceptance have recorded evidence.
