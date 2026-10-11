import { createServer } from 'node:http';
import { randomUUID, timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { chromium } from 'playwright';
import { createQuickJsSandbox } from './quickjs.mjs';

export async function createBrowserService({ token, channel, port = 0, host = '127.0.0.1', timeout = 30000, maxSessions = 4 } = {}) {
  if (!token) throw new Error('READER_BROWSER_TOKEN is required');
  const browser = await chromium.launch({ headless: true, ...(channel ? { channel } : {}) });
  const quickJs = createQuickJsSandbox();
  const sessions = new Map();
  let active = 0;
  const authorize = (value = '') => { const a = Buffer.from(value); const b = Buffer.from(`Bearer ${token}`); return a.length === b.length && timingSafeEqual(a, b); };
  const webUrl = (value) => { const url = new URL(value); if (!['http:', 'https:'].includes(url.protocol)) throw new Error('Only HTTP/HTTPS pages are supported'); return url.href; };
  const state = async (session) => ({ id: session.id, url: session.page.url(), title: await session.page.title(), screenshot: (await session.page.screenshot({ type: 'png' })).toString('base64'), width: 1024, height: 768, storageState: await session.context.storageState() });
  const release = async (session) => { if (session) { sessions.delete(session.id); await session.context.close(); } };
  async function open(input) {
    if (sessions.size + active >= maxSessions) throw new Error('Browser concurrency limit reached');
    active++;
    let context;
    try {
      const url = webUrl(input.url);
      const headers = Object.fromEntries(Object.entries(input.headers || {}).filter(([key]) => !['cookie', 'host', 'content-length'].includes(key.toLowerCase())));
      context = await browser.newContext({ viewport: { width: 1024, height: 768 }, extraHTTPHeaders: headers, storageState: input.storageState || undefined });
      context.setDefaultTimeout(timeout); context.setDefaultNavigationTimeout(timeout);
      if (input.cookies?.length) await context.addCookies(input.cookies);
      const cookieHeader = Object.entries(input.headers || {}).find(([key]) => key.toLowerCase() === 'cookie')?.[1];
      if (cookieHeader) {
        const explicit = String(cookieHeader).split(';').map((part) => part.trim()).filter((part) => part.includes('='))
          .map((part) => ({ url, name: part.slice(0, part.indexOf('=')), value: part.slice(part.indexOf('=') + 1) }));
        if (explicit.length) await context.addCookies(explicit);
      }
      const page = await context.newPage();
      page.on('dialog', (dialog) => dialog.dismiss().catch(() => {}));
      const session = { id: randomUUID(), context, page, updated: Date.now(), busy: false, matches: [], pages: [page] };
      if (input.sourceRegex) { const regex = new RegExp(input.sourceRegex); context.on('request', (request) => { if (regex.test(request.url())) session.matches.push(request.url()); }); }
      if (input.overrideUrlRegex) { const regex = new RegExp(input.overrideUrlRegex); page.on('framenavigated', (frame) => { if (frame === page.mainFrame() && regex.test(frame.url())) session.matches.push(frame.url()); }); }
      context.on('page', (popup) => { if (session.pages.length < 4) session.pages.push(popup); else popup.close().catch(() => {}); });
      if (input.html) {
        await page.route(url, async (route) => { await route.fulfill({ contentType: 'text/html; charset=utf-8', body: input.html }); await page.unroute(url); });
      }
      await page.goto(url, { waitUntil: 'domcontentloaded' });
      sessions.set(session.id, session);
      return session;
    } catch (error) { await context?.close(); throw error; }
    finally { active--; }
  }
  async function execute(input) {
    const session = await open(input);
    let timer;
    try {
      const operation = async () => {
        let result;
        if (input.js) result = await session.page.evaluate(async (script) => {
          try { return await (0, eval)(script); }
          catch (error) { if (error instanceof SyntaxError && /return/i.test(error.message)) return await new Function(script)(); throw error; }
        }, input.js);
        if (input.delayTime) await session.page.waitForTimeout(Math.min(15000, Math.max(0, Number(input.delayTime))));
        if (input.sourceRegex || input.overrideUrlRegex) {
          const deadline = Date.now() + timeout - 500;
          while (!session.matches.length && Date.now() < deadline) await session.page.waitForTimeout(100);
          if (!session.matches.length) throw new Error('No matching browser resource/navigation');
          result = session.matches[0];
        }
        if (result == null) result = await session.page.content();
        return { body: typeof result === 'string' ? result : JSON.stringify(result), url: session.page.url(), storageState: await session.context.storageState() };
      };
      return await Promise.race([operation(), new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Browser execution timed out')), timeout); })]);
    } finally { clearTimeout(timer); await release(session); }
  }
  async function decodeImage(input) {
    const maxBytes = 2 * 1024 * 1024;
    const maxOutputBytes = 8 * 1024 * 1024;
    const maxDimension = 8192;
    const maxPixels = 16 * 1024 * 1024;
    const base64 = input.base64;
    if (typeof base64 !== 'string' || !base64 || base64.length % 4 || !/^[A-Za-z0-9+/]*={0,2}$/.test(base64)) throw new Error('Invalid image base64');
    if (base64.length > Math.ceil(maxBytes / 3) * 4) throw new Error('Image is too large');
    const bytes = Buffer.from(base64, 'base64');
    if (bytes.length > maxBytes) throw new Error('Image is too large');
    if (bytes.toString('base64') !== base64) throw new Error('Invalid image base64');
    const webp = bytes.length >= 12 && bytes.toString('ascii', 0, 4) === 'RIFF' && bytes.toString('ascii', 8, 12) === 'WEBP';
    const png = bytes.length >= 24 && bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
    const jpeg = bytes.length >= 3 && bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255;
    const gif = ['GIF87a', 'GIF89a'].includes(bytes.toString('ascii', 0, 6));
    const bmp = bytes.toString('ascii', 0, 2) === 'BM';
    const avif = bytes.toString('ascii', 4, 8) === 'ftyp' && /avif|avis/.test(bytes.toString('ascii', 8, 32));
    if (!webp && !png && !jpeg && !gif && !bmp && !avif) throw new Error('Invalid or unsupported image');
    const checkDimensions = (width, height) => {
      if (!width || !height || width > maxDimension || height > maxDimension || width * height > maxPixels) throw new Error('Image dimensions or pixels exceed the limit');
    };
    // Inspect common headers before native decoding to reject compressed oversized images.
    if (png) checkDimensions(bytes.readUInt32BE(16), bytes.readUInt32BE(20));
    if (webp) {
      for (let offset = 12; offset + 8 <= bytes.length;) {
        const size = bytes.readUInt32LE(offset + 4);
        if (offset + 8 + size > bytes.length) throw new Error('Invalid WebP image');
        const kind = bytes.toString('ascii', offset, offset + 4);
        if (kind === 'VP8X' && size >= 10) checkDimensions(bytes.readUIntLE(offset + 12, 3) + 1, bytes.readUIntLE(offset + 15, 3) + 1);
        if (kind === 'VP8L' && size >= 5 && bytes[offset + 8] === 47) {
          const dimensions = bytes.readUInt32LE(offset + 9);
          checkDimensions((dimensions & 0x3fff) + 1, ((dimensions >>> 14) & 0x3fff) + 1);
        }
        if (kind === 'VP8 ' && size >= 10 && bytes.subarray(offset + 11, offset + 14).equals(Buffer.from([157, 1, 42]))) checkDimensions(bytes.readUInt16LE(offset + 14) & 0x3fff, bytes.readUInt16LE(offset + 16) & 0x3fff);
        offset += 8 + size + (size % 2);
      }
    }
    if (sessions.size + active >= maxSessions) throw new Error('Browser concurrency limit reached');
    active++;
    let context;
    let timer;
    try {
      context = await browser.newContext({ serviceWorkers: 'block' });
      await context.route('**/*', (route) => route.abort());
      const page = await context.newPage();
      const converted = await Promise.race([
        page.evaluate(async ({ encoded, maxDimension, maxPixels }) => {
          const bytes = Uint8Array.from(atob(encoded), (character) => character.charCodeAt(0));
          let image;
          try { image = await createImageBitmap(new Blob([bytes])); }
          catch { throw new Error('Invalid or unsupported image'); }
          const canvas = document.createElement('canvas');
          try {
            if (!image.width || !image.height || image.width > maxDimension || image.height > maxDimension || image.width * image.height > maxPixels) throw new Error('Image dimensions or pixels exceed the limit');
            canvas.width = image.width; canvas.height = image.height;
            canvas.getContext('2d').drawImage(image, 0, 0);
            return canvas.toDataURL('image/png').split(',')[1];
          } finally { image.close(); canvas.width = 0; canvas.height = 0; }
        }, { encoded: base64, maxDimension, maxPixels }),
        new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Image decoding timed out')), timeout); }),
      ]);
      if (!converted || converted.length > Math.ceil(maxOutputBytes / 3) * 4 || Buffer.from(converted, 'base64').length > maxOutputBytes) throw new Error('Converted image is too large');
      return { base64: converted };
    } finally { clearTimeout(timer); try { await context?.close(); } finally { active--; } }
  }
  const server = createServer(async (request, response) => {
    const send = (status, value) => { if (!response.headersSent) { response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); response.end(JSON.stringify(value)); } };
    if (request.method === 'GET' && request.url === '/health') return send(200, { status: 'ok', sessions: sessions.size, quickJsWorkers: quickJs.active });
    if (!authorize(request.headers.authorization)) return send(401, { error: 'Unauthorized' });
    try {
      const chunks = []; let bytes = 0;
      for await (const chunk of request) { bytes += chunk.length; if (bytes > 4 * 1024 * 1024) throw new Error('Request too large'); chunks.push(chunk); }
      const input = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
      if (request.method !== 'POST') return send(405, { error: 'POST required' });
      if (request.url === '/quickjs') {
        const controller = new AbortController();
        const disconnect = () => { if (!response.writableEnded) controller.abort(); };
        response.once('close', disconnect);
        try { return send(200, await quickJs.evaluate(input.script, { signal: controller.signal })); }
        finally { response.removeListener('close', disconnect); }
      }
      if (request.url === '/execute') return send(200, await execute(input));
      if (request.url === '/decode-image') return send(200, await decodeImage(input));
      if (request.url === '/session') return send(200, await state(await open(input)));
      const session = sessions.get(input.id); if (!session) return send(404, { error: 'Browser session expired' });
      if (session.busy) return send(409, { error: 'Browser session is busy' });
      session.busy = true; session.updated = Date.now();
      try {
        if (request.url === '/close') {
          try {
            if (input.refetch) await session.page.reload({ waitUntil: 'domcontentloaded' });
            const result = { body: await session.page.content(), storageState: await session.context.storageState(), url: session.page.url() };
            return send(200, result);
          } finally { await release(session); }
        }
        if (request.url !== '/action') return send(404, { error: 'Unknown operation' });
        switch (input.action) {
          case 'click': await session.page.mouse.click(Number(input.x), Number(input.y)); break;
          case 'type': await session.page.keyboard.insertText(String(input.text || '').slice(0, 10000)); break;
          case 'key': if (!['Enter', 'Tab', 'Backspace', 'Escape', 'Control+A', 'ArrowDown', 'ArrowUp'].includes(input.key)) throw new Error('Unsupported key'); await session.page.keyboard.press(input.key); break;
          case 'scroll': await session.page.mouse.wheel(0, Math.max(-2000, Math.min(2000, Number(input.delta || 0)))); break;
          case 'refresh': break;
          default: throw new Error('Unsupported action');
        }
        const popup = session.pages.at(-1); if (popup && !popup.isClosed()) session.page = popup;
        await session.page.waitForTimeout(200);
        return send(200, await state(session));
      } finally { session.busy = false; }
    } catch (error) { send(400, { error: error.message }); }
  });
  const gc = setInterval(() => { for (const session of sessions.values()) if (!session.busy && session.updated < Date.now() - 300000) release(session).catch(() => {}); }, 30000); gc.unref();
  await new Promise((resolve) => server.listen(port, host, resolve));
  return { port: server.address().port, close: async () => { clearInterval(gc); await quickJs.close(); await browser.close(); await new Promise((resolve) => server.close(resolve)); } };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const service = await createBrowserService({ token: process.env.READER_BROWSER_TOKEN, channel: process.env.READER_BROWSER_CHANNEL || (process.platform === 'win32' ? 'msedge' : undefined), port: Number(process.env.READER_BROWSER_PORT || 8090), host: process.env.READER_BROWSER_HOST || '127.0.0.1' });
  process.stdout.write(`Reader browser listening on ${service.port}\n`);
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, async () => { await service.close(); process.exit(0); });
}
