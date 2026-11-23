import test from 'node:test'
import assert from 'node:assert/strict'
import {
  MODES,
  createTaskState,
  transitionTaskState,
  updateTaskState,
  finishTaskState,
  routeMode,
  createTaskStateStore,
} from '../src/runtime-core.js'

test('registers a complete set of user-facing execution modes', () => {
  assert.ok(MODES.length >= 8)
  for (const id of ['adaptive', 'fast', 'deep-research', 'engineering', 'batch', 'strict', 'planning', 'recovery', 'parallel']) {
    assert.ok(MODES.some((mode) => mode.id === id), id)
  }
})

test('adaptive routing selects specialist modes from task intent', () => {
  assert.equal(routeMode({ objective: '修复仓库 CI 报错' }).id, 'engineering')
  assert.equal(routeMode({ objective: '调查最新技术资料' }).id, 'deep-research')
  assert.equal(routeMode({ objective: '处理网络故障失败' }).id, 'recovery')
  assert.equal(routeMode({ objective: '简单问候', complexity: 'low' }).id, 'fast')
})

test('task state enforces ordered execution and independent verification', () => {
  let flow = createTaskState({ id: 't1', objective: '实现功能' })
  assert.throws(() => transitionTaskState(flow, 'execute'), /非法阶段转换/)
  flow = transitionTaskState(flow, 'inspect')
  flow = transitionTaskState(flow, 'plan')
  flow = updateTaskState(flow, { acceptanceCriteria: ['测试通过'] })
  flow = transitionTaskState(flow, 'execute')
  flow = transitionTaskState(flow, 'verify')
  assert.throws(() => finishTaskState(flow, { outcome: 'completed' }), /deliver/)
  flow = transitionTaskState(flow, 'deliver')
  flow = finishTaskState(flow, { outcome: 'completed', summary: '已验收' })
  assert.equal(flow.status, 'completed')
  assert.equal(flow.acceptanceCriteria.length, 1)
})

test('task state store isolates task state and rejects duplicate IDs', () => {
  const store = createTaskStateStore()
  store.create({ id: 'a', objective: '任务 A' })
  assert.throws(() => store.create({ id: 'a', objective: '重复' }), /已存在/)
  assert.throws(() => store.create({ id: ' a ', objective: '空白绕过重复检查' }), /已存在/)
  assert.throws(() => store.create({ id: '   ', objective: '空 ID' }), /不能为空/)
  assert.equal(store.get('a').phase, 'understand')
  assert.equal(store.list().length, 1)
})
