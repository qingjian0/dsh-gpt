# GPT API Hook Runtime Control

The local GPT API is the control-plane entry point for the Hook Runtime. It does not run hooks itself. It authenticates a local API request, validates the request, and relays the operation through the module-owned ContentProvider to the ChatGPT target process.

## Authentication and exposure

- Every route below requires `Authorization: Bearer <client apiKey>`.
- The API key must be configured; protected routes return `503 configuration_required` when it is blank.
- The gateway binds to `127.0.0.1` by default. LAN binding is optional and should remain disabled unless needed.
- The module-owned provider accepts these API control calls only from the module app UID. The ChatGPT target process separately polls accepted config and discovery work.
- Never expose the gateway publicly or put upstream ChatGPT credentials in Hook rules.

## Routes

| Method | Path | Purpose |
|---|---|---|
| GET | `/v1/hook/status` | Read configured rule count, enabled state, config revision, last observation timestamp and current discovery task |
| GET | `/v1/hook/events` | Read the bounded metadata-only observation report |
| GET | `/v1/hook/config` | Read the active configuration snapshot |
| PUT | `/v1/hook/config` | Validate and save a declarative Hook configuration |
| PUT | `/v1/hook/enabled` | Enable or disable Hook callbacks |
| POST | `/v1/hook/discover` | Queue an explicit bounded DEX discovery request |
| GET | `/v1/hook/discovery/{requestId}` | Read discovery task and result |
| DELETE | `/v1/hook/discovery/{requestId}` | Request cancellation of a discovery task |

### Read status

```http
GET /v1/hook/status
Authorization: Bearer <client apiKey>
```

The `targetObserved` field means that a target-process observation/runtime snapshot has reached the provider. `activeHookCount` and `installedHooks` come from the target process's actual HookHandle registry, not from the configuration file or discovery candidates. Each listed entry is marked `state: installed` and includes its rule ID, exact method signature, sample interval and invocation count. `hookInventoryTruncated` indicates that more handles exist than are included in the bounded list. A missing runtime snapshot is reported as `runtimeState: unavailable`, not as a confirmed zero-Hook state.

### Update rules

```http
PUT /v1/hook/config
Authorization: Bearer <client apiKey>
Content-Type: application/json

{
  "schemaVersion": 1,
  "targetPackage": "com.openai.chatgpt",
  "rules": []
}
```

The request body is the configuration object itself, not a wrapper. A successful response means the configuration was validated and persisted. The target process polls configuration changes and applies them asynchronously; callers must inspect subsequent target-process observations before claiming runtime application.

### Enable or disable

```http
PUT /v1/hook/enabled
Authorization: Bearer <client apiKey>
Content-Type: application/json

{"enabled": false}
```

The response acknowledges persistence of the requested state. Target-process application is asynchronous.

### Discover classes and methods

```http
POST /v1/hook/discover
Authorization: Bearer <client apiKey>
Content-Type: application/json

{"prefix":"com.openai.auth","limit":100,"cursor":0}
```

The prefix and optional exact class must remain within `com.openai.*`. The request returns HTTP 202 and a request ID. Poll the corresponding discovery route until the task reaches a terminal state. Discovery is explicitly requested; it is not a full DEX scan on every app launch.

### Observation boundary

The current report contains bounded metadata such as rule ID, method signature, elapsed time, sequence, exception type and a bounded snapshot of actually installed Hook handles. It does not contain method arguments, return values, HTTP headers, cookies, access tokens, request/response bodies, or challenge/proof material.

## Current capability boundary

This control-plane increment exposes configuration, observation, a target-process installed-Hook inventory and bounded discovery through the local GPT API. It does not yet implement the legacy mutation actions `return`, `noop`, `args` or `throw`, nor does it claim an installed-hook inventory. Those require a separately validated rule schema, explicit authorization, type checks, reliable hook-handle lifecycle, rollback and target-device tests.
