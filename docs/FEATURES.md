# 功能开关：反代注入 + hook 规则

实现在 `tools/openai-relay.mjs`，通过环境变量控制，注入到 `/backend-api/conversation` 的请求体。

## 1 反代注入（已实测）

| 功能 | 环境变量 | 注入字段 |
|---|---|---|
| 临时聊天 | `DSH_TEMPORARY=1` | `temporary_chat=true` + `history_and_training_disabled=true` |
| 个性化 开/关 | `DSH_PERSONALIZATION=1/0` | `personalization=true/false` |
| 深入思考 | `DSH_REASONING=low\|medium\|high` | `reasoning_effort="<值>"` |
| 会话上限扩容 | `DSH_MAX_TOKENS=32768` | `max_tokens=<值>` |
| 插件 / 工具 | `DSH_PLUGINS=1` | `tools=[]`（由客户端后续填充） |
| 只读转正常 | `DSH_READONLY=0` | 重置 `conversation_mode={kind:"primary_assistant"}` |

启动：

```sh
DSH_TEMPORARY=1 DSH_REASONING=high DSH_PERSONALIZATION=0 DSH_MAX_TOKENS=32768 \
  node tools/openai-relay.mjs --kit ./kit --port 8790
```

实测（2026-11-23 03:45）：注入后 `/backend-api/conversation` 仍 200，模型答「9.9」正确，
响应中出现 `reasoning` 字段，说明 `reasoning_effort=high` 被上游接受。

## 2 hook 规则模板（粘进 DSH GPT 的 GPT Hook 编辑器）

### 2.1 临时聊天 —— 强制 `isTemporary` 为 true

```json
{"schemaVersion":1,"targetPackage":"com.openai.chatgpt","rules":[
 {"id":"tmp.settings","className":"com.openai.feature.settings.impl.memory.a","methodName":"a","parameterTypes":[],"enabled":true,"sampleEvery":1}
]}
```

> 类名需先用「扫描并生成类表」得到真实短名，`class-scan.txt` 里搜
> `temporary|incognito|ephemeral` 定位；本模板只演示结构。

### 2.2 个性化开关

搜索 `personaliz|memory_enabled` 落在的类，观察其 `isEnabled()` 之类方法。

### 2.3 深入思考

搜索 `reasoning_effort|thinking`，hook 返回 String 的方法替换成 `"high"`。

### 2.4 文件 / 插件

搜索 `plugins|connectors|tools`，hook 返回 List 的方法追加元素。

### 2.5 会话上限

搜索 `max_tokens|context_window`，hook 返回 Int 的方法放大到 32768。

### 2.6 只读转正常

搜索 `read_only|readonly`，hook 返回 boolean 的方法返回 false。

## 3 为什么不直接 hook UI

这类开关在客户端是「服务端下发的 flag + 请求体参数」双重控制：

- 页面显隐由 `/backend-api/models` 的 `product_features` 与 conversation 响应里的 `banner_info` 决定
- 实际能力由请求体的 `temporary_chat` / `reasoning_effort` / `tools` 决定

改请求体比重写 UI 可靠得多，所以优先做反代注入；hook 规则只在需要改 UI 状态时补。

## 4 待办

- [ ] 把 `class-scan.txt` 里 6 个关键词对应的真实类名逐个 inspect，产出可直接用的规则
- [ ] `DSH_PLUGINS` 需要一个 tools 数组模板（当前只给空数组占位）
- [ ] 只读模式在 `f/conversation` 新格式下的字段名待确认
