# GPT API Hook Runtime Control

The local GPT API is the control-plane entry point for the Hook Runtime. It authenticates requests, validates declarative rules, and relays operations through the module-owned ContentProvider to the ChatGPT target process.

## Authentication and exposure

- Every route below requires `Authorization: Bearer <client apiKey>`.
- The API key must be configured; protected routes return `503 configuration_required` when it is blank.
- The gateway binds to `127.0.0.1` by default. LAN binding is optional and should remain disabled unless needed.
- The module-owned provider accepts API control calls only from the module app UID. The ChatGPT target process separately polls accepted config and discovery work.
- Never expose the gateway publicly or put upstream ChatGPT credentials in Hook rules.

## Routes

| Method | Path | Purpose |
|---|---|---|
| GET | `/v1/hook/status` | Read configured rule count, enabled state, config revision, last observation timestamp and current discovery task |
| GET | `/v1/hook/events` | Read the bounded metadata-only observation report |
| GET | `/v1/hook/startup-discovery` | Read the latest automatic bounded DEX scan snapshot |
| GET | `/v1/hook/config` | Read the active configuration snapshot |
| PUT | `/v1/hook/config` | Validate and save a declarative Hook configuration |
| PUT | `/v1/hook/enabled` | Enable or disable Hook callbacks |
| POST | `/v1/hook/inspect` | Inspect one exact class and return bounded method/field metadata |
| POST | `/v1/hook/discover` | Queue an explicit bounded DEX discovery request |
| GET | `/v1/hook/discovery/{requestId}` | Read discovery task and result |
| DELETE | `/v1/hook/discovery/{requestId}` | Request cancellation of a discovery task |

## Read status

```http
GET /v1/hook/status
Authorization: Bearer <client apiKey>
```

`targetObserved` means a target-process observation/runtime snapshot reached the provider. `lastApplyReport` reports the latest target-side application result and added/replaced/retained/removed/failed counts. `activeHookCount` and `installedHooks` come from the target process's actual retained HookHandle registry, not the config file or discovery candidates. Each entry includes rule ID, exact method signature, sample interval and invocation count. A missing runtime snapshot is `unavailable`, not a confirmed zero-Hook state.

## Exact method selection

Rules may use either the legacy `parameterTypes` selector or the exact JVM `methodDescriptor` selector. Do not provide both. The descriptor includes the return type and disambiguates overloaded methods completely:

```json
{
  "schemaVersion": 1,
  "targetPackage": "com.openai.chatgpt",
  "rules": [{
    "id": "session-validity",
    "className": "com.openai.auth.Session",
    "methodName": "isValid",
    "methodDescriptor": "(Ljava/lang/String;)Z",
    "enabled": true,
    "sampleEvery": 1
  }]
}
```

Descriptors follow JVM syntax: primitive types use single-letter codes, object types use `Lpackage/Class;`, arrays use `[`, and the return type immediately follows `)` with no whitespace (for example `(I)Z`). Exact descriptors are validated before persistence and compared against the reflected method signature in the target process. If omitted, existing `parameterTypes` behavior is preserved; an empty list remains a wildcard for legacy compatibility, so prefer descriptors for overloaded methods.

A successful `PUT /v1/hook/config` means only that the configuration was validated and persisted. The target process applies it asynchronously; verify `lastApplyReport` and the installed-Hook inventory before claiming it is active.

## Enable or disable

```http
PUT /v1/hook/enabled
Authorization: Bearer <client apiKey>
Content-Type: application/json

{"enabled": false}
```

The response acknowledges persistence. Target-process application is asynchronous.

## Automatic startup discovery

When the ChatGPT target process reaches its Application context, the module starts one bounded metadata-only DEX scan in a background worker. Class-load observation is enabled by default when omitted from config; it records deduplicated `com.openai.*` class names up to a fixed budget. Set `observeClassLoads: false` to disable it. Current scan limits: 16 DEX files, 2,500 class names, 1.5 seconds, 100 member records and a 64 KiB response. It does not initialize or invoke discovered app classes. Results are partial when truncation is reported.

## Inspect one exact class

Use exact inspection to retrieve declared and inherited method signatures, constructors and field metadata for one class. This is a metadata-only operation; it does not initialize the class or invoke methods.

```http
POST /v1/hook/inspect
Authorization: Bearer <client apiKey>
Content-Type: application/json

{"className":"com.openai.auth.Session","limit":200,"cursor":0}
```

The endpoint requires an exact `className` under `com.openai.*` and returns HTTP 202 with a request ID. Poll `/v1/hook/discovery/{requestId}` for the result. Method records include signatures suitable for deriving exact JVM descriptors; generated candidate proposals remain suggestions and are not installed automatically.

## Discover classes and methods

```http
POST /v1/hook/discover
Authorization: Bearer <client apiKey>
Content-Type: application/json

{"prefix":"com.openai.auth","limit":100,"cursor":0}
```

The prefix and optional exact class must remain within `com.openai.*`. The request returns HTTP 202 and a request ID. Poll the corresponding discovery route until the task reaches a terminal state.

## Observation boundary

Reports contain bounded metadata such as rule ID, method signature, elapsed time, sequence, exception type and actual installed-Hook handles. They do not contain method arguments, return values, HTTP headers, cookies, access tokens, request/response bodies or challenge/proof material.

## Current capability boundary

This increment adds exact overload selection for metadata-only observation rules. It does not add behavior-changing actions such as `return`, `noop`, `args` or `throw`. Those require a separately typed and authorized action contract, explicit risk restrictions, verified LibXposed chain semantics, lifecycle rollback and target-device tests. Arbitrary code execution is not supported.
