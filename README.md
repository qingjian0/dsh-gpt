# DSH-GPT Runtime

DSH-GPT 是一个以 Plugin 为运行骨架、Skills 为按需加载工作方法的 DeepSeek Harness 扩展。插件负责系统提示词贡献、技能提供方注册、运行配置和状态工具；技能正文通过 DSH 官方 Skill Registry 与 Skill Tool 按需加载。

## 设计原则

- **Plugin = Runtime**：注册和生命周期、提示词段落、运行配置、工具与诊断。
- **Skills = Instructions**：专业工作流程、判断规则、检查清单和交付标准。
- **DSH = Host**：Agent Loop、模型调用、会话、工具调度、权限和审批。
- **Tools = Actions**：实际读写文件、访问网络、操作 GitHub 等能力由 DSH 原生工具或其他插件提供。

不复制或替换 DSH Agent Loop，不另造工具调度器，不把 Skill 指令当作权限授权。

## 当前功能

### Plugin
- 通过 ctx.systemPrompt.section() 注入独立的 dsh-gpt:persona 提示词段落。
- 支持中文、回答详细度、执行策略提示和用户自定义行为指令。
- 通过 ctx.skills.registerProvider() 注册随插件打包的技能库。
- 提供 gpt_runtime_status、gpt_prompt_profile、gpt_task_plan 三个诊断/规划工具。
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

每个技能都是独立的 skills/<name>/SKILL.md，使用 DSH 原生 YAML frontmatter。插件的 bundled provider 只发布名称和描述；完整正文在调用时才读取。

## DSH 原生 Skills 集成

本插件提供 bundled skills，但模型侧的目录与 skill 加载工具由 DSH 官方消费者提供。目标 Profile 需要组合：

- @deepseek-ai/dsh-skill：提供 ctx.skills 注册表。
- @deepseek-ai/dsh-tool-skill：提供模型可见技能目录和按需加载工具。
- @deepseek-ai/dsh-skill-filesystem：可选但推荐，发现项目和用户本地 Skills。
- @dsh-external/dsh-gpt-runtime：本项目插件和内置技能 Provider。

仓库的 cordis.patch.yml 给出组合示例；实际包版本、Profile 插入位置和发布渠道必须按目标 DSH 安装环境验证。

## 安装与开发

1. 使用目标 DSH 版本支持的 Node.js 运行环境。
2. 将本包安装到目标 DSH Profile 可加载的插件目录。
3. 按 cordis.patch.yml 将 Skill Registry、Skill Tool Consumer 和 DSH-GPT 加入同一 Profile。
4. 可选启用 dsh-skill-filesystem，以便同时发现工作区和用户 Skills。
5. 启动 DSH，确认可见 skill 工具和六个 bundled skills。
6. 在多步骤任务中检查模型是否先加载匹配 Skill，再执行 DSH 工具。

## 配置

| 字段 | 默认值 | 说明 |
|---|---|---|
| enabled | true | 启用 Plugin |
| skillsEnabled | true | 注册内置 Skill Provider |
| profileName | DSH-GPT 默认配置 | 配置档名称 |
| language | zh-CN | 输出语言提示 |
| verbosity | balanced | concise / balanced / detailed |
| executionPolicy | confirm-sensitive-actions | 行为提示，不替代宿主权限 |
| promptProfile | 内置中文规范 | 用户自定义行为指令 |

## 验证状态与限制

运行 npm test 执行静态契约测试。正式发布前仍须在真实 DSH 宿主完成插件加载/卸载、技能目录发现、技能正文按需加载、用户显式调用、Profile 提示词顺序和模型可见工具等集成验收。

静态测试通过不代表真实宿主集成已经验收。

## 许可证

GPL-3.0-only
