#!/usr/bin/env node
// gpt-hook 动态配置 CLI：改完自动热加载（hook 进程 2s 内轮询 mtime）
import fs from 'node:fs';

const CFG = '/data/data/com.openai.chatgpt/files/gpt-hook.json';
const TARGET = 'com.openai.chatgpt';

function load() {
  let raw;
  try { raw = fs.readFileSync(CFG, 'utf8'); }
  catch { raw = fs.readFileSync(new URL('../app/src/main/assets/gpt-hook.default.json', import.meta.url), 'utf8'); }
  const j = JSON.parse(raw);
  j.hooks ??= []; j.fields ??= []; j.noop ??= [];
  return j;
}
function save(j) {
  fs.writeFileSync(CFG, JSON.stringify(j, null, 2));
  console.log('saved', CFG, 'hooks=' + j.hooks.length);
  console.log('热加载：目标应用 2 秒内自动生效；必要时 am force-stop ' + TARGET);
}

const [cmd, ...a] = process.argv.slice(2);
const j = load();

switch (cmd) {
  case 'show':
    console.log(JSON.stringify(j, null, 2));
    break;
  case 'add': {
    const h = JSON.parse(a.join(' '));
    j.hooks.push(h);
    save(j);
    break;
  }
  case 'noop': {
    j.noop.push(a[0] + '.' + a[1]);
    save(j);
    console.log('noop', a[0] + '.' + a[1]);
    break;
  }
  case 'field': {
    j.fields.push({ class: a[0], name: a[1], value: JSON.parse(a[2] ?? 'true'), static: true });
    save(j);
    break;
  }
  case 'ret': {  // ret <class> <method> <jsonvalue> [mode]
    j.hooks.push({ class: a[0], method: a[1], mode: a[3] || 'return', result: JSON.parse(a[2] ?? 'true') });
    save(j);
    break;
  }
  case 'gateway': {
    j.gateway = a[0] !== 'off';
    save(j);
    console.log('gateway=' + j.gateway + ' (需 force-stop 目标后重启生效)');
    break;
  }
  case 'capture': {
    j.capture = a[0] !== 'off';
    save(j);
    console.log('capture=' + j.capture);
    break;
  }
  case 'inspect': {
    j.hooks.push({ class: a[0], method: 'inspect', mode: 'inspect' });
    save(j);
    console.log('inspect', a[0], '—— 重启目标后看 files/gpt-hook.log 的类结构');
    break;
  }
  case 'rm': {
    const i = parseInt(a[0], 10);
    const removed = j.hooks.splice(i, 1);
    save(j);
    console.log('removed', JSON.stringify(removed));
    break;
  }
  case 'scan': {
    j.scan_on_start = a[0] !== 'off';
    save(j);
    console.log('scan_on_start=' + j.scan_on_start + ' —— 下次启动应用会把类名表写到 files/class-scan.txt');
    break;
  }
  case 'log': {
    console.log(fs.readFileSync('/data/data/' + TARGET + '/files/gpt-hook.log', 'utf8').split('\n').slice(-80).join('\n'));
    break;
  }
  case 'cat': {
    console.log(fs.readFileSync('/data/data/' + TARGET + '/files/class-scan.txt', 'utf8').split('\n').filter(l => l.includes(a[0] || '')).slice(0, 200).join('\n'));
    break;
  }
  default:
    console.log(`用法:
  show                       查看当前配置
  add '<json>'               追加 hook，例: add '{"class":"a.b.C","method":"d","mode":"dump","stack":true}'
  noop <class> <method>      直接置空
  field <class> <name> <v>   改静态字段
  ret <class> <method> <v>   强制返回
  rm <index>                 删除第 n 个 hook
  scan on|off                启动时枚举类名
  log                        看 hook 日志尾部
  cat <keyword>              过滤 class-scan.txt`);
}
