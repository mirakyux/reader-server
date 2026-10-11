import { chromium, expect as playwrightExpect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { inflateRawSync } from 'node:zlib';
import assert from 'node:assert/strict';

// Run from any working directory after deploying the current frontend and backend.
// The real server APIs are exercised; only this run's uniquely named fixtures are removed.
const frontend = new URL(process.env.READER_DEPLOY_URL || 'http://192.168.31.107:4396').origin;
const expect = playwrightExpect.configure({ timeout: 30000 });
const runId = randomUUID();
const namespace = `https://reader-source-verification.invalid/${runId}`;
const bookUrl = `${namespace}/book`;
const bookName = `换源部署验收 ${runId}`;
const author = `验收作者 ${runId}`;
const group = `source-verification-${runId}`;
const titles = ['第1章 起始', '第2章 定位', '第3章 返回'];
const sourceUrls = [`${namespace}/source-a`, `${namespace}/source-b`];
const sourceNames = [`验收临时源 A ${runId.slice(0, 8)}`, `验收临时源 B ${runId.slice(0, 8)}`];
const logsDirectory = fileURLToPath(new URL('../../logs/', import.meta.url));
const screenshotDirectory = fileURLToPath(new URL('../screenshots/', import.meta.url));
const sources = sourceUrls.map((origin, index) => {
  const metadata = { bookUrl, name: bookName, author, origin, originName: sourceNames[index], tocUrl: `${bookUrl}/toc`, intro: '独立换源部署验收数据。' };
  const chapters = titles.map((title, chapter) => ({ title, url: `${bookUrl}/chapter/${chapter + 1}` }));
  if (index === 1) chapters.unshift({ title: '序章 前言', url: `${bookUrl}/prologue` });
  return {
    bookSourceUrl: origin, bookSourceName: sourceNames[index], bookSourceGroup: group, enabled: true, enabledExplore: false,
    mainJs: `
function search(key,page){return [${JSON.stringify(metadata)}];}
function getBookInfo(book){return ${JSON.stringify(metadata)};}
function getChapters(book){return ${JSON.stringify(chapters)};}
function getContent(chapter,book){return ${JSON.stringify(`书源 ${index ? 'B' : 'A'} 正文：`)}+chapter.title+'。此正文用于验证章节定位与书源缓存隔离。';}
`,
  };
});
const fixtureBook = { bookUrl, name: bookName, author, origin: sourceUrls[0], originName: sourceNames[0], tocUrl: `${bookUrl}/toc`, type: 0 };
const evidence = { url: frontend, runId, bookUrl, sources: sourceUrls, startedAt: new Date().toISOString(), passed: false, steps: {}, pageErrors: [], requests: [], cleanupErrors: [] };
let browser;
let page;
let baseline;
let fixturesTouched = false;
let failure;

async function call(action, data) {
  const response = await fetch(`${frontend}/reader3/${action}`, {
    ...(data === undefined ? {} : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) }),
    signal: AbortSignal.timeout(60000),
  });
  assert(response.ok, `${action}: HTTP ${response.status}`);
  const value = await response.json();
  assert(value.isSuccess, `${action}: ${value.errorMsg || '服务器未能完成请求'}`);
  return value.data;
}

const bookIdentity = (book) => JSON.stringify([book.bookUrl, book.origin || '']);
const candidateIdentity = (book) => JSON.stringify([book.origin, book.bookUrl]);

