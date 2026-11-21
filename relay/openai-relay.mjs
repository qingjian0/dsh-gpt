#!/usr/bin/env node
/**
 * OpenAI 兼容反代出口（本地服务）
 *   node openai-relay.mjs --kit ./kit --port 8788 [--key SK-xxx]
 * 路由: GET /v1/models, POST /v1/chat/completions(流式/非流式), ANY /backend-api/* 透传, GET /health
 */
import fs from 'node:fs';
import http from 'node:http';
import https from 'node:https';
import path from 'node:path';
import crypto from 'node:crypto';

const argv = process.argv.slice(2);
const opt = (k, d) => { const i = argv.indexOf('--' + k); return i >= 0 ? argv[i + 1] : d; };
const KIT = path.resolve(opt('kit', './kit'));
const PORT = parseInt(opt('port', '8788'), 10);
const KEY = opt('key', '');
const BASE = 'https://android.chat.openai.com';
const PROXY = process.env.HTTPS_PROXY || process.env.https_proxy || '';

const auth = JSON.parse(fs.readFileSync(path.join(KIT, 'auth.json'), 'utf8'));
const dev = JSON.parse(fs.readFileSync(path.join(KIT, 'device.json'), 'utf8'));
const claims = JSON.parse(fs.readFileSync(path.join(KIT, 'claims.json'), 'utf8'));
const SESSIONS = new Map(); // key -> {conversationId, parentId, updatedAt}
const MODELS = ['auto', 'gpt-6', 'gpt-6-mini', 'gpt-5-6-mini', 'research'];

function headers() {
  return {
    'Authorization': 'DPoP ' + auth.access_token,
    'oai-did': dev.installation_id || '',
    'oai-session-id': claims.session_id || '',
    'User-Agent': 'ChatGPT/1.2026.321 (Android 15; 22081212C)',
    'Accept-Language': 'zh-CN,zh;q=0.9',
    'Content-Type': 'application/json',
  };
}

function upstream(method, p, body, accept) {
  return new Promise((resolve, reject) => {
    const u = new URL(BASE + p);
    const h = { ...headers(), Accept: accept };
    const payload = body ? Buffer.from(typeof body === 'string' ? body : JSON.stringify(body)) : null;
    if (payload) h['Content-Length'] = payload.length;
    const req = https.request({ hostname: u.hostname, port: 443, path: u.pathname + u.search, method, headers: h },
      res => resolve(res));
    req.on('error', reject);
    req.setTimeout(120000, () => req.destroy(new Error('timeout')));
    if (payload) req.write(payload);
    req.end();
  });
}

const textOf = (c) => {
  if (typeof c === 'string') return c;
  if (Array.isArray(c)) return c.map(x => typeof x === 'string' ? x : (x?.text || '')).join('');
  if (c?.parts) return c.parts.join('');
  return '';
};

function toConversation(body, sess) {
  const msgs = body.messages || [];
  let sys = '', hist = '', last = '';
  for (const m of msgs) {
    const t = textOf(m.content);
    if (!t) continue;
    if (m.role === 'system') sys += t + '\n';
    else if (m.role === 'assistant') hist += 'Assistant: ' + t + '\n';
  }
  for (let i = msgs.length - 1; i >= 0; i--) {
    if (msgs[i].role !== 'system' && msgs[i].role !== 'assistant') { last = textOf(msgs[i].content); break; }
  }
  const payload = {
    action: 'next',
    messages: [{ id: crypto.randomUUID(), author: { role: 'user' }, content: { content_type: 'text', parts: [sys + hist + last] } }],
    parent_message_id: crypto.randomUUID(),
    model: body.model && body.model !== '' ? body.model : 'auto',
    conversation_mode: { kind: 'primary_assistant' },
    timezone_offset_min: -480,
    history_and_training_disabled: false,
  };
  if (sess && sess.conversationId) {
    payload.conversation_id = sess.conversationId;
    if (sess.parentId) payload.parent_message_id = sess.parentId;
  }
  return payload;
}

const chunk = (id, model, delta, finish = null) => JSON.stringify({
  id, object: 'chat.completion.chunk', created: (Date.now() / 1000) | 0, model,
  choices: [{ index: 0, delta: delta ? { content: delta } : {}, finish_reason: finish }]
});

function convMeta(line) {
  if (!line.startsWith('data:')) return null;
  const p = line.slice(5).trim();
  if (!p || p === '[DONE]') return null;
  try { const o = JSON.parse(p); return { conversationId: o.conversation_id, messageId: o.message?.id, role: o.message?.author?.role }; }
  catch { return null; }
}

