# DSH Hook Agent Extension

This is the DSH-side Cordis profile plugin for the standalone `qingjian0/dsh-gpt`
Android APK / LSPosed module. It does not contain Hook runtime code and is not
part of the LSPosed APK.

## Connection configuration

The plugin reads one app-private file from the DSH engine home:

`$DSH_HOME/config/dsh-gpt-hook.json`

```json
{
  "baseUrl": "http://127.0.0.1:8766",
  "apiKey": "<the local API key shown by dsh-gpt>"
}
```

The default API port is 8766, but the configured port may differ. The URL is
restricted to plain HTTP loopback (127.0.0.1, localhost or ::1); remote hosts,
credentials embedded in URLs, query strings and fragments are rejected. Every
request uses `Authorization: Bearer <apiKey>`. Missing or invalid config fails
closed and does not register any fallback unauthenticated bridge.

The API key must remain in DSH app-private storage. Do not place it in AGENTS.md,
prompts, tool output, logs, exports, or ChatGPT target-process data.

## Tools

- `dsh_hook_status`: inspect runtime state and the target process's live HookHandle inventory.
- `dsh_hook_events`: read bounded metadata-only observations.
- `dsh_hook_rules_get` / `dsh_hook_rules_update`: inspect and replace declarative rule snapshots.
- `dsh_hook_discover`: request bounded metadata-only class/method discovery.
- `dsh_hook_discovery_status` / `dsh_hook_discovery_cancel`: inspect or cancel discovery.
- `dsh_hook_enable`: enable or disable the LSPosed Hook runtime.

The plugin enablement and LSPosed runtime enablement are separate controls.
Disabling/removing this DSH plugin does not uninstall, disable, or grant
permissions to the LSPosed module. Enabling this DSH plugin never silently
enables Hook callbacks.

## API contract

| Method | Path |
|---|---|
| GET | `/v1/hook/status` |
| GET | `/v1/hook/events` |
| GET | `/v1/hook/config` |
| PUT | `/v1/hook/config` |
| PUT | `/v1/hook/enabled` |
| POST | `/v1/hook/discover` |
| POST | `/v1/hook/inspect` (exact class inspection) |
| GET | `/v1/hook/discovery/{requestId}` |
| DELETE | `/v1/hook/discovery/{requestId}` |

## Security and data boundary

- No generated code is executed in the Android target process.
- Discovery is bounded and restricted by dsh-gpt to `com.openai.*`.
- Observation is metadata-only; no method arguments, return values, HTTP headers,
  cookies, tokens, request/response bodies, device identifiers or anti-abuse proof.
- Rule changes are declarative and must be verified against live HookHandle
  inventory; a stored config revision is not proof of successful application.
- The adapter accepts only loopback API endpoints and never forwards the API key
  into model prompts or tool results.

## Validation

Run `node --check integration/dsh-agent-hook-extension/index.mjs` and validate
the npm package metadata. End-to-end acceptance additionally requires the DSH
profile plugin, app-private config writer, local API authentication, LSPosed
Provider IPC and a real target-process Hook test. Do not mark the integration
complete until those layers are tested together.
