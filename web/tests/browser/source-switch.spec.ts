import { expect, test, type Page } from '@playwright/test';
import type { Book } from '../../src/types';

const book: Book = { bookUrl: 'https://switch.test/book', name: '换源回归', author: '回归作者', origin: 'https://switch.test/original', originName: '原书源', totalChapterNum: 3, durChapterIndex: 1, durChapterTitle: '第二章', durChapterPos: 28 };
const alternate: Book = { ...book, bookUrl: 'https://switch.test/alternate', origin: 'https://switch.test/other', originName: '备用书源' };
const chapters = ['第一章', '第二章', '第三章'].map((title, index) => ({ title, index, url: `/chapter/${index}` }));

async function fixture(page: Page, cached: Book[] = [], sourceCount = 3, inShelf = true) {
  const calls: { endpoint: string; body: Record<string, unknown> }[] = [];
  let shelf = inShelf ? [book] : [];
  await page.addInitScript(() => {
    Object.defineProperty(document, 'hidden', { get: () => true });
    localStorage.setItem('reader.settings', JSON.stringify({ prefetchChapters: 0, keepAwake: false }));
  });
  await page.route('**/reader3/**', async (route) => {
    const endpoint = new URL(route.request().url()).pathname.split('/reader3/')[1];
    const body = route.request().method() === 'POST' ? route.request().postDataJSON() || {} : {};
    calls.push({ endpoint, body });
    const values: Record<string, unknown> = {
      getBookshelf: shelf, getBookSources: Array.from({ length: sourceCount }, (_, index) => ({ bookSourceUrl: index === 0 ? book.origin : `https://switch.test/source-${index}`, bookSourceName: `书源${index}`, enabled: true })),
      getBookGroups: [], getUserInfo: { secure: false }, getReplaceRules: [], getBookmarks: [], getBookInfo: book, getShelfBook: book,
      getChapterList: chapters, getBookContentDetails: { content: `${body.index === 2 ? '第三章' : '第二章'}原源正文` }, getBookContent: '原源正文',
      getAvailableBookSource: cached, searchBookSource: { list: [alternate], lastIndex: sourceCount - 1 },
      getAlternateChapter: { content: '替代章节正文' }, getSourceCapabilities: {}, drainSourceInteractions: { actions: [] },
    };
    if (endpoint === 'setBookSource') {
      const updated = { ...alternate, durChapterIndex: 2, durChapterTitle: '第三章', durChapterPos: 0 };
      shelf = [updated];
      await route.fulfill({ json: { isSuccess: true, data: updated } }); return;
    }
    await route.fulfill({ json: { isSuccess: true, data: values[endpoint] ?? true } });
  });
  return { calls, setShelf: (next: Book[]) => { shelf = next; } };
}

