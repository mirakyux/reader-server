import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

async function fixture(page: Page) {
  const state = { username: '', offline: false };
  await page.route('**/reader3/**', async (route) => {
    const endpoint = new URL(route.request().url()).pathname.split('/reader3/')[1];
    if (state.offline) { await route.abort('failed'); return; }
    if (endpoint === 'login') state.username = route.request().postDataJSON().username;
    if (endpoint === 'logout') state.username = '';
    const values: Record<string, unknown> = {
      login: { accessToken: `${state.username}:test-token` },
      getUserInfo: { secure: true, ...(state.username ? { userInfo: { username: state.username } } : {}) },
      getBookshelf: state.username ? [{ bookUrl: `https://books.test/${state.username}`, name: `${state.username}的书` }] : [],
      getBookSources: [], getBookGroups: [],
      'sync/webdav/config': { url: '', username: '', dailyBackup: false },
      'reading/stats': { days: [], books: [], totalSeconds: 0, totalCharacters: 0, totalSessions: 0 },
    };
    await route.fulfill({ json: { isSuccess: true, data: values[endpoint] ?? true } });
  });
  return state;
}

async function login(page: Page, username: string) {
  await page.getByRole('button', { name: /^(登录 \/ 注册|切换账户)$/ }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('用户名', { exact: true }).fill(username);
  await dialog.getByLabel('密码', { exact: true }).fill('test-password');
  await dialog.getByRole('switch').click();
  await dialog.getByRole('button', { name: '登录', exact: true }).click();
  await expect(dialog).toHaveCount(0);
}

async function libraryKeys(page: Page) {
  return page.evaluate(() => new Promise<string[]>((resolve, reject) => {
    const opening = indexedDB.open('reader-chapters', 2);
    opening.onerror = () => reject(opening.error);
    opening.onsuccess = () => {
      const db = opening.result;
      const operation = db.transaction('metadata').objectStore('metadata').getAllKeys();
      operation.onsuccess = () => { db.close(); resolve(operation.result.map(String).filter((key) => key.endsWith('@library'))); };
      operation.onerror = () => { db.close(); reject(operation.error); };
    };
  }));
}

test('unremembered Cookie accounts keep independent offline libraries across reload and logout', async ({ page }) => {
  const state = await fixture(page);
  await page.goto('/#/settings');
  await login(page, 'alice');
  await expect.poll(() => libraryKeys(page)).toContain('/reader3@alice@library');
  await login(page, 'bob');
  await expect.poll(() => libraryKeys(page)).toContain('/reader3@bob@library');
  expect(await libraryKeys(page)).toContain('/reader3@alice@library');
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('reader.session')!).accessToken)).toBe('');
  await page.reload();
  await expect(page.getByText('bob', { exact: true })).toBeVisible();
  state.offline = true;
  await page.goto('/#/');
  await page.reload();
  await expect(page.getByRole('heading', { name: 'bob的书', exact: true }).first()).toBeVisible();
  await expect(page.getByRole('heading', { name: 'alice的书', exact: true })).toHaveCount(0);
  state.offline = false;
  await page.goto('/#/settings');
  await page.reload();
  await page.getByRole('button', { name: '退出登录', exact: true }).click();
  await expect(page.getByRole('button', { name: '登录 / 注册', exact: true })).toBeVisible();
  await page.goto('/#/');
  await expect(page.getByRole('heading', { name: 'bob的书', exact: true })).toHaveCount(0);
});

for (const width of [1440, 390, 320]) {
  test(`settings groups remain reachable without horizontal overflow at ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await fixture(page);
    await page.goto('/#/settings');
    const tabs = page.getByRole('tablist', { name: '设置分类', exact: true });
    await expect(tabs).toBeVisible();
    await expect(page.getByRole('heading', { name: '后端连接', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: '书籍缓存与下载', exact: true })).toHaveCount(0);
    for (const [tab, heading] of [['阅读', '阅读配置备份'], ['缓存与下载', '书籍缓存与下载'], ['同步', '远程 WebDAV 同步'], ['统计', '阅读统计'], ['连接与账户', '后端连接']]) {
      await tabs.getByRole('tab', { name: tab, exact: true }).click();
      await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    }
    await tabs.getByRole('tab', { name: '连接与账户', exact: true }).focus();
    await page.keyboard.press('ArrowRight');
    await expect(tabs.getByRole('tab', { name: '阅读', exact: true })).toBeFocused();
    await expect(page.getByRole('heading', { name: '阅读配置备份', exact: true })).toBeVisible();
  });
}

test('browser chapter bodies follow their URLs across catalog insertion reorder and deletion', async ({ page }) => {
  await fixture(page);
  await page.goto('/');
  const result = await page.evaluate(async () => {
    const path = '/src/lib/cache.ts';
    const { cacheCatalog, cachedChapter, cacheChapter, chapterCacheKey } = await import(path);
    const book = { bookUrl: 'book', name: '缓存身份' };
    const chapter = (url: string, index: number) => ({ url, index, title: url });
    await cacheCatalog(book, [chapter('A', 0), chapter('B', 1)]);
    await cacheChapter(chapterCacheKey('book', 0), '正文A');
    await cacheChapter(chapterCacheKey('book', 1), '正文B');
    await cacheCatalog(book, [chapter('B', 0), chapter('X', 1), chapter('A', 2)]);
    const inserted = await Promise.all([0, 1, 2].map((index) => cachedChapter(chapterCacheKey('book', index))));
    await cacheCatalog(book, [chapter('A', 0)]);
    const removed = await Promise.all([0, 1, 2].map((index) => cachedChapter(chapterCacheKey('book', index))));
    return { inserted, removed };
  });
  expect(result).toEqual({ inserted: ['正文B', undefined, '正文A'], removed: ['正文A', undefined, undefined] });
});
