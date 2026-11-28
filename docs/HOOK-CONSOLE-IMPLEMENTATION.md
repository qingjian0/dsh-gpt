# Hook Console Implementation Status

## Delivered in this change

- Reorganized the Hook screen into Overview, Built-in Hooks, Dynamic Hooks, Class Discovery, Events, and Diagnostics sections.
- Dynamic rules support create, edit, enable/disable, and delete through the existing `/v1/hook/config` endpoint.
- Rule detail expands to show reported method signatures, invocation counts, and related metadata events.
- DEX discovery submits prefix scans and exact-class inspections, renders class/member counts, truncation flags, pagination, and candidate proposals.
- Candidate proposals can be added as observation-only rules using the supplied JVM descriptor.
- Target process freshness is exposed by `/v1/hook/status`; reports older than 30 seconds are not considered online.
- The UI explicitly labels the dynamic Hook switch as controlling dynamic rules only.

## Deliberate status boundaries

- Built-in Hardening hooks are catalogued by group, but the current runtime report does not include per-target install outcomes. The UI therefore labels them `not individually confirmed`; it does not infer success from configuration or aggregate hook counts.
- The current API does not expose a complete request-dump settings/clear contract. The UI does not invent a request-capture toggle or claim that sensitive log cleanup is available.
- Startup discovery has a separate endpoint; the console exposes its timestamp and class count, while the task-based DEX discovery flow renders paged results and member metadata.
- The old `gpt-hook/` implementation is not part of the current APK and is labelled as not migrated.
- Runtime target online status is a freshness heuristic (30 seconds), not a process heartbeat or proof that every Hook remains installed.

## Verification

- CI release build, JVM tests, and LSPosed metadata validation are required before merge.
- Physical-device verification still requires installing the resulting APK and checking it in the user's LSPosed/KernelSU environment.

## Data handling

- The console displays rule/class/method metadata, elapsed time, invocation counts, and exception types.
- It does not display method arguments, return values, cookies, tokens, request headers, or request bodies.