async function readerSource(page: Page) {
  await page.goto(`/#/reader?url=${encodeURIComponent(book.bookUrl)}`);
  await expect(page.getByRole('heading', { name: '第二章', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '切换书源', exact: true }).click();
  return page.getByRole('dialog', { name: '切换书源', exact: true });
}

test('opening an empty alternate cache searches immediately and shows the current source', async ({ page }) => {
  const { calls } = await fixture(page);
  const dialog = await readerSource(page);
  await expect.poll(() => calls.filter((call) => call.endpoint === 'searchBookSource').length).toBe(1);
  expect(calls.find((call) => call.endpoint === 'searchBookSource')?.body).toMatchObject({ url: book.bookUrl, lastIndex: -1, searchSize: 5 });
  await expect(dialog.getByText('当前书源：原书源', { exact: true })).toBeVisible();
  await expect(dialog.getByText('备用书源', { exact: true })).toBeVisible();
  await expect(dialog.getByRole('button', { name: '已搜索全部书源', exact: true })).toBeDisabled();
});

test('cached sources retain distinct origins sharing a URL and never offer the current source or another author', async ({ page }) => {
  const { calls } = await fixture(page, [book, { ...book, bookUrl: 'https://switch.test/same-source-other-url' }, alternate, alternate, { ...alternate, origin: 'https://switch.test/third', originName: '第三书源' }, { ...alternate, origin: 'https://switch.test/wrong-author', author: '另一作者', originName: '同名异作者书源' }]);
  const dialog = await readerSource(page);
  await expect(dialog.getByText('备用书源', { exact: true })).toHaveCount(1);
  await expect(dialog.getByText('第三书源', { exact: true })).toBeVisible();
  await expect(dialog.getByRole('button', { name: '整书换源', exact: true })).toHaveCount(2);
  await expect(dialog.getByText('同名异作者书源', { exact: true })).toHaveCount(0);
  expect(calls.filter((call) => call.endpoint === 'searchBookSource')).toHaveLength(0);
});

test('search failure is visible and retry repeats the unadvanced cursor', async ({ page }) => {
  await fixture(page); const requested: number[] = [];
  await page.route('**/reader3/searchBookSource*', async (route) => {
    requested.push(route.request().postDataJSON().lastIndex);
    await route.fulfill({ json: requested.length === 1 ? { isSuccess: false, errorMsg: '书源搜索临时失败' } : { isSuccess: true, data: { list: [alternate], lastIndex: 2 } } });
  });
  const dialog = await readerSource(page);
  await expect(dialog.getByText('书源搜索临时失败', { exact: true })).toBeVisible();
  await dialog.getByRole('button', { name: '重试', exact: true }).click();
  await expect(dialog.getByText('备用书源', { exact: true })).toBeVisible();
  expect(requested).toEqual([-1, -1]);
});

test('zero matches are explicit and searching all sources can be repeated', async ({ page }) => {
  const { calls } = await fixture(page);
  await page.route('**/reader3/searchBookSource*', async (route) => route.fulfill({ json: { isSuccess: true, data: { list: [], lastIndex: 2 } } }));
  const dialog = await readerSource(page);
  await expect(dialog.getByText('未找到同名同作者的其他书源', { exact: true })).toBeVisible();
  const initialReads = calls.filter((call) => call.endpoint === 'getAvailableBookSource').length;
  await dialog.getByRole('button', { name: '重新搜索', exact: true }).click();
  await expect.poll(() => calls.filter((call) => call.endpoint === 'getAvailableBookSource').length).toBe(initialReads + 1);
});

test('partial batches advance by the returned source cursor and stop at the last index', async ({ page }) => {
  await fixture(page); const requested: number[] = [];
  await page.route('**/reader3/searchBookSource*', async (route) => {
    requested.push(route.request().postDataJSON().lastIndex);
    await route.fulfill({ json: { isSuccess: true, data: { list: requested.length === 1 ? [alternate] : [alternate, { ...alternate, origin: 'https://switch.test/third', originName: '第三书源' }], lastIndex: requested.length === 1 ? 0 : 2 } } });
  });
  const dialog = await readerSource(page);
  await expect(dialog.getByText('备用书源', { exact: true })).toBeVisible();
  await dialog.getByRole('button', { name: '查找更多书源', exact: true }).click();
  await expect(dialog.getByText('第三书源', { exact: true })).toBeVisible();
  expect(requested).toEqual([-1, 0]);
  await expect(dialog.getByRole('button', { name: '已搜索全部书源', exact: true })).toBeDisabled();
});

test('whole-book switching uses the server chapter match while invalid responses never navigate', async ({ page }) => {
  const { calls } = await fixture(page, [alternate]);
  let attempts = 0;
  await page.route('**/reader3/setBookSource*', async (route) => {
    attempts++;
    if (attempts === 1) { await route.fulfill({ json: { isSuccess: true, data: null } }); return; }
    await route.fallback();
  });
  const dialog = await readerSource(page);
  await dialog.getByRole('button', { name: '整书换源', exact: true }).click();
  await page.getByRole('button', { name: '确认', exact: true }).click();
  await expect(dialog.getByText('换源返回的书籍信息不完整，请重试', { exact: true })).toBeVisible();
  expect(new URL(page.url()).hash).toContain(encodeURIComponent(book.bookUrl));
  await page.getByRole('button', { name: '取消', exact: true }).click();
  await dialog.getByRole('button', { name: '整书换源', exact: true }).click();
  await page.getByRole('button', { name: '确认', exact: true }).click();
  await expect(page.getByRole('heading', { name: '第三章', exact: true })).toBeVisible();
  expect(new URL(page.url()).hash).toContain(encodeURIComponent(alternate.bookUrl));
  expect(calls.find((call) => call.endpoint === 'setBookSource')?.body).toMatchObject({ bookUrl: book.bookUrl, newUrl: alternate.bookUrl, bookSourceUrl: alternate.origin });
});

test('temporary switching retains the whole book and returns to the original source next chapter', async ({ page }) => {
  const { calls } = await fixture(page, [alternate]);
  const dialog = await readerSource(page);
  await dialog.getByRole('button', { name: '只换本章', exact: true }).click();
  await expect(page.locator('.reader-content')).toContainText('替代章节正文');
  await page.getByRole('button', { name: '下一章', exact: true }).click();
  await expect(page.locator('.reader-content')).toContainText('第三章原源正文');
  expect(calls.some((call) => call.endpoint === 'setBookSource')).toBe(false);
  expect(calls.find((call) => call.endpoint === 'getAlternateChapter')?.body).toMatchObject({ url: book.bookUrl, index: 1, alternateBookUrl: alternate.bookUrl, bookSourceUrl: alternate.origin });
});

test('bookshelf whole-book switching stays on the shelf and hides temporary chapter actions', async ({ page }) => {
  await fixture(page, [alternate]); await page.goto('/');
  await page.getByRole('button', { name: `书籍操作 · ${book.name}`, exact: true }).click();
  await page.getByRole('menuitem', { name: '切换书源', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '切换书源', exact: true });
  await expect(dialog.getByRole('button', { name: '只换本章', exact: true })).toHaveCount(0);
  await dialog.getByRole('button', { name: '整书换源', exact: true }).click();
  await page.getByRole('button', { name: '确认', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page.getByRole('heading', { name: '我的书架', exact: true })).toBeVisible();
  await expect(page.locator('.book-card')).toContainText('第三章');
  expect(new URL(page.url()).hash).not.toContain('/reader');
});

test('cached-source load failure has a retry without starting a misleading empty search', async ({ page }) => {
  const { calls } = await fixture(page); let attempts = 0;
  await page.route('**/reader3/getAvailableBookSource*', async (route) => {
    // React Strict Mode aborts the first initialization request before accepting the second.
    await route.fulfill({ json: ++attempts <= 2 ? { isSuccess: false, errorMsg: '候选书源读取失败' } : { isSuccess: true, data: [alternate] } });
  });
  const dialog = await readerSource(page);
  await expect(dialog.getByText('候选书源读取失败', { exact: true })).toBeVisible();
  await expect(dialog.getByText('未找到同名同作者的其他书源', { exact: true })).toHaveCount(0);
  await dialog.getByRole('button', { name: '重试', exact: true }).click();
  await expect(dialog.getByText('备用书源', { exact: true })).toBeVisible();
  expect(calls.some((call) => call.endpoint === 'searchBookSource')).toBe(false);
});

test('pending whole-book switches disable all actions and cannot submit twice', async ({ page }) => {
  await fixture(page, [alternate]); let attempts = 0; let release!: () => void;
  const hold = new Promise<void>((resolve) => { release = resolve; });
  await page.route('**/reader3/setBookSource*', async (route) => { attempts++; await hold; await route.fulfill({ json: { isSuccess: false, errorMsg: '测试换源失败，可重试' } }); });
  try {
    const dialog = await readerSource(page);
    await dialog.getByRole('button', { name: '整书换源', exact: true }).click();
    await dialog.getByRole('button', { name: '确认', exact: true }).click();
    await expect.poll(() => attempts).toBe(1);
    for (const action of ['确认', '取消', '整书换源', '只换本章']) await expect(dialog.getByRole('button', { name: action, exact: true })).toBeDisabled();
    await expect(dialog.getByRole('status')).toContainText('正在切换书源并匹配章节');
    release();
    await expect(dialog.getByText('测试换源失败，可重试', { exact: true })).toBeVisible();
    await expect(dialog.getByRole('button', { name: '确认', exact: true })).toBeEnabled();
    expect(attempts).toBe(1);
  } finally { release(); }
});

test('books outside the shelf explain why whole-book switching is unavailable', async ({ page }) => {
  const { calls } = await fixture(page, [alternate], 3, false);
  const dialog = await readerSource(page);
  await expect(dialog.getByText('整书换源前，请先将本书加入书架。', { exact: true })).toBeVisible();
  await expect(dialog.getByRole('button', { name: '整书换源', exact: true })).toBeDisabled();
  await expect(dialog.getByRole('button', { name: '只换本章', exact: true })).toBeEnabled();
  expect(calls.some((call) => call.endpoint === 'setBookSource')).toBe(false);
});

test('switching origin with the same book URL reloads the catalog and body from the new source', async ({ page }) => {
  const sameUrl = { ...alternate, bookUrl: book.bookUrl };
  const state = await fixture(page, [sameUrl]);
  let switchedAt = 0;
  let releaseReload!: () => void;
  const reloadPending = new Promise<void>((resolve) => { releaseReload = resolve; });
  await page.route('**/reader3/getBookshelf*', async (route) => {
    if (switchedAt > 0) await reloadPending;
    await route.fallback();
  });
  await page.route('**/reader3/setBookSource*', async (route) => {
    const updated = { ...sameUrl, durChapterIndex: 2, durChapterTitle: '第三章', durChapterPos: 0 };
    state.setShelf([updated]);
    switchedAt = state.calls.length;
    await route.fulfill({ json: { isSuccess: true, data: updated } });
  });
  await page.route('**/reader3/getBookContentDetails*', async (route) => {
    const body = route.request().postDataJSON();
    await route.fulfill({ json: { isSuccess: true, data: { content: body.bookSourceUrl === alternate.origin ? '同链接新书源正文' : '原书源正文' } } });
  });
  const dialog = await readerSource(page);
  await expect(page.locator('.reader-content')).toContainText('原书源正文');
  await dialog.getByRole('button', { name: '整书换源', exact: true }).click();
  await dialog.getByRole('button', { name: '确认', exact: true }).click();
  await expect.poll(() => switchedAt).toBeGreaterThan(0);
  await page.evaluate(() => window.dispatchEvent(new Event('online')));
  releaseReload();
  await expect(page.locator('.reader-content')).toContainText('同链接新书源正文');
  await expect(page.getByRole('heading', { name: '第三章', exact: true })).toBeVisible();
  expect(new URL(page.url()).hash).toContain(encodeURIComponent(book.bookUrl));
  expect(state.calls.filter((call) => call.endpoint === 'getChapterList').at(-1)?.body).toMatchObject({ bookSourceUrl: alternate.origin, refresh: 1 });
  await expect.poll(() => state.calls.slice(switchedAt).filter((call) => call.endpoint === 'saveBookProgress').length).toBeGreaterThan(0);
  expect(state.calls.slice(switchedAt).filter((call) => call.endpoint === 'saveBookProgress').every((call) => call.body.index === 2)).toBe(true);
});

for (const width of [390, 320]) test(`alternate source metadata remains readable beside actions at ${width}px`, async ({ page }) => {
  await page.setViewportSize({ width, height: 844 });
  await fixture(page, [alternate]);
  const dialog = await readerSource(page);
  const metadata = dialog.locator('li h3').locator('..');
  await expect(metadata).toContainText('备用书源');
  expect((await metadata.boundingBox())!.width).toBeGreaterThan(150);
  await expect(dialog.getByRole('button', { name: '整书换源', exact: true })).toBeInViewport();
  await expect(dialog.getByRole('button', { name: '只换本章', exact: true })).toBeInViewport();
});

for (const failureStage of ['catalog', 'body'] as const) {
  test(`same-URL source switch never falls back to the old source's cached ${failureStage}`, async ({ page }) => {
    const sameUrl = { ...alternate, bookUrl: book.bookUrl };
    const state = await fixture(page, [sameUrl]);
    const message = failureStage === 'catalog' ? '新书源目录读取失败' : '新书源正文读取失败';
    await page.route('**/reader3/setBookSource*', async (route) => {
      state.setShelf([sameUrl]);
      await route.fulfill({ json: { isSuccess: true, data: sameUrl } });
    });
    await page.route(`**/reader3/${failureStage === 'catalog' ? 'getChapterList' : 'getBookContentDetails'}*`, async (route) => {
      if (route.request().postDataJSON().bookSourceUrl !== alternate.origin) { await route.fallback(); return; }
      await route.fulfill({ json: { isSuccess: false, errorMsg: message } });
    });
    const dialog = await readerSource(page);
    // Guarantee the old chapter is cached before testing source isolation on a failed request.
    await expect.poll(() => page.evaluate(async ({ bookUrl, index }) => {
      const cachePath = '/src/lib/cache.ts';
      const { cachedChapter, chapterCacheKey } = await import(cachePath);
      return cachedChapter(chapterCacheKey(bookUrl, index));
    }, { bookUrl: book.bookUrl, index: 1 })).toContain('第二章原源正文');
    await dialog.getByRole('button', { name: '整书换源', exact: true }).click();
    await dialog.getByRole('button', { name: '确认', exact: true }).click();
    await expect(page.getByText(message, { exact: true })).toBeVisible();
    await expect(page.locator('.reader-main')).not.toContainText('第二章原源正文');
  });
}
