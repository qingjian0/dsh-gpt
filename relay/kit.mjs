#!/usr/bin/env node
/**
 * GPT 反代要素采集器（只读）
 * 产出 kit/{auth,claims,device}.json + endpoints.txt + headers.txt + README.md
 */
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

const HOME = process.env.HOME || '/data/user/0/dsh.dsh.mobile/files/dsh-home';
const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const KIT = path.join(ROOT, 'kit');
const APPDIR = '/data/data/com.openai.chatgpt';
const DEXDIR = path.join(path.dirname(ROOT), 'rev', 'dex');
fs.mkdirSync(KIT, { recursive: true });

const w = (n, s) => { fs.writeFileSync(path.join(KIT, n), s); console.log('  →', n, s.length + 'B'); };

/* ---------- 1. auth0 凭证 ---------- */
function parseXml(xml) {
  const out = {};
  const re = /<(string|long|int|boolean)\s+name="([^"]+)"(?:\s+value="([^"]*)")?\s*>([\s\S]*?)<\/\1>|<(long|int|boolean)\s+name="([^"]+)"\s+value="([^"]*)"\s*\/>/g;
  let m;
  while ((m = re.exec(xml))) {
    if (m[1] && m[2] !== undefined) {
      const raw = (m[4] || '').replace(/&quot;/g, '"').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&apos;/g, "'");
      out[m[2]] = m[3] !== undefined && m[3] !== '' ? m[3] : raw.trim();
    } else if (m[6]) out[m[6]] = m[7];
  }
  return out;
}

const authXml = path.join(APPDIR, 'shared_prefs/com.auth0.authentication.storage.xml');
let prefs = {};
try { prefs = parseXml(fs.readFileSync(authXml, 'utf8')); }
catch (e) { console.error('auth0 xml 读取失败:', e.message); }

const sess = Object.keys(prefs).find(k => k.endsWith('.com.auth0.access_token'));
const pfx = sess ? sess.slice(0, sess.lastIndexOf('.com.auth0.access_token')) : '';
const g = k => prefs[pfx + '.' + k];

