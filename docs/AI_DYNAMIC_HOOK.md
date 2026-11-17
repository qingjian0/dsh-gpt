# DSH AI Dynamic Hook Runtime (MVP)

## On-device control loop

1. Open the DSH module app and connect to the LSPosed service.
2. Edit or paste an AI-generated declarative JSON configuration in the on-device console.
3. The console validates the schema and writes `hook_config` to LSPosed remote preferences.
4. The scoped ChatGPT process reads the remote-preference change and independently validates and applies the rules.
5. The target process records bounded, metadata-only observations in its own in-memory buffer.

**Current boundary:** configuration delivery is implemented. Observation delivery from the target process back to the module app is not implemented yet. libxposed API documentation explicitly describes remote preferences as read-only in hooked apps, so the target process cannot use that channel to publish observations. The current build does not claim a complete observe → report → AI revise → reapply loop, and it performs no hidden network upload.

## Configuration schema

```json
{
  "schemaVersion": 1,
  "targetPackage": "com.openai.chatgpt",
  "rules": [
    {
      "id": "sample-method",
      "className": "com.openai.example.Sample",
      "methodName": "run",
      "parameterTypes": ["java.lang.String"],
      "enabled": true,
      "sampleEvery": 1
    }
  ]
}
```

An empty `parameterTypes` array matches overloads of the named method (at most eight). Prefer explicit parameter types.

## Runtime behavior

- Config changes are validated in the module app and validated again inside the target process.
- Hook IDs are deterministic per rule and method signature; overload enumeration is sorted before application.
- A failure to hook one method is isolated and does not stop other methods or rules from being processed.
- Existing hooks are retained for keys still requested when an attempted replacement fails; hooks absent from a valid new snapshot are unhooked.
- At most 64 rules, 8 overloads per rule, 128 KiB config, and sampling 1..10000.
- Only target classes in the `com.openai.*` namespace are accepted.
- Hook failures are isolated per method and reported using the `GPTHook` tag.

## Observation and privacy

The MVP records method signature, elapsed time, invocation sequence, rule ID and exception class only. It never reads or serializes method arguments, return values, HTTP headers, cookies, authorization data, request/response bodies, device identifiers or challenge/proof material. It does not implement anti-abuse challenge solving or bypass.

The event buffer is bounded and process-local. There is currently no target-to-module-app observation transport or in-app report viewer. A future transport must be explicitly implemented and validated with caller identity/package checks, bounded payloads, and no sensitive data collection before the app can display or export target-process reports.

## AI integration status

The on-device console is the control plane for AI-generated rules. `AiResearchBridge` defines local JSON import/export validation. A hosted AI endpoint and report delivery channel are not bundled; they require an explicitly selected provider and user-configured credentials. This keeps credentials out of the hooked app and prevents silent data uploads.

## Build and validation

CI builds the debug APK, verifies LSPosed module metadata, and uploads an artifact. CI success does not establish runtime correctness on a device. Device verification must still confirm the installed LSPosed version, service connection, module scope, remote-preference updates and actual hook callbacks. Observation return transport remains a known incomplete item.
