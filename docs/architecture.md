# DSH-GPT Runtime Architecture

## Goal

Provide a DSH-native, inspectable implementation of a GPT-style assistant workflow without depending on private OpenAI internals.

## Layers

1. **Host runtime (DSH)** — owns the model provider, conversation/session lifecycle, tool scheduler, permissions, and plugin lifecycle.
2. **DSH-GPT plugin** — contributes a persona section, planning contract, and plugin-local observability tools.
3. **Model** — interprets the user's request, decides whether a registered tool is relevant, and synthesizes a response from tool results.
4. **External capabilities** — remain separate DSH tools/plugins and are invoked only through their registered interfaces and host policy.

## Request lifecycle

1. DSH's agent loop claims a queued user prompt and opens a turn in the session log.
2. DSH assembles the prompt from global and scoped system-prompt contributors.
3. The model receives the assembled prompt, conversation history, and available tool schemas.
4. The model may choose a registered tool and construct arguments.
5. DSH validates and dispatches the tool call through its guarded tool pipeline.
6. The tool's `execute` function runs.
7. `output.render` converts the canonical result into model-readable content.
8. DSH appends model-visible facts to the session log; the model may continue with another step or produce a final response.

This describes the public DSH architecture and plugin contract; it is not a claim to reproduce ChatGPT's private internal orchestration.

## Prompt/configuration model

- `promptProfile` is a user-owned, plugin-specific prompt template.
- The plugin registers it as the additive section `dsh-gpt:persona` through `ctx.systemPrompt.section()`.
- The section order is resolved relative to DSH's named `DEPLOYMENT_PERSONA_PREFIX` slot.
- The plugin does not register a `complete` section and therefore does not replace other host or plugin prompt sections.
- The plugin's prompt inspection tool returns only its own configured contribution.

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
- [ ] Add an optional dynamic context provider after confirming the exact context data contract and scope requirements.
- [ ] Add structured lifecycle events if the selected DSH release exposes stable event names and payload schemas.
- [ ] Add operator-facing diagnostics without collecting private conversation data.