// Read the chapter entries of our small generated EPUB using its ZIP directory.
function epubChapters(bytes) {
  const end = bytes.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  assert(end >= 0, 'EPUB缺少ZIP目录');
  const count = bytes.readUInt16LE(end + 10);
  let cursor = bytes.readUInt32LE(end + 16);
  const chapters = [];
  for (let index = 0; index < count; index++) {
    assert.equal(bytes.readUInt32LE(cursor), 0x02014b50);
    const method = bytes.readUInt16LE(cursor + 10);
    const size = bytes.readUInt32LE(cursor + 20);
    const nameLength = bytes.readUInt16LE(cursor + 28);
    const extraLength = bytes.readUInt16LE(cursor + 30);
    const commentLength = bytes.readUInt16LE(cursor + 32);
    const local = bytes.readUInt32LE(cursor + 42);
    const name = bytes.subarray(cursor + 46, cursor + 46 + nameLength).toString('utf8');
    if (/\/Text\/chapter_\d+\.(?:html|xhtml)$/.test(name)) {
      assert.equal(bytes.readUInt32LE(local), 0x04034b50);
      const start = local + 30 + bytes.readUInt16LE(local + 26) + bytes.readUInt16LE(local + 28);
      assert(method === 0 || method === 8, 'EPUB章节压缩方式无效');
      const compressed = bytes.subarray(start, start + size);
      chapters.push((method === 8 ? inflateRawSync(compressed) : compressed).toString('utf8'));
    }
    cursor += 46 + nameLength + extraLength + commentLength;
  }
  return chapters;
}
async function snapshot() {
  const [books, bookSources] = await Promise.all([call('getBookshelf'), call('getBookSources')]);
  assert(Array.isArray(books) && Array.isArray(bookSources), '书架和书源应返回数组');
  return {
    books, sources: bookSources,
    bookIdentities: [...new Set(books.map(bookIdentity))].sort(),
    sourceIdentities: [...new Set(bookSources.map((source) => source.bookSourceUrl))].sort(),
  };
}

async function fixtureShelfBook() {
  return (await call('getBookshelf')).find((book) => book.bookUrl === bookUrl);
}

async function openCatalogChapter(title) {
  await page.getByRole('button', { name: '目录', exact: true }).click();
  await page.locator('.catalog-list button').filter({ hasText: title }).click();
  await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible();
}

async function openReaderSources() {
  await page.getByRole('button', { name: '切换书源', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '切换书源', exact: true });
  await expect(dialog).toBeVisible();
  return dialog;
}

function sourceRow(dialog, sourceName) {
  return dialog.locator('li').filter({ hasText: sourceName });
}

async function cleanupStep(label, action) {
  try { return await action(); }
  catch (error) { evidence.cleanupErrors.push({ action: label, error: error.message }); return undefined; }
}

