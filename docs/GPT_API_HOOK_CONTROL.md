# GPT API Hook Runtime Control

The local GPT API controls the live Hook Runtime in the ChatGPT target process. Every route requires `Authorization: Bearer <client apiKey>`.

## Routes

| Method | Path | Purpose |
|---|---|---|
| GET | `/v1/hook/status` | Actual installed Hook inventory, invocation counts and apply report |
| GET | `/v1/hook/events` | Bounded metadata-only observations |
| GET | `/v1/hook/startup-discovery` | Latest automatic bounded DEX scan |
| GET | `/v1/hook/config` | Current rule snapshot |
| PUT | `/v1/hook/config` | Replace rules and apply them live |
| PUT | `/v1/hook/enabled` | Enable or disable the runtime |
| POST | `/v1/hook/inspect` | Inspect one exact class |
| POST | `/v1/hook/discover` | Discover classes and method metadata |
| GET | `/v1/hook/discovery/{requestId}` | Read discovery result |
| DELETE | `/v1/hook/discovery/{requestId}` | Cancel a discovery task |

## Dynamic Hook example

```http
PUT /v1/hook/config
Authorization: Bearer <client apiKey>
Content-Type: application/json

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

Modes: `observe`/`dump`, `return`, `args`, `noop`, `throw`. For `args`, use a JSON object with zero-based argument indexes and primitive/String scalar values. The runtime checks actual parameter/return types before installing a behavior-changing hook. Constructors use `methodName: "<init>"` and support observe mode only.

Changes are picked up by the target process on its short config poll. Matching classes loaded later are hooked automatically. Existing Hook IDs are replaced; removed rules are unhooked. No APK rebuild or target-app restart is required.

## Status

`activeHookCount` and `installedHooks` come from the target process's retained HookHandle registry. Each entry reports the applied mode. `lastApplyReport` reports added, replaced, retained, removed and failed counts.

## Boundaries

Discovery and Hook rules are restricted to `com.openai.*`. Behavior-changing modes are blocked for authentication, session, token, credential, network, payment, attestation, security and cryptographic targets, and sensitive methods such as verify/authorize/sign. Only primitive/String scalar values are accepted; arbitrary code execution and object injection are not supported.

Observation reports do not contain method arguments, return values, authorization headers, cookies, tokens, request/response bodies or anti-abuse proof material. Do not use rules to bypass authentication, payment checks, access controls or security enforcement.
