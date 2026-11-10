import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { BUNDLED_SKILL_RANK } from '@deepseek-ai/dsh-skill'
import { stripSkillFrontmatter } from './skill-content.js'

const PROVIDER_NAME = 'dsh-gpt-bundled'

const definitions = [
  { name: 'task-planning', description: '将复杂目标拆解为可执行阶段、依赖、风险和验收标准。用户要求规划项目、任务或多步骤工作时加载。', whenToUse: 'Use before multi-step work when scope, dependencies, milestones, or acceptance criteria need to be made explicit.', file: '../skills/task-planning/SKILL.md' },
  { name: 'tool-orchestration', description: '指导模型选择、组合和调度 DSH 工具，处理并行、依赖、权限和结果归并。涉及多个工具或外部操作时加载。', whenToUse: 'Use when a task requires multiple tools, ordered calls, parallel independent calls, or external side effects.', file: '../skills/tool-orchestration/SKILL.md' },
  { name: 'code-engineering', description: '提供软件工程工作流：理解仓库、定位问题、设计最小补丁、实现、测试和审查。代码开发或修复时加载。', whenToUse: 'Use for repository exploration, implementation, refactoring, debugging, or code review.', file: '../skills/code-engineering/SKILL.md' },
  { name: 'research', description: '指导多来源研究、检索策略、证据分级、交叉验证和引用。需要调查、比较或最新资料时加载。', whenToUse: 'Use for factual research, technical comparisons, current information, or evidence-backed recommendations.', file: '../skills/research/SKILL.md' },
  { name: 'verification', description: '定义独立验收流程，核对文件、构建、测试、CI 和运行结果，区分成功、失败与未验证。', whenToUse: 'Use before claiming that code, a build, deployment, or external action is complete.', file: '../skills/verification/SKILL.md' },
  { name: 'error-recovery', description: '提供故障分类、根因定位、最小修复、回归测试和停止条件，避免盲目重试。', whenToUse: 'Use when a tool, build, test, network request, or workflow fails or behaves inconsistently.', file: '../skills/error-recovery/SKILL.md' },
  { name: 'agent-execution', description: '规范多步骤 Agent 任务的完整执行循环，包括目标理解、上下文检查、计划、技能选择、工具调用、结果观察、验证和交付。', whenToUse: 'Use for any substantial multi-step task that requires planning, tool use, iterative execution, or a verifiable deliverable.', file: '../skills/agent-execution/SKILL.md' },
]

const candidates = definitions.map((definition) => {
  const locator = new URL(definition.file, import.meta.url)
  return {
    name: definition.name,
    description: definition.description,
    whenToUse: definition.whenToUse,
    invocation: { modelInvocable: true, userInvocable: true },
    provider: PROVIDER_NAME,
    source: 'bundled',
    rank: BUNDLED_SKILL_RANK,
    locator,
    resourceBase: { kind: 'directory', path: fileURLToPath(new URL('.', locator)) },
  }
})

const byName = new Map(candidates.map((candidate) => [candidate.name, candidate]))

export function registerBundledSkills(ctx) {
  const provider = {
    name: PROVIDER_NAME,
    async list(options = {}) {
      if (options.signal?.aborted) throw options.signal.reason
      return candidates
    },
    async get(candidate, options = {}) {
      if (options.signal?.aborted) throw options.signal.reason
      const selected = byName.get(candidate.name)
      if (!selected || selected.locator.href !== candidate.locator?.href) return undefined
      const raw = await readFile(selected.locator, { encoding: 'utf8', signal: options.signal })
      const content = stripSkillFrontmatter(raw, selected.name)
      return {
        name: selected.name,
        description: selected.description,
        whenToUse: selected.whenToUse,
        invocation: selected.invocation,
        provider: selected.provider,
        source: selected.source,
        resourceBase: selected.resourceBase,
        content,
      }
    },
  }

  return ctx.skills.registerProvider(() => provider)
}

export const bundledSkillNames = definitions.map(({ name }) => name)
