import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)

test('plugin declares DSH tools, system prompt, and skills services', async () => {
  const source = await readFile(new URL('src/index.js', root), 'utf8')
  assert.match(source, /export const name = 'dsh-gpt-runtime'/)
  assert.match(source, /export const inject = \['tools', 'systemPrompt', 'skills'\]/)
  assert.match(source, /ctx\.systemPrompt\.section\(/)
  assert.match(source, /registerBundledSkills\(ctx\)/)
  assert.match(source, /interpolate: false/)
  assert.match(source, /order: 100/)
  assert.match(source, /const workflowStore = createWorkflowStore\\(\\)/)
  assert.doesNotMatch(source, /^const workflowStore = createWorkflowStore\\(\\)/m)
  assert.doesNotMatch(source, /^const counters =/m)
})

test('plugin exposes mode and stateful workflow tools', async () => {
  const source = await readFile(new URL('src/index.js', root), 'utf8')
  for (const tool of [
    'gpt_mode_list',
    'gpt_workflow_start',
    'gpt_workflow_status',
    'gpt_workflow_advance',
    'gpt_workflow_update',
    'gpt_workflow_finish',
  ]) assert.match(source, new RegExp(`name: '${tool}'`))
  assert.match(source, /createWorkflowStore\(\)/)
  assert.match(source, /defaultMode:/)
})

test('bundled provider follows the DSH SkillProvider contract', async () => {
  const source = await readFile(new URL('src/skills-provider.js', root), 'utf8')
  assert.match(source, /BUNDLED_SKILL_RANK/)
  assert.match(source, /ctx\.skills\.registerProvider\(/)
  assert.match(source, /async list\(/)
  assert.match(source, /async get\(/)
  assert.match(source, /modelInvocable: true, userInvocable: true/)
  assert.match(source, /source: 'bundled'/)
  assert.match(source, /locator = new URL\\(/)
  assert.match(source, /stripSkillFrontmatter\\(raw, selected.name\\)/)
})

test('all bundled skills have valid frontmatter and matching kebab-case names', async () => {
  const names = [
    'task-planning',
    'tool-orchestration',
    'code-engineering',
    'research',
    'verification',
    'error-recovery',
    'agent-execution',
  ]
  for (const name of names) {
    const body = await readFile(new URL(`skills/${name}/SKILL.md`, root), 'utf8')
    assert.match(body, /^---\n/)
    assert.match(body, new RegExp(`^name: ${name}$`, 'm'))
    assert.match(body, /^description: .+$/m)
    assert.match(body, /\n# /)
  }
})

test('package includes runtime, skill assets, and DSH skill peer dependencies', async () => {
  const pkg = JSON.parse(await readFile(new URL('package.json', root), 'utf8'))
  assert.ok(pkg.files.includes('src'))
  assert.ok(pkg.files.includes('skills'))
  assert.ok(pkg.peerDependencies['@deepseek-ai/dsh-skill'])
  assert.ok(pkg.peerDependencies['@deepseek-ai/dsh-tool-skill'])
  assert.ok(pkg.peerDependencies['@deepseek-ai/dsh-skill-filesystem'])
  assert.equal(pkg.engines.node, '>=24')
  assert.equal(pkg.dsh.manifestVersion, 1)
})
