import test from 'node:test';
import assert from 'node:assert/strict';
import { request } from 'node:http';
import { createQuickJsSandbox, QUICKJS_MAX_INPUT_CHARS } from '../quickjs.mjs';
import { createBrowserService } from '../server.mjs';

const success = (value) => ({ success: true, value });
const failed = (error) => ({ success: false, error });

test('FU QuickJS evaluates modern syntax and preserves global completion and string content', async () => {
  const sandbox = createQuickJsSandbox();
  try {
    for (const [script, value] of [
      ["'正文中文 😀';", '正文中文 😀'], ["''", ''], ["'a\\u0000b'", 'a\u0000b'],
      ["const x={chapter:{title:'正文'}}; x?.chapter?.title ?? 'empty'", '正文'],
      ["let x='a'; if(true) x='b'; x;", 'b'],
      ["String(9007199254740993n + 7n)", '9007199254741000'],
      ["JSON.stringify(Array.from(new Uint8Array([1,2,255])))", '[1,2,255]'],
      ["JSON.stringify=()=> 'overridden'; 'actual string'", 'actual string'],
      ["'a\\ud800b'", 'a\ud800b'],
    ]) assert.deepEqual(await sandbox.evaluate(script), success(value));
  } finally { await sandbox.close(); }
});

test('FU QuickJS top-level await, completion Promises and pending tasks retain distinct results', async () => {
  const sandbox = createQuickJsSandbox();
  try {
    assert.deepEqual(await sandbox.evaluate("await Promise.resolve('ok')"), success('ok'));
    assert.deepEqual(await sandbox.evaluate("let x=''; Promise.resolve().then(()=>x='microtask'); await Promise.resolve(); x;"), success('microtask'));
    assert.deepEqual(await sandbox.evaluate("Promise.resolve('ok')"), success('Promise { <state>: "fulfilled" }'));
    for (const script of ["new Promise(()=>{})", "await new Promise(()=>{})"])
      assert.deepEqual(await sandbox.evaluate(script), success('Promise { <state>: "pending" }'));
    assert.deepEqual(await sandbox.evaluate("await Promise.reject('bad').catch(()=> 'ok')"), success('ok'));
    for (const script of ["await Promise.reject('bad')", "Promise.reject('bad')"])
      assert.deepEqual(await sandbox.evaluate(script), failed('EVALUATION_FAILED'));
  } finally { await sandbox.close(); }
});

test('FU QuickJS requires strings and does not mask syntax errors or coerce arbitrary values', async () => {
  const sandbox = createQuickJsSandbox();
  try {
    for (const script of ['42', 'true', 'null', 'undefined', '({value:"text"})', '["text"]', '1n', 'Symbol("x")', 'throw Error("failure")', 'const = invalid', 'return "illegal top-level return"'])
      assert.deepEqual(await sandbox.evaluate(script), failed('EVALUATION_FAILED'), script);
    assert.deepEqual(await sandbox.evaluate("String(42)"), success('42'));
  } finally { await sandbox.close(); }
});

test('FU QuickJS native rejection tracking catches detached async failures and respects handlers', async () => {
  const sandbox = createQuickJsSandbox();
  try {
    for (const script of ["Promise.reject(Error('bad')); 'ok'", "(async()=>{throw Error('bad')})(); 'ok'", "Promise.resolve().then(()=>{throw Error('bad')}); 'ok'"])
      assert.deepEqual(await sandbox.evaluate(script), failed('EVALUATION_FAILED'), script);
    for (const script of ["Promise.reject('bad').catch(()=>{}); 'ok'", "(async()=>{throw Error('bad')})().catch(()=>{}); 'ok'", "const p=Promise.reject('bad'); await Promise.resolve(); p.catch(()=>{}); 'ok'"])
      assert.deepEqual(await sandbox.evaluate(script), success('ok'), script);
    assert.deepEqual(await sandbox.evaluate("'recovered'"), success('recovered'));
  } finally { await sandbox.close(); }
});

test('QuickJS globals, builtins and source identities cannot leak between calls', async () => {
  const sandbox = createQuickJsSandbox();
  try {
    assert.deepEqual(await sandbox.evaluate("globalThis.saved='secret'; Array.prototype.shared='secret'; 'ok'"), success('ok'));
    assert.deepEqual(await sandbox.evaluate("[typeof saved,typeof [].shared].join('|')"), success('undefined|undefined'));
    const names = ['java','source','sourceApi','baseUrl','cookie','cache','Packages','appCtx','android','app','fetch','require','process','std','os','load','read'];
    assert.deepEqual(await sandbox.evaluate(`[${names.map((name)=>`typeof ${name}`).join(',')}].join('|')`), success(names.map(()=>'undefined').join('|')));
    assert.deepEqual(await sandbox.evaluate("await import('node:fs').then(()=> 'escaped',()=> 'blocked')"), success('blocked'));
    assert.deepEqual(await sandbox.evaluate("await import('std').then(()=> 'escaped',()=> 'blocked')"), success('blocked'));
  } finally { await sandbox.close(); }
});

