import fs from 'node:fs';
const [, , promptFile, outFile, modelArg] = process.argv;
if (!promptFile || !outFile) { console.log('用法: node gen.mjs <prompt.txt> <out.md> [model]'); process.exit(1); }
const prompt = fs.readFileSync(promptFile, 'utf8');
const model = modelArg || 'auto';
const r = await fetch('http://127.0.0.1:8788/v1/chat/completions', {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ model, stream: true, messages: [{ role: 'user', content: prompt }] }),
});
if (!r.ok) { console.log('HTTP', r.status, (await r.text()).slice(0, 300)); process.exit(1); }
const dec = new TextDecoder();
let buf = '', out = '';
const reader = r.body.getReader();
while (true) {
  const { done, value } = await reader.read();
  if (done) break;
  buf += dec.decode(value, { stream: true });
  const lines = buf.split('\n'); buf = lines.pop() || '';
  for (const line of lines) {
    if (!line.startsWith('data:')) continue;
    const p = line.slice(5).trim();
    if (p === '[DONE]') continue;
    try { out += JSON.parse(p).choices?.[0]?.delta?.content || ''; } catch {}
  }
}
fs.writeFileSync(outFile, out);
console.log('wrote', outFile, out.length, 'chars');
