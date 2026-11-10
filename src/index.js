import Schema from '@deepseek-ai/schemastery'
import { defineTool } from '@deepseek-ai/dsh-tools'
import { registerBundledSkills, bundledSkillNames } from './skills-provider.js'
import { MODES, createTaskStateStore } from './runtime-core.js'

export const name = 'dsh-gpt-runtime'
export const inject = ['tools', 'systemPrompt', 'skills']

export const Config = Schema.object({
  enabled: Schema.boolean().default(true),
  skillsEnabled: Schema.boolean().default(true),
  profileName: Schema.string().default('DSH-GPT 默认配置'),
  language: Schema.string().default('zh-CN'),
  verbosity: Schema.union(['concise', 'balanced', 'detailed']).default('balanced'),
  defaultMode: Schema.union(['adaptive', 'fast', 'deep-research', 'engineering', 'batch', 'strict', 'planning', 'recovery', 'parallel']).default('adaptive'),
  executionPolicy: Schema.union(['assist', 'confirm-sensitive-actions']).default('confirm-sensitive-actions'),
  promptProfile: Schema.string().default(
    '你是一个严谨、透明的任务执行助手。先理解目标与约束，再给出可验证的步骤；调用工具前说明目的，调用后核对结果。区分已完成、进行中、失败和未验证事项。不得声称访问了未授权的内部信息，不得编造执行结果。对不可逆或敏感操作，先取得明确授权。'
  ),
})

function asText(value) {
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2)
}

const output = {
  schema: { type: 'string' },
  render: (_args, value) => [{ type: 'text', text: value }],
}

