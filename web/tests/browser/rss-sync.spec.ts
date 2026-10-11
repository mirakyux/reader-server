import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

async function fixture(page: Page) {
  const calls: { path: string; body: Record<string, unknown> }[] = [];
  const article = { origin: 'https://rss.test', link: 'https://rss.test/one', title: '第一篇文章', description: '摘要' };
  const lists: Record<string, Record<string, unknown>[]> = { favorites: [], history: [], offline: [] };
  let config: Record<string, unknown> = { url: 'https://dav.test/legado', username: 'reader', passwordConfigured: true, dailyBackup: false };
  await page.route('**/reader3/**', async (route) => {
    const url = new URL(route.request().url()); const path = url.pathname.split('/reader3/')[1];
    let body: Record<string, unknown> = {}; try { body = route.request().postDataJSON() || {}; } catch { /* GET */ }
    calls.push({ path, body });
    const values: Record<string, unknown> = { getBookshelf: [], getBookSources: [], getBookGroups: [], getUserInfo: { secure: false }, getReplaceRules: [],
      getRssSources: [{ sourceUrl: 'https://rss.test', sourceName: '阅读日报', sortUrl: '今日::https://rss.test/start', enabled: true }], 'rss/categories': [{ title: '今日', url: 'https://rss.test/start' }],
      'getRssContent': '<p>文章实际正文。</p><script>window.rssUnsafe=true</script>', 'rss/debug': { elapsedMs: 15, articles: [article], nextUrl: 'https://rss.test/cursor/2', logs: ['列表大小:1'], content: '实际正文' },
      'sync/webdav/test': [], 'sync/webdav/progress': { received: 1, sent: 2 }, 'sync/webdav/backup': { path: 'backup2026-10-09.zip' },
      'sync/webdav/backups': [{ path: 'backup2026-10-09.zip', name: 'backup2026-10-09.zip', size: 1024 }],
      'reading/stats': { timezone: 'Asia/Shanghai', days: [], books: [], totalSeconds: 125, totalCharacters: 0, totalSessions: 0, legacyRecords: [{ bookName: 'FU 历史书', deviceId: 'fu-phone', seconds: 125, lastRead: 1720000000000 }] } };
    let data = values[path] ?? true;
    if (path === 'getRssArticles') data = Number(body.page) > 1 ? { first: [{ ...article, link: 'https://rss.test/two', title: '第二篇文章' }, article], second: null } : { first: [article], second: 'https://rss.test/cursor/2' };
    if (path === 'rss/library') data = lists[url.searchParams.get('kind') || 'favorites'];
    if (path === 'rss/article') { const saved = body.article as Record<string, unknown>; const kind = String(body.kind); lists[kind] = [...lists[kind].filter((row) => row.link !== saved.link), saved]; data = saved; }
    if (path === 'rss/article/cache') { data = { ...body.article as object, content: '<p>文章实际正文。</p><img src="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==">' }; lists.offline = [data as Record<string, unknown>]; }
    if (path === 'rss/article/delete') { lists[String(body.kind)] = lists[String(body.kind)].filter((row) => !body.all && row.link !== body.link); }
    if (path === 'sync/webdav/config') { if (route.request().method() === 'POST') config = { ...config, ...body }; data = config; }
    await route.fulfill({ json: { isSuccess: true, data } });
  });
  return calls;
}

test('RSS follows actual next URL and persists grouped favorites history and sanitized article content', async ({ page }) => {
  const calls = await fixture(page); await page.goto('/#/rss');
  await page.getByRole('button', { name: '阅读日报', exact: true }).click();
  await page.getByRole('button', { name: '加载更多', exact: true }).click();
  await expect.poll(() => calls.filter((call) => call.path === 'getRssArticles')[1]?.body).toMatchObject({ page: 2, sortUrl: 'https://rss.test/cursor/2' });
  await expect(page.getByRole('button', { name: '第一篇文章 摘要' })).toHaveCount(1);
  await page.getByRole('button', { name: '第一篇文章 摘要' }).click();
  await expect(page.getByText('文章实际正文。', { exact: true })).toBeVisible(); expect(await page.evaluate(() => 'rssUnsafe' in window)).toBe(false);
  await page.getByLabel('收藏分组', { exact: true }).fill('技术'); await page.getByRole('button', { name: '保存分组', exact: true }).click();
  expect(calls.find((call) => call.path === 'rss/article' && call.body.kind === 'favorites')?.body).toMatchObject({ article: { group: '技术', content: expect.stringContaining('文章实际正文') } });
  expect(calls.some((call) => call.path === 'rss/article' && call.body.kind === 'history')).toBe(true);
  await page.getByRole('dialog').getByRole('button', { name: 'Close', exact: true }).click();
  await page.getByRole('button', { name: '收藏', exact: true }).click(); await page.getByLabel('收藏分组').selectOption('技术');
  await expect(page.getByRole('button', { name: '第一篇文章 摘要' })).toBeVisible();
});

test('RSS source debug displays parsed list next URL and body evidence', async ({ page }) => {
  const calls = await fixture(page); await page.goto('/#/rss'); await page.getByRole('button', { name: '阅读日报', exact: true }).click();
  await page.getByLabel('订阅搜索').fill('搜索词'); await page.getByRole('button', { name: '搜索', exact: true }).click();
  await expect.poll(() => calls.filter((call) => call.path === 'getRssArticles').at(-1)?.body.key).toBe('搜索词');
  await page.getByRole('button', { name: '调试此订阅', exact: true }).click();
  await expect(page.getByRole('dialog')).toContainText('列表大小:1'); await expect(page.getByRole('dialog')).toContainText('https://rss.test/cursor/2');
});

test('remote sync retains credentials unless replaced and FU cumulative statistics have an honest separate display', async ({ page }) => {
  const calls = await fixture(page); await page.goto('/#/settings');
  await page.getByRole('tab', { name: '统计', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'FU 历史阅读记录', exact: true })).toBeVisible(); await expect(page.getByText('FU 历史书', { exact: true })).toBeVisible();
  await page.getByRole('tab', { name: '同步', exact: true }).click();
  await page.getByRole('button', { name: '保存远程配置', exact: true }).click();
  expect(calls.filter((call) => call.path === 'sync/webdav/config' && call.body.url).at(-1)?.body.password).toBeUndefined();
  await page.getByRole('button', { name: '测试连接', exact: true }).click(); await expect(page.getByRole('status').filter({ hasText: '连接成功，文件夹包含 0 项' })).toBeVisible();
  await page.getByRole('button', { name: '同步阅读进度', exact: true }).click(); await expect(page.getByRole('status').filter({ hasText: '读取 1 份远程进度，发送 2 份进度' })).toBeVisible();
  await page.getByRole('button', { name: '查看远程备份', exact: true }).click(); await page.getByRole('button', { name: '恢复此备份', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: '确认', exact: true }).click();
  expect(calls.find((call) => call.path === 'sync/webdav/restore')?.body.path).toBe('backup2026-10-09.zip');
});
