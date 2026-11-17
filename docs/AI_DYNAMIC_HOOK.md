# DSH AI Dynamic Hook Workbench

## Delivered control and observation loop

1. The module app validates declarative JSON rules and writes `hook_config` through libxposed remote preferences.
2. The scoped target process independently validates the configuration and applies/removes hooks on preference changes.
3. Hook callbacks record only bounded method metadata: rule ID, class/method/signature, elapsed time, sequence and exception class.
4. A debounced publisher sends a bounded JSON snapshot to the module app's exported ContentProvider.
5. The provider accepts writes only when Binder caller UID maps to `com.openai.chatgpt`; reads and clears are restricted to the module app UID.
6. The console can refresh, copy, or share the sanitized report to an explicitly selected AI assistant.
7. Paste an AI-suggested configuration into the editor; it is validated again before dispatch. This completes a user-mediated analysis → revise → apply loop without a hidden hosted AI client.

## Observation IPC security

- Authority: `com.gpt.hook.observations`.
- Target-to-module transport uses Android ContentProvider `call()`; remote preferences are not used for reverse transport.
- The provider is exported solely because the target app has a different UID. Every publish verifies the Binder caller UID's package set contains the exact configured target package. Read/clear require the module app UID.
- The provider accepts only the expected report schema and at most 128 KiB per report.
- The in-process buffer holds at most 100 events. Publication is debounced to avoid Binder calls on every hooked invocation.
- The provider exposes no query/insert/update/delete data surface.
- The target process captures its own application Context during `Instrumentation.callApplicationOnCreate(Application)` and calls the exported provider directly; Binder therefore carries the target app UID. This bootstrap and caller-UID behavior must still be verified on-device.

## Configuration schema

```json
{
  "schemaVersion": 1,
  "targetPackage": "com.openai.chatgpt",
  "rules": [{
    "id": "sample-method",
    "className": "com.openai.example.Sample",
    "methodName": "run",
    "parameterTypes": ["java.lang.String"],
    "enabled": true,
    "sampleEvery": 1
  }]
}
```

At most 64 rules, 8 overloads per rule, 128 KiB config, and sampleEvery 1..10000. Classes must be in `com.openai.*`; prefer explicit parameter types.

## AI integration boundary

The app does not embed an AI API key or silently upload reports. "Share to AI" invokes Android's chooser with the sanitized JSON; the user selects the assistant/provider. AI-generated rules must be pasted back and pass local validation before application. A fully automated hosted AI client requires provider selection, credentials, privacy disclosure and network implementation, and is intentionally not claimed by this build.

## Privacy boundary

Never collect or serialize method arguments, return values, HTTP headers, cookies, authorization data, request/response bodies, device identifiers, anti-abuse challenge/proof material or credentials. This project does not solve or bypass anti-abuse challenges or access controls.

## Build and validation

CI runs `assembleDebug`, verifies the LSPosed metadata entries and uploads the APK artifact. CI success proves compilation and packaging only. It does not prove LSPosed injection, IPC caller identity behavior, target app hook callbacks or Android-version compatibility on a physical device. Those require installation and device testing.
