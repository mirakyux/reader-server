import { expect, test, type Page, type Route, type Request } from '@playwright/test';

const book = { bookUrl: 'https://reader-race.test/book', name: '目录刷新回归', origin: 'https://reader-race.test/source', durChapterIndex: 0 };
const catalog = (reordered = false) => (reordered ? ['B', 'A'] : ['A', 'B']).map((name, index) => ({ index, title: `章节${name}`, url: `/chapter/${name}` }));

function heldResponse() {
  let resolve!: (data: unknown) => void;
  const promise = new Promise<unknown>((done) => { resolve = done; });
  let request: Request | undefined;
  return {
    request: () => request,
    release: (data: unknown) => resolve(data),
    handle: async (route: Route) => { request = route.request(); const data = await promise; await route.fulfill({ json: { isSuccess: true, data } }).catch(() => {}); },
  };
}

async function fixture(page: Page, mode: 'content' | 'prefetch') {
  const old = heldResponse(); const refreshedCatalog = heldResponse();
  let refreshing = false;
  let oldCanceled = false;
  page.on('requestfailed', (request) => { if (request === old.request()) oldCanceled = true; });
  await page.addInitScript((prefetch) => {
    Object.defineProperty(document, 'hidden', { get: () => true });
    localStorage.setItem('reader.settings', JSON.stringify({ prefetchChapters: prefetch, keepAwake: false }));
  }, mode === 'prefetch' ? 1 : 0);
  await page.route('**/reader3/**', async (route) => {
    const endpoint = new URL(route.request().url()).pathname.split('/reader3/')[1];
    const body = route.request().postDataJSON() || {};
    if (endpoint === 'getChapterList') {
      if (body.refresh) { refreshing = true; await refreshedCatalog.handle(route); return; }
      await route.fulfill({ json: { isSuccess: true, data: catalog() } }); return;
    }
    if (endpoint === 'getBookContentDetails') {
      if (mode === 'content' && !refreshing) { await old.handle(route); return; }
      await route.fulfill({ json: { isSuccess: true, data: { content: refreshing ? '新目录正文B' : '当前正文A' } } }); return;
    }
    if (endpoint === 'getBookContent') {
      if (mode === 'prefetch' && !refreshing) { await old.handle(route); return; }
      await route.fulfill({ json: { isSuccess: true, data: '新目录正文A' } }); return;
    }
    const values: Record<string, unknown> = { getBookshelf: [book], getBookSources: [], getBookGroups: [], getUserInfo: { secure: false }, getBookmarks: [], getReplaceRules: [], getSourceCapabilities: {}, drainSourceInteractions: { actions: [] } };
    await route.fulfill({ json: { isSuccess: true, data: values[endpoint] ?? true } });
  });
  return { old, refreshedCatalog, oldCanceled: () => oldCanceled };
}

async function cachedBodies(page: Page) {
  return page.evaluate(async (bookUrl) => {
    const modulePath = '/src/lib/cache.ts';
    const { cachedChapter, chapterCacheKey } = await import(modulePath);
    return Promise.all([0, 1].map((index) => cachedChapter(chapterCacheKey(bookUrl, index))));
  }, book.bookUrl);
}

for (const mode of ['content', 'prefetch'] as const) {
  test(`catalog refresh cancels pending ${mode} before the updated catalog arrives`, async ({ page }) => {
    const state = await fixture(page, mode);
    try {
      await page.goto(`/#/reader?url=${encodeURIComponent(book.bookUrl)}`);
      await expect.poll(() => Boolean(state.old.request())).toBe(true);
      await page.getByRole('button', { name: '目录', exact: true }).click();
      await page.getByRole('button', { name: '刷新目录', exact: true }).click();
      await expect.poll(() => Boolean(state.refreshedCatalog.request())).toBe(true);
      await expect.poll(state.oldCanceled).toBe(true);
      state.old.release(mode === 'content' ? { content: '过期正文A' } : '过期预读正文B');
      // The new catalog is still pending, so its old in-flight content cannot write a body.
      expect(await cachedBodies(page)).toEqual(mode === 'content' ? [undefined, undefined] : ['当前正文A', undefined]);
      state.refreshedCatalog.release(catalog(true));
      await page.keyboard.press('Escape');
      await expect(page.getByRole('heading', { name: '章节B', exact: true })).toBeVisible();
      await expect(page.locator('.reader-content')).toContainText('新目录正文B');
      await expect.poll(async () => (await cachedBodies(page))[0]).toBe('新目录正文B');
      expect((await cachedBodies(page)).some((body) => body?.includes('过期'))).toBe(false);
    } finally {
      state.old.release(mode === 'content' ? { content: '过期正文A' } : '过期预读正文B');
      state.refreshedCatalog.release(catalog(true));
    }
  });
}
