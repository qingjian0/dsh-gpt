# DSH-GPT Runtime 架构

## 架构定位

采用 Plugin + Skills 混合方案，但不自建第二套 Agent Runtime。

| 层 | 职责 | 实现归属 |
|---|---|---|
| Host Runtime | Agent Loop、模型请求、Session、权限、工具调度 | DSH |
| DSH-GPT Plugin | 提示词段落、配置、Skill Provider、诊断工具 | 本仓库 |
| Skill Registry | Provider 合并、作用域、优先级、按需读取 | @deepseek-ai/dsh-skill |
| Skill Consumer | 会话技能目录、模型侧 skill 工具、显式命令 | @deepseek-ai/dsh-tool-skill |
| Local Skill Provider | 项目/用户目录发现和变更监控 | @deepseek-ai/dsh-skill-filesystem |
| Bundled Skills | 专业工作流程和检查清单 | 本仓库 skills/*/SKILL.md |
| Action Tools | 文件、网络、GitHub、终端等实际操作 | DSH 原生工具或其他插件 |

## 请求执行流程

1. DSH 接收用户消息并进入 Agent Loop。
2. DSH 组装全局与当前作用域的 System Prompt；本插件通过 ctx.systemPrompt.section() 贡献 dsh-gpt:persona。
3. DSH Skill Consumer 观察当前工作目录下的 Skill Registry，并把可用技能名称和简短描述加入会话上下文。
4. 模型根据用户目标和技能描述判断是否需要某项技能。
5. 模型调用官方 skill 工具，Registry 从胜出的 Provider 读取该技能的完整正文。
6. 模型按技能中的工作方法分析任务，并选择可用的 DSH 工具。
7. DSH 校验工具参数、权限和调用范围，再执行工具。
8. 工具结果返回模型；模型继续下一步、加载其他技能或形成最终答复。
9. 验收阶段通过独立工具/测试检查结果，向用户报告证据与未完成项。

## Bundled Skill Provider

src/skills-provider.js 实现官方 SkillProvider 契约：

- Provider 在插件 apply() 阶段同步注册到 ctx.skills。
- list() 只返回 SkillCandidate 元数据，不读取技能正文。
- get() 根据 Registry 选中的 candidate 读取对应 SKILL.md。
- 技能使用 kebab-case 名称，来源标记为 bundled，优先级使用 DSH 的 BUNDLED_SKILL_RANK。
- 读取遵循调用方 AbortSignal；Provider 的注册生命周期由 Cordis 管理。
- 正文中的相对资源以技能目录作为 resourceBase。

## Skills 目录规范

DSH 本地 Skill Provider 接受 <name>/SKILL.md 或顶层 <name>.md，要求 YAML frontmatter 中有 name 和 description。本仓库使用目录 Bundle 形式，每个技能只承担一个相对独立的工作方法。

## 提示词与职责边界

- dsh-gpt:persona 是本插件自己的附加提示词段落，不覆盖 DSH 或其他插件段落。
- 技能正文按需加载，不把全部技能全文塞入常驻系统提示词。
- Skill 是指令，不是工具，也不是授权。
- Plugin 不覆盖 DSH Agent Loop、不绕过权限，也不直接执行外部副作用。
- executionPolicy 只是模型行为指导；宿主权限、审批和工具策略始终有效。

## 可观测性边界

当前插件计数仅覆盖本插件注册的工具调用。它不是完整的模型请求追踪、宿主 Agent Loop 事件流或其他插件调用记录。后续若扩展 tracing，应优先消费 DSH 公开事件和日志接口，并避免采集凭据、完整私有负载或隐藏推理。

## 后续路线

- [ ] 真实 DSH Profile 集成测试与发布包验收。
- [ ] 增加 Skill 加载与命中统计（仅记录名称、时间和状态，不记录完整内容）。
- [ ] 接入 DSH 公开 Agent 生命周期事件，构建可观测执行 Trace。
- [ ] 基于 DSH 已有 Context/Session 能力完善上下文管理，不另建冲突状态源。
- [ ] 如需长期记忆，先评估 DSH 原生 Memory/World State 能力，再决定是否扩展。
