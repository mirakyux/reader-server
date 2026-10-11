import { chromium, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';

const frontend = process.env.READER_DEPLOY_URL || 'http://192.168.31.107:4396';
const sourceUrl = `https://reader-deployment.invalid/${randomUUID()}`;
const bookUrl = `${sourceUrl}/book`;
const bookName = '部署验收测试书';
async function call(action, data) {
  const response = await fetch(`${frontend}/reader3/${action}`, data === undefined ? undefined : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) });
  const value = await response.json();
  assert(value.isSuccess, `${action}: ${value.errorMsg}`);
  return value.data;
}
const browser = await chromium.launch({ channel: process.platform === 'win32' ? 'msedge' : undefined, headless: true });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, serviceWorkers: 'block' });
const page = await context.newPage();
const errors = [];
const navigations = [];
const searches = [];
const contentResponses = [];
page.on('pageerror', (error) => errors.push(error.message));
page.on('response', (response) => { if (new URL(response.url()).pathname.endsWith('/reader3/getBookContentDetails')) contentResponses.push({ status: response.status() }); });
page.on('framenavigated', (frame) => { if (frame === page.mainFrame()) navigations.push(frame.url()); });
page.on('request', (request) => { if (new URL(request.url()).pathname.endsWith('/reader3/searchBook')) searches.push({ url: request.url(), method: request.method(), body: request.postData() }); });
const evidence = { url: frontend, bookUrl, initialBooks: (await call('getBookshelf')).length, initialSources: (await call('getBookSources')).length };
let source;
try {
  await mkdir('screenshots', { recursive: true });
  await mkdir('../logs', { recursive: true });
  await page.goto(`${frontend}/#/`);
  await expect(page.getByRole('heading', { name: '我的书架', exact: true })).toBeVisible();
  evidence.secureContext = await page.evaluate(() => window.isSecureContext);
  const png = await page.evaluate(() => { const canvas = document.createElement('canvas'); canvas.width = 2; canvas.height = 2; const graphics = canvas.getContext('2d'); graphics.fillStyle = '#346b4c'; graphics.fillRect(0, 0, 2, 2); return canvas.toDataURL('image/png'); });
  source = { bookSourceUrl: sourceUrl, bookSourceName: '部署验收临时源', enabled: true, mainJs: `
function search(key,page){return [{bookUrl:'${bookUrl}',name:'${bookName}',author:'部署验收',coverUrl:'${png}'}];}
function getBookInfo(book){return {bookUrl:'${bookUrl}',name:'${bookName}',author:'部署验收',tocUrl:'${sourceUrl}/toc',coverUrl:'${png}'};}
function getChapters(book){return [1,2,3].map(function(n){return {title:'第'+n+'章 部署验收',url:'${bookUrl}/'+n};});}
function getContent(chapter,book){return java.getQuickJsSandbox().evalString(JSON.stringify('<p>'+chapter.title+'：真实 ARM64 服务端正文与 QuickJS 😀。</p><img src="${png}" alt="章节图片">'));}
` };
  await call('saveBookSource', source);
  const searchProbe = await call('searchBook', { bookSourceUrl: sourceUrl, key: bookName, page: 1 });
  console.log('search API probe returned', searchProbe.length);
  assert.equal(searchProbe.length, 1);
  await page.goto(`${frontend}/#/search`);
  await page.reload();
  await page.getByLabel('搜索书源', { exact: true }).selectOption(sourceUrl);
  await page.getByLabel('书名 / 作者', { exact: true }).fill(bookName);
  const searchResponse = page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/reader3/searchBook') && response.request().method() === 'POST');
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  const response = await searchResponse;
  console.log('search UI response', response.status(), response.url());
  assert.equal(response.status(), 200);
  await page.locator('.book-title-button').click();
  await page.getByRole('button', { name: '加入书架', exact: true }).click();
  await page.getByRole('button', { name: '开始阅读', exact: true }).click();
  await expect(page.locator('.reader-content')).toContainText('真实 ARM64 服务端正文与 QuickJS 😀。', { timeout: 60000 });
  await page.screenshot({ path: 'screenshots/lan-deployment-reader.png', fullPage: true });
  assert.deepEqual(errors, []);
  evidence.readingAndQuickJs = true;
  await page.goto(`${frontend}/#/`);
  const card = page.locator('.book-card').filter({ has: page.getByRole('heading', { name: bookName, exact: true }) });
  for (const format of ['TXT', 'EPUB']) {
    await card.click({ button: 'right' });
    await page.getByRole('menuitem', { name: `下载 ${format}`, exact: true }).click();
    await expect(page.getByLabel('导出格式', { exact: true })).toHaveValue(format.toLowerCase());
    const downloadEvent = page.waitForEvent('download', { timeout: 60000 });
    await page.getByRole('button', { name: '缓存全部并导出', exact: true }).click();
    const download = await downloadEvent;
    const file = `../logs/lan-deployment-book.${format.toLowerCase()}`;
    await download.saveAs(file);
    const bytes = await readFile(file);
    if (format === 'TXT') for (const number of [1, 2, 3]) assert(bytes.toString('utf8').includes(`第${number}章 部署验收`));
    else assert.equal(bytes.subarray(0, 2).toString(), 'PK');
    evidence[format] = { filename: download.suggestedFilename(), bytes: bytes.length };
    await expect(page.getByText('完整书籍已下载。', { exact: true })).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toHaveCount(0);
  }
  assert.deepEqual(errors, []);
  evidence.pageErrors = errors;
  evidence.passed = true;
} catch (error) {
  evidence.pageErrors = errors;
  evidence.passed = false;
  evidence.error = error.message;
  evidence.navigations = navigations;
  evidence.searches = searches;
  evidence.contentResponses = contentResponses;
  await page.screenshot({ path: 'screenshots/lan-deployment-failure.png', fullPage: true });
  throw error;
} finally {
  await browser.close();
  if (source) {
    await call('reading/stats/delete', { bookUrl });
    await call('deleteBooks', [{ bookUrl, name: bookName, author: '部署验收' }]);
    await call('deleteBookSources', [source]);
  }
  evidence.finalBooks = (await call('getBookshelf')).length;
  evidence.finalSources = (await call('getBookSources')).length;
  await writeFile('../logs/lan-deployment-browser.json', JSON.stringify(evidence, null, 2));
  console.log(JSON.stringify(evidence));
}
