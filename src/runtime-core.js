export const WORKFLOW_PHASES = [
  'understand',
  'inspect',
  'plan',
  'execute',
  'verify',
  'deliver',
]

export const MODES = [
  { id: 'adaptive', label: '自适应模式', description: '按任务复杂度自动选择分析深度、工具策略和验证强度。', depth: 'adaptive', verification: 'adaptive', parallel: true },
  { id: 'fast', label: '极速模式', description: '优先最短可靠路径；简单任务减少规划开销，仍保留必要核验。', depth: 'low', verification: 'essential', parallel: true },
  { id: 'deep-research', label: '深度研究模式', description: '多来源检索、证据分级、交叉验证和不确定性分析。', depth: 'high', verification: 'strict', parallel: true },
  { id: 'engineering', label: '工程交付模式', description: '仓库审查、最小实现、测试、代码审查和可复现交付。', depth: 'high', verification: 'strict', parallel: true },
  { id: 'batch', label: '批量执行模式', description: '拆分独立任务、按依赖分组、批次执行并汇总结果。', depth: 'adaptive', verification: 'strict', parallel: true },
  { id: 'strict', label: '严格验证模式', description: '优先证据、独立复核、风险检查；未经验证不宣称完成。', depth: 'high', verification: 'strict', parallel: false },
  { id: 'planning', label: '自动规划模式', description: '先定义目标、约束、依赖、风险、里程碑和验收标准。', depth: 'high', verification: 'normal', parallel: true },
  { id: 'recovery', label: '故障恢复模式', description: '分类故障、定位根因、实施最小修复并执行回归验证。', depth: 'high', verification: 'strict', parallel: false },
  { id: 'parallel', label: '多任务并行模式', description: '识别无依赖任务并行执行，汇总结果并处理冲突。', depth: 'adaptive', verification: 'strict', parallel: true },
]

const MODE_BY_ID = new Map(MODES.map((mode) => [mode.id, mode]))

const TRANSITIONS = {
  understand: ['inspect'],
  inspect: ['plan'],
  plan: ['execute'],
  execute: ['verify'],
  verify: ['execute', 'deliver'],
  deliver: [],
}

const now = () => new Date().toISOString()

export function getMode(modeId) {
  return MODE_BY_ID.get(modeId) ?? null
}

export function routeMode({ objective = '', requestedMode = 'adaptive', complexity = 'auto' } = {}) {
  if (requestedMode !== 'adaptive') return getMode(requestedMode) ?? getMode('adaptive')
  const text = String(objective).toLowerCase()
  if (/报错|失败|故障|异常|恢复|debug|crash|error/.test(text)) return getMode('recovery')
  if (/仓库|代码|开发|编译|构建|测试|重构|修复|commit|pull request|ci/.test(text)) return getMode('engineering')
  if (/研究|调查|比较|调研|最新|资料|证据|research/.test(text)) return getMode('deep-research')
  if (/批量|多个任务|多项目|批处理/.test(text)) return getMode('batch')
  if (/并行|同时执行|多任务/.test(text)) return getMode('parallel')
  if (complexity === 'high') return getMode('planning')
  if (complexity === 'low') return getMode('fast')
  return getMode('adaptive')
}

export function createWorkflow({ id, objective, constraints = '', mode = 'adaptive', complexity = 'auto' }) {
  if (!String(objective ?? '').trim()) throw new Error('objective 不能为空')
  const selected = routeMode({ objective, requestedMode: mode, complexity })
  const timestamp = now()
  return {
    id,
    objective: String(objective).trim(),
    constraints: String(constraints).trim(),
    mode: selected.id,
    modeLabel: selected.label,
    complexity,
    phase: 'understand',
    status: 'active',
    createdAt: timestamp,
    updatedAt: timestamp,
    history: [{ phase: 'understand', status: 'entered', at: timestamp }],
    decisionSummary: [],
    evidence: [],
    blockers: [],
    acceptanceCriteria: [],
  }
}

export function transitionWorkflow(workflow, nextPhase, note = '') {
  if (!workflow || workflow.status !== 'active') throw new Error('工作流不存在或已结束')
  if (!TRANSITIONS[workflow.phase]?.includes(nextPhase)) {
    throw new Error(`非法阶段转换：${workflow.phase} -> ${nextPhase}`)
  }
  const timestamp = now()
  return {
    ...workflow,
    phase: nextPhase,
    updatedAt: timestamp,
    history: [...workflow.history, { phase: nextPhase, status: 'entered', note: String(note).slice(0, 1000), at: timestamp }],
  }
}

export function updateWorkflow(workflow, patch = {}) {
  if (!workflow || workflow.status !== 'active') throw new Error('工作流不存在或已结束')
  const allowed = ['decisionSummary', 'evidence', 'blockers', 'acceptanceCriteria']
  const next = { ...workflow }
  for (const key of allowed) {
    if (patch[key] !== undefined) {
      if (!Array.isArray(patch[key])) throw new Error(`${key} 必须是数组`)
      next[key] = [...workflow[key], ...patch[key].map((item) => String(item).slice(0, 2000))]
    }
  }
  next.updatedAt = now()
  return next
}

export function finishWorkflow(workflow, { outcome = 'completed', summary = '' } = {}) {
  if (!workflow || workflow.status !== 'active') throw new Error('工作流不存在或已结束')
  if (outcome === 'completed' && workflow.phase !== 'deliver') {
    throw new Error('只有进入 deliver 阶段后才能标记完成；请先完成独立验证和交付')
  }
  if (!['completed', 'blocked', 'failed', 'cancelled'].includes(outcome)) throw new Error('无效的结束状态')
  const timestamp = now()
  return {
    ...workflow,
    status: outcome,
    summary: String(summary).slice(0, 4000),
    updatedAt: timestamp,
    history: [...workflow.history, { phase: workflow.phase, status: outcome, at: timestamp }],
  }
}

export function createWorkflowStore() {
  const workflows = new Map()
  return {
    create(input) {
      if (workflows.has(input.id)) throw new Error('工作流 ID 已存在')
      const workflow = createWorkflow(input)
      workflows.set(workflow.id, workflow)
      return workflow
    },
    get(id) {
      return workflows.get(id) ?? null
    },
    transition(id, phase, note) {
      const updated = transitionWorkflow(workflows.get(id), phase, note)
      workflows.set(id, updated)
      return updated
    },
    update(id, patch) {
      const updated = updateWorkflow(workflows.get(id), patch)
      workflows.set(id, updated)
      return updated
    },
    finish(id, options) {
      const updated = finishWorkflow(workflows.get(id), options)
      workflows.set(id, updated)
      return updated
    },
    list() {
      return [...workflows.values()].map(({ id, objective, mode, phase, status, updatedAt }) => ({
        id, objective, mode, phase, status, updatedAt,
      }))
    },
  }
}
