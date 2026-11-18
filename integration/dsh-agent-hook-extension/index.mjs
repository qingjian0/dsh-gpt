import { defineTool } from '@deepseek-ai/dsh-tools'

export const name = 'dsh-gpt-hook-agent'
export const inject = ['tools']

const BASE_URL = 'http://127.0.0.1:3083'
const output = {
  schema: { type: 'string' },
  render: (_args, value) => [{ type: 'text', text: value }],
}

async function request(method, path, body) {
  const token = process.env.DSH_BRIDGE_TOKEN
  if (!token) throw new Error('DSH_BRIDGE_TOKEN is unavailable in the DSH engine environment')

  const response = await fetch(new URL(path, BASE_URL), {
    method,
    headers: {
      'X-DSH-Token': token,
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(15_000),
  })

  const raw = await response.text()
  let parsed
  try { parsed = JSON.parse(raw) } catch { parsed = { raw } }
  if (!response.ok) {
    const reason = parsed?.error || parsed?.message || response.statusText
    throw new Error(`DSH Hook bridge ${response.status}: ${reason}`)
  }
  if (parsed?.ok === false) throw new Error(parsed.error || 'DSH Hook operation failed')
  return JSON.stringify(parsed, null, 2)
}

function register(ctx, definition) {
  ctx.tools.register(defineTool({ ...definition, output }))
}

export function apply(ctx) {
  register(ctx, {
    name: 'dsh_hook_status',
    description: 'Read the live dsh-gpt LSPosed module connection, target process, enabled state and applied Hook counts.',
    parameters: {},
    async execute() {
      return request('GET', '/hook/status')
    },
  })

  register(ctx, {
    name: 'dsh_hook_events',
    description: 'Read incremental metadata-only Hook observations. Use since to continue from the last sequence.',
    parameters: {
      since: { type: 'integer', description: 'Return events with sequence greater than this value.' },
      limit: { type: 'integer', description: 'Maximum number of events, from 1 to 500.' },
    },
    async execute({ since = 0, limit = 100 } = {}) {
      const safeSince = Math.max(0, Number(since) || 0)
      const safeLimit = Math.min(500, Math.max(1, Number(limit) || 100))
      const query = new URLSearchParams({ since: String(safeSince), limit: String(safeLimit) })
      return request('GET', `/hook/events?${query}`)
    },
  })

  register(ctx, {
    name: 'dsh_hook_rules_get',
    description: 'Read the current declarative Hook rules from the LSPosed module.',
    parameters: {},
    async execute() {
      return request('GET', '/hook/rules')
    },
  })

  register(ctx, {
    name: 'dsh_hook_rules_update',
    description: 'Validate and apply a complete declarative Hook configuration snapshot. The module validates it again before applying.',
    parameters: {
      config: {
        type: 'object',
        required: true,
        description: 'Complete Hook config: schemaVersion, targetPackage and rules.',
      },
    },
    async execute({ config }) {
      if (!config || typeof config !== 'object' || Array.isArray(config)) {
        throw new Error('config must be a JSON object')
      }
      return request('POST', '/hook/rules', config)
    },
  })

  register(ctx, {
    name: 'dsh_hook_discover',
    description: 'Discover target-app class and method signatures by package prefix; returns metadata only.',
    parameters: {
      prefix: { type: 'string', description: 'Class/package prefix, for example com.openai.' },
      className: { type: 'string', description: 'Optional exact class name to inspect.' },
      limit: { type: 'integer', description: 'Maximum metadata records, from 1 to 500.' },
    },
    async execute({ prefix = 'com.openai.', className, limit = 100 } = {}) {
      const safeLimit = Math.min(500, Math.max(1, Number(limit) || 100))
      return request('POST', '/hook/discover', {
        prefix: String(prefix),
        ...(className ? { className: String(className) } : {}),
        limit: safeLimit,
      })
    },
  })

  register(ctx, {
    name: 'dsh_hook_enable',
    description: 'Enable or disable the LSPosed Hook runtime while keeping the module installed.',
    parameters: {
      enabled: { type: 'boolean', required: true, description: 'Whether Hook callbacks should be active.' },
    },
    async execute({ enabled }) {
      if (typeof enabled !== 'boolean') throw new Error('enabled must be boolean')
      return request('POST', '/hook/control', { enabled })
    },
  })
}
