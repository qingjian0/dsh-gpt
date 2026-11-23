# DSH-GPT Delivery Status

Last reviewed: 2026-11-23
Baseline: `main` at `6ffe6e5f1b3330fb996d57464154bd441de498c4`

This document separates source-code presence, CI evidence, host integration, and physical-device acceptance. A successful build is not runtime acceptance.

## Verified baseline

| Area | Current evidence | Status |
|---|---|---|
| Six-tab Compose console | Merged in PR #9; six tabs render from `MainActivity.kt` | UI baseline present; several screens remain placeholders |
| Android build | Main workflow run 45167634734 passed | CI verified |
| DSH plugin validation | Main workflow run 45167634830 passed | CI verified; host runtime still needs acceptance |
| Local API gateway | `ApiService`, `LocalApiServer`, health/models/chat routes exist | Source present; live upstream/device acceptance pending |
| Conversation persistence | SQLite repository and CRUD are present; basic list/create/delete UI exists | Partial; provider/session/Agent lifecycle gaps remain |
| Hook runtime | libxposed 102, rule parser/runtime, observation provider, bounded discovery, inherited method metadata and stable candidate proposals exist on main | Source present; target-process acceptance pending |
| Agent UI | Main conversation policy and depth selector are visible | Not connected to a real coordinator; controls are currently presentation-only |
| GPT UI Customizer | Tab exists | Not implemented; current screen is a placeholder |
| DSH host bridge | Host implementation exists on `refactor/dsh-gpt-hook-bridge-hardening`; CI runs 45003333937 and 45003333935 passed | Not merged into host main; branch divergence and device IPC acceptance remain |
| Physical device | Checklist exists in `docs/DEVICE-ACCEPTANCE.md` | Not evidenced by CI; must be run on device |

## Branch decisions

- PR #4 and #5: do not merge again. Their inherited-method and stable-candidate implementation is already present in current DSH-GPT main.
- PR #6 and #7: do not merge. They are the older View dashboard chain and conflict with the merged Compose six-tab UI direction.
- PR #8: do not merge as a whole. Its in-memory session map and caller identity fallback are not a replacement for the persistent caller-scoped conversation repository. Port only individually reviewed runtime changes after tests.
- PR #1: keep separate from the Android APK. It is a DSH host plugin package; CI passing does not prove profile installation, tool visibility, or workflow execution.
- `feat/dsh-agent-hook-extension`: do not merge wholesale without reviewing the host/runtime contract and resource limits.

## Fastest safe delivery sequence

1. Keep the merged Compose UI and avoid another UI redesign.
2. Complete the Android API and conversation path using the existing repository; add request/continuation tests and remove any misleading "connected" status labels.
3. Finish the DSH host bridge integration by reconciling `refactor/dsh-gpt-hook-bridge-hardening` with host `main`; do not merge a diverged branch blindly.
4. Connect Agent UI to negotiated host capabilities. If the host does not expose a capability, show it as unavailable rather than simulating execution. Reuse DSH's native subagent/workflow engine.
5. Keep Hook lifecycle states distinct: discovered, proposed, approved, validated, installed, running, failed, and unverified.
6. Implement GPT UI Customizer only after a real ChatGPT UI hook target and reversible rule contract are verified.
7. Run Android CI, plugin CI, APK artifact checks, then the manual device checklist. Record actual outputs and exact versions.

## Acceptance gates

- Build: Android Debug APK and DSH plugin tests pass.
- API: health, model list, authenticated chat, SSE completion, and two-turn conversation continuity pass against the live configured upstream.
- Host bridge: status, rules, discovery pagination/cancellation, event delivery, and capability negotiation pass between the actual DSH host and module.
- Hook: harmless test rule applies, survives identical snapshot, reports replacement/removal accurately, and can be disabled/rolled back.
- Agent: main conversation selection, child conversation lineage, bounded depth/concurrency, cancellation, and result aggregation run through DSH's native engine.
- Customizer: a real target UI element is changed, persisted, restored, and verified on the target app version.
- Device: all relevant items in `docs/DEVICE-ACCEPTANCE.md` are recorded on the supported Android/LSPosed device.

No completion claim is valid solely from source presence or CI success.