export function apply(ctx, config) {
  if (!config.enabled) return

  // State belongs to this Cordis plugin context, not the shared ESM module.
  // Multiple agents/scopes must not observe one another's counters or workflows.
  const counters = {
    startedAt: Date.now(),
    calls: 0,
    failures: 0,
    lastTool: null,
    lastCallAt: null,
  }
  const taskStateStore = createTaskStateStore()

  function recordCall(toolName, failed = false) {
    counters.calls += 1
    if (failed) counters.failures += 1
    counters.lastTool = toolName
    counters.lastCallAt = new Date().toISOString()
  }

  if (config.skillsEnabled) registerBundledSkills(ctx)

  const promptText = [
    '【DSH-GPT 运行配置】',
    `配置档：${config.profileName}`,
    `输出语言：${config.language}`,
    `回答详细度：${config.verbosity}`,
    `默认工作模式：${config.defaultMode}`,
    `操作策略提示：${config.executionPolicy}`,
    '',
    '【用户配置的行为指令】',
    config.promptProfile,
    '',
    '【模式与工作流】',
    '开始复杂任务前，先使用 gpt_mode_list 确认模式，按用户指定模式执行；用户未指定时使用自适应路由。用 gpt_task_state_start 建立轻量任务阶段记录，并按 understand → inspect → plan → execute → verify → deliver 推进；允许 verify → execute 回退修正。',
    '简单问答不必强行启动任务状态记录。极速模式减少非必要步骤，但不得省略必要核验；严格验证模式必须以独立证据验收。批量/并行模式只并行无依赖且互不冲突的任务。',
    '需要多个 subagent 的大型并行编排时，使用 DSH 原生 workflow 工具（dsh-tool-workflow / ctx.workflowEngine）；一两项委派优先使用 DSH subagent 工具。本插件的 gpt_task_state_* 只追踪任务阶段与证据，不执行 subagent，也不替代 DSH workflow engine。',
    '只记录可审查的决策摘要、依据、证据、阻塞项和验收标准；不要生成或声称提供隐藏思维链、逐字内心独白或私有推理过程。',
    '',
    '【技能使用规则】',
    '遇到复杂任务时先查看可用 Skills；选择匹配技能后，必须先通过 DSH 的 skill 工具加载完整指令，再按其流程工作。技能是方法指导，不是权限授权；实际操作仍须使用宿主提供并授权的工具。',
    '【执行边界】',
    '操作策略字段是行为提示，不代替 DSH 宿主的权限、审批和安全策略；宿主策略始终具有最终控制权。',
    '不得将未执行的操作描述为已完成；验证失败时必须明确报告。',
    '任务状态记录仅保存在当前插件进程内，重启后不会自动恢复；外部副作用和任务执行仍由 DSH 宿主工具负责。',
  ].join('\n')

  ctx.systemPrompt.section({
    name: 'dsh-gpt:persona',
    // External plugin contribution: after the persona prefix (0), before first-party policy (500).
    order: 100,
    text: promptText,
    interpolate: false,
  })

  const register = (definition) => {
    const execute = definition.execute
    ctx.tools.register(defineTool({
      ...definition,
      async execute(args) {
        try {
          const result = await execute(args)
          recordCall(definition.name)
          return result
        } catch (error) {
          recordCall(definition.name, true)
          throw error
        }
      },
    }))
  }

  register({
    name: 'gpt_runtime_status',
    description: '显示 DSH-GPT 配置、模式列表和本插件工具调用计数；不读取宿主隐藏提示词或私有运行时数据。',
    parameters: {},
    output,
    async execute() {
      return asText({
        plugin: name,
        profileName: config.profileName,
        language: config.language,
        verbosity: config.verbosity,
        defaultMode: config.defaultMode,
        executionPolicy: config.executionPolicy,
        promptSection: 'dsh-gpt:persona',
        skillsEnabled: Boolean(config.skillsEnabled),
        bundledSkills: config.skillsEnabled ? bundledSkillNames : [],
        modes: MODES.map(({ id, label }) => ({ id, label })),
        activeTasks: taskStateStore.list(),
        promptSectionOrder: 100,
        uptimeSeconds: Math.floor((Date.now() - counters.startedAt) / 1000),
        calls: counters.calls,
        failures: counters.failures,
        lastTool: counters.lastTool,
        lastCallAt: counters.lastCallAt,
        scope: '仅统计本插件实例注册的工具调用；工作流状态仅驻留内存。',
      })
    },
  })

  register({
    name: 'gpt_prompt_profile',
    description: '读取本插件实际注册到 DSH 系统提示词组装中的配置段落；不读取其他插件或宿主的提示词。',
    parameters: {},
    output,
    async execute() {
      return asText({
        profileName: config.profileName,
        language: config.language,
        verbosity: config.verbosity,
        defaultMode: config.defaultMode,
        executionPolicy: config.executionPolicy,
        promptSection: 'dsh-gpt:persona',
        skillsEnabled: Boolean(config.skillsEnabled),
        bundledSkills: config.skillsEnabled ? bundledSkillNames : [],
        effectivePluginPrompt: promptText,
        injection: '通过 ctx.systemPrompt.section() 注册，并参与 DSH 系统提示词组装。',
      })
    },
  })

  register({
    name: 'gpt_mode_list',
    description: '列出可用工作模式、适用场景和执行策略；不执行任务。',
    parameters: {},
    output,
    async execute() {
      return asText({ defaultMode: config.defaultMode, modes: MODES })
    },
  })

  register({
    name: 'gpt_task_plan',
    description: '根据目标和约束生成透明、可审查的任务执行大纲；只做规划，不执行外部操作。',
    parameters: {
      objective: { type: 'string', required: true, description: '用户希望达成的结果。' },
      constraints: { type: 'string', description: '已知限制、偏好、期限和安全要求。' },
    },
    output,
    async execute({ objective, constraints = '' }) {
      const safeObjective = String(objective).trim()
      if (!safeObjective) throw new Error('objective 不能为空')
      return asText({
        objective: safeObjective,
        constraints: String(constraints).trim() || '未提供额外约束。',
        stages: [
          { id: 1, name: '理解与拆解', action: '明确目标、边界、输入和验收标准。' },
          { id: 2, name: '信息与能力检查', action: '确认可用上下文、工具、权限和依赖；标记未知项。' },
          { id: 3, name: '制定执行步骤', action: '按依赖关系排序，区分只读操作、可逆修改和敏感操作。' },
          { id: 4, name: '执行与记录', action: '逐步执行获准操作，记录输入、结果、错误和副作用。' },
          { id: 5, name: '验证', action: '使用独立检查验证结果，不以命令成功或模型陈述代替验收。' },
          { id: 6, name: '交付', action: '报告完成项、未完成项、风险和可复现证据。' },
        ],
        executionPolicy: config.executionPolicy,
        note: '此工具只生成计划；实际操作必须通过单独授权的 DSH 工具执行。',
      })
    },
  })

  register({
    name: 'gpt_task_state_start',
    description: '为复杂任务建立可追踪任务阶段记录，自动路由或按用户指定模式启动。',
    parameters: {
      id: { type: 'string', required: true, description: '当前任务唯一 ID。' },
      objective: { type: 'string', required: true, description: '任务目标。' },
      constraints: { type: 'string', description: '约束、偏好、风险和期限。' },
      mode: { type: 'string', enum: MODES.map(({ id }) => id), description: '模式 ID；留空使用插件默认模式。' },
      complexity: { type: 'string', enum: ['low', 'normal', 'high', 'auto'], description: '任务复杂度。' },
    },
    output,
    async execute({ id, objective, constraints = '', mode = config.defaultMode, complexity = 'auto' }) {
      return asText(taskStateStore.create({ id: String(id).trim(), objective, constraints, mode, complexity }))
    },
  })

  register({
    name: 'gpt_task_state_status',
    description: '读取一个任务状态记录或列出当前插件上下文中的任务。',
    parameters: { id: { type: 'string', description: '工作流 ID；不填则列出全部。' } },
    output,
    async execute({ id } = {}) {
      if (!id) return asText(taskStateStore.list())
      const workflow = taskStateStore.get(id)
      if (!workflow) throw new Error('未找到该工作流')
      return asText(workflow)
    },
  })

  register({
    name: 'gpt_task_state_advance',
    description: '推进任务阶段；只允许合法阶段转换，verify 阶段可返回 execute 修正。',
    parameters: {
      id: { type: 'string', required: true, description: '工作流 ID。' },
      phase: { type: 'string', required: true, enum: ['inspect', 'plan', 'execute', 'verify', 'deliver'], description: '下一阶段。' },
      note: { type: 'string', description: '阶段切换依据或简短记录。' },
    },
    output,
    async execute({ id, phase, note = '' }) {
      return asText(taskStateStore.transition(id, phase, note))
    },
  })

  register({
    name: 'gpt_task_state_update',
    description: '向任务记录追加可审查的决策摘要、证据、阻塞项或验收标准；不收集隐藏思维链。',
    parameters: {
      id: { type: 'string', required: true, description: '工作流 ID。' },
      decisionSummary: { type: 'array', items: { type: 'string' }, description: '简短决策摘要，不是逐字思维链。' },
      evidence: { type: 'array', items: { type: 'string' }, description: '可核验的证据或来源。' },
      blockers: { type: 'array', items: { type: 'string' }, description: '当前阻塞和未解决问题。' },
      acceptanceCriteria: { type: 'array', items: { type: 'string' }, description: '验收标准。' },
    },
    output,
    async execute({ id, ...patch }) {
      return asText(taskStateStore.update(id, patch))
    },
  })

  register({
    name: 'gpt_task_state_finish',
    description: '结束任务记录。completed 仅允许在 deliver 阶段；其他状态用于明确记录阻塞、失败或取消。',
    parameters: {
      id: { type: 'string', required: true, description: '工作流 ID。' },
      outcome: { type: 'string', required: true, enum: ['completed', 'blocked', 'failed', 'cancelled'], description: '工作流结束状态。' },
      summary: { type: 'string', description: '交付摘要或结束原因。' },
    },
    output,
    async execute({ id, outcome, summary = '' }) {
      return asText(taskStateStore.finish(id, { outcome, summary }))
    },
  })
}
