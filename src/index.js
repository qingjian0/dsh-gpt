import Schema from '@deepseek-ai/schemastery'
import { defineTool } from '@deepseek-ai/dsh-tools'

export const name = 'dsh-gpt-runtime'
export const inject = ['tools', 'systemPrompt']

export const Config = Schema.object({
  enabled: Schema.boolean().default(true),
  profileName: Schema.string().default('DSH-GPT Default'),
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

  // Register an additive persona section in DSH's real system-prompt assembly.
  // Keep it after the deployment persona prefix and before first-party policy sections.
  ctx.systemPrompt.section({
    name: 'dsh-gpt:persona',
    order: ctx.systemPrompt.getSectionOrder('DEPLOYMENT_PERSONA_PREFIX') + 1,
    text: config.promptProfile,
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
    description: 'Show the DSH-GPT plugin configuration and plugin-local execution counters. Does not expose hidden host prompts or private runtime state.',
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
        scope: 'This report covers calls handled by this plugin instance only.',
      })
    },
  })

  register({
    name: 'gpt_prompt_profile',
    description: 'Return the DSH-GPT prompt profile registered as an additive system-prompt section. Does not read or reveal other plugins’ or the host’s private prompt sections.',
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
        promptProfile: config.promptProfile,
        injection: 'Registered through ctx.systemPrompt.section() and included in DSH prompt assembly.',
      })
    },
  })

  register({
    name: 'gpt_task_plan',
    description: 'Create a transparent, reviewable task execution outline from an objective and constraints. Planning only; it does not execute external actions.',
    parameters: {
      objective: {
        type: 'string',
        required: true,
        description: 'The outcome the user wants to achieve.',
      },
      constraints: {
        type: 'string',
        description: 'Known limits, preferences, deadlines, and safety requirements.',
      },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute({ objective, constraints = '' }) {
      const safeObjective = String(objective).trim()
      if (!safeObjective) throw new Error('objective must not be empty')
      return asText({
        objective: safeObjective,
        constraints: String(constraints).trim() || 'No additional constraints supplied.',
        stages: [
          { id: 1, name: '理解与拆解', action: '明确目标、边界、输入和验收标准。' },
          { id: 2, name: '信息与能力检查', action: '确认可用上下文、工具、权限和依赖；标记未知项。' },
          { id: 3, name: '制定执行步骤', action: '按依赖关系排序，区分只读操作、可逆修改和敏感操作。' },
          { id: 4, name: '执行与记录', action: '逐步执行获准操作，记录输入、结果、错误和副作用。' },
          { id: 5, name: '验证', action: '使用独立检查验证结果，不以命令成功或模型陈述代替验收。' },
          { id: 6, name: '交付', action: '报告完成项、未完成项、风险和可复现证据。' },
        ],
        executionPolicy: config.executionPolicy,
        note: 'This tool produces a plan only. Actual actions must use separately authorized DSH tools.',
      })
    },
  })
}
