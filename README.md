# DSH-GPT Runtime

DSH-GPT 是一个以 Plugin 为运行骨架、Skills 为按需加载工作方法的 DeepSeek Harness 扩展。除七个专业 Skills 外，插件还提供显式模式注册、任务模式路由和可追踪工作流状态机。

## 设计原则

- **Plugin = Runtime Extension**：提示词段落、配置、模式路由、工作流工具、Skill Provider 和诊断。
- **Workflow = Execution Control**：以明确状态推进任务，管理阶段、决策摘要、证据、阻塞项和验收标准。
- **Skills = Instructions**：专业工作流程、判断规则、检查清单和交付标准。
- **DSH = Host**：Agent Loop、模型调用、会话、工具调度、权限和审批。
- **Tools = Actions**：实际读写文件、访问网络、操作 GitHub 等能力由 DSH 原生工具或其他插件提供。

不复制或替换 DSH Agent Loop，不绕过宿主权限。工作流状态机管理任务阶段，但实际工具执行仍由 DSH Host 完成。

## 当前功能

### Runtime / Modes
- 9 种工作模式：自适应、极速、深度研究、工程交付、批量执行、严格验证、自动规划、故障恢复、多任务并行。
- 自适应路由按任务描述和复杂度选择专业模式；用户可在启动工作流时显式指定模式。
- 工作流状态机：understand → inspect → plan → execute → verify → deliver；验证发现问题时允许 verify → execute 回退。
- 严格完成门槛：只有进入 deliver 阶段才能标记 completed。
- 结构化记录决策摘要、证据、阻塞项和验收标准；不收集隐藏思维链。
- 当前工作流状态驻留插件进程内存，重启不持久化；这不是跨重启任务队列。

### Plugin
- 通过 ctx.systemPrompt.section() 注入独立的 dsh-gpt:persona 提示词段落。
- 支持中文、回答详细度、默认模式、执行策略提示和用户自定义行为指令。
- 通过 ctx.skills.registerProvider() 注册随插件打包的技能库。
- 提供运行状态、提示词配置、模式目录、任务规划和工作流管理工具。
- 只统计本插件工具调用；不采集宿主私有提示词、密钥、Cookie 或隐藏推理。

### Bundled Skills

| Skill | 用途 |
|---|---|
| task-planning | 目标拆解、依赖、风险、验收 |
| tool-orchestration | 工具发现、串并行、权限、结果归并 |
| code-engineering | 仓库理解、编码、测试、Git 纪律 |
| research | 多来源研究、证据分级、交叉验证 |
| verification | 静态、单元、集成、宿主和交付验收 |
| error-recovery | 故障分类、根因定位、回归与停止条件 |
| agent-execution | 完整 Agent 执行循环、迭代、验证和交付 |

每个技能都是独立的 skills/<name>/SKILL.md，使用 DSH 原生 YAML frontmatter。Bundled Provider 的 list() 只发布候选元数据；get() 按需读取文件、剥离 frontmatter，并返回纯指令正文。候选项使用 URL locator 和绝对 resourceBase，遵循 DSH SkillProvider 契约。

## 工作模式

| ID | 模式 | 主要用途 |
|---|---|---|
| adaptive | 自适应模式 | 根据任务复杂度自动路由 |
| fast | 极速模式 | 简单任务的最短可靠路径 |
| deep-research | 深度研究模式 | 多来源研究与证据交叉验证 |
| engineering | 工程交付模式 | 仓库修改、测试、审查和交付 |
| batch | 批量执行模式 | 依赖分组、批次执行和汇总 |
| strict | 严格验证模式 | 独立验收和风险检查 |
| planning | 自动规划模式 | 目标、依赖、风险和里程碑 |
| recovery | 故障恢复模式 | 根因定位、最小修复和回归 |
| parallel | 多任务并行模式 | 并行独立任务和冲突处理 |

## 工作流工具

- `gpt_mode_list`：列出模式和适用策略。
- `gpt_workflow_start`：创建任务并选择模式。
- `gpt_workflow_status`：读取单个或全部任务状态。
- `gpt_workflow_advance`：推进合法阶段。
- `gpt_workflow_update`：追加决策摘要、证据、阻塞项和验收标准。
- `gpt_workflow_finish`：完成、阻塞、失败或取消任务；completed 必须先进入 deliver。
- `gpt_task_plan`：生成独立任务大纲，不执行外部操作。

## DSH 原生 Skills 集成

本 Bundle 以 DSH 官方 @deepseek-ai/dsh-base 为基础（由基础层提供 systemPrompt 与 tools 服务）；目标 Profile 需要组合：
- @deepseek-ai/dsh-skill：提供 Skill Registry。
- @deepseek-ai/dsh-tool-skill：提供模型可见技能目录和按需加载工具。
- @deepseek-ai/dsh-skill-filesystem：可选，需单独加入 Profile，发现项目和用户本地 Skills。
- @dsh-external/dsh-gpt-runtime：本项目插件和内置技能 Provider。

## 配置

| 字段 | 默认值 | 说明 |
|---|---|---|
| enabled | true | 启用 Plugin |
| skillsEnabled | true | 注册内置 Skill Provider |
| profileName | DSH-GPT 默认配置 | 配置档名称 |
| language | zh-CN | 输出语言提示 |
| verbosity | balanced | concise / balanced / detailed |
| defaultMode | adaptive | 默认工作模式 |
| executionPolicy | confirm-sensitive-actions | 行为提示，不替代宿主权限 |
| promptProfile | 内置中文规范 | 用户自定义行为指令 |

## 验证状态与限制

当前兼容范围：Node.js >=24.9.0、DSH >=0.2.2-rc.1 且 <0.3.0。运行 `npm test` 执行静态契约、Skill frontmatter 解析、模式路由和工作流状态机测试。真实 DSH 宿主集成仍需验证插件加载/卸载、Provider 注册、技能发现与正文加载、模型工具可见性、真实工具执行和重启行为。静态测试通过不代表真实宿主集成已经验收。

## 许可证

GPL-3.0-only
