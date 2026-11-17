# DSH AI Dynamic Hook Runtime (MVP)

## Loop

1. The scoped app process loads the module.
2. The runtime reads `dsh-hook-rules.json` from the framework-managed module shared-files area.
3. The config parser validates schema, package, rule count, class namespace, method names and sampling limits.
4. HookRuntime resolves exact methods with the target app classloader and installs libxposed interceptors.
5. EventBuffer stores bounded metadata-only observations.
6. AiResearchBridge exports observations as JSON and validates AI-proposed declarative configs.
7. A revised config can be supplied for the next application process / supported hot-reload cycle.

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

## Observation and privacy

The MVP records method signature, elapsed time, invocation sequence, rule ID and exception class only. It never reads or serializes method arguments, return values, HTTP headers, cookies, authorization data, request/response bodies, device identifiers or challenge/proof material. It does not implement anti-abuse challenge solving or bypass.

## Operational limits

- Only classes in the `com.openai.*` namespace are accepted by the config validator.
- At most 64 rules, 8 overloads per rule, and a 128 KiB config.
- Sampling is bounded to 1..10000.
- Hook failures are isolated per rule; inspect the `GPTHook` tag.
- Remote files are read-only in the hooked process. Populate the framework-managed module shared file through the supported module/framework management workflow; this repository does not assume a device-specific filesystem path.
- The AI bridge is a local import/export contract, not a bundled hosted model client. Connect an explicitly configured AI endpoint in a later integration.

## Build and validation

CI runs the debug APK build. Device verification must still confirm the installed LSPosed version, module scope, remote-file capability and actual hook callbacks.
