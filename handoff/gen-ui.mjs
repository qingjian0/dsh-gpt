import fs from 'node:fs';
const prompt = fs.readFileSync('handoff/ui-prompt.txt', 'utf8');
const model = process.argv[2] || 'gpt-6';
const r = await fetch('http://127.0.0.1:8788/v1/chat/completions', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    model,
    stream: true,
    messages: [{ role: 'user', content: prompt }],
  }),
});
if (!r.ok) { console.log('HTTP', r.status, (await r.text()).slice(0, 300)); process.exit(1); }
const dec = new TextDecoder();
let buf = '', out = '';
const reader = r.body.getReader();
while (true) {
  const { done, value } = await reader.read();
  if (done) break;
  buf += dec.decode(value, { stream: true });
  const lines = buf.split('\n');
  buf = lines.pop() || '';
  for (const line of lines) {
    if (!line.startsWith('data:')) continue;
    const p = line.slice(5).trim();
    if (p === '[DONE]') continue;
    try { const j = JSON.parse(p); out += j.choices?.[0]?.delta?.content || ''; } catch {}
  }
}
fs.writeFileSync('handoff/ui-output.md', out);
const m = out.match(/```(?:kotlin|kt)\s*([\s\S]*?)```/);
const code = m ? m[1] : out;
fs.writeFileSync('handoff/MainActivity.kt', code.trim() + '\n');
console.log('chars:', out.length, '| code lines:', code.trim().split('\n').length);
