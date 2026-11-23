# DSH AI Dynamic Hook Runtime

## Runtime behavior

The Kotlin/libxposed API 102 runtime supports live rule application in the ChatGPT target process:

1. Send a new complete rule snapshot to `PUT /v1/hook/config`.
2. The module app stores it through the authenticated local API and Provider boundary.
3. The target process picks up the new revision on its short config poll and applies it without rebuilding the APK or restarting ChatGPT.
4. Existing Hook IDs are replaced when a rule changes; removed rules are unhooked.
5. If a configured class is not available yet, the ClassLoader watcher attaches it when that class is loaded later.
6. Runtime status exposes the actual installed Hook handles, modes and invocation counts.

## Dynamic rule schema

```json
{
  "schemaVersion": 1,
  "targetPackage": "com.openai.chatgpt",
  "observeClassLoads": true,
  "rules": [{
    "id": "sample-method",
    "className": "com.openai.example.Sample",
    "methodName": "calculate",
    "methodDescriptor": "(I)I",
    "mode": "return",
    "result": 7,
    "enabled": true,
    "sampleEvery": 1
  }]
}
```

Modes:
- `observe` / `dump`: call the original method and record bounded timing/exception metadata.
- `return`: return a configured primitive or String value without calling the original method.
- `args`: replace selected primitive/String arguments, then call the original method.
- `noop`: skip the original method and return its default value.
- `throw`: skip the original method and throw a configured `IllegalStateException`.

Argument indexes are zero-based. Return/argument values are JSON scalars and are type-checked against the actual reflected method before installation. Constructors support observation only. Exact JVM descriptors distinguish overloads; constructor descriptors end in `)V`. Up to eight matching overloads are attached per rule.

## Live control

- `GET /v1/hook/status`: actual installed Hook inventory and latest apply report.
- `GET /v1/hook/events`: bounded metadata-only runtime observations.
- `POST /v1/hook/discover`: bounded DEX discovery.
- `POST /v1/hook/inspect`: exact class metadata inspection.
- `PUT /v1/hook/config`: replace the active rule snapshot; the target runtime applies it live.
- `PUT /v1/hook/enabled`: disable and unhook all active rules, or re-enable the current configuration.

A config update is picked up on the target process's short poll. It does not require an APK rebuild or app restart. A successful config response means it is stored; check `lastApplyReport` and `installedHooks` for actual runtime state.

## Safety and privacy

Behavior-changing modes are rejected for authentication, session, token, credential, network, payment, attestation, security and cryptographic targets, and for sensitive method names such as verify/authorize/sign. Values are restricted to primitive and String scalars; arbitrary scripts and object injection are not supported.

Observation payloads never serialize method arguments, return values, authorization headers, cookies, tokens, request/response bodies or anti-abuse proof material. Do not use Hook rules to bypass authentication, access controls, payment checks or security enforcement.
