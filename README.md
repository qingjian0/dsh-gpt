# DSH-GPT Runtime

A configurable GPT-style workflow plugin for DeepSeek Harness (DSH).

> This project does not extract or reproduce OpenAI's private system instructions, hidden prompts, internal configuration, credentials, or hidden reasoning. It implements an independently authored, inspectable workflow profile using DSH's public plugin APIs.

## What it provides

- **System-prompt contribution** — registers the plugin-owned persona as an additive section through `ctx.systemPrompt.section()`.
- **Runtime status** — plugin version/configuration and plugin-local tool-call counters.
- **Prompt profile** — reads the exact prompt template owned and registered by this plugin; it does not inspect other prompt contributors.
- **Task planning** — produces a reviewable plan with validation and delivery stages.
- **Execution policy declaration** — makes the configured action policy visible to the model and operator.

## Important scope boundaries

- The plugin contributes only its own named prompt section, `dsh-gpt:persona`; it does not replace the complete host system prompt.
- Runtime counters cover only tools registered by this plugin instance. They are not a complete trace of DSH, the model provider, or other plugins.
- The planner creates a plan; it does not execute shell, network, file, GitHub, or other external actions.
- Sensitive actions must be authorized through the host's normal permission and policy mechanisms.
- No secrets, API keys, cookies, session identifiers, private prompts, or hidden chain-of-thought are collected.

## DSH compatibility

The plugin follows the documented Cordis module shape:

- `name`
- `inject = ['tools', 'systemPrompt']`
- `apply(ctx, config)`
- `ctx.systemPrompt.section(...)`
- `ctx.tools.register(defineTool(...))`
- `package.json#dsh.bundle.patch`

The prompt section is ordered immediately after the deployment persona prefix using DSH's named order API. The exact DSH runtime version and profile composition must still be verified in the target installation.

## Install / develop

1. Use a supported DSH installation and Node.js runtime as required by that DSH release.
2. Install this package into the target DSH profile using the profile's normal plugin workflow.
3. Add the bundle to the intended profile composition.
4. Inspect the composed configuration before starting the Web UI.
5. Start DSH and call `gpt_runtime_status`, `gpt_prompt_profile`, and `gpt_task_plan` from a conversation.

For local development, use the DSH repository's documented scratch-plugin and `--patch` workflow. The included `cordis.patch.yml` is an example and may need its insertion target adjusted for the selected DSH version.

## Configuration

See `cordis.patch.yml`. All deployment-specific behavior is configurable through the plugin's Schemastery schema.

## Tests

Run:

```sh
npm test
```

The initial scaffold includes static contract checks. Integration tests against a real DSH host are required before calling the plugin production-ready.

## License

GPL-3.0-only
