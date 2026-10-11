import { expect, test, type Page } from '@playwright/test';

const book = { bookUrl: 'https://books.test/download', name: '下载测试书', author: '作者', origin: 'https://source.test', type: 0, totalChapterNum: 3, durChapterIndex: 0 };

const pageErrors = new WeakMap<Page, string[]>();
test.beforeEach(({ page }) => {
  const errors: string[] = [];
  pageErrors.set(page, errors);
  page.on('pageerror', (error) => errors.push(error.message));
});
test.afterEach(({ page }) => { expect(pageErrors.get(page)).toEqual([]); });

async function fixture(page: Page, books = [book], complete = true) {
  await page.addInitScript(() => Object.defineProperty(document, 'hidden', { get: () => true }));
  const calls: { endpoint: string; body: Record<string, unknown> }[] = [];
  await page.route('**/reader3/**', async (route) => {
    const endpoint = new URL(route.request().url()).pathname.split('/reader3/')[1];
    const body = route.request().method() === 'POST' ? route.request().postDataJSON() || {} : {};
    calls.push({ endpoint, body });
    if (endpoint === 'cacheBookSSE') {
      const progress = complete
        ? { cachedCount: 3, successCount: 3, failedCount: 0, totalCount: 3, complete: true }
        : { cachedCount: 2, successCount: 2, failedCount: 1, totalCount: 3, failedChapters: [1], complete: false };
      await route.fulfill({ contentType: 'text/event-stream', body: `event: end\ndata: ${JSON.stringify(progress)}\n\n` });
      return;
    }
    if (endpoint === 'exportBook') {
      await route.fulfill({ contentType: body.isEpub ? 'application/epub+zip' : 'text/plain', headers: { 'Content-Disposition': `attachment; filename=book.${body.isEpub ? 'epub' : 'txt'}` }, body: 'complete export fixture' });
      return;
    }
    const values: Record<string, unknown> = {
      getBookshelf: books, getBookSources: [{ bookSourceUrl: book.origin, bookSourceName: '测试源', enabled: true }],
      getBookGroups: [], getUserInfo: { secure: false }, getEpubTemplate: { files: [] },
      getReplaceRules: [], getBookmarks: [],
      searchBookMulti: { list: [book], lastIndex: 0 }, getBookInfo: book,
      getChapterList: [{ index: 0, title: '第一章', url: '/one' }], getBookContent: '第一章\n完整正文。',
    };
    await route.fulfill({ json: { isSuccess: true, data: values[endpoint] ?? true } });
  });
  return calls;
}

