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

## Live Hook configuration

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
    "methodName": "run",
    "methodDescriptor": "(Ljava/lang/String;)V",
    "enabled": true,
    "sampleEvery": 1
  }]
}
```

The target process picks up changes on its short config poll. New or changed hooks are attached live; replaced hooks use stable LibXposed IDs; removed rules are unhooked. A class that is not loaded yet is hooked automatically when its class loader later loads it. No APK rebuild or target-app restart is required.

Use `methodName: "<init>"` to hook constructors; constructor descriptors must end in `)V`. Use either `methodDescriptor` or `parameterTypes`, not both.

## Status

`activeHookCount` and `installedHooks` come from the target process's retained HookHandle registry. `lastApplyReport` distinguishes added, replaced, retained, removed and failed hooks. A config write is asynchronous; check status to confirm target-side application.

## Boundaries

Discovery and Hook rules are restricted to `com.openai.*`. The runtime records bounded metadata only: rule ID, method signature, elapsed time, sequence and exception class. It does not capture arguments, return values, authorization headers, cookies, tokens, request/response bodies or anti-abuse proof material. Do not use rules to bypass authentication, payment checks, access controls or security enforcement. Arbitrary script execution is not supported.
