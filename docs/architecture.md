# DSH-GPT Runtime Architecture

## Goal

Provide a DSH-native, inspectable implementation of a GPT-style assistant workflow without depending on private OpenAI internals.

## Layers

1. **Host runtime (DSH)** — owns the model provider, conversation/session lifecycle, tool scheduler, permissions, and plugin lifecycle.
2. **DSH-GPT plugin** — contributes its own prompt profile, planning contract, and plugin-local observability tools.
3. **Model** — interprets the user's request, decides whether a registered tool is relevant, and synthesizes a response from tool results.
4. **External capabilities** — remain separate DSH tools/plugins and are invoked only through their registered interfaces and host policy.

## Request lifecycle (documented, observable abstraction)

1. Receive user input through the DSH conversation.
2. The model interprets the request and available tool schemas.
3. The model may choose a registered tool and construct arguments.
4. DSH validates and dispatches the tool call.
5. The tool's `execute` function runs.
6. `output.render` converts the canonical result into model-readable content.
7. The model uses the result to continue or produce a final response.

This describes the public DSH tool contract; it is not a claim to reproduce ChatGPT's private internal orchestration.

## Prompt/configuration model

- `promptProfile` is a user-owned, plugin-specific prompt template.
- `profileName`, `language`, `verbosity`, and `executionPolicy` are validated configuration.
- The current scaffold exposes the template through `gpt_prompt_profile`.
- It does not mutate or replace DSH's host system prompt. A future prompt integration must use a documented, version-compatible DSH prompt extension API and must be tested against the actual host.

## Observability

The scaffold records only:
- plugin-local tool call count;
- plugin-local failure count;
- last tool name;
- last tool call timestamp;
- plugin instance start time.

It deliberately excludes:
- hidden model reasoning;
- provider-private request/response payloads;
- credentials, cookies, and session identifiers;
- prompts or instructions not explicitly supplied to this plugin.

## Security

- Keep host permission and policy enforcement authoritative.
- Do not add shell/network/file access implicitly.
- Do not log secrets or full conversation content.
- Treat user-configured prompt text as untrusted input, not as a way to override host policy.
- Require explicit authorization for irreversible or sensitive actions.

## Roadmap

- [ ] Verify manifest and patch composition against the target DSH release.
- [ ] Add real-host load/unload integration tests.
- [ ] Add a documented prompt extension adapter only after confirming the supported DSH API.
- [ ] Add structured lifecycle events if the selected DSH release exposes stable event names and payload schemas.
- [ ] Add operator-facing diagnostics without collecting private conversation data.
