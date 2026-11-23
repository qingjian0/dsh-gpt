# DSH-GPT Delivery Status

Last reviewed: 2026-11-23
Baseline: `main` at `1a410482ac6b34ba1ea8d72d2e5bc81e57542b01`

This is the delivery truth source. Source presence and CI success are not substitutes for live upstream, DSH-host, or physical-device acceptance.

## Current verified state

| Area | Evidence | Status |
|---|---|---|
| Six-tab Compose console | PR #9 merged | UI baseline present; Agent and Customizer remain incomplete |
| Android build | Main workflow run [45174709799](https://github.com/qingjian0/dsh-gpt/actions/runs/45174709799) succeeded on this exact baseline | CI passed; artifact is attached to the run |
| Conversations | PR #11 merged; persistent local conversation records, chat UI and local gateway call path | Code and build verified; live upstream multi-turn/device acceptance pending |
| Agent runtime package | PR #12 merged; 9 modes, task-state tools, 7 Skills, native DSH workflow reuse | Node contract CI passed; real DSH Profile install/tool visibility/subagent execution unverified |
| Hook discovery | PR #13 merged; bounded DEX metadata discovery, pagination/cancel/task state | CI passed; target-process device execution unverified |
| R3 inherited metadata and stable candidates | Present on main; bounded inherited method metadata and evidence-backed candidate proposals | Source and JVM CI present; device acceptance pending |
| Hook execution | Current HookRuntime applies metadata-observation interceptors; candidate proposals are not auto-installed | Declarative mutation modes and verified install/remove lifecycle are not complete |
| DSH host bridge | Existing bridge work is not fully reconciled/accepted against the host runtime | End-to-end IPC and capability negotiation pending |
| Agent UI/orchestration | Agent page exists, but its controls are not yet connected to a live coordinator | Not complete |
| GPT UI Customizer | Tab exists as a placeholder | Not implemented |
| Physical device | See `docs/DEVICE-ACCEPTANCE.md` | Not run/recorded in this delivery |

## Superseded pull requests

- PR #1: plugin implementation was ported into `integration/dsh-agent-runtime/` by PR #12; keep host-profile acceptance separate.
- PR #4 and #5: their inherited metadata and stable candidate work is already present on main through the later discovery integration. Do not merge their stacked branches again.
- PR #6 and #7: old View dashboard chain is superseded by the six-tab Compose UI.
- PR #8: do not merge wholesale. It combines an old View UI, a process-local relay session map, full class dump behavior, and active runtime mutation modes. The current conversation repository and bounded discovery path supersede parts of it; the mutation modes require a separately reviewed permission/rollback contract and tests.

## Remaining release blockers

1. Validate the authenticated API gateway against the configured live upstream, including SSE and at least two consecutive turns using persisted conversation identity.
2. Install the Agent package into a real DSH Profile and verify tool visibility, Skill loading, task-state tools, native workflow/subagent execution, cancellation and result aggregation.
3. Verify module-to-host IPC, capability negotiation, discovery pagination/cancellation, and rule configuration on the target process.
4. Add a guarded Hook apply/remove lifecycle with explicit discovered/proposed/approved/validated/installed/running/failed states; verify harmless test rules and rollback on-device.
5. Implement GPT UI Customizer against a confirmed ChatGPT UI target, with reversible changes and restore-default behavior.
6. Run and record `docs/DEVICE-ACCEPTANCE.md` on the supported Android/KernelSU/LSPosed device.

## Release gate

The latest main Android workflow is green. DSH-GPT is **buildable and partially integrated, not yet a complete device-accepted release**. Do not claim full completion until every blocker above has recorded evidence.