function deltaOf(line, sent) {
  if (!line.startsWith('data:')) return null;
  const p = line.slice(5).trim();
  if (!p || p === '[DONE]') return null;
  let o; try { o = JSON.parse(p); } catch { return null; }
  const msg = o.message;
  if (!msg || msg.author?.role !== 'assistant') return null;
  const full = (msg.content?.parts || []).join('');
  if (!full || full.length <= sent.length) return null;
  return full.slice(sent.length);
}

const send = (res, code, obj) => {
  const b = Buffer.from(JSON.stringify(obj));
  res.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': b.length });
  res.end(b);
};

http.createServer(async (req, res) => {
  try {
    if (KEY && (req.headers.authorization || '').replace(/^Bearer\s+/i, '') !== KEY) {
      return send(res, 401, { error: { type: 'invalid_api_key', message: 'bad key' } });
    }
    if (req.url === '/health') return send(res, 200, { ok: true, port: PORT, models: MODELS });
    if (req.url === '/v1/sessions') {
      const out = {}; for (const [k, v] of SESSIONS) out[k] = v;
      return send(res, 200, out);
    }
    if (req.url === '/v1/models') {
      return send(res, 200, { object: 'list', data: MODELS.map(id => ({ id, object: 'model', created: 0, owned_by: 'openai-session' })) });
    }
    const chunks = [];
    for await (const c of req) chunks.push(c);
    const raw = chunks.length ? Buffer.concat(chunks).toString('utf8') : '';

    if (req.method === 'POST' && req.url.startsWith('/v1/chat/completions')) {
      const body = JSON.parse(raw || '{}');
      const stream = !!body.stream;
      const model = body.model || 'auto';
      const id = 'chatcmpl-' + crypto.randomUUID().replace(/-/g, '').slice(0, 24);
      const key = body.user || body.model || 'default';
      const sess = SESSIONS.get(key);
      const up = await upstream('POST', '/backend-api/conversation', toConversation(body, sess), 'text/event-stream');
      const track = { conversationId: sess?.conversationId, parentId: sess?.parentId };
      if (up.statusCode >= 400) {
        let e = ''; for await (const c of up) e += c;
        return send(res, up.statusCode, JSON.parse(e || '{}'));
      }
      let sent = '', acc = '';
      const queue = [];
      let lineBuf = '';
      const dec = (b) => {
        lineBuf += b.toString('utf8');
        const lines = lineBuf.split('\n');
        lineBuf = lines.pop() || '';
        for (const line of lines) {
          const meta = convMeta(line);
          if (meta) {
            if (meta.conversationId) track.conversationId = meta.conversationId;
            if (meta.role === 'assistant' && meta.messageId) track.parentId = meta.messageId;
            else if (meta.role === 'user' && meta.messageId && !track.parentId) track.parentId = meta.messageId;
          }
          const d = deltaOf(line, sent); if (d) { sent += d; acc += d; queue.push(d); }
        }
      };
      if (stream) {
        res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
        let done = false;
        up.on('data', b => { dec(b); while (queue.length) res.write(`data: ${chunk(id, model, queue.shift())}\n\n`); });
        await new Promise(r => up.on('end', r));
        if (track.conversationId) SESSIONS.set(key, { ...track, updatedAt: Date.now() });
        res.write(`data: ${chunk(id, model, '', 'stop')}\n\n`);
        res.write('data: [DONE]\n\n');
        res.end();
      } else {
        for await (const b of up) dec(b);
        if (track.conversationId) SESSIONS.set(key, { ...track, updatedAt: Date.now() });
        send(res, 200, {
          id, object: 'chat.completion', created: (Date.now() / 1000) | 0, model,
          choices: [{ index: 0, message: { role: 'assistant', content: acc }, finish_reason: 'stop' }],
        });
      }
      return;
    }
    if (req.url.startsWith('/backend-api')) {
      const up = await upstream(req.method, req.url, raw || null, req.headers.accept || 'application/json');
      res.writeHead(up.statusCode, { 'Content-Type': up.headers['content-type'] || 'application/json' });
      up.pipe(res);
      return;
    }
    send(res, 404, { error: { type: 'not_found', message: 'route not found' } });
  } catch (e) {
    try { send(res, 502, { error: { type: 'relay_error', message: String(e.message || e) } }); } catch { }
  }
}).listen(PORT, '0.0.0.0', () => console.log(`openai-relay listening http://0.0.0.0:${PORT}/v1`));
