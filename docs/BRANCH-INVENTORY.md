# Repository Branch Inventory

Snapshot: 2026-11-22. Comparisons are against each repository's `main`.
This is a review/triage record, not an instruction to merge.

## qingjian0/dsh-gpt

| Branch | Ahead | Behind | PR / state | Assessment |
|---|---:|---:|---|---|
| `main` | 0 | 0 | — | Protected baseline |
| `refactor/hook-capability-baseline` | 7 | 0 | No PR | Keep as the reviewed Kotlin/libxposed baseline |
| `refactor/r1-bounded-discovery` | 43 | 0 | No PR | Keep; current R1 delivery branch, Android Debug CI and JVM tests passed |
| `feat/dsh-agent-hook-extension` | 27 | 3 | No PR found | Keep as a source donor; do not merge wholesale because of scan/IPC issues |
| `feat/dsh-gpt-runtime-plugin` | 89 | 92 | PR #1 open, Draft | Keep. Separate DSH general runtime/modes/skills plugin; not the Android Hook runtime. Needs a deliberate rebase/review before any integration |
| `feat/observation-bridge-console` | 0 | 9 | PR #3 merged | No unique commits remain. Safe cleanup candidate |
| `feat/prslc-template-migration` | 0 | 22 | PR #2 merged | No unique commits remain. Safe cleanup candidate |

### Branch handling

- PR #2 (`feat/prslc-template-migration`) is merged.
- PR #3 (`feat/observation-bridge-console`) is merged.
- Those two branch refs are stale cleanup candidates; their changes are already in `main`.
- PR #1 (`feat/dsh-gpt-runtime-plugin`) remains open and Draft. Do not delete its branch.
- `feat/dsh-agent-hook-extension` contains useful external Agent tool work but has three commits of drift from main; port reviewed components rather than merging the whole branch.
- Branch deletion was not performed: the currently available GitHub operations in this session do not expose a branch-delete action.

## qingjian0/Deepseek-Harness-Local-Android

| Branch | Ahead | Behind | Assessment |
|---|---:|---:|---|
| `feat/dsh-gpt-hook-agent-bridge` | 27 | 12 | Contains the real HookAgentBridge, HTTP routes, EndpointCatalog and bundled profile plugin. Keep as source baseline; do not merge wholesale |
| `refactor/dsh-gpt-hook-bridge-hardening` | 36 | 12 | Keep; protocol alignment, status/retry/cancel routes and plugin validation in progress |

The host branch remains 12 commits behind `main`; conflict reconciliation is a separate task. The hardening branch is not merged.

## Merge policy

Only merge a reviewed, tested subset after:
1. the branch is current with its target base or conflicts are explicitly resolved;
2. Android and plugin CI are green;
3. Provider/host protocol versions match;
4. the device acceptance checklist passes.

A branch being behind is not by itself a reason to delete it; PR state and unique commits are checked first.
