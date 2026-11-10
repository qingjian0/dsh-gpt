import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

test('plugin declares DSH tool and system-prompt dependencies', async () => {
  const source = await readFile(new URL('../src/index.js', import.meta.url), 'utf8')
  assert.match(source, /export const name = 'dsh-gpt-runtime'/)
  assert.match(source, /export const inject = \['tools', 'systemPrompt'\]/)
  assert.match(source, /export function apply\(ctx, config\)/)
  assert.match(source, /ctx\.tools\.register\(defineTool/)
  assert.match(source, /ctx\.systemPrompt\.section\(/)
})

test('plugin registers only an additive named persona section', async () => {
  const source = await readFile(new URL('../src/index.js', import.meta.url), 'utf8')
  assert.match(source, /name: 'dsh-gpt:persona'/)
  assert.match(source, /getSectionOrder\('DEPLOYMENT_PERSONA_PREFIX'\)/)
  assert.doesNotMatch(source, /complete:\s*true/)
})
