# DSH-GPT Runtime 架构

## 架构定位

采用 Plugin + Task State Tracker + Skills 混合方案，不自建第二套模型 Agent Loop 或多 Agent Workflow Engine。DSH Host 仍负责模型调用、Session、权限和真实工具执行；本插件提供可配置的模式路由和任务阶段状态机。

| 层 | 职责 | 实现归属 |
|---|---|---|
| Host Runtime | Agent Loop、模型请求、Session、权限、工具调度 | DSH |
| DSH-GPT Plugin | 提示词段落、配置、工具注册、Skill Provider | 本仓库 |
| Mode Router | 9 种模式目录和自适应模式选择 | src/runtime-core.js |
| Task State Engine | 轻量阶段状态机、任务状态、证据和验收元数据 | src/runtime-core.js |
| Multi-Agent Workflow | 脚本化 subagent 编排与运行生命周期 | DSH 原生 ctx.workflowEngine / dsh-tool-workflow |
| Skill Registry | Provider 合并、作用域、优先级、按需读取 | @deepseek-ai/dsh-skill |
| Skill Consumer | 会话技能目录、模型侧 skill 工具、显式命令 | @deepseek-ai/dsh-tool-skill |
| Local Skill Provider | 项目/用户目录发现和变更监控 | @deepseek-ai/dsh-skill-filesystem |
| Bundled Skills | 专业工作流程和检查清单 | 本仓库 skills/*/SKILL.md |
| Action Tools | 文件、网络、GitHub、终端等实际操作 | DSH 原生工具或其他插件 |

## 模式目录

| ID | 目标 |
|---|---|
| adaptive | 根据任务描述和复杂度自动路由 |
| fast | 简单任务的最短可靠路径，不跳过必要核验 |
| deep-research | 多来源检索、证据分级和交叉验证 |
| engineering | 仓库审查、最小实现、测试和交付 |
| batch | 依赖分组、批次执行和结果汇总 |
| strict | 独立验证、风险检查和证据优先 |
| planning | 目标、约束、依赖、风险、里程碑和验收 |
| recovery | 故障分类、根因定位、最小修复和回归 |
| parallel | 仅并行无依赖且互不冲突的任务 |

自适应路由是启发式初选，不是模型能力保证。用户显式指定的模式优先于自动路由。执行模式仅指导工作策略，不授予额外工具权限。

## 任务状态机

```text
understand -> inspect -> plan -> execute -> verify -> deliver
                                      ^          |
                                      |__________|
                                    verify发现问题
```

- understand：确认目标、输入、边界和验收条件。
- inspect：检查上下文、依赖、工具和权限，标记未知项。
- plan：形成步骤、依赖、风险和回滚策略。
- execute：调用 DSH 已授权工具执行，记录实际结果。
- verify：独立检查产物、测试、外部状态和验收标准。
- deliver：交付结果、证据、未完成项和风险。

只有 deliver 阶段允许以 completed 结束。blocked、failed、cancelled 可在任何活动阶段明确结束。状态机允许 verify 回退到 execute 修正问题，不允许跳过阶段。

## 任务状态工具契约

- `gpt_mode_list`：返回模式目录。
- `gpt_task_state_start`：以唯一 ID 创建任务状态记录；mode 可指定模式，adaptive 自动路由。
- `gpt_task_state_status`：查询任务状态或列表。
- `gpt_task_state_advance`：执行合法阶段转换。
- `gpt_task_state_update`：追加决策摘要、证据、阻塞项和验收标准。
- `gpt_task_state_finish`：按规则结束任务状态记录。
- `gpt_task_plan`：生成计划，不触发外部副作用。

决策摘要只保留结论、理由要点和证据引用，不记录隐藏思维链或逐字内心独白。

## 请求执行流程

1. DSH 接收用户消息并进入 Agent Loop。
2. DSH 组装全局与当前作用域的 System Prompt；本插件通过 `ctx.systemPrompt.section()` 以外部扩展顺序 100 贡献 dsh-gpt:persona（persona prefix 为 0，first-party plan policy 为 500）。
3. 对复杂任务，模型使用 gpt_mode_list 选择工作模式，并调用 gpt_task_state_start 建立任务阶段记录。
4. 模型按 understand → inspect → plan → execute → verify → deliver 推进；必要时从 verify 回到 execute。
5. DSH Skill Consumer 暴露技能目录；模型按需调用官方 skill 工具加载完整正文。
6. 需要大规模 subagent 编排时，使用 DSH 原生 workflow 工具；本插件任务状态工具只记录阶段、证据和验收元数据。
7. 模型按技能工作方法选择 DSH 工具；DSH 校验参数、权限和调用范围，再执行工具。
8. 模型观察工具结果、追加证据和状态，必要时修正。
9. 独立验收后进入 deliver，报告证据、未完成项和风险。

简单问答无需强制建立任务状态记录。

## Bundled Skill Provider

src/skills-provider.js 实现官方 SkillProvider 契约：
- Provider 在插件 apply() 阶段通过 `ctx.skills.registerProvider(() => provider)` 同步注册；注册生命周期归当前 Cordis Context 管理。
- list() 返回完整 SkillCandidate：kebab-case name、description、whenToUse、invocation、provider、source、rank、URL locator 和目录 resourceBase；不读取正文。
- get() 校验候选项归属，按需读取 URL locator，遵循调用方 AbortSignal。
- get() 必须返回 SkillDefinition 的纯正文 content；本项目使用 stripSkillFrontmatter() 移除 YAML frontmatter。
- 不把 candidate 的 rank/locator 泄漏到 SkillDefinition；返回 invocation、provider、source 和 resourceBase。
- 正文中的相对资源以技能目录作为 resourceBase。

## 提示词与职责边界

- dsh-gpt:persona 是本插件自己的附加提示词段落，不覆盖 DSH 或其他插件段落。
- 技能正文按需加载，不把全部技能全文塞入常驻系统提示词。
- Skill 是指令，不是工具，也不是授权。
- Task State Engine 只管理任务阶段，不取代 DSH Agent Loop、原生 workflowEngine 或真实工具调度。
- executionPolicy 只是模型行为指导；宿主权限、审批和工具策略始终有效。
- 不提取或复制其他产品的私有系统提示词、隐藏指令或隐藏推理。

## 状态与可观测性边界

- 任务状态存储与工具计数器均在每个 apply(ctx, config) 调用内部创建，避免不同 Cordis scope 共享状态；仍只保存在插件进程内存，插件或 DSH 重启后不持久化。
- 插件计数仅覆盖本插件注册的工具调用，不是完整模型请求或宿主 Agent Loop Trace。
- 后续持久化应优先接入 DSH Session/Memory/State 公开接口，避免创建冲突状态源。
- 追踪应只记录工具名、时间、状态、阶段和证据引用，不记录凭据、完整私有负载或隐藏推理。

## 后续集成验收

- [ ] 真实 DSH Profile 插件加载/卸载。
- [ ] 七个 bundled Skills 被发现且正文按需读取，返回内容不含 YAML frontmatter。
- [ ] Provider candidate 使用有效 URL locator 和 directory resourceBase，get() 返回完整 SkillDefinition。
- [ ] 两个独立 Cordis Context 的工作流与工具计数器互相隔离。
- [ ] gpt_mode_list 和全部任务状态工具在模型侧可见。
- [ ] 模式选择与状态机在真实任务中按预期工作。
- [ ] 宿主工具权限、审批和失败处理不被绕过。
- [ ] 重启后的状态行为符合文档（当前为内存态）。
- [ ] 打包文件清单和发布包内容完整。
