export function routeMode({ objective = '', requestedMode = 'adaptive', complexity = 'auto' } = {}) {
  if (requestedMode !== 'adaptive') return getMode(requestedMode) ?? getMode('adaptive')
  const text = String(objective).toLowerCase()
  const isEngineering = /仓库|代码|开发|编译|构建|测试|重构|修复|commit|pull request|ci/.test(text)
  if (/报错|失败|故障|异常|恢复|debug|crash|error/.test(text) && !isEngineering) return getMode('recovery')
  if (isEngineering) return getMode('engineering')
