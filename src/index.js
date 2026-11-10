import Schema from '@deepseek-ai/schemastery'
import { defineTool } from '@deepseek-ai/dsh-tools'

export const name = 'dsh-gpt-runtime'
export const inject = ['tools', 'systemPrompt']

export const Config = Schema.object({
  enabled: Schema.boolean().default(true),
  profileName: Schema.string().default('DSH-GPT 默认配置'),
  language: Schema.string().default('zh-CN'),
  verbosity: Schema.union(['concise', 'balanced', 'detailed']).default('balanced'),
  executionPolicy: Schema.union(['assist', 'confirm-sensitive-actions']).default('confirm-sensitive-actions'),
  promptProfile: Schema.string().default(
    '你是一个严谨、透明的任务执行助手。先理解目标与约束，再给出可验证的步骤；调用工具前说明目的，调用后核对结果。区分已完成、进行中、失败和未验证事项。不得声称访问了未授权的内部信息，不得编造执行结果。对不可逆或敏感操作，先取得明确授权。'
  ),
})

const counters = {
  startedAt: Date.now(),
  calls: 0,
  failures: 0,
  lastTool: null,
  lastCallAt: null,
}

function recordCall(toolName, failed = false) {
  counters.calls += 1
  if (failed) counters.failures += 1
  counters.lastTool = toolName
  counters.lastCallAt = new Date().toISOString()
}

function asText(value) {
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2)
}

export function apply(ctx, config) {
  if (!config.enabled) return

  const promptText = [
    '【DSH-GPT 运行配置】',
    `配置档：${config.profileName}`,
    `输出语言：${config.language}`,
    `回答详细度：${config.verbosity}`,
    `操作策略提示：${config.executionPolicy}`,
    '',
    '【用户配置的行为指令】',
    config.promptProfile,
    '',
    '【执行边界】',
    '操作策略字段是行为提示，不代替 DSH 宿主的权限、审批和安全策略；宿主策略始终具有最终控制权。',
    '不得将未执行的操作描述为已完成；验证失败时必须明确报告。',
  ].join('\n')

  // Register an additive persona section in DSH's real system-prompt assembly.
  // Keep it after the deployment persona prefix and before first-party policy sections.
  ctx.systemPrompt.section({
    name: 'dsh-gpt:persona',
    order: ctx.systemPrompt.getSectionOrder('DEPLOYMENT_PERSONA_PREFIX') + 1,
    text: promptText,
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
    description: '显示 DSH-GPT 插件配置和本插件工具调用计数；不读取宿主隐藏提示词或私有运行时数据。',
    parameters: {},
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute() {
      return asText({
        plugin: name,
        profileName: config.profileName,
        language: config.language,
        verbosity: config.verbosity,
        executionPolicy: config.executionPolicy,
        promptSection: 'dsh-gpt:persona',
        promptSectionOrder: ctx.systemPrompt.getSectionOrder('DEPLOYMENT_PERSONA_PREFIX') + 1,
        uptimeSeconds: Math.floor((Date.now() - counters.startedAt) / 1000),
        calls: counters.calls,
        failures: counters.failures,
        lastTool: counters.lastTool,
        lastCallAt: counters.lastCallAt,
        scope: '仅统计本插件实例注册的工具调用。',
      })
    },
  })

  register({
    name: 'gpt_prompt_profile',
    description: '读取本插件实际注册到 DSH 系统提示词组装中的配置段落；不读取其他插件或宿主的提示词。',
    parameters: {},
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute() {
      return asText({
        profileName: config.profileName,
        language: config.language,
        verbosity: config.verbosity,
        executionPolicy: config.executionPolicy,
        promptSection: 'dsh-gpt:persona',
        effectivePluginPrompt: promptText,
        injection: '通过 ctx.systemPrompt.section() 注册，并参与 DSH 系统提示词组装。',
      })
    },
  })

  register({
    name: 'gpt_task_plan',
    description: '根据目标和约束生成透明、可审查的任务执行大纲；只做规划，不执行外部操作。',
    parameters: {
      objective: {
        type: 'string',
        required: true,
        description: '用户希望达成的结果。',
      },
      constraints: {
        type: 'string',
        description: '已知限制、偏好、期限和安全要求。',
      },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
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
}