const b64 = s => JSON.parse(Buffer.from(String(s).replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf8'));
function jwt(tok) {
  try { const p = String(tok).split('.'); return { header: b64(p[0]), payload: b64(p[1]) }; }
  catch { return null; }
}

const auth = {
  source: authXml,
  session_prefix: pfx,
  access_token: g('com.auth0.access_token'),
  id_token: g('com.auth0.id_token'),
  refresh_token: g('com.auth0.refresh_token'),
  token_type: g('com.auth0.token_type'),
  scope: g('com.auth0.scope'),
  expires_at_ms: Number(g('com.auth0.expires_at') || 0),
  earliest_refresh_at_ms: Number(g('earliest_refresh_at') || 0),
  user_id: g('user_id'),
  key_pair_alias: g('key_pair_alias'),
  workspace_tokens: g('workspace_tokens'),
  auth_session_logging_id: g('auth_session_logging_id'),
};
w('auth.json', JSON.stringify(auth, null, 2));

const at = jwt(auth.access_token), it = jwt(auth.id_token);
const claims = {
  access: at && at.payload,
  id: it && it.payload,
  dpop_cnf_jkt: at?.payload?.cnf?.jkt,
  client_id: at?.payload?.client_id,
  issuer: at?.payload?.iss,
  audience: at?.payload?.aud,
  scopes: at?.payload?.scp,
  chatgpt_account_id: at?.payload?.['https://api.openai.com/auth']?.chatgpt_account_id,
  chatgpt_user_id: at?.payload?.['https://api.openai.com/auth']?.chatgpt_user_id,
  plan_type: at?.payload?.['https://api.openai.com/auth']?.chatgpt_plan_type,
  orgs: it?.payload?.['https://api.openai.com/auth']?.organizations,
  iat: at?.payload?.iat, exp: at?.payload?.exp, jti: at?.payload?.jti,
  session_id: at?.payload?.session_id,
};
w('claims.json', JSON.stringify(claims, null, 2));

/* ---------- 2. 设备标识 ---------- */
const readSafe = (p) => { try { return fs.readFileSync(p, 'utf8').trim(); } catch { return null; } };
const device = {
  installation_id: readSafe(path.join(APPDIR, 'files/INSTALLATION')),   // oai-did 候选
  firebase_app_id: null,
  fcm_token: null,
  android_id: null,
  
  session_id_from_jwt: claims.session_id,
  build: { model: readProp('ro.product.model'), manufacturer: readProp('ro.product.manufacturer'), sdk: readProp('ro.build.version.sdk') },
};
try {
  const p = parseXml(fs.readFileSync(path.join(APPDIR, 'shared_prefs/com.google.android.gms.appid.xml'), 'utf8'));
  const v = Object.values(p)[0];
  if (v) { const j = JSON.parse(v); device.fcm_token = j.token; device.firebase_app_id = j.appVersion; }
} catch {}
try {
  const sec = execFileSync('sh', ['-c', `sqlite3 /data/data/com.openai.chatgpt/files/datastore/*.preferences_pb 2>/dev/null | head -1`], { encoding: 'utf8' });
} catch {}

function readProp(k) {
  try { return execFileSync('getprop', [k], { encoding: 'utf8' }).trim(); } catch { return null; }
}
w('device.json', JSON.stringify(device, null, 2));

/* ---------- 3. 端点 ---------- */
let dexFiles = [];
try { dexFiles = fs.readdirSync(DEXDIR).filter(f => f.endsWith('.dex')).map(f => path.join(DEXDIR, f)); } catch {}
let urls = [], paths = [], headers = [];
if (dexFiles.length) {
  const rg = (re) => execFileSync('rg', ['-a', '-o', '--no-filename', re, ...dexFiles], { encoding: 'utf8', maxBuffer: 64 << 20 });
  urls = [...new Set(rg('https?://[a-zA-Z0-9._-]+[a-zA-Z0-9._/-]*').split('\n').filter(Boolean))].sort();
  paths = [...new Set(rg('/backend-(?:api|anon)/[a-zA-Z0-9/_{}.-]{2,80}').split('\n').filter(Boolean))].sort();
  headers = [...new Set(rg('(?:oai|x-oai|x-openai)-[a-z0-9-]{3,40}').split('\n').filter(Boolean))].sort();
}
const byHost = {};
for (const u of urls) { const h = u.split('/')[2]; (byHost[h] ||= []).push(u); }
w('endpoints.txt',
  `# hosts: ${Object.keys(byHost).length}  urls: ${urls.length}  backend paths: ${paths.length}\n\n` +
  Object.entries(byHost).filter(([h]) => /openai|chatgpt/.test(h)).map(([h, v]) => `## ${h}\n${v.join('\n')}`).join('\n\n') +
  `\n\n## backend paths\n${paths.join('\n')}\n`);
w('headers.txt', headers.join('\n') + '\n');

/* ---------- 4. 说明 ---------- */
const expIso = auth.expires_at_ms ? new Date(auth.expires_at_ms).toISOString() : '?';
w('README.md', `# GPT 反代要素包

生成时间: ${new Date().toISOString()}

## 1 认证
- 类型: Auth0 + **DPoP**（Authorization 头为 \`DPoP <jwt>\`，不是 Bearer）
- client_id: \`${claims.client_id}\`
- issuer: \`${claims.issuer}\`  audience: \`${JSON.stringify(claims.audience)}\`
- access_token 过期: ${expIso}
- refresh_token 最早可刷新: ${auth.earliest_refresh_at_ms ? new Date(auth.earliest_refresh_at_ms).toISOString() : '?'}
- DPoP 绑定指纹 cnf.jkt: \`${claims.dpop_cnf_jkt}\`
- Keystore alias: \`${auth.key_pair_alias}\` —— **私钥不可导出**，proof 只能由 ChatGPT 进程现场签

## 2 账户
- user_id: \`${claims.chatgpt_user_id || auth.user_id}\`
- account_id: \`${claims.chatgpt_account_id}\`
- plan: \`${claims.plan_type}\`
- session_id: \`${claims.session_id}\`

## 3 端点
见 endpoints.txt。核心:
- https://android.chat.openai.com/backend-api/
- https://android.chat.openai.com/backend-anon/
- https://auth.openai.com/oauth/token

## 4 请求头
见 headers.txt。必备: Authorization(DPoP) / DPoP / oai-did / oai-client-auth-session / oai-session-id

## 5 设备
- oai-did 候选: \`${device.installation_id}\`（files/INSTALLATION）
- FCM token 已导出（device.json）

## 6 结论
反代必须走「设备内签 proof」：离线无法伪造 DPoP。见 hooks/Gateway.java。
`);
console.log('kit 完成 →', KIT);
