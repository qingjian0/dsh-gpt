import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

test('plugin declares the documented DSH entry points', async () => {
  const source = await readFile(new URL('../src/index.js', import.meta.url), 'utf8')
  assert.match(source, /export const name = 'dsh-gpt-runtime'/)
  assert.match(source, /export const inject = \['tools'\]/)
  assert.match(source, /export function apply\(ctx, config\)/)
  assert.match(source, /ctx\.tools\.register\(defineTool/)
})

test('plugin explicitly labels prompt profile as plugin-owned data', async () => {
  const source = await readFile(new URL('../src/index.js', import.meta.url), 'utf8')
  assert.match(source, /not automatically injected into the host system prompt/i)
})
