# 反代会话复用（已实测）

## 结论

ChatGPT backend 支持续聊，只要在 `POST /backend-api/conversation` 的 body 里带上：

```json
{
  "conversation_id": "<上一次返回的 conversation_id>",
  "parent_message_id": "<上一条 assistant 消息的 message.id>",
  "messages": [ ... 本次新增的一条 user ... ]
}
```

不带这两个字段 = 每次全新对话（这正是之前的现象）。

## 实测

```
第 1 轮: "记住数字47，只回复收到"  → "收到"
第 2 轮: "我刚才让你记的数字是多少？" → "47"
sessions: {"t1":{"conversationId":"6b01a5ac-cab4-83e9-b7a7-3d991c528736",
                "parentId":"5134fc86-f784-4709-951c-69362f1bb914"}}
```

## 实现要点（见 tools/openai-relay.mjs）

1. 维护 `SESSIONS: Map<key, {conversationId, parentId, updatedAt}>`。
2. `key` 取 OpenAI 请求体的 `user` 字段，缺省用 `model`。
3. 每次请求前，若命中会话就把 `conversation_id` / `parent_message_id` 塞进 payload。
4. 流式解析时从每条 `data:` 里取 `conversation_id` 与 `message.id`，assistant 的 id 作为下一次的 parent。
5. `GET /v1/sessions` 可直接查看当前会话表。

## Kotlin 侧要做的对应改动

`LocalApiServer.chat()` 里：

- 增加 `private val sessions = ConcurrentHashMap<String, String[]>()`（key → [conversationId, parentId]）
- `OpenAiCompat.toConversation()` 增加两个可选参数并写入 payload
- `extractDelta` 同一条 JSON 里顺带取 `conversation_id` / `message.id` 并回写
- key 同样用 OpenAI 的 `user` 字段

客户端用法不变，只要在请求里带稳定的 `user` 就能自动续聊。
