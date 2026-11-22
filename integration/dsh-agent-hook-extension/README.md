# DSH Hook Agent Extension

This package is the DSH-side tool adapter for `dsh-gpt`. It is deliberately outside
the Android Gradle project and is never packaged into the LSPosed APK.

## Tools

- `dsh_hook_status`: inspect bridge/runtime status.
- `dsh_hook_events`: read bounded metadata-only observations.
- `dsh_hook_rules_get` / `dsh_hook_rules_update`: inspect and replace declarative rule snapshots.
- `dsh_hook_discover`: request paginated class/method/constructor/field metadata discovery.
- `dsh_hook_discover_status` / `dsh_hook_discover_cancel`: inspect or cancel a leased discovery task.
- `dsh_hook_enable`: enable or disable the runtime.

The plugin reads `DSH_BRIDGE_TOKEN` only in the DSH engine process and calls the
loopback bridge at `127.0.0.1:3083`. It does not contain model weights, AI API keys,
or a model runtime.

## Security and data boundary

- No arbitrary generated code is executed in the Android target process.
- Discovery is metadata-only; it does not invoke app methods or read field values.
- Observation events must not contain method arguments, return values, HTTP headers,
  cookies, request/response bodies, credentials, or anti-abuse challenge material.
- The bridge token must never be forwarded to the ChatGPT process or the Provider.
- Provider access must be authenticated by the host bridge and the Android Binder
  caller identity; package-name-only checks are not equivalent to certificate pinning.

## Bridge contract

The plugin expects an authenticated local DSH bridge exposing:

| Method | Route |
|---|---|
| GET | `/hook/status` |
| GET | `/hook/events?since=N&limit=N` |
| GET | `/hook/rules` |
| POST | `/hook/rules` |
| POST | `/hook/discover` |
| GET | `/hook/discover?requestId=...` |
| POST | `/hook/discover/cancel` |
| POST | `/hook/control` |

**Integration status:** the plugin client and tool definitions are present in this
repository. The corresponding host bridge routes in
`Deepseek-Harness-Local-Android` have not yet passed source-level end-to-end
verification in this delivery branch. Do not mark the system end-to-end complete
until the host adapter, Provider IPC, target process, and real device are tested
together.

## Validation

Run `node --check integration/dsh-agent-hook-extension/index.mjs` and validate
the package metadata. Android build and Agent-plugin validation run as separate
CI workflows so external Node code does not increase APK size.
