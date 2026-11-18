# DSH Hook Agent Extension

This directory contains the DSH-side Agent tools for the `dsh-gpt` LSPosed module.
It is intentionally separate from the Android module APK: DSH loads this as a Cordis
plugin, and the DSH host bridge talks to the module's ContentProvider.

## Agent tools

- `dsh_hook_status`: module connection, target process and applied-rule status.
- `dsh_hook_events`: bounded metadata-only events, optionally incremental by sequence.
- `dsh_hook_rules_get`: current declarative rules.
- `dsh_hook_rules_update`: validate and apply a complete declarative rules snapshot.
- `dsh_hook_discover`: inspect available target classes/method signatures.
- `dsh_hook_enable`: enable or disable the Hook runtime without uninstalling the module.

The Agent can call these tools repeatedly; there is no clipboard/paste step. The plugin
does not contain an AI API key and does not send data to a third-party service.

## DSH runtime compatibility

The plugin follows the bundled `android-plugins/device-tools.mjs` convention:
it resolves `defineTool` from the active DSH engine layout with
`fileURLToPath(import.meta.url)`, registers tools in async `apply(ctx)`, declares an
object output schema, and returns structured objects rather than JSON-encoded strings.
The bridge client reads `DSH_BRIDGE_TOKEN` only inside the DSH engine process.

## Transport contract

The plugin calls the authenticated DSH loopback bridge at `127.0.0.1:3083`.
That token stays inside the DSH host/engine and is never copied into the ChatGPT
target process.

The following host bridge routes are the intended contract for
`Deepseek-Harness-Local-Android`:

| Method | Route | Purpose |
|---|---|---|
| GET | `/hook/status` | Module/target connection and runtime state |
| GET | `/hook/events?since=N&limit=N` | Incremental event retrieval |
| GET | `/hook/rules` | Read current rules |
| POST | `/hook/rules` | Validate and atomically apply rules |
| POST | `/hook/discover` | Discover class/method metadata |
| POST | `/hook/control` | Enable/disable runtime |

The bridge adapter must call `contentResolver.call()` on
`content://com.gpt.hook.observations`; it must not expose the provider directly to
the engine or pass `DSH_BRIDGE_TOKEN` through to the target process. The provider
must authenticate the DSH host independently (exact package UID plus an explicit
pairing credential, or a verified signing identity) before granting host operations.
Module-app-only read access must not be weakened to arbitrary callers.

## Rules and observations

Rules remain declarative JSON validated by `HookConfigParser`. No arbitrary code,
method arguments, return values, HTTP headers, cookies, request/response bodies,
credentials, or anti-abuse challenge material are accepted or recorded. Events
remain bounded metadata (rule ID, class, method, signature, elapsed time and
exception type). Discovery returns class/method/signature metadata only.

The module validates its own package scope, rule schema and limits. The Agent can
iterate over valid rules and invoke discovery repeatedly; invalid or unsupported
requests must return explicit errors.

## Integration status

- [x] Agent tool definitions and authenticated loopback client, aligned with DSH's
      native plugin loader and structured output convention.
- [ ] DSH host bridge routes and provider adapter.
- [ ] Provider host-caller authentication / pairing.
- [ ] Device validation with the configured LSPosed target process.

The HTTP routes above are a contract, not a claim that the host-side endpoints are
already implemented. The plugin is not end-to-end functional until those items are
completed and device-tested.