test('QuickJS enforces the FU UTF-16 input and output boundaries and releases capacity', async () => {
  const sandbox = createQuickJsSandbox();
  try {
    assert.deepEqual(await sandbox.evaluate("'" + '中'.repeat(QUICKJS_MAX_INPUT_CHARS-2) + "'"), failed('OUTPUT_TOO_LARGE'));
    assert.deepEqual(await sandbox.evaluate("'ok';" + ' '.repeat(QUICKJS_MAX_INPUT_CHARS-5)), success('ok'));
    assert.deepEqual(await sandbox.evaluate(' '.repeat(QUICKJS_MAX_INPUT_CHARS+1)), failed('INPUT_TOO_LARGE'));
    assert.deepEqual(await sandbox.evaluate("'😀'.repeat(32768)"), success('😀'.repeat(32768)));
    assert.deepEqual(await sandbox.evaluate("'😀'.repeat(32769)"), failed('OUTPUT_TOO_LARGE'));
    assert.deepEqual(await sandbox.evaluate(null), failed('INPUT_READ_FAILED'));
    assert.equal(sandbox.active, 0);
  } finally { await sandbox.close(); }
});

test('QuickJS bounds CPU, microtask loops, stack and memory and recovers after failures', async () => {
  const sandbox = createQuickJsSandbox({ timeout: 1500 });
  try {
    for (const script of ['while(true){}', "function loop(){Promise.resolve().then(loop)} loop(); 'ok'"])
      assert.deepEqual(await sandbox.evaluate(script), failed('EVALUATION_TIMEOUT'));
    for (const script of ['function f(){return f()} f()', "new ArrayBuffer(128*1024*1024); 'ok'"])
      assert.deepEqual(await sandbox.evaluate(script), failed('EVALUATION_FAILED'));
    assert.deepEqual(await sandbox.evaluate("'recovered'"), success('recovered'));
    assert.equal(sandbox.active, 0);
  } finally { await sandbox.close(); }
});

test('QuickJS cancellation and concurrency leave the helper service usable', async () => {
  const sandbox = createQuickJsSandbox({ maxWorkers: 1 });
  const controller = new AbortController();
  try {
    const running = sandbox.evaluate('while(true){}', { signal: controller.signal });
    assert.equal(sandbox.active, 1);
    assert.deepEqual(await sandbox.evaluate("'busy'"), failed('BUSY'));
    controller.abort();
    assert.deepEqual(await running, failed('PROCESS_DIED'));
    assert.equal(sandbox.active, 0);
    assert.deepEqual(await sandbox.evaluate("'available'"), success('available'));
    await sandbox.close();
    assert.deepEqual(await sandbox.evaluate("'closed'"), failed('PROCESS_DIED'));
  } finally { await sandbox.close(); }
});

test('QuickJS HTTP endpoint uses the existing private token and works alongside browser execution', async () => {
  const service = await createBrowserService({ token: 'quickjs-private-fixture', channel: process.platform === 'win32' ? 'msedge' : undefined });
  const call = async (script, token='quickjs-private-fixture') => {
    const response = await fetch(`http://127.0.0.1:${service.port}/quickjs`, {
      method:'POST', headers:{'Content-Type':'application/json', Authorization:`Bearer ${token}`}, body:JSON.stringify({script}),
    });
    return { status:response.status, data:await response.json() };
  };
  try {
    assert.equal((await call("'secret'", 'wrong')).status,401);
    assert.deepEqual(await call("await Promise.resolve('actual QuickJS')"), {status:200,data:success('actual QuickJS')});
    assert.deepEqual((await call('42')).data,failed('EVALUATION_FAILED'));
    assert.deepEqual((await call("'after failure'")).data,success('after failure'));
    const encoded = Buffer.from(JSON.stringify({ script: "'完整正文 😀'" }));
    const cut = encoded.indexOf(Buffer.from('正'))+1;
    const segmented = await new Promise((resolve, reject) => {
      const client = request(`http://127.0.0.1:${service.port}/quickjs`, {
        method:'POST', headers:{'Content-Type':'application/json',Authorization:'Bearer quickjs-private-fixture'},
      }, (response) => {
        const chunks=[]; response.on('data',(chunk)=>chunks.push(chunk));
        response.on('end',()=>resolve(JSON.parse(Buffer.concat(chunks).toString())));
      });
      client.on('error',reject); client.write(encoded.subarray(0,cut));
      setTimeout(()=>client.end(encoded.subarray(cut)),10);
    });
    assert.deepEqual(segmented,success('完整正文 😀'));
    const health = await fetch(`http://127.0.0.1:${service.port}/health`).then((response)=>response.json());
    assert.equal(health.quickJsWorkers,0); assert.equal(health.sessions,0);
  } finally { await service.close(); }
});
