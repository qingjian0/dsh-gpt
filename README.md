# DSH GPT Diagnostics

An LSPosed module scaffold based on [Prslc/LSPosed_Template](https://github.com/Prslc/LSPosed_Template) and libxposed API 102.

## Current baseline

- Kotlin entry point: `com.gpt.hook.MainModule`
- Modern module metadata under `app/src/main/resources/META-INF/xposed/`
- Target scope: `com.openai.chatgpt`
- Centralized Logcat / LSPosed logger with tag `GPTHook`
- Java 21, Gradle 9.6.1, Android Gradle Plugin 9.3.0
- GitHub Actions builds a debug APK and uploads it as an artifact

## Build

Use JDK 21 and Gradle 9.6.1:

```bash
gradle assembleDebug --no-daemon
```

## Install and verify

1. Install the generated APK.
2. Enable DSH GPT Diagnostics in LSPosed.
3. Confirm `com.openai.chatgpt` is in the module scope.
4. Restart the target app.
5. Check Logcat for tag `GPTHook` and the module-loaded / package-ready messages.

## Safety and data handling

This baseline logs module lifecycle and non-sensitive runtime metadata only. It does not collect authorization headers, access tokens, device credentials, challenge tokens, request bodies, or anti-abuse proof material.

## API metadata

The template declares `minApiVersion=101` and `targetApiVersion=101`, while the compile-time dependency is `io.github.libxposed:api:102.0.0`. Keep these values aligned with the runtime framework compatibility requirements when changing the supported LSPosed release.
