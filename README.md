# DSH-GPT Runtime

一个面向 DeepSeek Harness（DSH）的 GPT 风格工作流插件。基于 DSH 官方 Cordis 插件机制实现，提供可配置的人设提示词、运行配置、任务规划和插件自身的调用统计。

> 本项目不提取或复刻 OpenAI 未公开的系统指令、隐藏提示词、私有配置、凭据或隐藏推理过程。项目实现的是独立编写、用户可配置、可审计的功能方案。

## 当前功能

- **系统提示词扩展**：通过 `ctx.systemPrompt.section()` 将插件自己的行为规范作为独立段落注入 DSH 系统提示词组装。
- **运行配置**：可配置语言、回答详细度、执行策略提示和自定义行为指令。
- **运行状态**：查看插件配置、运行时长、插件自身工具调用次数、失败次数和最近调用信息。
- **提示词查看**：读取本插件实际注册的提示词段落，不读取宿主或其他插件的提示词。
- **任务规划**：根据目标和约束生成可审查的六阶段执行大纲。

## 功能边界

- 插件只注册自己的 `dsh-gpt:persona` 段落，不替换或覆盖宿主完整系统提示词。
- 运行统计只覆盖本插件注册的工具，不代表 DSH、模型供应商或其他插件的完整调用链路。
- 任务规划工具只生成计划，不会执行 Shell、网络、文件、GitHub 或其他外部操作。
- `executionPolicy` 是传递给模型的行为提示，不代替 DSH 宿主权限、审批和安全策略。
- 不收集密钥、Cookie、会话标识、隐藏提示词或隐藏思维链。

## DSH 插件规范

按官方 Cordis 插件结构实现：

- `name`
- `inject = ['tools', 'systemPrompt']`
- `apply(ctx, config)`
- `ctx.systemPrompt.section(...)`
- `ctx.tools.register(defineTool(...))`
- `package.json#dsh.bundle.patch`

提示词段落使用 DSH 的 `DEPLOYMENT_PERSONA_PREFIX` 命名位置确定顺序，插入在部署人设前缀之后。目标 DSH 版本和 Profile 组合仍需在实际宿主中验证。

## 安装与开发

1. 使用目标 DSH 版本所支持的 Node.js 运行环境。
2. 按目标 Profile 的常规插件流程安装本包。
3. 将 Bundle 加入指定 Profile 的组合配置。
4. 启动 Web UI 前先检查组合后的配置。
5. 启动 DSH，在对话中调用：`gpt_runtime_status`、`gpt_prompt_profile`、`gpt_task_plan`。

本地开发可参考 DSH 官方 scratch-plugin 与 `--patch` 工作流。仓库内的 `cordis.patch.yml` 是示例；实际使用时可能需要按所选 DSH 版本调整插入目标。

## 配置

查看 `cordis.patch.yml`。插件通过 Schemastery Schema 校验配置并提供默认值。

| 配置项 | 作用 |
|---|---|
| `enabled` | 是否启用插件 |
| `profileName` | 配置档名称 |
| `language` | 默认输出语言 |
| `verbosity` | 回答详细程度：简洁、平衡、详细 |
| `executionPolicy` | 执行策略提示：辅助或敏感操作前确认 |
| `promptProfile` | 用户自定义行为指令，实际注入插件提示词段落 |

## 测试

运行：

```sh
npm test
```

当前包含静态插件契约测试。正式发布前仍需在真实 DSH 宿主中完成插件加载、卸载、提示词组装和工具调用集成测试。

## 许可证

GPL-3.0-only
