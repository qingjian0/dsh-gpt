# DSH AI Dynamic Hook Runtime

## Runtime behavior

The Kotlin/libxposed API 102 runtime supports live rule application in the ChatGPT target process:

1. Send a new complete rule snapshot to `PUT /v1/hook/config`.
2. The module app stores it through the authenticated local API and Provider boundary.
3. The target process picks up the new revision on its short config poll and applies it without rebuilding the APK or restarting ChatGPT.
4. Existing Hook IDs are replaced atomically when the same target method is updated; removed rules are unhooked.
5. If a configured class is not available yet, the rule remains active in the runtime rule set. The ClassLoader watcher attaches it when that class is loaded later.
6. Runtime status and observation events expose the actual installed Hook handles and invocation counts.

Class-load watching is enabled by default. It filters to `com.openai.*`, deduplicates class names, guards callback re-entry, and performs work only for classes with matching active rules. It does not enumerate arbitrary app data or call target methods.

## Rule schema

```json
{
  "schemaVersion": 1,
  "targetPackage": "com.openai.chatgpt",
  "observeClassLoads": true,
  "rules": [{
    "id": "sample-method",
    "className": "com.openai.example.Sample",
    "methodName": "run",
    "methodDescriptor": "(Ljava/lang/String;)V",
    "enabled": true,
    "sampleEvery": 1
  }]
}
```

Use either `methodDescriptor` or `parameterTypes`, not both. Descriptors disambiguate overloads and include the return type. Constructor rules use `methodName: "<init>"` and a descriptor ending in `)V`. Up to eight matching overloads are attached per rule.

## Control API

- `GET /v1/hook/status`: actual installed Hook inventory and latest apply report.
- `GET /v1/hook/events`: bounded metadata-only runtime observations.
- `POST /v1/hook/discover`: bounded DEX discovery.
- `POST /v1/hook/inspect`: exact class metadata inspection.
- `PUT /v1/hook/config`: replace the active rule snapshot; the target runtime applies it live.
- `PUT /v1/hook/enabled`: disable and unhook all active rules, or re-enable the current configuration.

A successful config response means it is stored; target-process application is asynchronous. Confirm the actual result with `lastApplyReport` and `installedHooks`.

## Scope and privacy

The runtime currently implements observation hooks and constructor/method interception. It does not execute arbitrary scripts or collect method arguments, return values, authorization headers, cookies, access tokens, request/response bodies, or anti-abuse proof material. Do not use Hook rules to bypass authentication, access controls, payment checks, or security enforcement.

The old standalone Java/Xposed implementation is not part of the APK build. Its broad behavior-mutating modes are not silently imported into this API 102 runtime.