for (const format of ['TXT', 'EPUB'] as const) {
  test(`bookshelf right click downloads ${format} after complete caching`, async ({ page }) => {
    const calls = await fixture(page);
    await page.goto('/');
    await page.locator('.book-card').click({ button: 'right' });
    await expect(page.getByRole('menuitem', { name: `下载 ${format}`, exact: true })).toBeVisible();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(page).not.toHaveURL(/#\/reader/);
    await page.getByRole('menuitem', { name: `下载 ${format}`, exact: true }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading')).toHaveText(`缓存与导出 · ${book.name}`);
    await expect(page.getByLabel('导出格式', { exact: true })).toHaveValue(format.toLowerCase());
    const download = page.waitForEvent('download');
    await dialog.getByRole('button', { name: '缓存全部并导出', exact: true }).click();
    expect((await download).suggestedFilename()).toBe(`book.${format.toLowerCase()}`);
    expect(calls.find((call) => call.endpoint === 'exportBook')?.body).toMatchObject({ url: book.bookUrl, isEpub: format === 'EPUB' ? 1 : 0 });
    expect(calls.findIndex((call) => call.endpoint === 'exportBook')).toBeGreaterThan(calls.findIndex((call) => call.endpoint === 'cacheBookSSE'));
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(page.getByRole('button', { name: `书籍操作 · ${book.name}`, exact: true })).toBeFocused();
  });
}

for (const width of [1440, 390, 320]) {
  test(`shelf download menu stays reachable at ${width}px in grid and list layouts`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await fixture(page, [book, { ...book, bookUrl: 'https://books.test/two', name: '长书名测试：宇宙尽头的一本书' }]);
    await page.goto('/');
    const trigger = page.getByRole('button', { name: `书籍操作 · ${book.name}`, exact: true });
    for (const layout of ['grid', 'list']) {
      await expect(trigger).toBeVisible();
      await trigger.click();
      await expect(page.getByRole('menuitem', { name: '下载 EPUB', exact: true })).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await page.screenshot({ path: `screenshots/shelf-download-${width}-${layout}.png`, fullPage: true });
      await page.getByRole('menuitem', { name: '下载 EPUB', exact: true }).click();
      await expect(page.getByLabel('导出格式', { exact: true })).toHaveValue('epub');
      await page.keyboard.press('Escape');
      await expect(trigger).toBeFocused();
      if (layout === 'grid') {
        await page.getByRole('button', { name: '管理书架', exact: true }).click();
        await page.getByRole('menuitem', { name: '书架布局与排序', exact: true }).click();
        await page.getByLabel('布局', { exact: true }).selectOption('list');
        await page.getByRole('button', { name: '完成', exact: true }).click();
      }
    }
  });
}

test('keyboard menus preserve focus and cover/title clicks keep their original actions', async ({ page }) => {
  await fixture(page);
  await page.goto('/');
  const trigger = page.getByRole('button', { name: `书籍操作 · ${book.name}`, exact: true });
  await trigger.focus();
  await trigger.press('ArrowDown');
  await expect(page.getByRole('menuitem', { name: '下载 TXT', exact: true })).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(trigger).toBeFocused();
  await page.locator('.book-title-button').focus();
  await page.keyboard.press('Shift+F10');
  await expect(page.getByRole('menuitem', { name: '下载 TXT', exact: true })).toBeVisible();
  await page.keyboard.press('Escape');
  await page.locator('.book-title-button').click();
  await expect(page.getByRole('dialog').getByRole('heading', { name: '书籍详情', exact: true })).toBeVisible();
  await page.keyboard.press('Escape');
  await page.locator('.book-cover-button').click();
  await expect(page).toHaveURL(/#\/reader/);
  await expect(page.getByText('完整正文。', { exact: true })).toBeVisible();
});

test('shelf download keeps partial caches and exports only after a successful retry', async ({ page }) => {
  const calls = await fixture(page, [book], false);
  await page.goto('/');
  await page.locator('.book-cover-button').click({ button: 'right' });
  await page.getByRole('menuitem', { name: '下载 TXT', exact: true }).click();
  await page.getByRole('button', { name: '缓存全部并导出', exact: true }).click();
  await expect(page.getByText('失败章节：第 2 章', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '重试并导出', exact: true })).toBeEnabled();
  expect(calls.some((call) => call.endpoint === 'exportBook')).toBe(false);
  await page.route('**/reader3/cacheBookSSE*', (route) => route.fulfill({ contentType: 'text/event-stream', body: 'event: end\ndata: {"cachedCount":3,"successCount":1,"failedCount":0,"totalCount":3,"complete":true}\n\n' }));
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: '重试并导出', exact: true }).click();
  expect((await download).suggestedFilename()).toBe('book.txt');
});

test('local and media books explain why text downloads are unavailable', async ({ page }) => {
  await fixture(page, [{ ...book, origin: 'loc_book' }, { ...book, bookUrl: 'https://books.test/audio', name: '音频书', type: 1 }]);
  await page.goto('/');
  for (const [name, reason] of [[book.name, '本地原文件可在书仓下载'], ['音频书', '此类型不支持文本导出']]) {
    await page.getByRole('button', { name: `书籍操作 · ${name}`, exact: true }).click();
    await expect(page.getByRole('menuitem', { name: '下载 TXT', exact: true })).toBeDisabled();
    await expect(page.getByRole('menuitem', { name: '下载 EPUB', exact: true })).toBeDisabled();
    await expect(page.getByRole('menu')).toContainText(reason);
    await page.keyboard.press('Escape');
  }
});

test('search result cards do not expose bookshelf-only download actions', async ({ page }) => {
  await fixture(page);
  await page.goto('/#/search');
  await page.getByLabel('书名 / 作者', { exact: true }).fill('下载');
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  await expect(page.locator('.book-card')).toBeVisible();
  await expect(page.getByRole('button', { name: /书籍操作/ })).toHaveCount(0);
  await page.locator('.book-card').click({ button: 'right' });
  await expect(page.getByRole('menu')).toHaveCount(0);
});
