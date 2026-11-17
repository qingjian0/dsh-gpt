# DSH GPT Diagnostics

An LSPosed module and on-device configuration console based on Prslc/LSPosed_Template and libxposed API 102.

## What is included

- Modern libxposed module entry point (`com.gpt.hook.MainModule`)
- On-device DSH configuration console connected through `libxposed/service`
- JSON declarative Hook rules stored in LSPosed remote preferences
- Live preference-change listener: rules can be changed without rebuilding or restarting the target app
- Dynamic method resolution and libxposed interceptor installation with stable Hook IDs
- Atomic hook replacement and removal of obsolete rules
- Bounded metadata-only observation buffer and AI-oriented JSON schema bridge
- CI debug APK build plus verification of packaged LSPosed metadata

## Build

Use JDK 21 and Gradle 9.6.1:

```bash
gradle assembleDebug --no-daemon
```

## Install and operate

1. Install the generated APK.
2. Enable DSH GPT Diagnostics in LSPosed and include `com.openai.chatgpt` in scope.
3. Open the DSH app and confirm the LSPosed service connection.
4. Paste or edit declarative JSON rules and tap **Validate and apply configuration**.
5. The target process receives the remote-preference change and updates Hook rules in-process.
6. Inspect Logcat using tag `GPTHook`.

See [AI_DYNAMIC_HOOK.md](docs/AI_DYNAMIC_HOOK.md) for the rule schema and operational details.

## Data handling boundary

The runtime records method signatures, timing, invocation sequence and exception classes only. It does not read or export authorization headers, access tokens, cookies, device credentials, request/response bodies or anti-abuse challenge/proof material. It does not implement challenge solving or bypass.

The current AI bridge is a local JSON contract. A hosted AI provider/report transport is not bundled; any future provider must be explicitly configured and must not receive secrets or raw request content.

## Validation status

A successful CI build verifies compilation, APK packaging and LSPosed metadata. It does not replace installation and runtime verification on the user's LSPosed device.