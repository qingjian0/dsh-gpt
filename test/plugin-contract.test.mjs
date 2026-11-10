import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { stripSkillFrontmatter } from '../src/skill-content.js'

const root = new URL('../', import.meta.url)

test('plugin declares DSH tools, system prompt, and skills services', async () => {
  const source = await readFile(new URL('src/index.js', root), 'utf8')
  assert.match(source, /export const name = 'dsh-gpt-runtime'/)
  assert.match(source, /export const inject = \['tools', 'systemPrompt', 'skills'\]/)
  assert.match(source, /ctx\.systemPrompt\.section\(/)
  assert.match(source, /registerBundledSkills\(ctx\)/)
  assert.match(source, /interpolate: false/)
  assert.match(source, /order: 100/)
  assert.ok(source.indexOf('const taskStateStore = createTaskStateStore()') > source.indexOf('export function apply'))
  assert.ok(source.indexOf('const counters = {') > source.indexOf('export function apply'))
})

test('plugin exposes mode and stateful task state tools with constrained parameters', async () => {
  const source = await readFile(new URL('src/index.js', root), 'utf8')
  for (const tool of ['gpt_mode_list', 'gpt_task_state_start', 'gpt_task_state_status', 'gpt_task_state_advance', 'gpt_task_state_update', 'gpt_task_state_finish']) {
    assert.match(source, new RegExp("name: '" + tool + "'"))
  }
  assert.match(source, /createTaskStateStore\(\)/)
  assert.match(source, /defaultMode:/)
  assert.match(source, /enum: MODES\.map/)
  assert.match(source, /enum: \['completed', 'blocked', 'failed', 'cancelled'\]/)
})

test('bundled provider follows the DSH SkillProvider contract', async () => {
  const source = await readFile(new URL('src/skills-provider.js', root), 'utf8')
  assert.match(source, /BUNDLED_SKILL_RANK/)
  assert.match(source, /ctx\.skills\.registerProvider\(/)
  assert.match(source, /async list\(/)
  assert.match(source, /async get\(/)
  assert.match(source, /invocation: \{ modelInvocable: true, userInvocable: true \}/)
  assert.match(source, /source: 'bundled'/)
  assert.match(source, /const locator = new URL\(/)
  assert.match(source, /stripSkillFrontmatter\(raw, selected\.name\)/)
})

test('skill provider strips YAML frontmatter and preserves instruction body', () => {
  const raw = '---\nname: demo\ndescription: Demo skill\n---\n\n# Body\n\nKeep this.'
  assert.equal(stripSkillFrontmatter(raw), '# Body\n\nKeep this.')
  assert.throws(() => stripSkillFrontmatter('# no frontmatter'), /frontmatter/)
  assert.throws(() => stripSkillFrontmatter('---\nname: empty\n---\n'), /empty instruction body/)
})

test('all bundled skills have valid frontmatter and matching kebab-case names', async () => {
  const names = ['task-planning', 'tool-orchestration', 'code-engineering', 'research', 'verification', 'error-recovery', 'agent-execution']
  for (const name of names) {
    const body = await readFile(new URL('skills/' + name + '/SKILL.md', root), 'utf8')
    assert.match(body, /^---\n/)
    assert.match(body, new RegExp('^name: ' + name + '$', 'm'))
    assert.match(body, /^description: .+$/m)
    assert.match(body, /\n# /)
    assert.ok(stripSkillFrontmatter(body).startsWith('# '))
  }
})

test('bundle reuses dsh-base services instead of inserting duplicate providers', async () => {
  const patch = await readFile(new URL('cordis.patch.yml', root), 'utf8')
  assert.match(patch, /id: dsh-gpt-runtime/)
  assert.equal(patch.includes("name: '@deepseek-ai/dsh-skill'"), false)
  assert.equal(patch.includes("name: '@deepseek-ai/dsh-tool-skill'"), false)
  assert.equal(patch.includes("name: '@deepseek-ai/dsh-skill-filesystem'"), false)
  assert.match(patch, /dsh-base already mounts/)
})

test('package includes runtime, skill assets, and DSH skill peer dependencies', async () => {
  const pkg = JSON.parse(await readFile(new URL('package.json', root), 'utf8'))
  assert.ok(pkg.files.includes('src'))
  assert.ok(pkg.files.includes('skills'))
  assert.ok(pkg.peerDependencies['@deepseek-ai/dsh-skill'])
  assert.ok(pkg.peerDependencies['@deepseek-ai/dsh-tool-skill'])
  assert.ok(pkg.peerDependencies['@deepseek-ai/dsh-skill-filesystem'])
  assert.equal(pkg.engines.node, '>=24.9.0')
  assert.equal(pkg.engines.dsh, '>=0.2.2-rc.1 <0.3.0')
  assert.ok(pkg.peerDependencies['@deepseek-ai/dsh-system-prompt'])
  assert.equal(pkg.dsh.manifestVersion, 1)
})
