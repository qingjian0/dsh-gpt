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

---

## 5 实测结论（2026-11-23）

### 5.1 请求体注入：可用

`DSH_TEMPORARY / DSH_REASONING / DSH_PERSONALIZATION / DSH_MAX_TOKENS` 注入后
`POST /backend-api/conversation` 仍返回 200，模型正常作答，响应出现 `reasoning` 字段。

### 5.2 静态定位混淆类：不可行

写了一个 DEX 解析器（`tools/dexscan.mjs`，直接读 field_ids / method_ids / class_data），
对 10 个 dex（97226 类）按 `temporary|personaliz|reasoning|thinking|plugin|connector|max_tokens|read_only` 扫描，
命中 34 个类，其中 `com.openai.*` 只有 10 个，且全部是业务无关或非主链路：

| 类 | 字段/方法 | 说明 |
|---|---|---|
| `com.openai.valdi.dil.DILMessageMetadata` | `_isReadOnly` | 文档内消息只读标志，非「只读模式」 |
| `com.openai.valdi.chatgpt.subscription.pricing.SubscriptionPricingNativeState` | `_personalization` | 订阅定价页文案，非设置项 |
| `com.openai.valdi.chatgpt.subscription.pricing.SubscriptionPricingNativeFeatureCopy` | `_advancedImageQualityWithThinking` | 定价页特性文案 |
| 其余 7 个 | `*Connector*` | 金融/购物连接器，与「插件」不是一回事 |

原因：R8 把 model 类字段重命成 `a`/`b`/`c`，只有 protobuf 生成类保留字段名。
所以「按字段名找类」这条路在混淆包里走不通。

### 5.3 运行时字段扫描：被 LSPosed 配置缓存挡住

装了一个独立探针（包名 `com.insp.tool`，源码 `gpt-insp/`），在 `handleLoadPackage`
里遍历 `BaseDexClassLoader` 的 dexElements，对 `com.openai.*` 每个类反射检查字段/方法名。
但因为新增模块要写 `modules_config.db`，而 lspd 只在启动时读该库，配置变更不热加载 ——
要么重启设备，要么重启 lspd（后者在这台机器上会把守护进程弄挂）。

### 5.4 结论与替代方案

- 6 个功能的**能力开关**已经通过请求体注入拿到（第 1 节），这是可靠且已验证的路径。
- **UI 显隐**无法通过改字段名实现，需要**改写响应 JSON**：客户端把 `/backend-api/models`
  的 `product_features` 与 conversation 响应里的 `banner_info` 直接映射到界面。
- 下一步应做的是「反代响应改写器」，而不是继续找混淆类：

```
GET /backend-api/models        → 在 models[].product_features 里补 attachments/tools
POST /backend-api/conversation → 响应里把 banner_info 清空、blocked_features 置空
GET  /backend-api/me           → 补 features / entitlements 字段
```

这套改写不依赖任何类名，纯 JSON 层，改了立即生效，且能在 relay 的 `/backend-api/*` 透传分支里做。
