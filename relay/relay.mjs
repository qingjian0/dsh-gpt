#!/usr/bin/env node
/**
 * 独立反代出口（不依赖手机网关）：直连 android.chat.openai.com
 * 用法:
 *   node relay.mjs --kit ./kit --get /backend-api/me
 *   node relay.mjs --kit ./kit --post /backend-api/conversation --body '{"action":"next",...}'
 *   node relay.mjs --kit ./kit --serve 8788     # 起一个本地反代服务
 * 依赖: kit/auth.json + kit/device.json（由 kit.mjs 生成）
 * 环境: HTTPS_PROXY / https_proxy 可选（走企业/机场代理）
 */
import fs from 'node:fs';
import https from 'node:https';
import http from 'node:http';
import path from 'node:path';
import crypto from 'node:crypto';

const argv = process.argv.slice(2);
const opt = (k, d) => { const i = argv.indexOf('--' + k); return i >= 0 ? argv[i + 1] : d; };
const KIT = path.resolve(opt('kit', './kit'));
const BASE = 'https://android.chat.openai.com';

const auth = JSON.parse(fs.readFileSync(path.join(KIT, 'auth.json'), 'utf8'));
const dev = JSON.parse(fs.readFileSync(path.join(KIT, 'device.json'), 'utf8'));
const claims = JSON.parse(fs.readFileSync(path.join(KIT, 'claims.json'), 'utf8'));

const PROXY = process.env.HTTPS_PROXY || process.env.https_proxy || '';

function headers(extra = {}) {
  return {
    'Authorization': 'DPoP ' + auth.access_token,
    'oai-did': dev.installation_id || '',
    'oai-session-id': claims.session_id || '',
    'Accept': 'application/json',
    'Accept-Language': 'zh-CN,zh;q=0.9',
    'User-Agent': 'ChatGPT/1.2026.321 (Android 15; 22081212C)',
    'Content-Type': 'application/json',
    ...extra
  };
}

function proxyAgent(target) {
  if (!PROXY) return undefined;
  const u = new URL(PROXY);
  const mod = u.protocol === 'https:' ? https : http;
  return new mod.Agent({ keepAlive: false });
}

function request(method, p, body, extraHeaders = {}) {
  return new Promise((resolve, reject) => {
    const u = new URL(BASE + (p.startsWith('/backend') ? p : '/backend-api' + p));
    const h = headers(extraHeaders);
    const payload = body ? Buffer.from(typeof body === 'string' ? body : JSON.stringify(body)) : null;
    if (payload) h['Content-Length'] = payload.length;
    const req = https.request({
      hostname: u.hostname, port: 443, path: u.pathname + u.search, method, headers: h,
      agent: proxyAgent(u)
    }, res => resolve(res));
    req.on('error', reject);
    req.setTimeout(120000, () => req.destroy(new Error('timeout')));
    if (payload) req.write(payload);
    req.end();
  });
}

async function main() {
  const serve = opt('serve');
  if (serve) {
    http.createServer(async (req, res) => {
      try {
        const chunks = [];
        for await (const c of req) chunks.push(c);
        const body = chunks.length ? Buffer.concat(chunks) : null;
        const r = await request(req.method, req.url, body, { Accept: req.headers.accept || 'application/json' });
        res.writeHead(r.statusCode, { 'Content-Type': r.headers['content-type'] || 'application/json' });
        r.pipe(res);
      } catch (e) {
        res.writeHead(502, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: String(e) }));
      }
    }).listen(parseInt(serve, 10), '127.0.0.1');
    console.log('relay listening http://127.0.0.1:' + serve);
    return;
  }
  const get = opt('get'), post = opt('post');
  if (!get && !post) { console.log('用法: --get PATH | --post PATH --body JSON | --serve PORT'); process.exit(1); }
  const r = await request(post ? 'POST' : 'GET', get || post, post ? (opt('body') || '{}') : null,
    { Accept: (post || '').includes('conversation') ? 'text/event-stream' : 'application/json' });
  console.log('HTTP', r.statusCode);
  r.pipe(process.stdout);
}
main().catch(e => { console.error('ERR', e.message); process.exit(1); });
