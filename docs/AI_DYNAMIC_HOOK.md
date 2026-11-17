# DSH AI Dynamic Hook Runtime (MVP)

## On-device loop

1. Open the DSH module app and connect to the LSPosed service.
2. Edit or paste an AI-generated declarative JSON configuration in the on-device console.
3. The console validates the schema and writes `hook_config` to LSPosed remote preferences.
4. The scoped ChatGPT process receives the remote-preference change event and applies the rules without recompiling or restarting the target app.
5. HookRuntime records bounded metadata-only observations. Export/AI transport is an explicit next integration; no hidden network client is included.

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
- Hook IDs enable atomic replacement on the same executable; removed rules are unhooked.
- At most 64 rules, 8 overloads per rule, 128 KiB config, and sampling 1..10000.
- Only target classes in the `com.openai.*` namespace are accepted.
- Hook failures are isolated per rule and reported using the `GPTHook` tag.

## Observation and privacy

The MVP records method signature, elapsed time, invocation sequence, rule ID and exception class only. It never reads or serializes method arguments, return values, HTTP headers, cookies, authorization data, request/response bodies, device identifiers or challenge/proof material. It does not implement anti-abuse challenge solving or bypass.

## AI integration status

The on-device console is the control plane for AI-generated rules. `AiResearchBridge` defines local JSON import/export validation. A hosted AI endpoint and report delivery channel are not bundled; they require an explicitly selected provider and user-configured credentials. This keeps credentials out of the hooked app and prevents silent data uploads.

## Build and validation

CI builds the debug APK and uploads an artifact. Device verification must still confirm the installed LSPosed version, service connection, module scope, remote-preference updates and actual hook callbacks.
