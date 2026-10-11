// Run against dev.ps1 -Verification (18080/4173/18090), never the user's default services.
import { chromium, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
const frontend = 'http://127.0.0.1:4173';
const backend = 'http://127.0.0.1:18080/reader3';
const id = randomUUID(); const bookName = `FU联测${id}`;
const sourceUrl = `https://fu-parity.invalid/${id}`; const bookUrl = `${sourceUrl}/book`;
const davFiles = new Map(); const directories = new Set(['/dav']); const requests = [];
const pixel = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aG1EAAAAASUVORK5CYII=', 'base64');
const escapeXml = (value) => value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('"', '&quot;');
const fixture = createServer(async (request, response) => {
  try {
    const path = decodeURIComponent(new URL(request.url, 'http://fixture').pathname).replace(/\/$/, ''); requests.push(`${request.method} ${path}`);
    const chunks = []; for await (const chunk of request) chunks.push(chunk); const body = Buffer.concat(chunks);
    if (path.startsWith('/dav')) {
      if (request.headers.authorization !== `Basic ${Buffer.from('reader:fixture').toString('base64')}`) { response.writeHead(401).end(); return; }
      if (request.method === 'MKCOL') { const parent = path.slice(0, path.lastIndexOf('/')); if (!directories.has(parent)) response.writeHead(409).end(); else if (directories.has(path)) response.writeHead(405).end(); else { directories.add(path); response.writeHead(201).end(); } return; }
      if (request.method === 'PUT') { if (!directories.has(path.slice(0, path.lastIndexOf('/')))) { response.writeHead(409).end(); return; } const existing = davFiles.get(path); if ((request.headers['if-none-match'] === '*' && existing) || (request.headers['if-match'] && request.headers['if-match'] !== existing?.etag)) { response.writeHead(412).end(); return; } davFiles.set(path, { body, etag: `"${randomUUID()}"` }); response.writeHead(201).end(); return; }
      if (request.method === 'GET') { const entry = davFiles.get(path); if (!entry) response.writeHead(404).end(); else response.writeHead(200, { 'Content-Type': 'application/octet-stream' }).end(entry.body); return; }
      if (request.method === 'PROPFIND') {
        if (!directories.has(path)) { response.writeHead(404).end(); return; }
        const entries = [[path, { directory: true }], ...[...davFiles.entries()].filter(([name]) => name.slice(0, name.lastIndexOf('/')) === path)];
        const xml = `<d:multistatus xmlns:d="DAV:">${entries.map(([name, entry]) => `<d:response><d:href>${escapeXml(name)}</d:href><d:propstat><d:prop><d:resourcetype>${entry.directory ? '<d:collection/>' : ''}</d:resourcetype><d:getcontentlength>${entry.body?.length || 0}</d:getcontentlength><d:getetag>${escapeXml(entry.etag || '')}</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>`).join('')}</d:multistatus>`;
        response.writeHead(207, { 'Content-Type': 'application/xml' }).end(xml); return;
      }
    }
    if (path === '/pixel.png') { response.writeHead(200, { 'Content-Type': 'image/png' }).end(pixel); return; }
    const page = path.includes('/cursor/') ? 2 : 1;
    const html = path.startsWith('/article/') ? `<div class="content">实际RSS正文<img src="/pixel.png"><script>window.unsafe=true</script></div>` : `<article><a href="/article/${page}">实际文章${page}</a><p>摘要${page}</p></article>${page === 1 ? '<a class="next" href="/feed/cursor/second">下一页</a>' : ''}`;
    response.writeHead(200, { 'Content-Type': 'text/html; charset=UTF-8' }).end(html);
  } catch (error) { response.writeHead(500).end(error.message); }
});
await new Promise((done) => fixture.listen(0, '127.0.0.1', done));
const remote = `http://127.0.0.1:${fixture.address().port}`;
const source = { bookSourceUrl: sourceUrl, bookSourceName: 'FU真实联测源', enabled: true,
  mainJs: `function search(key,page){return [];}
function getBookInfo(book){return {bookUrl:book.bookUrl,tocUrl:book.bookUrl,name:'${bookName}',author:'',origin:'${sourceUrl}'};}
function getChapters(book){return [{title:'第一章 开始',url:book.bookUrl+'/1'},{title:'第二章 前路',url:book.bookUrl+'/2'}];}
function getContent(chapter,book){return Array(60).join('实际后端正文，章节'+chapter.index+'。\\n');}` };
const book = { bookUrl, name: bookName, author: '', origin: sourceUrl, originName: source.bookSourceName, tocUrl: bookUrl };
const rss = { sourceUrl: `${remote}/feed/first`, sourceName: 'FU真实RSS联测', sortUrl: `今日::${remote}/feed/first`, ruleArticles: 'article', ruleTitle: 'a@text', ruleLink: 'a@href', ruleDescription: 'p@html', ruleNextPage: 'a.next@href', ruleContent: '.content@html', enabled: true };
async function call(action, data) { const response = await fetch(`${backend}/${action}`, data === undefined ? undefined : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) }); const value = await response.json(); assert(value.isSuccess, `${action}: ${value.errorMsg}`); return value.data; }
const browser = await chromium.launch({ channel: process.platform === 'win32' ? 'msedge' : undefined, headless: true });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, serviceWorkers: 'block' }); const page = await context.newPage(); const errors = [];
page.on('pageerror', (error) => errors.push(error.message));
let oldDav; let dictionaryCreated = false;
try {
  await mkdir('screenshots', { recursive: true }); await mkdir('../logs', { recursive: true });
  oldDav = await call('sync/webdav/config');
  await call('saveBookSource', source); await call('saveBook', book); await call('saveRssSource', rss);
  await call('saveDictRule', { name: id, urlRule: `${remote}/article/1?word={{key}}`, showRule: '.content@text' }); dictionaryCreated = true;
  assert((await call('lookupDictionary', { word: '实际词语', ruleId: id })).results[0].content.includes('实际RSS正文'));
  await page.goto(`${frontend}/#/reader?url=${encodeURIComponent(bookUrl)}`); await expect(page.getByRole('heading', { name: '第一章 开始', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '阅读设置', exact: true }).click(); await page.getByText('阅读工具与设备离线', { exact: true }).click(); await page.getByRole('button', { name: '编辑本章', exact: true }).click();
  await page.getByLabel('章节标题', { exact: true }).fill('实际修正标题'); await page.getByLabel('章节正文', { exact: true }).fill('持久编辑后的实际正文。'); await page.getByRole('button', { name: '保存修改', exact: true }).click();
  await expect(page.getByRole('heading', { name: '实际修正标题', exact: true })).toBeVisible(); await page.reload(); await expect(page.locator('.reader-content')).toContainText('持久编辑后的实际正文。');
  await page.screenshot({ path: 'screenshots/fu-parity-reader-live.png', fullPage: true });
  const cached = await (await fetch(`${backend}/cacheBookSSE?url=${encodeURIComponent(bookUrl)}&refresh=1&concurrentCount=1`)).text(); assert(cached.includes('"complete":true'), cached);
  const txt = await (await fetch(`${backend}/exportBook?url=${encodeURIComponent(bookUrl)}&isEpub=0`)).text(); assert(txt.includes('实际修正标题') && txt.includes('持久编辑后的实际正文。'));
  const epub = await fetch(`${backend}/exportBook?url=${encodeURIComponent(bookUrl)}&isEpub=1`); assert(epub.ok); const epubBytes = Buffer.from(await epub.arrayBuffer()); assert.equal(epubBytes.subarray(0, 2).toString(), 'PK'); await writeFile('../logs/fu-parity-edited.epub', epubBytes);
  await call('restoreChapterEdit', { url: bookUrl, index: 0 }); await page.reload(); await expect(page.locator('.reader-content')).toContainText('实际后端正文');
  await page.goto(`${frontend}/#/rss`); await page.getByRole('button', { name: rss.sourceName, exact: true }).click(); await expect(page.getByText('实际文章1', { exact: true })).toBeVisible(); await page.getByRole('button', { name: '加载更多', exact: true }).click(); await expect(page.getByText('实际文章2', { exact: true })).toBeVisible(); assert(requests.some((item) => item.endsWith('/feed/cursor/second')));
  const article = { origin: rss.sourceUrl, link: `${remote}/article/1`, title: '实际文章1', group: '联测' };
  await call('rss/article', { kind: 'favorites', article }); assert((await call('rss/library?kind=favorites')).some((row) => row.link === article.link));
  const offlineArticle = await call('rss/article/cache', { article }); assert(offlineArticle.content.includes('data:image/png;base64,') && !offlineArticle.content.includes('<script'));
  await page.screenshot({ path: 'screenshots/fu-parity-rss-live.png', fullPage: true });
  await call('sync/webdav/config', { url: `${remote}/dav/legado`, username: 'reader', password: 'fixture', dailyBackup: false });
  await call('saveBookProgress', { url: bookUrl, index: 1, chapterPos: 13, time: Date.now() });
  const sent = await call('sync/webdav/progress', {}); assert(sent.sent >= 1); assert(directories.has('/dav/legado/bookProgress'));
  const progressPath = [...davFiles.keys()].find((path) => path.includes(bookName) && path.endsWith('.json')); assert(progressPath);
  const progress = JSON.parse(davFiles.get(progressPath).body); Object.assign(progress, { durChapterIndex: 0, durChapterPos: 17, durChapterTime: Date.now() + 1000 }); davFiles.set(progressPath, { body: Buffer.from(JSON.stringify(progress)), etag: '"remote-newer"' });
  await call('sync/webdav/progress', {}); const resumed = await call(`getShelfBook?url=${encodeURIComponent(bookUrl)}`); assert.equal(resumed.durChapterIndex, 0); assert.equal(resumed.durChapterPos, 17);
  const backup = await call('sync/webdav/backup', {}); assert(davFiles.has(`/dav/legado/${backup.path}`)); await call('sync/webdav/restore', { path: backup.path });
  // Reset only this verification instance's fixture day to exercise the actual server timer.
  await call('sync/webdav/config', { url: `${remote}/dav/legado`, username: 'reader', password: 'fixture', dailyBackup: true });
  const configPath = new URL('../../build/gap-live/storage/data/default/remoteWebdav.json', import.meta.url);
  const dailyConfig = JSON.parse(await readFile(configPath, 'utf8')); dailyConfig.lastBackupDay = ''; dailyConfig.lastBackupTime = 0;
  await writeFile(configPath, JSON.stringify(dailyConfig));
  const manualUploads = requests.filter((entry) => /^PUT .*\.zip$/.test(entry)).length;
  await expect.poll(() => requests.filter((entry) => /^PUT .*\.zip$/.test(entry)).length, { timeout: 75000, intervals: [500, 1000] }).toBeGreaterThan(manualUploads);
  assert((await call('sync/webdav/config')).lastBackupDay);
  await page.goto(`${frontend}/#/settings`); await page.getByRole('tab', { name: '同步', exact: true }).click(); await expect(page.getByRole('button', { name: '同步阅读进度', exact: true })).toBeVisible(); await expect(page.getByRole('heading', { name: 'AI 提供商', exact: true })).toHaveCount(0); await page.screenshot({ path: 'screenshots/fu-parity-settings-live.png', fullPage: true });
  await page.goto(`${frontend}/`); await page.setViewportSize({ width: 390, height: 844 }); await expect(page.getByRole('heading', { name: bookName, exact: true, level: 3 })).toBeVisible(); assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)); await page.screenshot({ path: 'screenshots/fu-parity-shelf-mobile.png', fullPage: true });
  assert.deepEqual(errors, []);
  const evidence = { result: 'passed', realBackend: true, editedReload: true, editedTxtEpub: true, restoreOriginal: true, dictionary: true, rssCursorPage: true, rssFavorites: true, rssOfflineImage: true, remoteDavProgress: true, emptyAuthorProgress: true, remoteBackupRestore: true, dailyBackupTimer: true, frontendErrors: errors, epubBytes: epubBytes.length };
  await writeFile('../logs/fu-parity-live.json', JSON.stringify(evidence, null, 2)); console.log(JSON.stringify(evidence));
} catch (error) { await page.screenshot({ path: 'screenshots/fu-parity-live-failure.png', fullPage: true }); console.error((await page.locator('body').innerText()).slice(0, 2500)); throw error; }
finally {
  await browser.close();
  for (const kind of ['favorites', 'history', 'offline']) await call('rss/article/delete', { kind, origin: rss.sourceUrl, link: `${remote}/article/1` }).catch(() => {});
  if (dictionaryCreated) await call('deleteDictRule', { name: id }).catch(() => {});
  await call('deleteRssSource', rss).catch(() => {}); await call('deleteBook', book).catch(() => {}); await call('deleteBookSource', source).catch(() => {});
  if (oldDav) await call('sync/webdav/config', { ...oldDav, password: '' }).catch(() => {});
  await new Promise((done) => fixture.close(done));
}
