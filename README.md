# DSH GPT Diagnostics

An LSPosed module and on-device dynamic Hook configuration and observation console based on libxposed API 102.

## Features

- Modern libxposed module entry point and LSPosed metadata.
- Declarative JSON Hook rules with local validation and live remote-preference updates.
- Deterministic method matching and stable Hook IDs; per-method failures are isolated.
- Bounded metadata-only observation buffer with debounced target-to-module IPC.
- Exported ContentProvider validates the publishing Binder UID against the configured target package; only the module app UID may read reports.
- On-device report refresh, JSON copy and Android share sheet for an explicitly selected AI assistant.
- User-mediated AI iteration: share sanitized report → analyze externally → paste suggested JSON → validate → apply.
- CI debug APK build and metadata verification.

## Build

Use JDK 21 and Gradle 9.6.1:

```bash
gradle assembleDebug --no-daemon
```

## Install and operate

1. Install the debug APK and enable the module in LSPosed.
2. Ensure `com.openai.chatgpt` is in module scope.
3. Open DSH and confirm LSPosed service connection.
4. Paste/edit declarative JSON and tap **Validate and apply configuration**.
5. Trigger a configured method in the target app.
6. Return to DSH and tap **Refresh** to view the bounded observation report.
7. Use **Copy JSON** or **Share to AI**. Paste AI-generated rules back into the editor and validate/apply them.

See [AI_DYNAMIC_HOOK.md](docs/AI_DYNAMIC_HOOK.md) for schema, IPC design and privacy constraints.

## Privacy and security

Only method signature, timing, sequence, rule ID and exception class are recorded. No method arguments, return values, headers, cookies, tokens, request/response bodies, device identifiers or anti-abuse challenge/proof material are collected. No silent network upload or challenge bypass is implemented.

The observation ContentProvider is exported because the target app and module app have separate UIDs. It accepts writes only from the configured target package UID and restricts reads to the module app UID. IPC behavior still requires real-device verification.

## Validation status

A successful CI build verifies compilation, APK packaging and LSPosed metadata. It does not establish runtime hook behavior or IPC compatibility on a physical device.