await mkdir(logsDirectory, { recursive: true });
await mkdir(screenshotDirectory, { recursive: true });
try {
  baseline = await snapshot();
  evidence.before = { bookIdentities: baseline.bookIdentities, sourceIdentities: baseline.sourceIdentities };
  assert(!baseline.books.some((book) => book.bookUrl === bookUrl || book.name === bookName && book.author === author), '临时书籍身份与现有数据冲突');
  assert(sourceUrls.every((origin) => !baseline.sourceIdentities.includes(origin)), '临时书源身份与现有数据冲突');

  fixturesTouched = true;
  for (const source of sources) await call('saveBookSource', source);
  browser = await chromium.launch({ channel: process.platform === 'win32' ? 'msedge' : undefined, headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, serviceWorkers: 'block' });
  page = await context.newPage();
  page.on('pageerror', (error) => evidence.pageErrors.push(error.message));
  page.on('request', (request) => {
    const endpoint = new URL(request.url()).pathname.split('/reader3/')[1];
    if (!['searchBookSource', 'getChapterList', 'getBookContentDetails', 'getAlternateChapter', 'setBookSource', 'saveBookProgress'].includes(endpoint)) return;
    let body;
    try { body = request.postDataJSON(); } catch { return; }
    if (body?.url === bookUrl || body?.bookUrl === bookUrl) evidence.requests.push({ endpoint, body });
  });

  await page.goto(`${frontend}/#/search`);
  await page.getByLabel('搜索书源', { exact: true }).selectOption(sourceUrls[0]);
  await page.getByLabel('书名 / 作者', { exact: true }).fill(bookName);
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  await page.getByRole('button', { name: bookName, exact: true }).click();
  const details = page.getByRole('dialog', { name: '书籍详情', exact: true });
  await expect(details.getByText('共 3 章', { exact: true })).toBeVisible();
  await expect(details.getByRole('button', { name: '切换书源', exact: true })).toBeVisible();
  evidence.steps.detailsActualChapterCount = 3;
  await details.getByRole('button', { name: '加入书架', exact: true }).click();
  await expect.poll(async () => (await fixtureShelfBook())?.origin).toBe(sourceUrls[0]);

  // searchBookSource supports an exact source group. Prewarm only A/B, never the user's hundreds of sources.
  const searched = await call('searchBookSource', { url: bookUrl, lastIndex: -1, searchSize: 5, bookSourceGroup: group });
  assert(searched.list.some((book) => book.origin === sourceUrls[1] && book.bookUrl === bookUrl), '限定分组检索未找到书源 B');
  const available = await call('getAvailableBookSource', { url: bookUrl });
  const identities = new Set(available.map(candidateIdentity));
  assert(sourceUrls.every((origin) => identities.has(JSON.stringify([origin, bookUrl]))), '不同书源共享 URL 的候选缓存未同时保留 A/B');
  evidence.steps.scopedCandidateCache = { group, candidates: [...identities], lastIndex: searched.lastIndex };

  await details.getByRole('button', { name: '开始阅读', exact: true }).click();
  await expect(page.locator('.reader-content')).toContainText(`书源 A 正文：${titles[0]}`);
  await expect(page.locator('.reader-main')).not.toContainText('书籍链接与书源动作');
  evidence.steps.uninterruptedReader = true;
  await openCatalogChapter(titles[1]);
  await expect(page.locator('.reader-content')).toContainText(`书源 A 正文：${titles[1]}`);
  await expect.poll(async () => (await fixtureShelfBook())?.durChapterIndex).toBe(1);

  let dialog = await openReaderSources();
  await expect(dialog.getByText(`当前书源：${sourceNames[0]}`, { exact: true })).toBeVisible();
  await sourceRow(dialog, sourceNames[1]).getByRole('button', { name: '只换本章', exact: true }).click();
  await expect(page.locator('.reader-content')).toContainText(`书源 B 正文：${titles[1]}`);
  await expect(page.getByText('本章临时换源', { exact: true })).toBeVisible();
  assert.equal((await fixtureShelfBook()).origin, sourceUrls[0], '临时换源更改了整书来源');
  await page.getByRole('button', { name: '下一章', exact: true }).click();
  await expect(page.locator('.reader-content')).toContainText(`书源 A 正文：${titles[2]}`);
  await expect(page.getByText('本章临时换源', { exact: true })).toHaveCount(0);
  evidence.steps.temporarySourceIsOneChapter = true;

  await openCatalogChapter(titles[1]);
  await expect(page.locator('.reader-content')).toContainText(`书源 A 正文：${titles[1]}`);
  await expect.poll(async () => (await fixtureShelfBook())?.durChapterIndex).toBe(1);
  dialog = await openReaderSources();
  await sourceRow(dialog, sourceNames[1]).getByRole('button', { name: '整书换源', exact: true }).click();
  await dialog.getByRole('button', { name: '确认', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page.getByRole('heading', { name: titles[1], exact: true })).toBeVisible();
  await expect(page.locator('.reader-content')).toContainText(`书源 B 正文：${titles[1]}`);
  const switched = await fixtureShelfBook();
  assert.equal(switched.origin, sourceUrls[1]);
  assert.equal(switched.bookUrl, bookUrl);
  assert.equal(switched.durChapterIndex, 2, '插入序章后没有按章节标题定位');
  assert.equal(switched.totalChapterNum, 4);
  evidence.steps.wholeBookSourceMatchesChapter = { origin: switched.origin, index: switched.durChapterIndex, title: switched.durChapterTitle, chapters: switched.totalChapterNum };
  await page.reload();
  await expect(page.getByRole('heading', { name: titles[1], exact: true })).toBeVisible();
  await expect(page.locator('.reader-content')).toContainText(`书源 B 正文：${titles[1]}`);
  assert.equal((await fixtureShelfBook()).origin, sourceUrls[1]);
  evidence.steps.sourceSurvivesReload = true;
  await page.screenshot({ path: `${screenshotDirectory}/source-fix-deployment-reader.png`, fullPage: true });

  await page.getByRole('button', { name: '返回书架', exact: true }).click();
  await expect(page.getByRole('heading', { name: '我的书架', exact: true })).toBeVisible();
  const card = page.locator('.book-card').filter({ has: page.getByRole('heading', { name: bookName, exact: true }) });
  await card.click({ button: 'right' });
  await page.getByRole('menuitem', { name: '切换书源', exact: true }).click();
  dialog = page.getByRole('dialog', { name: '切换书源', exact: true });
  await expect(dialog.getByText(`当前书源：${sourceNames[1]}`, { exact: true })).toBeVisible();
  await expect(sourceRow(dialog, sourceNames[0])).toBeVisible();
  await expect(dialog.getByRole('button', { name: '只换本章', exact: true })).toHaveCount(0);
  await dialog.getByRole('button', { name: '关闭', exact: true }).click();
  evidence.steps.shelfContextMenuSourceEntry = true;

  for (const format of ['TXT', 'EPUB']) {
    await card.click({ button: 'right' });
    await page.getByRole('menuitem', { name: `下载 ${format}`, exact: true }).click();
    const downloadEvent = page.waitForEvent('download', { timeout: 60000 });
    await page.getByRole('button', { name: '缓存全部并导出', exact: true }).click();
    const download = await downloadEvent;
    const path = `${logsDirectory}/source-fix-${runId}.${format.toLowerCase()}`;
    await download.saveAs(path);
    const bytes = await readFile(path);
    if (format === 'TXT') {
      const text = bytes.toString('utf8');
      for (const title of ['序章 前言', ...titles]) assert(text.includes(`书源 B 正文：${title}`), `换源后 TXT 缺少 B 源章节 ${title}`);
      assert(!text.includes('书源 A 正文'), '换源后 TXT 混入 A 源旧缓存');
    } else {
      const contents = epubChapters(bytes);
      assert.equal(contents.length, 4, 'EPUB正文应为四章');
      const text = contents.join('\n');
      for (const title of ['序章 前言', ...titles]) assert(text.includes(`书源 B 正文：${title}`), `换源后 EPUB 缺少 B 源章节 ${title}`);
      assert(!text.includes('书源 A 正文'), '换源后 EPUB 混入 A 源旧缓存');
    }
    await expect(page.getByText('完整书籍已下载。', { exact: true })).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toHaveCount(0);
    evidence.steps[`switchedSource${format}Export`] = { bytes: bytes.length, filename: download.suggestedFilename() };
  }

  await page.getByRole('button', { name: bookName, exact: true }).click();
  await expect(details.getByText('共 4 章', { exact: true })).toBeVisible();
  await details.getByRole('button', { name: '切换书源', exact: true }).click();
  dialog = page.getByRole('dialog', { name: '切换书源', exact: true });
  await expect(sourceRow(dialog, sourceNames[0])).toBeVisible();
  await expect(dialog.getByRole('button', { name: '只换本章', exact: true })).toHaveCount(0);
  await dialog.getByRole('button', { name: '关闭', exact: true }).click();
  await details.getByRole('button', { name: '关闭', exact: true }).click();
  evidence.steps.detailsSourceEntry = true;
  assert(!evidence.requests.some((item) => item.endpoint === 'searchBookSource' && item.body.bookSourceGroup !== group), 'UI触发了不受限的全书源搜索');
  assert.deepEqual(evidence.pageErrors, [], '真实页面出现脚本错误');
  evidence.passed = true;
} catch (error) {
  failure = error;
  evidence.error = error.message;
  if (page) await cleanupStep('failure-screenshot', () => page.screenshot({ path: `${screenshotDirectory}/source-fix-deployment-failure.png`, fullPage: true }));
} finally {
  // Close only this ephemeral browser context first, stopping fixture progress timers and discarding its local caches.
  if (browser) await cleanupStep('browser-close', () => browser.close());
  if (fixturesTouched) {
    const current = await cleanupStep('inspect-fixture-before-cleanup', snapshot);
    if (current) {
      const temporarySources = current.sources.filter((source) => sourceUrls.includes(source.bookSourceUrl));
      const existingBook = current.books.find((book) => book.bookUrl === bookUrl);
      const ownIdentity = !existingBook || existingBook.name === bookName && existingBook.author === author;
      if (!ownIdentity) evidence.cleanupErrors.push({ action: 'delete-fixture', error: '书籍身份与本次验收不一致，已保留该记录' });
      else {
        let cleanupBook = existingBook;
        // Detail requests can create the unique name_author directory before a book is added.
        // A fixture-only stub lets deleteBooks remove that entire directory even after an early UI failure.
        if (!cleanupBook && temporarySources.length) cleanupBook = await cleanupStep('register-fixture-for-directory-cleanup', () => call('saveBook', fixtureBook));
        if (cleanupBook) await cleanupStep('delete-fixture-chapter-cache', () => call('deleteBookCache', { url: bookUrl }));
        await cleanupStep('delete-fixture-book-and-directory', () => call('deleteBooks', [fixtureBook]));
        await cleanupStep('delete-fixture-reading-stats', () => call('reading/stats/delete', { bookUrl }));
      }
      if (temporarySources.length) await cleanupStep('delete-fixture-sources', () => call('deleteBookSources', temporarySources));
    }
  }
  const final = await cleanupStep('verify-preserved-user-data', snapshot);
  if (final && baseline) {
    evidence.after = { bookIdentities: final.bookIdentities, sourceIdentities: final.sourceIdentities };
    const missingBooks = baseline.bookIdentities.filter((identity) => !final.bookIdentities.includes(identity));
    const missingSources = baseline.sourceIdentities.filter((identity) => !final.sourceIdentities.includes(identity));
    const fixtureRemains = final.books.some((book) => book.bookUrl === bookUrl) || final.sources.some((source) => sourceUrls.includes(source.bookSourceUrl));
    evidence.userDataPreserved = !missingBooks.length && !missingSources.length;
    evidence.fixtureRemoved = !fixtureRemains;
    evidence.userDataCounts = { beforeBooks: baseline.books.length, afterBooks: final.books.length, beforeSources: baseline.sources.length, afterSources: final.sources.length };
    if (!evidence.userDataPreserved || fixtureRemains) evidence.cleanupErrors.push({ action: 'verify-preserved-user-data', error: `缺失书架身份 ${missingBooks.length} 个、书源身份 ${missingSources.length} 个；临时数据残留 ${fixtureRemains}` });
  }
  if (evidence.cleanupErrors.length) evidence.passed = false;
  evidence.finishedAt = new Date().toISOString();
  const document = JSON.stringify(evidence, null, 2);
  await writeFile(`${logsDirectory}/source-fix-deployment-${runId}.json`, document);
  await writeFile(`${logsDirectory}/source-fix-deployment.json`, document);
  console.log(JSON.stringify({ url: frontend, runId, passed: evidence.passed, steps: evidence.steps, userDataPreserved: evidence.userDataPreserved, fixtureRemoved: evidence.fixtureRemoved, counts: evidence.userDataCounts, errors: evidence.cleanupErrors, error: evidence.error }));
}
if (failure) throw failure;
if (!evidence.passed) throw new Error('部署验收清理或原数据保留检查失败，详见 logs/source-fix-deployment.json');
