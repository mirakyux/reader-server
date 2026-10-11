import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createBrowserService } from '../server.mjs';

// Lossless 2 x 2 WebP containing opaque RGB(224, 16, 32) pixels.
const webpFixture = 'UklGRh4AAABXRUJQVlA4TBEAAAAvAUAAAAdQiIIXpP+BiOh/AAA=';
const imageCall = async (service, input, token = 'image-fixture-token') => {
  const response = await fetch(`http://127.0.0.1:${service.port}/decode-image`, { method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: JSON.stringify(input) });
  return { status: response.status, data: await response.json() };
};

test('memory-only WebP decoding returns a real PNG and rejects unauthenticated or invalid images', async () => {
  let networkRequests = 0;
  const fixture = createServer((req, res) => { networkRequests++; res.end('external image must not be requested'); });
  await new Promise((resolve) => fixture.listen(0, '127.0.0.1', resolve));
  const service = await createBrowserService({ token: 'image-fixture-token', channel: process.platform === 'win32' ? 'msedge' : undefined });
  try {
    assert.equal((await imageCall(service, { base64: webpFixture }, 'wrong')).status, 401);
    const converted = await imageCall(service, { base64: webpFixture });
    assert.equal(converted.status, 200);
    assert.equal(typeof converted.data.base64, 'string');
    assert.ok(!converted.data.base64.startsWith('data:'));
    const png = Buffer.from(converted.data.base64, 'base64');
    assert.deepEqual(png.subarray(0, 8), Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
    assert.equal(png.toString('ascii', 12, 16), 'IHDR');
    assert.equal(png.readUInt32BE(16), 2);
    assert.equal(png.readUInt32BE(20), 2);
    for (const base64 of ['', 'not base64!', 'data:image/webp;base64,' + webpFixture, Buffer.from('not an image').toString('base64')]) {
      const failed = await imageCall(service, { base64 });
      assert.equal(failed.status, 400);
      assert.match(failed.data.error, /image|base64/i);
    }
    assert.equal((await imageCall(service, { url: `http://127.0.0.1:${fixture.address().port}/external.png` })).status, 400);
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="2" height="2"><image href="http://127.0.0.1:${fixture.address().port}/external.png" width="2" height="2"/></svg>`;
    assert.equal((await imageCall(service, { base64: Buffer.from(svg).toString('base64') })).status, 400);
    assert.equal(networkRequests, 0);
    assert.equal((await imageCall(service, { base64: webpFixture })).status, 200);
    assert.equal((await fetch(`http://127.0.0.1:${service.port}/health`).then((response) => response.json())).sessions, 0);
    const rendered = await fetch(`http://127.0.0.1:${service.port}/execute`, {
      method: 'POST', headers: { Authorization: 'Bearer image-fixture-token', 'Content-Type': 'application/json' },
      body: JSON.stringify({
        url: `http://127.0.0.1:${fixture.address().port}/pixel-check`, html: '<html><title>PNG pixels</title></html>',
        js: `(async () => { const image = await createImageBitmap(new Blob([Uint8Array.from(atob("${converted.data.base64}"), c => c.charCodeAt(0))])); const canvas = document.createElement("canvas"); canvas.width = image.width; canvas.height = image.height; const context = canvas.getContext("2d"); context.drawImage(image, 0, 0); return Array.from(context.getImageData(0, 0, 2, 2).data); })()`,
      }),
    }).then((response) => response.json());
    assert.deepEqual(JSON.parse(rendered.body), Array(4).fill([224, 16, 32, 255]).flat());
  } finally { await service.close(); await new Promise((resolve) => fixture.close(resolve)); }
});

test('image decoding limits encoded bytes, dimensions and total pixels', async () => {
  const service = await createBrowserService({ token: 'image-fixture-token', channel: process.platform === 'win32' ? 'msedge' : undefined });
  try {
    const oversized = await imageCall(service, { base64: Buffer.alloc(2 * 1024 * 1024 + 1).toString('base64') });
    assert.equal(oversized.status, 400);
    assert.match(oversized.data.error, /too large/i);
    // Real uniformly colored WebP fixtures, dimensions 8193 x 1 and 4097 x 4097.
    for (const base64 of ['UklGRh4AAABXRUJQVlA4TBEAAAAvACAAAAdQiIIXpP+BiOh/AAA=', 'UklGRh4AAABXRUJQVlA4TBEAAAAvABAABAdQiIIXpP+BiOh/AAA=']) {
      const failed = await imageCall(service, { base64 });
      assert.equal(failed.status, 400);
      assert.match(failed.data.error, /dimensions|pixels/i);
    }
    assert.equal((await imageCall(service, { base64: webpFixture })).status, 200);
  } finally { await service.close(); }
});

test('image decoding shares the session and in-flight browser concurrency limit', async () => {
  const fixture = createServer((req, res) => { res.setHeader('Content-Type', 'text/html'); res.end('<title>Capacity fixture</title>'); });
  await new Promise((resolve) => fixture.listen(0, '127.0.0.1', resolve));
  const service = await createBrowserService({ token: 'image-fixture-token', channel: process.platform === 'win32' ? 'msedge' : undefined, maxSessions: 1 });
  const sessionCall = async (path, input) => {
    const response = await fetch(`http://127.0.0.1:${service.port}${path}`, { method: 'POST', headers: { Authorization: 'Bearer image-fixture-token', 'Content-Type': 'application/json' }, body: JSON.stringify(input) });
    return { status: response.status, data: await response.json() };
  };
  try {
    const opened = await sessionCall('/session', { url: `http://127.0.0.1:${fixture.address().port}` });
    assert.equal(opened.status, 200);
    const blocked = await imageCall(service, { base64: webpFixture });
    assert.equal(blocked.status, 400);
    assert.match(blocked.data.error, /concurrency limit/i);
    await sessionCall('/close', { id: opened.data.id });
    const simultaneous = await Promise.all([imageCall(service, { base64: webpFixture }), imageCall(service, { base64: webpFixture })]);
    assert.deepEqual(simultaneous.map((item) => item.status).sort(), [200, 400]);
    assert.match(simultaneous.find((item) => item.status === 400).data.error, /concurrency limit/i);
    assert.equal((await imageCall(service, { base64: webpFixture })).status, 200);
  } finally { await service.close(); await new Promise((resolve) => fixture.close(resolve)); }
});

test('real DOM execution, protected access, cookies, web storage, resource capture and interactive login', async () => {
  const fixture = createServer((req, res) => {
    if (req.url === '/login') { res.setHeader('Content-Type', 'text/html'); res.end('<input id="name"><button id="login" onclick="document.cookie=\'session=authenticated; path=/\';localStorage.setItem(\'auth\',\'yes\');document.title=\'Logged in\'">Login</button>'); }
    else if (req.url === '/refetch') { res.setHeader('Content-Type', 'text/html'); res.end(`<p>Refetched with ${req.headers.cookie || ''}</p>`); }
    else if (req.url === '/asset.png') { res.end('fixture'); }
    else { res.setHeader('Content-Type', 'text/html'); res.end('<p id="value">before</p><script>document.getElementById("value").textContent="dynamic"</script>'); }
  });
  await new Promise((resolve) => fixture.listen(0, '127.0.0.1', resolve));
  const url = `http://127.0.0.1:${fixture.address().port}`;
  const service = await createBrowserService({ token: 'fixture-token', channel: process.platform === 'win32' ? 'msedge' : undefined });
  const call = async (path, input, token = 'fixture-token') => { const response = await fetch(`http://127.0.0.1:${service.port}${path}`, { method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: JSON.stringify(input) }); return { status: response.status, data: await response.json() }; };
  try {
    assert.equal((await call('/execute', { url }, 'wrong')).status, 401);
    let result = await call('/execute', { url, js: 'document.querySelector("#value").textContent' });
    assert.equal(result.data.body, 'dynamic');
    result = await call('/execute', { url, headers: { Cookie: 'manual=header-value' }, js: 'document.cookie' });
    assert.equal(result.data.body, 'manual=header-value');
    result = await call('/execute', { url, html: '<script>document.cookie="fixture=one; path=/";localStorage.setItem("key","stored")</script>', js: 'document.cookie' });
    assert.match(result.data.body, /fixture=one/);
    const persisted = result.data.storageState;
    result = await call('/execute', { url, storageState: persisted, js: 'localStorage.getItem("key")+":"+document.cookie' });
    assert.equal(result.data.body, 'stored:fixture=one');
    result = await call('/execute', { url, js: 'document.cookie' }); assert.equal(result.data.body, '');
    result = await call('/execute', { url, sourceRegex: 'asset\\.png', js: 'new Image().src="/asset.png"' });
    assert.equal(result.data.body, `${url}/asset.png`);
    const opened = await call('/session', { url: `${url}/login` }); assert.ok(opened.data.screenshot);
    const id = opened.data.id;
    await call('/action', { id, action: 'click', x: 222, y: 18 });
    result = await call('/close', { id });
    assert.ok(result.data.storageState.cookies.some((cookie) => cookie.name === 'session' && cookie.value === 'authenticated'));
    assert.match(result.data.body, /Logged in/);
    assert.equal((await call('/action', { id, action: 'refresh' })).status, 404);
    const verification = await call('/session', { url: `${url}/refetch`, html: '<title>Before refetch</title><script>document.cookie="awaitProof=verified; path=/"</script>' });
    result = await call('/close', { id: verification.data.id, refetch: true });
    assert.match(result.data.body, /Refetched with awaitProof=verified/);
    assert.equal(result.data.url, `${url}/refetch`);
    assert.equal((await call('/execute', { url: 'file:///C:/Windows/win.ini' })).status, 400);
  } finally { await service.close(); await new Promise((resolve) => fixture.close(resolve)); }
});
