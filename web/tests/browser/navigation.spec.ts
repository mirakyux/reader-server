import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

const book = { bookUrl: 'https://books.test/navigation', name: '导航测试书', author: '作者', origin: 'https://source.test', totalChapterNum: 2, durChapterIndex: 0, group: 1 };
const source = { bookSourceUrl: book.origin, bookSourceName: '导航测试源', enabled: true, enabledExplore: true, exploreUrl: '科幻::/science' };

async function fixture(page: Page) {
  await page.addInitScript(() => Object.defineProperty(document, 'hidden', { get: () => true }));
  const calls: { endpoint: string; body: Record<string, unknown> }[] = [];
  await page.route('**/reader3/**', async (route) => {
    const endpoint = new URL(route.request().url()).pathname.split('/reader3/')[1];
    let body: Record<string, unknown> = {};
    try { body = route.request().postDataJSON() || {}; } catch { /* GET or multipart body */ }
    calls.push({ endpoint, body });
    const values: Record<string, unknown> = {
      getBookshelf: [book], getBookSources: [source], getBookGroups: [{ groupId: 1, groupName: '科幻', show: true }], getUserInfo: { secure: false },
      getBookmarks: [], getReplaceRules: [], getRssSources: [], getLocalStoreFileList: [], getWebdavFileList: [], getInvalidBookSources: [],
      searchBookMulti: { list: [book], lastIndex: 0 }, exploreBook: [book],
      getSourceExploreUi: { categories: [{ index: 0, title: '科幻', type: 'url', url: '/science' }], filters: [], values: {} },
      'ai/config': { providers: [], prompts: [] }, 'tts/config': { engines: [] }, 'assistant/skills': { version: 1, skills: [] },
      'reading/stats': { days: [], books: [], totalSeconds: 0, totalCharacters: 0, totalSessions: 0, timezone: 'Asia/Shanghai' },
    };
    await route.fulfill({ json: { isSuccess: true, data: values[endpoint] ?? true } });
  });
  return calls;
}

const tools = [
  ['书签与摘录', '/bookmarks', '书签与摘录'],
  ['订阅', '/rss', '订阅'],
  ['书源工作台', '/sources', '书源工作台'],
  ['替换规则', '/rules', '正文替换规则'],
  ['本地书仓', '/local', '本地书仓'],
  ['WebDAV 与备份', '/webdav', 'WebDAV 与备份'],
  ['设置', '/settings', '设置'],
] as const;

for (const [device, width] of [['desktop', 1280], ['narrow mobile', 320]] as const) {
  test(`${device} navigation keeps reading visible and opens every secondary tool`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    await fixture(page);
    const errors: string[] = [];
    page.on('pageerror', (error) => errors.push(error.message));
    await page.goto('/');
    await expect(page.getByRole('heading', { name: '我的书架', exact: true })).toBeVisible();
    const navigation = page.getByRole('navigation', { name: '主导航', exact: true });
    await expect(navigation.getByRole('link')).toHaveCount(2);
    await expect(navigation.getByRole('link', { name: '书架', exact: true })).toHaveAttribute('aria-current', 'page');
    await expect(navigation.getByRole('button', { name: '更多', exact: true })).toBeVisible();
    await expect(navigation.getByRole('link', { name: /书源/ })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '继续阅读', exact: true })).toBeVisible();

    for (const [label, path, heading] of tools) {
      await navigation.getByRole('button', { name: '更多', exact: true }).click();
      await expect(page.getByRole('menu')).toBeVisible();
      await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
      await page.getByRole('menuitem', { name: label, exact: true }).click();
      await expect(page).toHaveURL(new RegExp(`#${path}$`));
      await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible();
      await expect(page.getByRole('menu')).toHaveCount(0);
      await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    }

    await navigation.getByRole('link', { name: '书架', exact: true }).click();
    await expect(page.getByRole('heading', { name: '我的书架', exact: true })).toBeVisible();
    expect(errors).toEqual([]);
  });
}

test('keyboard menus restore focus and keep shelf management reachable', async ({ page }) => {
  await fixture(page);
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '我的书架', exact: true })).toBeVisible();
  const more = page.getByRole('navigation', { name: '主导航', exact: true }).getByRole('button', { name: '更多', exact: true });
  await more.focus();
  await more.press('Enter');
  await expect(page.getByRole('menuitem', { name: '书签与摘录', exact: true })).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('menu')).toHaveCount(0);
  await expect(more).toBeFocused();
  await more.press('ArrowDown');
  await page.keyboard.press('End');
  await expect(page.getByRole('menuitem', { name: '设置', exact: true })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { name: '设置', exact: true })).toBeVisible();

  await page.getByRole('navigation', { name: '主导航', exact: true }).getByRole('link', { name: '书架', exact: true }).click();
  const manage = page.getByRole('button', { name: '管理书架', exact: true });
  await manage.focus();
  await manage.press('Enter');
  await expect(page.getByRole('menuitem', { name: '检查更新', exact: true })).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(manage).toBeFocused();
  await manage.press('ArrowDown');
  await page.keyboard.press('End');
  await expect(page.getByRole('menuitem', { name: '分组管理', exact: true })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('dialog').getByRole('heading', { name: '分组管理', exact: true })).toBeVisible();
  await expect(page.getByRole('dialog')).toContainText('科幻');
  await page.getByRole('dialog').getByRole('button', { name: '完成', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
});

test('find books switches search and discovery with the current route marked', async ({ page }) => {
  const calls = await fixture(page);
  await page.goto('/');
  const primary = page.getByRole('navigation', { name: '主导航', exact: true });
  await primary.getByRole('link', { name: '找书', exact: true }).click();
  await expect(page.getByRole('heading', { name: '搜索好书', exact: true })).toBeVisible();
  const modes = page.getByRole('navigation', { name: '找书方式', exact: true });
  await expect(primary.getByRole('link', { name: '找书', exact: true })).toHaveAttribute('aria-current', 'page');
  await expect(modes.getByRole('link', { name: '搜索', exact: true })).toHaveAttribute('aria-current', 'page');
  await expect(modes.getByRole('link', { name: '发现', exact: true })).not.toHaveAttribute('aria-current', 'page');
  await page.getByLabel('书名 / 作者', { exact: true }).fill('导航');
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  await expect(page.getByRole('heading', { name: book.name, exact: true })).toBeVisible();
  expect(calls.find((call) => call.endpoint === 'searchBookMulti')?.body).toMatchObject({ key: '导航' });

  await modes.getByRole('link', { name: '发现', exact: true }).click();
  await expect(page.getByRole('heading', { name: '发现书海', exact: true })).toBeVisible();
  await expect(primary.getByRole('link', { name: '找书', exact: true })).toHaveAttribute('aria-current', 'page');
  await expect(modes.getByRole('link', { name: '发现', exact: true })).toHaveAttribute('aria-current', 'page');
  await expect(modes.getByRole('link', { name: '搜索', exact: true })).not.toHaveAttribute('aria-current', 'page');
  await page.getByRole('button', { name: source.bookSourceName, exact: true }).click();
  await page.getByRole('button', { name: '科幻', exact: true }).click();
  await expect(page.getByRole('heading', { name: book.name, exact: true })).toBeVisible();
  expect(calls.some((call) => call.endpoint === 'exploreBook')).toBe(true);
  await modes.getByRole('link', { name: '搜索', exact: true }).click();
  await expect(page.getByRole('heading', { name: '搜索好书', exact: true })).toBeVisible();
  await expect(modes.getByRole('link', { name: '搜索', exact: true })).toHaveAttribute('aria-current', 'page');
});
