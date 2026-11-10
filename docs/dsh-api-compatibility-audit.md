# DSH Plugin API 兼容性审计

审计对象：`@dsh-external/dsh-gpt-runtime`  
审计分支：`feat/dsh-gpt-runtime-plugin`  
参考实现：`deepseek-ai/deepseek-harness` 当前 `master`，根包版本 `0.2.2-rc.1`。  
审计原则：以 DSH 官方 API 文档、类型定义和官方插件实现为准；静态测试通过不等于真实宿主验收。

## 结论摘要

| 项目 | 审计结果 | 说明 |
|---|---|---|
| Cordis 插件入口 | 符合 | `name`、`inject`、`Config`、`apply(ctx, config)` |
| 服务注入 | 符合目标 Base Profile | 使用 `tools`、`systemPrompt`、`skills`；由 DSH Base 提供 |
| System Prompt | 符合 | `ctx.systemPrompt.section()`、有限顺序 100、`interpolate: false` |
| Tool 注册 | 符合公开 DSL | `defineTool()`、参数 Schema、输出 Schema 和 renderer |
| Skill Provider | 已按契约修正 | URL locator、rank、invocation、provider/source、resourceBase、按需读取 |
| Skill 正文 | 已按契约修正 | 返回正文前移除 YAML frontmatter |
| 插件生命周期 | 符合 Cordis 作用域模型 | 注册由当前 Context 生命周期管理；任务状态和计数器在 apply 内创建 |
| Bundle 组合 | 已修正 | 只插入本插件，不重复插入 DSH Base 已有服务 |
| 多 Agent 编排 | 复用 DSH 原生能力 | 大型 subagent workflow 使用 `dsh-tool-workflow` / `ctx.workflowEngine` |
| 真实宿主验收 | 未完成 | 尚未在目标 DSH Profile 启动并执行端到端验收 |

## 逐项核对

### 1. Cordis Plugin / Service API

官方插件形式使用：
- `export const name`
- `export const inject`
- `export const Config`（Schemastery Schema）
- `export function apply(ctx, config)`

本项目使用的服务：
- `tools`：注册插件工具。
- `systemPrompt`：贡献插件自己的提示词段。
- `skills`：注册 Bundled Skill Provider。

这些服务在官方 `@deepseek-ai/dsh-base` 组合中提供。本 Bundle 的 patch 不再重复插入这些官方服务，避免同一服务包被重复加载。

### 2. System Prompt API

本项目使用：

```js
ctx.systemPrompt.section({
  name: 'dsh-gpt:persona',
  order: 100,
  text: promptText,
  interpolate: false,
})
```

DSH 官方契约要求 section 名称在作用域内唯一、order 为有限数字；外部贡献方可使用任意有限 order。当前顺序 100 位于 deployment persona prefix（0）之后、first-party plan policy（500）之前。注册随 Cordis Context 生命周期管理。

### 3. Tool API

工具通过 `defineTool()` 创建并交给 `ctx.tools.register()`：
- 参数采用 DSH `ParameterSchemaSpec`：每个字段声明类型，必填字段使用 `required: true`。
- 字符串枚举使用 `enum`，数组通过 `items` 描述元素类型。
- 必须提供 `output.schema` 与 `output.render`。
- `execute` 返回与输出 Schema 对应的规范值。

本项目所有工具以 JSON 字符串作为规范输出，并用 text renderer 转换为模型可见内容。工具注册 disposer 由 Cordis Context 生命周期管理。

### 4. SkillProvider API

官方 `SkillProvider` 契约：
- `name`：Provider 唯一名称。
- `list(options)`：返回 SkillCandidate 数组或完整性观察，不应读取正文。
- `get(candidate, options)`：按需返回 SkillDefinition 或 `undefined`。
- `ctx.skills.registerProvider(() => provider)`：同步注册，返回 disposer。

候选项按官方字段提供：
- `name`、`description`、`whenToUse`
- `invocation: { modelInvocable, userInvocable }`
- `provider`、`source: 'bundled'`、`rank: BUNDLED_SKILL_RANK`
- URL `locator` 与目录型 `resourceBase`

`get()` 读取文件时传递 `AbortSignal`，并通过 `stripSkillFrontmatter()` 移除 YAML frontmatter，只把 Markdown 指令正文放入 `SkillDefinition.content`。返回值不泄漏候选项的 `rank` 和 `locator`。

### 5. DSH Base 与 Bundle 组合

官方 Base 已提供：
- `@deepseek-ai/dsh-system-prompt`
- `@deepseek-ai/dsh-tools`
- `@deepseek-ai/dsh-skill`
- `@deepseek-ai/dsh-tool-skill`
- `@deepseek-ai/dsh-skill-filesystem`
- `@deepseek-ai/dsh-workflow-ptc`
- `@deepseek-ai/dsh-tool-workflow`

因此本项目的 `cordis.patch.yml` 只插入 `@dsh-external/dsh-gpt-runtime`。使用非 Base Profile 时，部署方必须自行挂载所需服务。

### 6. 原生 Workflow 与本项目 Task State 的边界

DSH 原生 `workflow` 工具通过 `ctx.workflowEngine` 执行模型编写的 JavaScript 编排脚本，并将工作扇出给 subagent。它负责真正的多 Agent 执行、并行组合、取消和运行生命周期。

本项目 `gpt_task_state_*` 只负责轻量任务阶段、证据、阻塞项和验收元数据，不执行 subagent，不替代 DSH Workflow Engine。简单任务不必创建状态记录；大型多 Agent 编排优先使用 DSH 原生 workflow 工具。

### 7. 版本范围

当前官方 Harness 根包版本为 `0.2.2-rc.1`，Node 最低版本为 `24.9.0`。本项目在 package manifest 中声明：
- Node：`>=24.9.0`
- DSH：`>=0.2.2-rc.1 <0.3.0`

升级 DSH 主版本或进入 0.3.x 前，应重新执行 API 审计和真实宿主集成验收。

## 验证记录

- CI 工作流：Node.js 24，执行 `npm test`。
- 当前静态与单元测试：11 passed / 0 failed。
- 测试覆盖：插件声明、工具名称与 Schema 限制、SkillProvider 静态契约、frontmatter 清理、七个 Skills 元数据、Bundle 服务去重、模式路由、任务状态转换和 ID 校验。
- 未覆盖：真实 Cordis Loader、真实 DSH Profile 启动、模型侧工具可见性、真实 skill 工具加载、真实 subagent workflow 执行和插件卸载后的宿主行为。

## 官方依据

- Plugin services: https://deepseek-harness.github.io/deepseek-harness/en/develop/framework/service
- Tool authoring: https://deepseek-harness.github.io/deepseek-harness/en/develop/basic/tool
- Skill subsystem: https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/skills.md
- Skill Provider source: https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/skill/skill/src/index.ts
- Official bundled provider: https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/experimental/skill-badge/src/index.ts
- System Prompt API: https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/core/system-prompt/README.md
- Workflow subsystem: https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/workflow.zh.md
- Native workflow tool: https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/workflow/tool-workflow/README.zh.md
- Base composition: https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/bundle/base/cordis.patch.yml
